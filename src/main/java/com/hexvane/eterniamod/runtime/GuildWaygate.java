package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/** A physical entrance to guild travel, never a new public travel anchor. */
public final class GuildWaygate {
    private GuildWaygate(){}
    private record WaygateKey(Owner owner) {}
    public static boolean canPlace(EterniaModPlugin plugin,HubPlotRecord plot,Owner custody){return canPlace(plugin,plot,custody,false);}
    public static boolean canPlace(EterniaModPlugin plugin,HubPlotRecord plot,Owner custody,boolean preview){
        return plot.getGuildOwnerUuid()!=null&&Owner.guild(plot.getGuildOwnerUuid()).equals(custody)
            &&(preview?plugin.getServices().readView(new WaygateKey(custody),java.time.Duration.ofSeconds(5),()->plugin.getServices().ownership().owns(custody,GuildBenefitService.WAYGATE)):plugin.getServices().ownership().owns(custody,GuildBenefitService.WAYGATE))
            &&plot.getProps().stream().noneMatch(prop->prop.getPropId().equals("guild_waygate"));
    }
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Vector3i position){
        if(position==null)return;
        String world=store.getExternalData().getWorld().getName();
        try{WorldDestinationsMenu.open(plugin,ref,store,player,(r,s)->requireUse(plugin,r,s,player,world,position));}
        catch(IllegalStateException error){player.sendMessage(Message.raw(error.getMessage()));}
    }
    private static void requireUse(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String expectedWorld,Vector3i position){
        var world=store.getExternalData().getWorld();
        if(!world.getName().equals(expectedWorld))throw new IllegalStateException("Return to the guild portal to choose a destination.");
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        if(transform==null||transform.getPosition().distanceSquared(position.x+.5,position.y+.5,position.z+.5)>64)throw new IllegalStateException("Move closer to the guild portal.");
        var block=ChunkSectionBlockUtil.blockType(world,position);
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).findPlotContainingHorizontal(position.x,position.z);
        if(block==null||!block.getId().equals("Eternia_Guild_Waygate")||plot==null||plot.getGuildOwnerUuid()==null)throw new IllegalStateException("This portal must be placed through the guild build inventory.");
        var owner=Owner.guild(plot.getGuildOwnerUuid());
        if(!HousingAccess.can(plugin,plot,player.getUuid(),"convenience.use")||!plugin.getServices().ownership().owns(owner,GuildBenefitService.WAYGATE))throw new IllegalStateException("Your guild needs an active Waygate unlock, and your role must allow its use.");
        var prop=plot.getProps().stream().filter(p->p.getPropId().equals("guild_waygate")&&p.getAnchorX()==position.x&&(p.getAnchorY()==position.y||p.getAnchorY()+1==position.y)&&p.getAnchorZ()==position.z).findFirst().orElse(null);
        var instance=prop==null?null:plugin.getServices().provenance().find(prop.getInstanceId()).orElse(null);
        if(instance==null||!instance.owner().equals(owner)||!instance.propertyId().equals(plot.getPlotId())||!instance.contentId().equals(GuildBenefitService.WAYGATE_PROP)||!instance.state().equals("PLACED"))throw new IllegalStateException("Pack and replace this portal from the guild build inventory before using it.");
    }
}
