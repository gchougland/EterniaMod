package com.hexvane.eterniamod.localplayground;

import com.google.gson.Gson;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.setup.*;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Explicit, additive manual fixtures. Never rewrites existing worlds, homes, guilds, or live payment records. */
public final class LocalPlayground {
    public static final String VILLAGE="eternia_playground",TRIALS="eternia_trials";
    public static final UUID SELLER=UUID.nameUUIDFromBytes("eternia.local.seller.v1".getBytes(StandardCharsets.UTF_8));
    private static final UUID GUILD_LEADER=UUID.nameUUIDFromBytes("eternia.local.guild.v1".getBytes(StandardCharsets.UTF_8));
    private static final Gson JSON=new Gson();private static CompletableFuture<Void> building;
    private record Manifest(int version,Map<String,UUID> worlds,boolean ready,boolean villageReady) {
        Manifest(int version,Map<String,UUID> worlds,boolean ready){this(version,worlds,ready,ready);}
    }
    private LocalPlayground(){}
    public static void require(EterniaModPlugin plugin,UUID actor){if(plugin==null||!plugin.getRuntimeConfig().local())throw new DomainException(DomainException.Code.FORBIDDEN,"The playground is available only on a local test server.");SetupAccess.require(actor);}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        require(plugin,player.getUuid());var choices=new ArrayList<ChoicePage.Choice>();
        choices.add(new ChoicePage.Choice("Create or visit the village: six NPCs, a shop house, guild hall and claimable land","Visit village",(r,s)->visit(plugin,player)));
        choices.add(new ChoicePage.Choice("Collect 5,000 Crowns, 2,000 coins, mail and housing supplies once","Starter supplies",(r,s)->supplies(plugin,r,s,player,false)));
        choices.add(new ChoicePage.Choice("Get another local test allowance and item parcel","More supplies",(r,s)->ChoicePage.open(r,s,player,"More test supplies","Add another 5,000 Crowns, 2,000 coins and sample items to this local profile.",List.of(new ChoicePage.Choice("Issue another local testing allowance","Confirm",(rr,ss)->supplies(plugin,rr,ss,player,true))))));
        choices.add(new ChoicePage.Choice("Try real combat, mining and crop XP in the adventure test world","Activity trials",(r,s)->visitTrials(plugin,player)));
        choices.add(new ChoicePage.Choice("Find mineral beds, harvest crops or start a training battle","Trial controls",(r,s)->PlaygroundActivities.open(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Select a short practice pass: defeat 3 mobs, mine 4 kinds and harvest 3 crops","Practice chapter",(r,s)->{require(plugin,player.getUuid());PlaygroundContent.register(plugin.getServices());plugin.getServices().seasons().select(player.getUuid(),PlaygroundContent.PASS);player.sendMessage(Message.raw("Village Field Notes is now your active pass. Earn its XP through the activity trials; its Premium track is 100 test Crowns."));}));
        choices.add(new ChoicePage.Choice("Review a test guild with four offline example members","Guild workshop",(r,s)->guildWorkshop(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Open world setup to get the Road Designer and place Hub services","Builder tools",(r,s)->InfrastructureSetup.open(plugin,r,s,player)));
        choices.add(new ChoicePage.Choice("Open deliveries to collect native items from your mail and purchases","Item deliveries",(r,s)->com.hexvane.eterniamod.inventory.ui.CommerceDeskPage.open(plugin,r,s,player,com.hexvane.eterniamod.inventory.ui.CommerceDeskPage.Mode.DELIVERIES)));
        choices.add(new ChoicePage.Choice("A walkthrough for every test station","Testing guide",(r,s)->guide(r,s,player)));
        ChoicePage.open(ref,store,player,"Eternia playground","A persistent local testing village. Create it once, then keep your changes between sessions. Your current home and guild are retained.",choices);
    }
    private static void guide(Ref<EntityStore> r,Store<EntityStore>s,PlayerRef p){ChoicePage.open(r,s,p,"Your testing route","Use Adventure mode for activity XP. The village is a housing world: use the housing and road tools to build.",List.of(
        new ChoicePage.Choice("1. Talk to all six NPCs; check names, professions and your interaction key","Village",(rr,ss)->visit(EterniaModPlugin.get(),p)),
        new ChoicePage.Choice("2. Collect supplies, then buy a pet, decoration or plot upgrade from Lyra","Crown Store",(rr,ss)->com.hexvane.eterniamod.premium.PremiumShopPage.open(EterniaModPlugin.get(),rr,ss,p)),
        new ChoicePage.Choice("3. Visit Iris Wren’s player shop, buy with coins and collect your delivery","Player shops",(rr,ss)->EterniaModPlugin.get().getMenuActions().perform(com.hexvane.eterniamod.socialui.SocialUiActions.Action.SHOP_BROWSE,rr,ss,p)),
        new ChoicePage.Choice("4. Claim west of the main lane near (-19, 34); place your free house","Housing",(rr,ss)->HousingInventory.open(EterniaModPlugin.get(),rr,ss,p)),
        new ChoicePage.Choice("5. Open your mailbox, claim the parcel, and send a letter to Iris Wren","Mailbox",(rr,ss)->com.hexvane.eterniamod.socialui.SocialUiBootstrap.open(rr,ss,p,EterniaModPlugin.get().getServices(),EterniaModPlugin.get().getMenuActions(),com.hexvane.eterniamod.socialui.EterniaServicesPage.Section.MAIL)),
        new ChoicePage.Choice("6. Complete actual activity objectives, then claim free and paid pass rewards","Trials",(rr,ss)->visitTrials(EterniaModPlugin.get(),p))));}
    private static void visit(EterniaModPlugin plugin,PlayerRef player){require(plugin,player.getUuid());player.sendMessage(Message.raw("Preparing your local village. The first visit may take a little time."));ensure(plugin,player.getUuid()).whenComplete((v,error)->{
        if(error!=null){failed(plugin,player,error);return;}travel(plugin,player,VILLAGE,0,1,0);
    });}
    private static void visitTrials(EterniaModPlugin plugin,PlayerRef player){require(plugin,player.getUuid());ensure(plugin,player.getUuid()).whenComplete((v,error)->{if(error!=null)failed(plugin,player,error);else travel(plugin,player,TRIALS,0,1,0);});}
    private static void failed(EterniaModPlugin plugin,PlayerRef player,Throwable error){plugin.getLogger().atSevere().withCause(error).log("Local playground setup did not complete");Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();player.sendMessage(Message.raw("Playground setup paused: "+String.valueOf(cause.getMessage()).replaceFirst("[.!]+$", "")+". Existing worlds and homes were retained."));}
    public static synchronized CompletableFuture<Void> ensure(EterniaModPlugin plugin,UUID actor){require(plugin,actor);if(building!=null&&!building.isDone())return building;
        building=CompletableFuture.runAsync(()->{try{
            PlaygroundContent.register(plugin.getServices());
            World village=world(plugin,VILLAGE),trials=world(plugin,TRIALS);
            if(!manifest(plugin).ready()){
                if(!manifest(plugin).villageReady()){
                    load(village,-2,4,-2,5);
                    on(village,()->buildVillage(plugin,village,actor)).join();
                    synchronized(LocalPlayground.class){var m=manifest(plugin);write(plugin,new Manifest(1,m.worlds(),false,true));}
                }
                // Load immediately before use. Building/furnishing the village can
                // take longer than the unattended trials sections' unload timeout.
                load(trials,-1,3,-1,3);
                on(trials,()->{require(plugin,actor);register(plugin,trials,"adventure",false,new HousingInfrastructure.Point(.5,1,.5),Map.of());PlaygroundActivities.prepare(plugin,trials,actor);}).join();
                synchronized(LocalPlayground.class){var m=manifest(plugin);write(plugin,new Manifest(1,m.worlds(),true));}
            }else on(trials,()->PlaygroundActivities.register(plugin,trials,actor)).join();
            com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().join();
        }catch(Exception e){throw new CompletionException(e);}});return building;
    }
    public static boolean managedWorld(EterniaModPlugin plugin,String name){try{World w=Universe.get().getWorld(name);return plugin.getRuntimeConfig().local()&&w!=null&&Objects.equals(manifest(plugin).worlds().get(name),w.getWorldConfig().getUuid());}catch(Exception e){return false;}}
    /** Builds the same persistent manual examples headlessly; it does not claim a client has played them. */
    public static void nativeSmoke(EterniaModPlugin plugin)throws Exception{
        if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||!plugin.getRuntimeConfig().local()||plugin.getRuntimeConfig().postgres())throw new IllegalStateException("Playground native check requires isolated smoke");
        UUID actor=UUID.randomUUID();var permission=Set.of(com.hypixel.hytale.server.core.permissions.HytalePermissions.BUILDER_TOOLS_EDITOR.getId());var permissions=com.hypixel.hytale.server.core.permissions.PermissionsModule.get();permissions.addUserPermission(actor,permission);
        try{
            ensure(plugin,actor).join();int homes=plugin.getServices().housing().allSlots().size();var offers=plugin.getServices().market().search("");
            // Reproduce an interrupted setup resumed from saved native chunks,
            // with nobody in the trials world to request its vertical sections.
            if(!Universe.get().removeWorld(TRIALS))throw new IllegalStateException("Could not unload isolated trials for resume check");
            synchronized(LocalPlayground.class){var m=manifest(plugin);write(plugin,new Manifest(1,m.worlds(),false,true));}
            ensure(plugin,actor).join();
            if(offers.stream().filter(o->o.seller().equals(SELLER)).count()!=2)throw new IllegalStateException("The manual village did not create both actual player shop listings");
            ensure(plugin,actor).join();if(homes!=plugin.getServices().housing().allSlots().size()||!offers.equals(plugin.getServices().market().search("")))throw new IllegalStateException("Revisiting the playground recreated a house or listing");
            World village=Universe.get().getWorld(VILLAGE);load(village,-2,4,-2,5);on(village,()->{
                var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(village,plugin);if(manager.listPlots().size()!=2||manager.listPlots().stream().anyMatch(p->!p.hasBuilding()))throw new IllegalStateException("Playground house and guild hall were not placed");
                var plot=manager.getPlot(plugin.getServices().housing().find(Owner.player(SELLER)).orElseThrow().propertyId());int x=plot.getBuilding().getAnchorX(),z=plot.getBuilding().getAnchorZ();boolean safe=false;
                for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)if(ChunkSectionBlockUtil.blockId(village,x+dx,1,z+dz)!=0&&ChunkSectionBlockUtil.blockId(village,x+dx,2,z+dz)==0&&ChunkSectionBlockUtil.blockId(village,x+dx,3,z+dz)==0)safe=true;
                if(!safe)throw new IllegalStateException("The example shop has no clear arrival near its configured entrance");
            }).join();
            PlaygroundActivities.nativeSmoke(plugin,Universe.get().getWorld(TRIALS),actor).join();
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_PLAYGROUND_PASS: persistent village, actual spline road, six distinct NPCs, managed portal, furnished seller and guild house, finite native-item listings, mineral and harvest stations; repeat setup retains state");
        }finally{permissions.removeUserPermission(actor,permission);}
    }
    private static synchronized World world(EterniaModPlugin plugin,String name)throws Exception{
        Manifest m=manifest(plugin);World existing=Universe.get().getWorld(name);UUID id=m.worlds().get(name);
        if(id!=null){if(existing==null)existing=Universe.get().loadWorld(name).join();if(!id.equals(existing.getWorldConfig().getUuid()))throw new IllegalStateException("The saved playground world identity changed: "+name);return existing;}
        if(existing!=null||Files.exists(Universe.get().getWorldsPath().resolve(name)))throw new IllegalStateException("The world "+name+" already exists and is not owned by this playground");
        var created=Universe.get().addWorld(name,"Flat",null).join();var worlds=new TreeMap<>(m.worlds());worlds.put(name,created.getWorldConfig().getUuid());write(plugin,new Manifest(1,worlds,false));
        on(created,()->{created.getWorldConfig().setSpawnProvider(new com.hypixel.hytale.server.core.universe.world.spawn.GlobalSpawnProvider(new com.hypixel.hytale.math.vector.Transform(.5,1,.5)));created.getWorldConfig().markChanged();}).join();return created;
    }
    private static Path file(EterniaModPlugin plugin){return plugin.getDataDirectory().resolve("local-playground.json");}
    private static Manifest manifest(EterniaModPlugin plugin)throws java.io.IOException{Path file=file(plugin);if(!Files.exists(file))return new Manifest(1,Map.of(),false);if(Files.isSymbolicLink(file)||Files.size(file)>65536)throw new java.io.IOException("Invalid playground marker");var m=JSON.fromJson(Files.readString(file),Manifest.class);if(m==null||m.version()!=1||m.worlds()==null||m.worlds().keySet().stream().anyMatch(n->!Set.of(VILLAGE,TRIALS).contains(n)))throw new java.io.IOException("Invalid playground marker");return m;}
    private static void write(EterniaModPlugin plugin,Manifest m)throws java.io.IOException{Path target=file(plugin),temp=target.resolveSibling("local-playground.pending");Files.writeString(temp,JSON.toJson(m));Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
    private static void load(World w,int minX,int maxX,int minZ,int maxZ){
        // A loaded column does not imply loaded vertical sections on 0.6.5,
        // especially when resuming a saved world before a player arrives there.
        // Await the actual block sections before any terrain validation or write.
        var loads=new ArrayList<CompletableFuture<?>>();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)loads.add(w.getChunkAsync(ChunkUtil.indexChunk(x,z)));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();loads.clear();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=0;y<ChunkUtil.HEIGHT/ChunkUtil.SIZE;y++)
            loads.add(w.getChunkStore().getChunkSectionReferenceAsync(x,y,z));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
    }
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static CompletableFuture<Void> on(World w,Work action){var result=new CompletableFuture<Void>();w.execute(()->{try{action.run();result.complete(null);}catch(Throwable e){result.completeExceptionally(e);}});return result;}
    private static void register(EterniaModPlugin plugin,World w,String role,boolean housing,HousingInfrastructure.Point arrival,Map<String,HousingInfrastructure.Area> areas){try{var snapshot=plugin.getInfrastructure().snapshot();if(snapshot.worlds().containsKey(w.getName()))return;var plan=new HousingInfrastructure.WorldPlan(role,List.of(),List.of(),arrival,housing).withAreas(areas);plugin.getInfrastructure().saveWorld(snapshot.revision(),w.getName(),plan);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    private static void buildVillage(EterniaModPlugin plugin,World w,UUID actor)throws Exception{require(plugin,actor);if(!managedWorld(plugin,w.getName()))throw new IllegalStateException("World is not a managed playground");
        w.getWorldConfig().setSpawningNPC(false);w.getWorldConfig().markChanged();
        var existingPlan=plugin.getInfrastructure().world(w.getName());
        if(existingPlan.isEmpty()){
            var stone=Objects.requireNonNull(BlockType.getAssetMap().getAsset("Rock_Stone_Brick"));
            int flat=BlockType.getAssetMap().getIndex(com.hypixel.hytale.server.core.util.TempAssetIdUtil.SOIL_GRASS),paved=BlockType.getAssetMap().getIndex(stone.getId());
            for(int x=-20;x<20;x++)for(int z=-20;z<20;z++){
                int current=ChunkSectionBlockUtil.blockId(w,x,0,z);if(current!=flat&&current!=paved)throw new IllegalStateException("The unfinished village plaza was changed at "+x+", 0, "+z+". Setup will not overwrite that block.");
                for(int y=1;y<=3;y++)if(ChunkSectionBlockUtil.blockId(w,x,y,z)!=0)throw new IllegalStateException("Clear the unfinished village plaza before resuming setup");
            }
            for(int x=-20;x<20;x++)for(int z=-20;z<20;z++)if(ChunkSectionBlockUtil.blockId(w,x,0,z)!=BlockType.getAssetMap().getIndex(stone.getId())&&!ChunkSectionBlockUtil.setBlock(w,x,0,z,stone,0))throw new IllegalStateException("Could not lay the village plaza");
            boolean hasHub=plugin.getInfrastructure().worlds().values().stream().anyMatch(p->p.role().equals("hub"));
            register(plugin,w,hasHub?"housing":"hub",true,new HousingInfrastructure.Point(.5,1,.5),Map.of("village-plaza",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,new PlotRect(-20,-20,40,40))));
        }
        com.hexvane.eterniamod.pathtool.SplineRoadTool.ensureExample(w,actor);
        int n=0;for(var identity:HubNpcIdentity.values()){int x=-12+(n%3)*12,z=n<3?-10:10;ManagedHubServices.ensureNpc(plugin,w,actor,identity,x,1,z,n<3?0:(float)Math.PI);n++;}
        ManagedHubServices.ensurePortal(plugin,w,actor,16,1,0,0);
        plugin.getRuntime().welcome(SELLER,"Iris Wren");var shop=house(plugin,w,SELLER,Owner.player(SELLER),new PlotRect(8,22,24,24),"hub_house",HousingRules.Scope.PUBLIC);
        placeShelf(plugin,w,shop,SELLER);
        for(String item:List.of("Ingredient_Bar_Iron","Food_Bread")){String receipt="local-playground:shop-v1:"+item;var stock=issue(plugin,SELLER,item,20,receipt);plugin.getServices().market().list(SELLER,stock.id(),item.equals("Food_Bread")?5:15,receipt+":listing");}
        plugin.getRuntime().welcome(GUILD_LEADER,"Rowan Ashford");var services=plugin.getServices();var guild=services.guilds().membership(GUILD_LEADER).map(m->services.guilds().find(m.guildId()).orElseThrow()).orElseGet(()->services.guilds().create(GUILD_LEADER,"The Lantern Company","local-playground:showcase-guild"));
        for(int i=1;i<=4;i++){UUID member=UUID.nameUUIDFromBytes(("eternia.local.guild.member."+i).getBytes(StandardCharsets.UTF_8));plugin.getRuntime().welcome(member,"Lantern Keeper "+i);if(services.guilds().membership(member).isEmpty())services.guilds().acceptInvite(member,services.guilds().invite(GUILD_LEADER,member));}
        house(plugin,w,GUILD_LEADER,Owner.guild(guild.id()),new PlotRect(8,52,48,48),"guild_hall",HousingRules.Scope.GUILD_ROOT);
    }
    private static HubPlotRecord house(EterniaModPlugin plugin,World w,UUID actor,Owner owner,PlotRect rect,String building,HousingRules.Scope scope)throws java.io.IOException{
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(w,plugin);var slot=plugin.getServices().housing().find(owner).orElse(null);
        UUID property=slot==null?plugin.getClaims().claim(w,actor,rect,0,scope,false,UUID.randomUUID()):slot.propertyId();var plot=Objects.requireNonNull(manager.getPlot(property),"Example plot is unavailable; restore its saved world before retrying");
        if(!plot.hasBuilding()){
            var prefab=PrefabResolveUtil.resolvePrefabBuffer(plugin.getBuildingCatalog().get(building).getPrefabPath());int x=rect.x()+rect.width()/2,z=rect.z()+rect.depth()/2;
            var placement=NativePlacementTransactions.place(plugin,w,plot,actor,building,new Vector3i(x,1,z),Rotation.None,prefab,true);
            plot.setBuilding(new HubPlotBuilding(building,x,1,z,Rotation.None,List.of()));manager.updatePlot(plot);manager.saveIfDirty();plugin.getServices().housing().updateBuildingPresent(owner,property,true);NativePlacementTransactions.complete(plugin,placement.operation());
        }return plot;
    }
    private static void placeShelf(EterniaModPlugin plugin,World w,HubPlotRecord plot,UUID actor)throws java.io.IOException{if(plugin.getServices().provenance().instances(plot.getPlotId()).stream().anyMatch(i->i.contentId().equals("eternia:prop/potion_shelf")&&i.state().equals("PLACED")))return;
        plugin.getServices().ownership().grant(new OwnershipService.GrantRequest("local-playground:showcase-shelf",Owner.player(actor),"eternia:prop/potion_shelf",OwnershipService.Kind.QUANTITY,1,null));
        var b=plot.getBuilding();var def=plugin.getPropCatalog().get("potion_shelf");var p=NativePlacementTransactions.place(plugin,w,plot,actor,"potion_shelf",new Vector3i(b.getAnchorX(),2,b.getAnchorZ()),Rotation.None,PrefabResolveUtil.resolvePrefabBuffer(def.getPrefabPath()),false);
        plot.addProp(new HubPlotProp(p.instanceId(),"potion_shelf",b.getAnchorX(),2,b.getAnchorZ(),Rotation.None));var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(w,plugin);manager.updatePlot(plot);manager.saveIfDirty();NativePlacementTransactions.complete(plugin,p.operation());
    }
    private static EscrowService.Escrow issue(EterniaModPlugin plugin,UUID player,String item,int amount,String receipt){var encoded=ItemStack.CODEC.encode(new ItemStack(item,amount),new ExtraInfo());return plugin.getServices().escrow().issueLocalExample(Owner.player(player),new EscrowService.Item(item,amount,Base64.getEncoder().encodeToString(encoded.toJson().getBytes(StandardCharsets.UTF_8)),true),receipt);}
    private static void supplies(EterniaModPlugin plugin,Ref<EntityStore> r,Store<EntityStore>s,PlayerRef p,boolean extra){require(plugin,p.getUuid());String receipt="local-playground:"+p.getUuid()+":"+(extra?UUID.randomUUID():"starter-v1");var services=plugin.getServices();plugin.getRuntime().welcome(p.getUuid(),p.getUsername());plugin.getRuntime().welcome(SELLER,"Iris Wren");
        PlaygroundContent.register(services);services.premium().creditLocalExample(p.getUuid(),5000,receipt+":crowns");services.economy().credit(Owner.player(p.getUuid()),2000,receipt+":coins");
        for(String id:List.of("aqua_lamp","starter_porch","potion_shelf"))services.ownership().grant(new OwnershipService.GrantRequest(receipt+":"+id,Owner.player(p.getUuid()),"eternia:prop/"+id,OwnershipService.Kind.QUANTITY,3,null));
        var parcel=issue(plugin,SELLER,"Ingredient_Bar_Iron",8,receipt+":parcel");services.mail().send(SELLER,p.getUuid(),"Welcome to the village","These iron bars are a real parcel. Claim them, collect them from Item deliveries, then try sending or listing a stack from your hand. Visit my house east of the lane to browse my shop.",List.of(parcel.id()),receipt+":mail");
        for(String tool:List.of("Tool_Pickaxe_Adamantite","Weapon_Sword_Copper")){var item=issue(plugin,p.getUuid(),tool,1,receipt+":"+tool);services.escrow().withdraw(Owner.player(p.getUuid()),item.id(),receipt+":"+tool+":delivery");}
        p.sendMessage(Message.raw(com.hexvane.eterniamod.customization.CustomizationTools.reissue(r,s,p,true)));p.sendMessage(Message.raw("Test allowance ready: 5,000 Crowns, 2,000 coins, housing props and an item parcel. Open the Crown Store and your mailbox to try them."));open(plugin,r,s,p);
    }
    private static void guildWorkshop(EterniaModPlugin plugin,Ref<EntityStore>r,Store<EntityStore>s,PlayerRef p){require(plugin,p.getUuid());if(plugin.getServices().guilds().membership(p.getUuid()).isPresent()){p.sendMessage(Message.raw("You already belong to a guild. Use its existing management menu; the playground will not replace it."));return;}
        ChoicePage.open(r,s,p,"Create your test guild","Become leader of a local guild with four offline example members. You can claim an estate, change roles and test guild inventory. Your guild will persist.",List.of(new ChoicePage.Choice("Create a five-member local guild","Create guild",(rr,ss)->{require(plugin,p.getUuid());var gs=plugin.getServices().guilds();String prefix=p.getUuid().toString().substring(0,8);var guild=gs.create(p.getUuid(),"Workshop "+prefix,"local-playground:guild:"+p.getUuid());for(int i=1;i<=4;i++){UUID member=UUID.nameUUIDFromBytes(("local-playground:"+p.getUuid()+":member:"+i).getBytes(StandardCharsets.UTF_8));plugin.getRuntime().welcome(member,"Workshop "+prefix+" "+i);if(gs.membership(member).isEmpty())gs.acceptInvite(member,gs.invite(p.getUuid(),member));}plugin.getRuntime().welcomeGuild(guild.id());p.sendMessage(Message.raw("Your test guild is ready. Talk to Torren to claim its estate and try the management pages."));})));
    }
    public static void travel(EterniaModPlugin plugin,PlayerRef player,String name,double x,double y,double z){require(plugin,player.getUuid());World world=Universe.get().getWorld(name);if(world==null||!managedWorld(plugin,name))throw new IllegalStateException("Create the playground first");world.getChunkStore().getChunkSectionReferenceAtBlockAsync((int)x,(int)y,(int)z).thenRun(()->world.execute(()->{
        require(plugin,player.getUuid());var floor=ChunkSectionBlockUtil.blockType(world,(int)x,(int)y-1,(int)z);if(floor==null||!floor.getMaterial().name().equals("Solid")||ChunkSectionBlockUtil.blockId(world,(int)x,(int)y,(int)z)!=0||ChunkSectionBlockUtil.blockId(world,(int)x,(int)y+1,(int)z)!=0){player.sendMessage(Message.raw("The test arrival is obstructed. Clear its pad before travelling."));return;}
        var ref=player.getReference();if(ref==null||!ref.isValid())return;var source=ref.getStore();source.getExternalData().getWorld().execute(()->{if(ref.isValid())source.putComponent(ref,Teleport.getComponentType(),Teleport.createForPlayer(world,new Vector3d(x+.5,y,z+.5),new Rotation3f()));});
    }));}
}


