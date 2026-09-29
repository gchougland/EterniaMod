package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import java.util.UUID;

/** Plot edit authority and inventory custody are separate; neither implies the other. */
public final class HousingCustody {
    private HousingCustody() {}
    public static final String PLACE="housing.prop.place",PACK="housing.prop.pack",RESERVE="inventory.reserve_for_build";
    public static boolean allows(Owner property,UUID attachedGuild,Owner custody,boolean plotPermission,UUID currentGuild,boolean reservePermission){
        if(property==null||custody==null||!plotPermission)return false;
        if(custody.kind()==Owner.Kind.PLAYER)return custody.equals(property);
        if(custody.kind()!=Owner.Kind.GUILD||!custody.id().equals(currentGuild)||!reservePermission)return false;
        return custody.equals(property)||property.kind()==Owner.Kind.PLAYER&&custody.id().equals(attachedGuild);
    }
    public static boolean can(EterniaModPlugin plugin,HubPlotRecord plot,UUID actor,Owner custody,String capability){
        UUID guild=plugin.getServices().guilds().membership(actor).map(m->m.guildId()).orElse(null);
        boolean reserve=custody!=null&&custody.kind()==Owner.Kind.GUILD&&plugin.getServices().guilds().can(actor,custody.id(),RESERVE);
        return allows(HousingAccess.owner(plot),plot.getAttachedGuildUuid(),custody,HousingAccess.can(plugin,plot,actor,capability),guild,reserve);
    }
    public static void require(EterniaModPlugin plugin,HubPlotRecord plot,UUID actor,Owner custody,String capability){
        if(!can(plugin,plot,actor,custody,capability))throw new IllegalStateException("Current plot permission, guild membership and guild build-inventory permission are required for this inventory source");
    }
}
