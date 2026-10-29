package com.hexvane.eterniamod.ui;

import com.google.gson.*;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.authoring.*;
import com.hexvane.eterniamod.building.BuildingDefinition;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.prefab.*;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Admin form for one prefab: its game entry, Crown store listing and season reward, uploaded to the website. */
public final class ContentEntryPage extends EterniaInteractiveCustomUIPage<ContentEntryPage.Data> {
    private record Pass(String id,String name,boolean live,int levels){}
    private static final long MAX_PREFAB_BYTES=10L*1024*1024;
    private final Path prefabFile;private final String prefabKey;
    private AuthoringDrafts.Content draft=new AuthoringDrafts.Content();
    private List<Pass> passes=List.of();
    private String message="Fill in the entry, then save. The website copy updates automatically.";
    private boolean busy;

    public ContentEntryPage(PlayerRef player,Path prefabFile,String virtualPath){
        super(player,CustomPageLifetime.CanDismiss,Data.CODEC);this.prefabFile=prefabFile;prefabKey=PrefabDefinitionFactory.prefabPathKeyFromResolved(prefabFile);
        draft.id=PrefabDefinitionFactory.idFromPrefabPath(virtualPath);draft.displayName=PrefabDefinitionFactory.displayNameFromPrefabPath(virtualPath);draft.prefabPath=prefabKey;
        var plugin=EterniaModPlugin.get();
        if(plugin!=null){
            var drafts=AuthoringDrafts.of(dataDirectory(plugin));
            for(String kind:ContentAuthoring.KINDS){var saved=drafts.content(kind,draft.id);if(saved.isPresent()&&prefabKey.equals(saved.get().prefabPath)){draft=saved.get();message="Editing the saved entry. Save again to update the website.";break;}}
            if(draft.websiteRevision==0){
                BuildingDefinition house=plugin.getBuildingCatalog().get(draft.id);PropDefinition prop=plugin.getPropCatalog().get(draft.id);
                if(house!=null&&prefabKey.equals(house.getPrefabPath())){draft.kind="house";draft.category="Housing";draft.displayName=house.getDisplayName();}
                else if(prop!=null&&prefabKey.equals(prop.getPrefabPath())){draft.kind=prop.getCategory().equals("addition")?"addition":"prop";draft.displayName=prop.getDisplayName();}
            }
        }
    }
    static Path dataDirectory(EterniaModPlugin plugin){return plugin.getPropCatalog().getDataDirectory().getParent();}

