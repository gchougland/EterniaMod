package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.*;
import org.joml.Vector3i;

/** Isolated native acceptance: the cactus timestamp regression and interrupted placement recovery. */
public final class NativePropRecoverySmoke {
    private NativePropRecoverySmoke() {}
    public static void exercise(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor) throws Exception {
        var owner=NativePlacementTransactions.owner(plot);var services=plugin.getServices();
        services.ownership().grant(new OwnershipService.GrantRequest("native-cactus",owner,"eternia:prop/cacti",OwnershipService.Kind.QUANTITY,1,null));
        var definition=plugin.getPropCatalog().get("cacti");var buffer=PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());
        var placed=NativePlacementTransactions.place(plugin,world,plot,actor,"cacti",new Vector3i(11,1,12),Rotation.None,buffer,false);
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        plot.addProp(new HubPlotProp(placed.instanceId(),"cacti",11,1,12,Rotation.None));manager.updatePlot(plot);manager.saveIfDirty();NativePlacementTransactions.complete(plugin,placed.operation());
        var pickup=NativePlacementTransactions.pickup(plugin,world,plot,actor,placed.instanceId());plot.removeProp(placed.instanceId());manager.updatePlot(plot);manager.saveIfDirty();NativePlacementTransactions.complete(plugin,pickup);

        services.ownership().grant(new OwnershipService.GrantRequest("native-cactus-recovery",owner,"eternia:prop/cacti",OwnershipService.Kind.QUANTITY,1,null));
        var snapshots=NativePlacementTransactions.snapshots(plugin);var operation=UUID.randomUUID();var instance=UUID.randomUUID();
        var reservation=services.ownership().reserve(owner,"eternia:prop/cacti",1,"native-recovery:"+operation);
        var manifest=new BsonDocument("before",new BsonString(placed.before().file().reference())).append("beforeHash",new BsonString(placed.before().file().sha256()))
            .append("after",new BsonString(placed.after().file().reference())).append("afterHash",new BsonString(placed.after().file().sha256()))
            .append("instance",new BsonString(instance.toString())).append("source",new BsonString(reservation.id()));
        var file=snapshots.files().write(operation+"-placement.json",manifest.toJson().getBytes(StandardCharsets.UTF_8));
        var op=services.journal().prepare(operation,"PLACE_PROP",owner,plot.getPlotId().toString());op=services.journal().attachVerifiedSnapshot(operation,op.revision(),file.reference(),file.sha256());
        services.journal().advance(operation,op.revision(),JournalService.State.RECOVERY_REQUIRED);
        snapshots.apply(world,placed.after(),placed.after().bounds().origin());
        try{FailedPropRecovery.recover(plugin,world,plot,UUID.randomUUID());throw new AssertionError("Foreign actor recovered property");}catch(IllegalStateException expected){}
        var changed=placed.after().document().clone();changed.getArray("blocks").get(0).asDocument().put("name",new BsonString("Soil_Dirt"));
        var edited=snapshots.save(operation+"-edited.json",placed.after().bounds(),changed);snapshots.apply(world,edited,edited.bounds().origin());
        try{FailedPropRecovery.recover(plugin,world,plot,actor);throw new AssertionError("Recovery overwrote an edited block");}catch(IllegalStateException expected){}
        if(!services.ownership().findReservation(reservation.id()).orElseThrow().state().equals("RESERVED"))throw new AssertionError("Rejected recovery returned quantity");
        snapshots.apply(world,placed.after(),placed.after().bounds().origin());
        if(FailedPropRecovery.recover(plugin,world,plot,actor)!=1||FailedPropRecovery.recover(plugin,world,plot,actor)!=0)throw new AssertionError("Recovery must complete once");
        if(services.ownership().available(owner,"eternia:prop/cacti")!=1||com.hexvane.eterniamod.housing.HousingAccess.locked(plugin,plot))throw new AssertionError("Recovery did not return exactly one item and unlock property");
        snapshots.verify(world,placed.before(),placed.before().bounds().origin(),Set.of(),true);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_PROP_RECOVERY_PASS: cactus placement/pickup, clock initialization, foreign actor and edited-block rejection, single quantity return and unlocked plot");
    }
}
