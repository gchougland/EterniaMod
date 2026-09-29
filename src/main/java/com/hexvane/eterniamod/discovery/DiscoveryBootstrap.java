package com.hexvane.eterniamod.discovery;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.*;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** One authored block-use adapter. No loot-table or physical-token authority is involved. */
public final class DiscoveryBootstrap implements AutoCloseable {
    private static volatile DiscoveryBootstrap active;
    private final EterniaModPlugin plugin;
    private final EterniaServices services;
    private final HousingInfrastructure infrastructure;
    private final DiscoveryRegistry registry;
    private final LocalDiscoveryRegistry localExamples;
    private volatile boolean closed;
    public DiscoveryBootstrap(EterniaModPlugin plugin,EterniaServices services,HousingInfrastructure infrastructure,Path definitionsFile)throws IOException {
        this.plugin=Objects.requireNonNull(plugin);this.services=Objects.requireNonNull(services);this.infrastructure=Objects.requireNonNull(infrastructure);registry=DiscoveryRegistry.load(definitionsFile);
        localExamples=plugin.getRuntimeConfig().local()?new LocalDiscoveryRegistry(plugin.getDataDirectory().resolve("local-playground-discoveries.json")):null;
        if(localExamples!=null){var combined=new ArrayList<>(registry.all());localExamples.all().forEach(e->combined.add(e.definition()));new DiscoveryRegistry(combined);}
        for(var definition:registry.all())if(!adventureWorld(definition.world()))throw new IOException("Discovery "+definition.id()+" must name an infrastructure world with role adventure");
        plugin.getCodecRegistry(Interaction.CODEC).register("EterniaDiscovery",EterniaDiscoveryInteraction.class,EterniaDiscoveryInteraction.CODEC);
        plugin.getEntityStoreRegistry().registerSystem(new CacheBreak());plugin.getEntityStoreRegistry().registerSystem(new CacheDamage());plugin.getEntityStoreRegistry().registerSystem(new CacheEnvironmentDamage());
        active=this;
    }
    static DiscoveryBootstrap active(){return active;}
    /** Saves server-owned discovery authority only. Rewards still require the real native Use interaction. */
    public static void registerLocalExample(EterniaModPlugin plugin,World world,UUID actor,DiscoveryService.Definition definition)throws IOException{
        com.hexvane.eterniamod.localplayground.LocalPlayground.require(plugin,actor);world.getEntityStore().getStore().assertThread();
        var service=active;if(service==null||service.closed||service.plugin!=plugin||service.localExamples==null)throw new IllegalStateException("Discovery setup is unavailable");
        if(!world.getName().equals(com.hexvane.eterniamod.localplayground.LocalPlayground.TRIALS)||!definition.world().equals(world.getName())||!service.adventureWorld(world.getName())||!com.hexvane.eterniamod.localplayground.LocalPlayground.managedWorld(plugin,world.getName()))throw new IllegalStateException("Local discoveries require the managed adventure trials world");
        if(service.registry.all().stream().anyMatch(d->d.id().equals(definition.id())||d.world().equals(definition.world())&&d.x()==definition.x()&&d.y()==definition.y()&&d.z()==definition.z()))throw new IllegalStateException("An authored discovery already owns that identity or location");
        service.localExamples.ensure(new LocalDiscoveryRegistry.Entry(world.getWorldConfig().getUuid(),definition));
    }
    private Optional<DiscoveryService.Definition> at(World world,Vector3i position){
        var authored=registry.at(world.getName(),position.x,position.y,position.z);if(authored.isPresent()||localExamples==null)return authored;
        if(!com.hexvane.eterniamod.localplayground.LocalPlayground.managedWorld(plugin,world.getName()))return Optional.empty();
        return localExamples.at(world.getWorldConfig().getUuid(),world.getName(),position.x,position.y,position.z);
    }
    private boolean adventureWorld(String name){return infrastructure.world(name).map(w->w.role().equals("adventure")).orElse(false);}
    void use(World world,CommandBuffer<EntityStore> buffer,Ref<EntityStore> ref,Vector3i position) {
        if(closed)throw new IllegalStateException("Discoveries are unavailable while Eternia stops");
        var player=buffer.getComponent(ref,PlayerRef.getComponentType());var entity=buffer.getComponent(ref,Player.getComponentType());var transform=buffer.getComponent(ref,TransformComponent.getComponentType());
        if(player==null||entity==null||transform==null)throw new IllegalArgumentException("A player is required to collect a discovery");
        var definition=at(world,position).orElseThrow(()->new IllegalArgumentException("This decorative cache is not an authored discovery"));
        String nativeBlock=blockId(world,position);if(nativeBlock==null)throw new IllegalStateException("The discovery block is not loaded");
        double distance=transform.getPosition().distanceSquared(position.x+.5,position.y+.5,position.z+.5);
        var evidence=new DiscoveryService.Evidence(world.getWorldConfig().getUuid(),world.getName(),position.x,position.y,position.z,nativeBlock,entity.getGameMode()==GameMode.Adventure,adventureWorld(world.getName()),distance);
        var result=services.discoveries().redeem(player.getUuid(),definition,evidence);
        String label=label(result.reward().contentId());boolean quantity=result.reward().kind()==OwnershipService.Kind.QUANTITY;
        String title=result.firstCollection()?(quantity?"Housing token collected":"Content unlocked"):"Discovery already collected";
        String description=result.firstCollection()?(quantity?result.reward().quantity()+" × "+label+" was added to your owned collection.":label+" is now unlocked."):"You have already collected this discovery. Your reward remains recorded on your account.";
        player.sendMessage(Message.raw(title+". "+description));
        ChoicePage.open(ref,buffer.getStore(),player,title,description,List.of(new ChoicePage.Choice(label,"Continue exploring",(r,s)->{
            var component=s.getComponent(r,Player.getComponentType());if(component!=null)component.getPageManager().setPage(r,s,Page.None);
        })));
    }
    private String label(String content){
        if(content.startsWith("eternia:prop/")){var p=plugin.getPropCatalog().get(content.substring("eternia:prop/".length()));if(p!=null)return p.getDisplayName()==null?p.getId():p.getDisplayName();}
        if(content.startsWith("eternia:house/")){var h=plugin.getBuildingCatalog().get(content.substring("eternia:house/".length()));if(h!=null)return h.getDisplayName()==null?h.getId():h.getDisplayName();}
        return content;
    }
    private static String blockId(World world,Vector3i position){
        var sectionRef=world.getChunkStore().getChunkSectionReferenceAtBlock(position.x,position.y,position.z);if(sectionRef==null||!sectionRef.isValid())return null;
        var section=world.getChunkStore().getStore().getComponent(sectionRef,BlockSection.getComponentType());if(section==null)return null;
        var block=BlockType.getAssetMap().getAsset(section.get(ChunkUtil.indexBlock(position.x,position.y,position.z)));return block==null?null:block.getId();
    }
    private boolean protectedMarker(World world,Vector3i position){return !closed&&adventureWorld(world.getName())&&at(world,position).map(d->d.markerBlockId().equals(blockId(world,position))).orElse(false);}
    private boolean creative(ArchetypeChunk<EntityStore> chunk,int index){var player=chunk.getComponent(index,Player.getComponentType());return player!=null&&player.getGameMode()==GameMode.Creative;}
    private final class CacheBreak extends EntityEventSystem<EntityStore,BreakBlockEvent>{
        CacheBreak(){super(BreakBlockEvent.class);}public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,BreakBlockEvent event){if(!creative(chunk,index)&&protectedMarker(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}
    }
    private final class CacheDamage extends EntityEventSystem<EntityStore,DamageBlockEvent>{
        CacheDamage(){super(DamageBlockEvent.class);}public Query<EntityStore> getQuery(){return Query.any();}
        public void handle(int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> buffer,DamageBlockEvent event){if(!creative(chunk,index)&&protectedMarker(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}
    }
    private final class CacheEnvironmentDamage extends WorldEventSystem<EntityStore,DamageBlockEvent>{
        CacheEnvironmentDamage(){super(DamageBlockEvent.class);}
        public void handle(Store<EntityStore> store,CommandBuffer<EntityStore> buffer,DamageBlockEvent event){if(protectedMarker(store.getExternalData().getWorld(),event.getTargetBlock()))event.setCancelled(true);}
    }
    @Override public void close(){closed=true;if(active==this)active=null;}
}
