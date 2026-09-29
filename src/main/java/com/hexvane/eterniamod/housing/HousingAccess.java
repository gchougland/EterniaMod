package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import java.util.UUID;

public final class HousingAccess {
    private HousingAccess() {}
    public static Owner owner(HubPlotRecord plot){return plot.getGuildOwnerUuid()!=null?Owner.guild(plot.getGuildOwnerUuid()):plot.getOwnerUuid()==null?null:Owner.player(plot.getOwnerUuid());}
    public static boolean locked(EterniaModPlugin plugin,HubPlotRecord plot) {
        Owner owner=owner(plot);if(owner==null)return true;
        var slot=plugin.getServices().housing().find(owner).orElse(null);
        if(slot!=null&&(slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(plot.getPlotId())))return true;
        return plugin.getServices().journal().unfinished().stream().anyMatch(op->op.owner().equals(owner));
    }
    public static boolean can(EterniaModPlugin plugin,HubPlotRecord plot,UUID player,String capability) {
        if(locked(plugin,plot))return false;
        if(plot.isOwnedBy(player))return true;
        if(capability.equals("housing.door.use")&&com.hexvane.eterniamod.inventory.ui.NativeShopTravel.isGuest(player,plot))return true;
        return plot.getGuildOwnerUuid()!=null&&plugin.getServices().guilds().can(player,plot.getGuildOwnerUuid(),capability);
    }
}