    @Override public void build(Ref<EntityStore> ref,UICommandBuilder c,UIEventBuilder e,Store<EntityStore> store){
        c.append("EterniaMod/ContentEntryPage.ui");
        c.set("#Prefab.Text","Prefab: "+prefabKey);
        c.set("#Kind.Text","Type: "+ContentAuthoring.kindLabel(draft.kind));
        c.set("#EntryId.Value",draft.id);c.set("#EntryName.Value",draft.displayName);c.set("#Details.Value",draft.description);
        c.set("#Sold.Text",draft.sold?"Sold in the Crown store: Yes":"Sold in the Crown store: No");
        c.set("#Price.Value",draft.crowns>0?Long.toString(draft.crowns):"");c.set("#Price.IsReadOnly",!draft.sold);
        c.set("#Category.Text","Category: "+draft.category);c.set("#Category.Disabled",!draft.sold);
        var pass=passes.stream().filter(p->p.id().equals(draft.passId)).findFirst();
        c.set("#Pass.Text","Pass: "+(draft.passId.isBlank()?"None":pass.map(Pass::name).orElse(draft.passId)));
        c.set("#Track.Text",draft.track.equals("PAID")?"Paid track":"Free track");c.set("#Track.Disabled",draft.passId.isBlank());
        c.set("#Level.Value",Integer.toString(draft.level));c.set("#Level.IsReadOnly",draft.passId.isBlank());
        c.set("#PassNote.Text",draft.passId.isBlank()?"Pick a pass to add this as a reward. Make new passes with /eternia season."
            :pass.map(p->p.live()?"This pass is live, so its rewards are fixed. You can only keep a reward it already has.":"Saving puts this on the draft pass. It has "+p.levels()+" levels.").orElse("Loading passes..."));
        c.set("#Status.Text",message);c.set("#Save.Disabled",busy);
        for(String action:new String[]{"Kind","Sold","Category","Pass","Track","Back","Save"})bind(e,"#"+action,action);
        if(passes.isEmpty())loadPasses(ref,store);
    }
    private void bind(UIEventBuilder e,String selector,String action){
        e.addEventBinding(CustomUIEventBindingType.Activating,selector,new EventData().append("Action",action).append("@EntryId","#EntryId.Value").append("@EntryName","#EntryName.Value")
            .append("@Details","#Details.Value").append("@Price","#Price.Value").append("@Level","#Level.Value"),false);
    }
    private void loadPasses(Ref<EntityStore> ref,Store<EntityStore> store){
        var plugin=EterniaModPlugin.get();if(plugin==null)return;var services=plugin.getServices();var drafts=AuthoringDrafts.of(dataDirectory(plugin));var world=store.getExternalData().getWorld();
        SocialUiBootstrap.supply(services,()->{
            var found=new TreeMap<String,Pass>();
            for(var d:services.seasons().definitions())found.put(d.id(),new Pass(d.id(),d.name(),true,d.thresholds().size()));
            for(var d:drafts.seasons())if(!found.containsKey(d.id))found.put(d.id,new Pass(d.id,d.name,false,d.thresholds.size()));
            return List.copyOf(found.values());
        }).whenComplete((list,error)->{if(error!=null||list.isEmpty()||isDismissed())return;try{world.execute(()->{if(isDismissed()||!ref.isValid())return;passes=list;rebuild();});}catch(RuntimeException ignored){/* World is closing. */}});
    }

    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(data.action==null||isDismissed())return;
        store.getExternalData().getWorld().execute(()->{if(!ref.isValid()||isDismissed())return;try{
            SetupAccess.require(playerRef.getUuid());read(data);
            switch(data.action){
                case "Kind"->{draft.kind=next(ContentAuthoring.KINDS,draft.kind);if(draft.kind.equals("house")&&draft.category.equals("Furnishings"))draft.category="Housing";else if(!draft.kind.equals("house")&&draft.category.equals("Housing"))draft.category="Furnishings";}
                case "Sold"->draft.sold=!draft.sold;
                case "Category"->draft.category=next(ContentAuthoring.STORE_CATEGORIES,draft.category);
                case "Pass"->{var ids=new ArrayList<String>();ids.add("");passes.forEach(p->ids.add(p.id()));draft.passId=next(ids,draft.passId);}
                case "Track"->draft.track=draft.track.equals("PAID")?"FREE":"PAID";
                case "Back"->{var player=store.getComponent(ref,Player.getComponentType());if(player!=null)player.getPageManager().openCustomPage(ref,store,new PrefabBrowserPage(playerRef));return;}
                case "Save"->{if(!busy)save(ref,store);return;}
                default->{return;}
            }
            rebuild();
        }catch(IllegalArgumentException|DomainException error){message=error.getMessage();rebuild();}});
    }
    private void read(Data data){
        if(data.id!=null)draft.id=data.id.strip().toLowerCase(Locale.ROOT);
        if(data.name!=null)draft.displayName=data.name.strip();
        if(data.details!=null)draft.description=data.details.strip();
        if(data.price!=null)draft.crowns=number(data.price,"Write the price as a whole number of Crowns.");
        if(data.level!=null)draft.level=(int)Math.min(Integer.MAX_VALUE,number(data.level,"Write the level as a whole number."));
    }
    private static long number(String value,String error){String v=value.strip().replace(",","");if(v.isEmpty())return 0;if(!v.matches("\\d{1,9}"))throw new IllegalArgumentException(error);return Long.parseLong(v);}
    private static String next(List<String> values,String current){int i=values.indexOf(current);return values.get((i+1)%values.size());}

    private void save(Ref<EntityStore> ref,Store<EntityStore> store){
        var plugin=EterniaModPlugin.get();if(plugin==null)return;
        ContentAuthoring.validate(draft);
        var pass=passes.stream().filter(p->p.id().equals(draft.passId)).findFirst();
        if(!draft.passId.isBlank()&&pass.isEmpty())throw new IllegalArgumentException("Wait for the passes to load, then try again.");
        IPrefabBuffer buffer=PrefabResolveUtil.resolvePrefabBuffer(prefabKey);
        if(buffer==null||!PrefabDefinitionFactory.validatePropPrefab(buffer))throw new IllegalArgumentException("This prefab could not be loaded or has no solid blocks.");
        JsonObject nativeFields=registerGameEntry(plugin,buffer);
        JsonElement prefab;
        try{if(Files.size(prefabFile)>MAX_PREFAB_BYTES)throw new IllegalArgumentException("This prefab is larger than 10 MB, so the website cannot take it.");prefab=JsonParser.parseString(Files.readString(prefabFile,StandardCharsets.UTF_8));}
        catch(java.io.IOException|JsonParseException error){throw new IllegalArgumentException("The prefab file could not be read.");}
        var entry=copy(draft);var services=plugin.getServices();var drafts=AuthoringDrafts.of(dataDirectory(plugin));var sync=WebsiteSync.from(plugin.getRuntimeConfig());
        UUID actor=playerRef.getUuid();String actorName=playerRef.getUsername();var world=store.getExternalData().getWorld();Path data=dataDirectory(plugin);
        busy=true;message="Saving...";rebuild();
        SocialUiBootstrap.supply(services,()->{
            var notes=new ArrayList<String>();
            if(entry.sold){
                var latest=services.premium().items().stream().filter(i->i.id().equals(ContentAuthoring.storeItemId(entry))).findFirst().orElse(null);
                var item=ContentAuthoring.storeItem(entry,latest);
                if(item.isPresent()){services.premium().register(item.get());writeJson(data.resolve("CrownShop").resolve(entry.id+".json"),item.get());notes.add("Crown store listing updated.");}
            }else if(services.premium().items().stream().anyMatch(i->i.id().equals(ContentAuthoring.storeItemId(entry))))notes.add("It stays in the Crown store, because store items cannot be removed yet.");
            AuthoringDrafts.Season season=null;
            if(!entry.passId.isBlank()){
                var live=services.seasons().definitions().stream().filter(d->d.id().equals(entry.passId)).findFirst();
                if(live.isPresent()){if(!ContentAuthoring.liveHasReward(live.get(),entry))throw new IllegalArgumentException("That pass is live, so it cannot get new rewards. Make a new pass with /eternia season.");}
                else{season=drafts.season(entry.passId).orElseThrow(()->new IllegalArgumentException("That draft pass was not found."));ContentAuthoring.placeReward(season,entry);save(drafts,season);notes.add("Added to "+season.name+" at level "+entry.level+".");}
            }
            save(drafts,entry);
            return new Saved(notes,season);
        }).thenCompose(saved->{
            if(!sync.configured())return java.util.concurrent.CompletableFuture.completedFuture(String.join(" ",saved.notes())+" Saved in game. The website is not connected on this server.");
            var upload=sync.content(ContentAuthoring.contentUpload(entry,prefab,nativeFields,actor,actorName));
            var both=saved.season()==null?upload.thenApply(r->new WebsiteSync.Result[]{r,null}):upload.thenCombine(sync.season(ContentAuthoring.seasonUpload(saved.season(),actor,actorName)),(a,b)->new WebsiteSync.Result[]{a,b});
            return both.thenApply(results->{
                entry.websiteRevision=results[0].revision();try{save(drafts,entry);}catch(RuntimeException ignored){/* The upload already succeeded. */}
                return String.join(" ",saved.notes())+(results[0].unchanged()?" The website already had this version.":" Uploaded to the website as version "+results[0].revision()+".");
            }).exceptionally(error->String.join(" ",saved.notes())+" Saved in game. "+WebsiteSync.reason(error));
        }).whenComplete((text,error)->{
            try{world.execute(()->{if(isDismissed())return;busy=false;
                if(error==null){draft.websiteRevision=Math.max(draft.websiteRevision,entry.websiteRevision);message=text.strip();}
                else message=WebsiteSync.reason(error);
                if(ref.isValid())rebuild();});}catch(RuntimeException ignored){/* World is closing. */}
        });
    }
    private record Saved(List<String> notes,AuthoringDrafts.Season season){}
    private static void save(AuthoringDrafts drafts,AuthoringDrafts.Content entry){try{drafts.save(entry);}catch(java.io.IOException e){throw new IllegalStateException("The entry could not be saved on the server.");}}
    private static void save(AuthoringDrafts drafts,AuthoringDrafts.Season season){try{drafts.save(season);}catch(java.io.IOException e){throw new IllegalStateException("The pass could not be saved on the server.");}}
    static void writeJson(Path file,Object value){try{Files.createDirectories(file.getParent());Files.writeString(file,new Gson().toJson(value),StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new IllegalStateException("The definition could not be saved on the server.");}}
    private static AuthoringDrafts.Content copy(AuthoringDrafts.Content d){return new Gson().fromJson(new Gson().toJson(d),AuthoringDrafts.Content.class);}

    /** Creates or relabels the game definition on the world thread, as the prefab browser does. */
    private JsonObject registerGameEntry(EterniaModPlugin plugin,IPrefabBuffer buffer){
        var fields=new JsonObject();
        try{
            if(draft.kind.equals("house")){
                var catalog=plugin.getBuildingCatalog();BuildingDefinition def=catalog.get(draft.id);
                if(def!=null&&!prefabKey.equals(def.getPrefabPath()))throw new IllegalArgumentException("That ID already belongs to another house. Choose a new ID.");
                if(def==null){
                    var built=PrefabDefinitionFactory.buildBuildingDefinition(prefabKey,prefabFile,buffer);if(built==null)throw new IllegalArgumentException("This prefab cannot be used as a house.");
                    def=BuildingDefinition.create(draft.id,draft.displayName,built.getPrefabPath(),built.getManagementBlockLocalPos());
                }else def=def.withDisplayName(draft.displayName);
                catalog.persist(def);catalog.register(def);
                fields.add("plotAnchorOffset",vector(def.getPlotAnchorOffset()));fields.addProperty("rotationYaw",def.getDefaultRotationYaw().name());
                int[] spawn=def.getSpawnLocalPos();fields.add("spawnLocalPos",vector(spawn==null?new int[]{0,1,0}:spawn));fields.addProperty("housingKind",def.getHousingKind());
                if(def.getManagementBlockLocalPos()!=null)fields.add("managementBlockLocalPos",vector(def.getManagementBlockLocalPos()));
            }else{
                var catalog=plugin.getPropCatalog();PropDefinition def=catalog.get(draft.id);
                if(def!=null&&!prefabKey.equals(def.getPrefabPath()))throw new IllegalArgumentException("That ID already belongs to another decoration. Choose a new ID.");
                if(def==null)def=PropDefinition.create(draft.id,draft.displayName,prefabKey);
                def=def.withPresentation(draft.displayName,draft.kind.equals("addition")?"addition":"prop");
                catalog.persist(def);catalog.register(def);
                fields.add("plotAnchorOffset",vector(def.getPlotAnchorOffset()));fields.addProperty("rotationYaw",def.getDefaultRotationYaw().name());
            }
        }catch(java.io.IOException e){throw new IllegalArgumentException("The game entry could not be saved on the server.");}
        catch(IllegalStateException e){throw new IllegalArgumentException(e.getMessage()==null?"The game entry is not valid.":e.getMessage());}
        return fields;
    }
    private static JsonArray vector(int[] v){var a=new JsonArray();for(int n:v)a.add(n);return a;}

    public static final class Data{
        public String action,id,name,details,price,level;
        public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new)
            .append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add()
            .append(new KeyedCodec<>("@EntryId",Codec.STRING),(d,v)->d.id=v,d->d.id).add()
            .append(new KeyedCodec<>("@EntryName",Codec.STRING),(d,v)->d.name=v,d->d.name).add()
            .append(new KeyedCodec<>("@Details",Codec.STRING),(d,v)->d.details=v,d->d.details).add()
            .append(new KeyedCodec<>("@Price",Codec.STRING),(d,v)->d.price=v,d->d.price).add()
            .append(new KeyedCodec<>("@Level",Codec.STRING),(d,v)->d.level=v,d->d.level).add().build();
    }
}
