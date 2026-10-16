package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import java.util.UUID;

public final class HousingAccess {
    private HousingAccess() {}
    public static Owner owner(HubPlotRecord plot){return plot.getGuildOwnerUuid()!=null?Owner.guild(plot.getGuildOwnerUuid()):plot.getOwnerUuid()==null?null:Owner.player(plot.getOwnerUuid());}
    public static boolean locked(EterniaModPlugin plugin,HubPlotRecord plot) {
        Owner owner=owner(plot);var property=plot.getPlotId();
        return plugin.getServices().batch(()->locked(plugin.getServices(),owner,property));
    }
    private static boolean locked(EterniaServices services,Owner owner,UUID property){
        if(owner==null)return true;
        var slot=services.housing().find(owner).orElse(null);
        if(slot!=null&&(slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(property)))return true;
        return services.journal().unfinished().stream().anyMatch(op->op.owner().equals(owner));
    }
    private record AccessKey(Owner owner,UUID property,UUID player,String capability,boolean guest){}
    public static boolean can(EterniaModPlugin plugin,HubPlotRecord plot,UUID player,String capability){return check(plugin,plot,player,capability,false);}
    /** Advisory grid/overlay only. Every native mutation still calls can with fresh records. */
    public static boolean previewCan(EterniaModPlugin plugin,HubPlotRecord plot,UUID player,String capability){return check(plugin,plot,player,capability,true);}
    private static boolean check(EterniaModPlugin plugin,HubPlotRecord plot,UUID player,String capability,boolean preview){
        Owner owner=owner(plot);var property=plot.getPlotId();
        boolean guest=capability.equals("housing.door.use")&&com.hexvane.eterniamod.inventory.ui.NativeShopTravel.isGuest(player,plot);
        var services=plugin.getServices();java.util.function.Supplier<Boolean> read=()->!locked(services,owner,property)
            &&(owner.equals(Owner.player(player))||guest||owner.kind()==Owner.Kind.GUILD&&services.guilds().can(player,owner.id(),capability));
        return preview?services.readView(new AccessKey(owner,property,player,capability,guest),java.time.Duration.ofSeconds(5),read):services.batch(read);
    }
}
