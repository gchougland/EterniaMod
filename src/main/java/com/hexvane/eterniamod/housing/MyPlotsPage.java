package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Account-level property directory; finding or packing a plot never requires standing inside it. */
public final class MyPlotsPage {
    private record Property(Owner owner,HousingService.Slot slot,HousingService.ClaimLocation location) {}
    private MyPlotsPage() {}
    private static String hexagons(int count){return count==1?"1 hexagon":count+" hexagons";}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        var choices=new ArrayList<ChoicePage.Choice>();
        var services=plugin.getServices();
        var directory=services.batch(()->{
            var owners=new ArrayList<Owner>();owners.add(Owner.player(player.getUuid()));services.guilds().membership(player.getUuid()).ifPresent(m->owners.add(Owner.guild(m.guildId())));
            return owners.stream().map(owner->new Property(owner,services.housing().find(owner).orElse(null),services.housing().location(owner).orElse(null))).toList();
        });
        for(var entry:directory) {
            var owner=entry.owner();var slot=entry.slot();var location=entry.location();
            String name=owner.kind()==Owner.Kind.PLAYER?"Your plot":"Guild estate";
            if(slot==null){choices.add(new ChoicePage.Choice(name+" · No plot claimed",com.hexvane.eterniamod.claimmap.ClaimMapRoutes.inHexWorld(store)?"Choose a plot":"Preview claim",(r,s)->com.hexvane.eterniamod.claimmap.ClaimMapRoutes.claimNew(plugin,r,s,player,owner.kind()==Owner.Kind.GUILD)));continue;}
            String where=location==null?"Location unavailable":location.cells()!=null?com.hexvane.eterniamod.runtime.WorldDisplayNames.name(location.worldId())+" · "+hexagons(location.cells().size()):com.hexvane.eterniamod.runtime.WorldDisplayNames.name(location.worldId())+" · X "+location.minX()+" to "+(location.minX()+location.width()-1)+", Z "+location.minZ()+" to "+(location.minZ()+location.depth()-1);
            choices.add(new ChoicePage.Choice(name+" · "+slot.state()+"\n"+where,"Manage",(r,s)->{
                var actions=new ArrayList<ChoicePage.Choice>();
                var pending=plugin.getServices().journal().unfinished().stream().filter(op->op.owner().equals(owner)).toList();
                if(!pending.isEmpty())actions.add(new ChoicePage.Choice("A housing placement was interrupted. Check it to restore access to your plot.","Recover placement",(rr,ss)->{
                    PropRecoveryMenu.open(plugin,rr,ss,player,owner);
                }));
                if(slot.state()==HousingService.State.ACTIVE){
                    actions.add(new ChoicePage.Choice("Travel to the edge of this plot from a public travel point","Locate plot",(rr,ss)->plugin.getTravel().visitPlot(player,owner)));
                    if(location!=null&&location.cells()!=null)actions.add(new ChoicePage.Choice("Grow your plot by one hexagon beside it. Each hexagon costs Coins.","Add a hexagon",(rr,ss)->{
                        if(!ss.getExternalData().getWorld().getName().equals(location.worldId())||!com.hexvane.eterniamod.claimmap.ClaimMapRoutes.expand(plugin,rr,ss,player,slot.propertyId()))
                            player.sendMessage(Message.raw("Go to "+com.hexvane.eterniamod.runtime.WorldDisplayNames.name(location.worldId())+" to add a hexagon to this plot."));
                    }));
                    actions.add(new ChoicePage.Choice("Save the plot, house and belongings, then choose a new site","Move plot",(rr,ss)->ChoicePage.open(rr,ss,player,"Move "+name.toLowerCase()+"?","This uses one move credit. You have "+plugin.getServices().ownership().available(owner,HousingService.MOVE_CREDIT)+". After packing, use your Plot Deed or /e claim to restore it. No separate move tool is needed.",List.of(new ChoicePage.Choice("Pack the entire plot and reserve one move credit","Confirm move",(rrr,sss)->{
                        var current=plugin.getServices().housing().location(owner).orElseThrow();var world=Universe.get().getWorld(current.worldId());
                        if(world==null){player.sendMessage(Message.raw("This plot’s world is not loaded."));return;}
                        world.execute(()->plugin.getPlotMover().request(world,owner,player));
                    })))));
                }else if(slot.state()==HousingService.State.PACKED)actions.add(new ChoicePage.Choice("Restore your saved plot in a new location","Place packed plot",(rr,ss)->com.hexvane.eterniamod.claimmap.ClaimMapRoutes.claimNew(plugin,rr,ss,player,owner.kind()==Owner.Kind.GUILD)));
                actions.add(new ChoicePage.Choice("Update the saved state of this property","Refresh",(rr,ss)->open(plugin,rr,ss,player)));
                ChoicePage.open(r,s,player,name,where+"\n"+(slot.state()==HousingService.State.ACTIVE?"The land is already claimed, even if a house has not been placed yet.":"State: "+slot.state()+". Unfinished operations retain your belongings; an administrator can inspect operation "+slot.operationId()+"."),actions);
            }));
        }
        ChoicePage.open(ref,store,player,"My plots","Find your land, restore a packed home, or review moving it. Closing a placement preview does not remove an already confirmed claim.",choices);
    }
}
