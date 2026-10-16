package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
import java.util.*;

/** Edge triggered entry: closing the picker while standing inside does not open it again. */
public final class GuildWaygateEntrySystem extends EntityTickingSystem<EntityStore> {
    private final EterniaModPlugin plugin;
    private final Map<Ref<EntityStore>,UUID> inside=Collections.synchronizedMap(new WeakHashMap<>());
    public GuildWaygateEntrySystem(EterniaModPlugin plugin){this.plugin=plugin;}
    @Override public Query<EntityStore> getQuery(){return Player.getComponentType();}
    @Override public void tick(float dt,int index,ArchetypeChunk<EntityStore> chunk,Store<EntityStore> store,CommandBuffer<EntityStore> commands){
        var ref=chunk.getReferenceTo(index);var transform=store.getComponent(ref,TransformComponent.getComponentType());var viewer=store.getComponent(ref,PlayerRef.getComponentType());
        if(transform==null||viewer==null)return;var position=transform.getPosition();var world=store.getExternalData().getWorld();
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).findPlotContainingHorizontal((int)Math.floor(position.x),(int)Math.floor(position.z));
        HubPlotProp entrance=null;Vector3i block=null;
        if(plot!=null&&plot.getGuildOwnerUuid()!=null)for(var prop:plot.getProps()){
            if(!prop.getPropId().equals("guild_waygate"))continue;
            if(Math.abs(position.x-(prop.getAnchorX()+.5))>.65||Math.abs(position.z-(prop.getAnchorZ()+.5))>.65)continue;
            // Both the original raised portal and the current ground level portal remain usable.
            for(int offset=0;offset<=1;offset++){
                int y=prop.getAnchorY()+offset;if(position.y<y-.25||position.y>y+2)continue;
                var type=ChunkSectionBlockUtil.blockType(world,prop.getAnchorX(),y,prop.getAnchorZ());
                if(type!=null&&type.getId().equals("Eternia_Guild_Waygate")){entrance=prop;block=new Vector3i(prop.getAnchorX(),y,prop.getAnchorZ());break;}
            }
            if(entrance!=null)break;
        }
        if(entrance==null){inside.remove(ref);return;}
        UUID previous=inside.put(ref,entrance.getInstanceId());if(entrance.getInstanceId().equals(previous))return;
        if(store.getComponent(ref,Player.getComponentType()).getPageManager().getCustomPage()!=null)return;
        Vector3i target=block;world.execute(()->{if(ref.isValid())GuildWaygate.open(plugin,ref,store,viewer,target);});
    }
}
