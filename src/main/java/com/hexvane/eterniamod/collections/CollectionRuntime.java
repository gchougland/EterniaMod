package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Domain snapshots are read on one worker; every native read/write runs on its owning world. */
public final class CollectionRuntime implements AutoCloseable {
    private static final int WORLD_PET_BUDGET = 128;
    private final EterniaModPlugin plugin;
    private final EterniaServices services;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> { var thread = new Thread(task,"Eternia-collections"); thread.setDaemon(true); return thread; });
    private final Map<UUID,Appearance> appearances = new ConcurrentHashMap<>();
    private final Map<UUID,String> displayNames = new ConcurrentHashMap<>();
    private final Map<UUID,String> statuses = new ConcurrentHashMap<>();
    private final Map<String,Map<UUID,VisiblePet>> worldPets = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final AtomicBoolean refreshQueued=new AtomicBoolean();

    CollectionRuntime(EterniaModPlugin plugin,EterniaServices services) { this.plugin=plugin;this.services=services;executor.scheduleWithFixedDelay(this::reconcile,2,2,TimeUnit.SECONDS); }
    public String status(UUID player) { return closed?"Collection renderer is unavailable.":statuses.getOrDefault(player,"Collection changes apply within two seconds."); }
    public String displayName(PlayerRef player) { return displayNames.getOrDefault(player.getUuid(),player.getUsername()); }
    public void refresh() {
        if(closed||!refreshQueued.compareAndSet(false,true))return;
        try{executor.execute(()->{refreshQueued.set(false);reconcile();});}
        catch(RejectedExecutionException ignored){refreshQueued.set(false);}
    }

    private void reconcile() {
        if(closed||Universe.get()==null)return;
        try {
            services.collection().reconcileEntitlements();
            Map<String,CollectionService.Definition> catalog=new HashMap<>();services.collection().definitions().forEach(definition->catalog.put(definition.id(),definition));
            Map<UUID,Selection> selections=new HashMap<>();
            for(PlayerRef player:List.copyOf(Universe.get().getPlayers())) {
                UUID actor=player.getUuid();
                if(services.accounts().find(actor).isEmpty())continue;
                var selected=services.collection().selection(actor);
                try {
                    String prefix=title(catalog.get(selected.prefix())),suffix=title(catalog.get(selected.suffix()));
                    Map<String,String> outfit=Map.of();var outfitDefinition=catalog.get(selected.outfit());
                    if(outfitDefinition!=null)outfit=SkinComposer.outfit(plugin.getDataDirectory(),outfitDefinition.assetId());
                    Map<String,CollectionService.Definition> wearables=new HashMap<>();selected.wearables().forEach((slot,id)->{var definition=catalog.get(id);if(definition!=null)wearables.put(slot,definition);});
                    selections.put(actor,new Selection(prefix,suffix,outfit,Map.copyOf(wearables)));
                } catch(RuntimeException failure) {statuses.put(actor,"Collection asset unavailable: "+safe(failure.getMessage()));}
            }
            Map<UUID,Property> properties=new HashMap<>();
            for(var slot:services.housing().allSlots()) {
                if(slot.state()!=HousingService.State.ACTIVE)continue;
                services.housing().location(slot.owner()).ifPresent(location->properties.put(slot.propertyId(),new Property(slot.propertyId(),location)));
            }
            List<PetPlan> pets=new ArrayList<>();
            for(var pet:services.collection().allPets()) {
                Owner owner=pet.owner();
                var definition=catalog.get(pet.contentId());if(definition==null||definition.kind()!=CollectionService.Kind.PET)continue;
                if(pet.assignment().equals("FOLLOWER")&&owner.kind()==Owner.Kind.PLAYER&&selections.containsKey(owner.id())) pets.add(new PetPlan(pet,definition,null));
                else if(pet.assignment().equals("PROPERTY")&&properties.containsKey(pet.propertyId()))pets.add(new PetPlan(pet,definition,properties.get(pet.propertyId())));
            }
            pets.sort(Comparator.comparingInt((PetPlan plan)->plan.property==null?0:1).thenComparing(plan->plan.pet.id()));
            for(World world:List.copyOf(Universe.get().getWorlds().values()))if(world.isAlive())world.execute(()->{if(!closed&&world.isAlive())apply(world,selections,pets);});
        }catch(RuntimeException failure) {
            plugin.getLogger().atWarning().log("Collection reconciliation failed: %s",safe(failure.getMessage()));
            // Fail closed: stale cached ownership never leaves appearance or pets active indefinitely.
            for(World world:List.copyOf(Universe.get().getWorlds().values()))if(world.isAlive())world.execute(()->{if(!closed)apply(world,Map.of(),List.of());});
        }
    }

    private static String title(CollectionService.Definition definition) {
        if(definition==null)return "";
        if(definition.kind()!=CollectionService.Kind.PREFIX_TITLE&&definition.kind()!=CollectionService.Kind.SUFFIX_TITLE)return "";
        return definition.assetId().replaceAll("[\\p{Cntrl}\\r\\n]","").strip();
    }

    private void apply(World world,Map<UUID,Selection> selections,List<PetPlan> plans) {
        Store<EntityStore> store=world.getEntityStore().getStore();
        Map<UUID,PlayerRef> players=new HashMap<>();
        for(PlayerRef player:List.copyOf(world.getPlayerRefs())) {
            var ref=player.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store)continue;
            players.put(player.getUuid(),player);applyAppearance(player,ref,store,selections.get(player.getUuid()));
        }
        Map<UUID,VisiblePet> visible=worldPets.computeIfAbsent(world.getName(),ignored->new HashMap<>());
        Set<UUID> desired=new HashSet<>();Map<UUID,Integer> residents=new HashMap<>();Set<UUID> followers=new HashSet<>();
        for(PetPlan plan:plans) {
            if(desired.size()>=WORLD_PET_BUDGET)break;
            Vector3d target;PetMotion.Bounds bounds=null;
            if(plan.property==null) {
                PlayerRef player=players.get(plan.pet.owner().id());if(player==null||!followers.add(player.getUuid()))continue;
                var ref=player.getReference();if(store.getComponent(ref,DeathComponent.getComponentType())!=null)continue;
                var transform=store.getComponent(ref,TransformComponent.getComponentType());if(transform==null)continue;
                var last=visible.get(plan.pet.id());var lastTransform=last!=null&&last.ref.isValid()?store.getComponent(last.ref,TransformComponent.getComponentType()):null;
                var position=transform.getPosition();var point=lastTransform==null?PetMotion.behind(position.x,position.z,transform.getRotation().yaw()):PetMotion.follow(lastTransform.getPosition().x,lastTransform.getPosition().z,position.x,position.z,transform.getRotation().yaw());
                target=new Vector3d(point.x(),position.y+.85,point.z());
            }else {
                var location=plan.property.location;if(!location.worldId().equals(world.getName()))continue;
                if(residents.merge(plan.property.id,1,Integer::sum)>12)continue;
                bounds=new PetMotion.Bounds(location.minX(),location.minZ(),(double)location.minX()+location.width(),(double)location.minZ()+location.depth());
                double x=(bounds.minX()+bounds.maxX())*.5,z=(bounds.minZ()+bounds.maxZ())*.5;
                boolean near=false;for(var player:players.values()) {var transform=store.getComponent(player.getReference(),TransformComponent.getComponentType());if(transform!=null&&Math.hypot(transform.getPosition().x-x,transform.getPosition().z-z)<96){near=true;break;}}
                if(!near)continue;
                var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(plan.property.id);
                if(plot==null||plot.getFootprint()==null)continue;
                double seed=(plan.pet.id().getLeastSignificantBits()&0xffff)*.001;
                x=bounds.x(x+Math.sin(seed+System.nanoTime()/1e10)*(location.width()*.32));
                z=bounds.z(z+Math.cos(seed+System.nanoTime()/1.3e10)*(location.depth()*.32));
                target=hoverSurface(world,x,z,plot.getFootprint().resolveVisualCenterY());if(target==null)continue;
            }
            if(!clear(world,target))continue;
            VisiblePet existing=visible.get(plan.pet.id());
            if(existing!=null&&(!existing.ref.isValid()||!existing.plan.definition.assetId().equals(plan.definition.assetId()))) {remove(store,existing);visible.remove(plan.pet.id());existing=null;}
            if(existing==null) {
                var asset=ModelAsset.getAssetMap().getAsset(plan.definition.assetId());if(asset==null)continue;
                Model model=Model.createScaledModel(asset,.65f);var box=model.getBoundingBox();
                if(box==null||box.min.x<-.35||box.max.x>.35||box.min.z<-.35||box.max.z>.35||box.min.y<0||box.max.y>.8) {
                    if(plan.pet.owner().kind()==Owner.Kind.PLAYER)statuses.put(plan.pet.owner().id(),"Pet model exceeds the supported cosmetic size.");
                    continue;
                }
                var holder=EntityStore.REGISTRY.newHolder();
                holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
                holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
                holder.addComponent(CosmeticPet.getComponentType(),new CosmeticPet(plan.pet.id(),plan.pet.owner(),plan.pet.sourceGrant()));
                holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(target,new Rotation3f()));
                holder.addComponent(HeadRotation.getComponentType(),new HeadRotation(new Rotation3f()));
                holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(model));
                var animations=new ActiveAnimationComponent();animations.setPlayingAnimation(AnimationSlot.Movement,"Idle");
                holder.addComponent(ActiveAnimationComponent.getComponentType(),animations);
                var ref=store.addEntity(holder,AddReason.SPAWN);if(ref==null)continue;
                // ModelSpawned installs a server collider. Purely cosmetic followers must not absorb projectiles or block movement.
                if(store.getComponent(ref,BoundingBox.getComponentType())!=null)store.removeComponent(ref,BoundingBox.getComponentType());
                existing=new VisiblePet(ref,plan,target,bounds);visible.put(plan.pet.id(),existing);
            }else {existing.plan=plan;existing.target=target;existing.bounds=bounds;}
            desired.add(plan.pet.id());
        }
        var iterator=visible.entrySet().iterator();while(iterator.hasNext()){var entry=iterator.next();if(!desired.contains(entry.getKey())){remove(store,entry.getValue());iterator.remove();}}
    }

    private void applyAppearance(PlayerRef player,Ref<EntityStore> ref,Store<EntityStore> store,Selection selection) {
        UUID actor=player.getUuid();var component=store.getComponent(ref,PlayerSkinComponent.getComponentType());
        if(component==null)return;
        Appearance state=appearances.computeIfAbsent(actor,ignored->new Appearance(new PlayerSkin(component.getPlayerSkin()),store.getComponent(ref,DisplayNameComponent.getComponentType())));
        if(state.lastApplied!=null&&!state.lastApplied.equals(component.getPlayerSkin()))state.original=new PlayerSkin(component.getPlayerSkin());
        PlayerSkin desired=new PlayerSkin(state.original);
        String name=player.getUsername();
        if(selection!=null) {
            name=(selection.prefix+" "+player.getUsername()+" "+selection.suffix).strip().replaceAll(" {2,}"," ");
            try {desired=SkinComposer.compose(state.original,selection.outfit,selection.wearables);CosmeticsModule.get().validateSkin(desired);statuses.put(actor,"Collection appearance and titles applied.");}
            catch(Exception failure){statuses.put(actor,"Appearance could not be applied: "+safe(failure.getMessage()));desired=new PlayerSkin(state.original);}
        }
        if(!component.getPlayerSkin().equals(desired))store.putComponent(ref,PlayerSkinComponent.getComponentType(),new PlayerSkinComponent(desired));
        state.lastApplied=new PlayerSkin(desired);
        if(!name.equals(displayNames.put(actor,name)))store.putComponent(ref,DisplayNameComponent.getComponentType(),new DisplayNameComponent(Message.raw(name)));
    }

    /** Called during the owning world tick. Cosmetic transforms never grant collision, combat or pickup behavior. */
    void tick(float dt,Store<EntityStore> store) {
        if(closed||dt<=0||!Float.isFinite(dt))return;
        World world=store.getExternalData().getWorld();var visible=worldPets.get(world.getName());if(visible==null)return;
        for(VisiblePet pet:visible.values()) {
            if(!pet.ref.isValid())continue;
            var transform=store.getComponent(pet.ref,TransformComponent.getComponentType());if(transform==null)continue;
            Vector3d target=new Vector3d(pet.target),lookAt=null;
            if(pet.plan.property==null) {
                PlayerRef owner=null;for(var player:world.getPlayerRefs())if(player.getUuid().equals(pet.plan.pet.owner().id())){owner=player;break;}
                if(owner==null||owner.getReference()==null||!owner.getReference().isValid())continue;
                if(store.getComponent(owner.getReference(),DeathComponent.getComponentType())!=null)continue;
                var position=store.getComponent(owner.getReference(),TransformComponent.getComponentType());if(position==null)continue;
                lookAt=new Vector3d(position.getPosition()).add(0,1.4,0);
                var p=position.getPosition();var point=PetMotion.follow(transform.getPosition().x,transform.getPosition().z,p.x,p.z,position.getRotation().yaw());
                target.set(point.x(),p.y+.85,point.z());
            }
            if(lookAt!=null) {
                var direction=lookAt.sub(transform.getPosition());
                float yaw=(float)Math.atan2(-direction.x,-direction.z);
                float pitch=(float)Math.atan2(direction.y,Math.hypot(direction.x,direction.z));
                transform.setRotation(new Rotation3f(0,yaw,0));
                var head=store.getComponent(pet.ref,HeadRotation.getComponentType());
                if(head!=null)head.setRotation(new Rotation3f(pitch,yaw,0));
            }
            var current=transform.getPosition();boolean recover=current.distanceSquared(target)>144;
            Vector3d next=recover?target:new Vector3d(PetMotion.blend(current.x,target.x,dt),PetMotion.blend(current.y,target.y,dt),PetMotion.blend(current.z,target.z,dt));
            if(pet.bounds!=null){next.x=pet.bounds.x(next.x);next.z=pet.bounds.z(next.z);}
            if(clear(world,next)&& (recover||clear(world,new Vector3d(current).add(next).mul(.5))))transform.setPosition(next);
        }
    }

    private Vector3d hoverSurface(World world,double x,double z,int baseline) {
        if(!ChunkSectionBlockUtil.isChunkInMemory(world,(int)Math.floor(x),(int)Math.floor(z)))return null;
        for(int d=0;d<=8;d++)for(int sign:new int[]{-1,1}) {
            int y=baseline+d*sign;if(y<0||y>316)continue;
            var block=ChunkSectionBlockUtil.blockType(world,(int)Math.floor(x),y,(int)Math.floor(z));
            if(block!=null&&block!=BlockType.EMPTY&&block.getMaterial()!=BlockMaterial.Empty) {
                var position=new Vector3d(x,y+1.4,z);if(clear(world,position))return position;
            }
        }
        return null;
    }
    private static boolean clear(World world,Vector3d position) {
        if(!Double.isFinite(position.x+position.y+position.z)||position.y<1||position.y>317)return false;
        for(double dx:new double[]{-.35,.35})for(double dz:new double[]{-.35,.35})for(double dy:new double[]{0,.8}) {
            int x=(int)Math.floor(position.x+dx),z=(int)Math.floor(position.z+dz),y=(int)Math.floor(position.y+dy);
            if(!ChunkSectionBlockUtil.isChunkInMemory(world,x,z)||ChunkSectionBlockUtil.sectionRefAt(world,x,y,z)==null)return false;
            var block=ChunkSectionBlockUtil.blockType(world,x,y,z);if(block!=null&&block!=BlockType.EMPTY&&block.getMaterial()!=BlockMaterial.Empty)return false;
        }
        return true;
    }
    private static void remove(Store<EntityStore> store,VisiblePet pet){if(pet.ref.isValid()&&pet.ref.getStore()==store)store.removeEntity(pet.ref,RemoveReason.REMOVE);}

    void departed(RemovedPlayerFromWorldEvent event) {
        var player=event.getHolder().getComponent(PlayerRef.getComponentType());if(player==null)return;
        UUID actor=player.getUuid();Appearance state=appearances.remove(actor);displayNames.remove(actor);statuses.remove(actor);
        if(state!=null){event.getHolder().putComponent(PlayerSkinComponent.getComponentType(),new PlayerSkinComponent(new PlayerSkin(state.original)));
            event.getHolder().putComponent(DisplayNameComponent.getComponentType(),state.originalName==null?new DisplayNameComponent(Message.raw(player.getUsername())):state.originalName);}
        World world=event.getWorld();world.execute(()->{var pets=worldPets.get(world.getName());if(pets==null)return;var iterator=pets.values().iterator();while(iterator.hasNext()){var pet=iterator.next();if(pet.plan.property==null&&pet.plan.pet.owner().equals(Owner.player(actor))){remove(world.getEntityStore().getStore(),pet);iterator.remove();}}});
    }
    void removedWorld(World world){worldPets.remove(world.getName());}
    @Override public void close() {
        closed=true;executor.shutdownNow();
        if(Universe.get()!=null)for(World world:List.copyOf(Universe.get().getWorlds().values()))if(world.isAlive())world.execute(()->{
            var store=world.getEntityStore().getStore();var pets=worldPets.remove(world.getName());if(pets!=null)pets.values().forEach(pet->remove(store,pet));
            for(PlayerRef player:world.getPlayerRefs()){var ref=player.getReference();Appearance state=appearances.remove(player.getUuid());if(ref==null||!ref.isValid()||state==null)continue;
                store.putComponent(ref,PlayerSkinComponent.getComponentType(),new PlayerSkinComponent(new PlayerSkin(state.original)));
                store.putComponent(ref,DisplayNameComponent.getComponentType(),state.originalName==null?new DisplayNameComponent(Message.raw(player.getUsername())):state.originalName);}
        });
        displayNames.clear();statuses.clear();
    }
    private static String safe(String text){return text==null?"Invalid content":text.length()>180?text.substring(0,180):text;}
    private record Selection(String prefix,String suffix,Map<String,String> outfit,Map<String,CollectionService.Definition> wearables){}
    private record Property(UUID id,HousingService.ClaimLocation location){}
    private record PetPlan(CollectionService.Pet pet,CollectionService.Definition definition,Property property){}
    private static final class Appearance {PlayerSkin original,lastApplied;final DisplayNameComponent originalName;Appearance(PlayerSkin original,DisplayNameComponent name){this.original=original;this.originalName=name;}}
    private static final class VisiblePet {final Ref<EntityStore> ref;PetPlan plan;Vector3d target;PetMotion.Bounds bounds;VisiblePet(Ref<EntityStore> ref,PetPlan plan,Vector3d target,PetMotion.Bounds bounds){this.ref=ref;this.plan=plan;this.target=target;this.bounds=bounds;}}
}
