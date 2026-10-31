package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.bson.*;
import org.joml.Vector3i;

/** Runs only in the isolated native acceptance server. */
public final class NativeHouseRecoverySmoke {
    private NativeHouseRecoverySmoke() {}
    public static void exercise(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor) throws Exception {
        var owner=NativePlacementTransactions.owner(plot);
        plugin.getServices().ownership().grant(new OwnershipService.GrantRequest("native-manor",owner,"eternia:house/hearthstone_manor",OwnershipService.Kind.UNLOCK,1,null));
        var definition=plugin.getBuildingCatalog().get("hearthstone_manor");
        var buffer=PrefabResolveUtil.resolvePrefabBuffer(definition.getPrefabPath());
        var yaw=Rotation.values()[1];var origin=new Vector3i(20,122,20);
        var placed=NativePlacementTransactions.place(plugin,world,plot,actor,"hearthstone_manor",origin,yaw,buffer,true);
        plot.setBuilding(new HubPlotBuilding("hearthstone_manor",20,122,20,yaw,List.of()));
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        manager.updatePlot(plot);manager.saveIfDirty();plugin.getServices().housing().updateBuildingPresent(owner,plot.getPlotId(),true);
        NativePlacementTransactions.complete(plugin,placed.operation());
        pack(plugin,world,plot,actor);

        // Reproduce the old interrupted manifest: no pose, no provenance, all house blocks
        // and decorative entities already in the world, with a recovery lock.
        var snapshots=NativePlacementTransactions.snapshots(plugin);var instance=UUID.randomUUID();var operation=UUID.randomUUID();
        var document=placed.after().document().clone();
        for(var value:document.getArray("entities")) {
            var registry=com.hypixel.hytale.server.core.universe.world.storage.EntityStore.REGISTRY;
            var holder=registry.deserialize(value.asDocument());
            holder.putComponent(EterniaPlacedInstance.getComponentType(),new EterniaPlacedInstance(EterniaPlacedInstance.Kind.BUILDING,instance,"hearthstone_manor"));
            holder.putComponent(com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType(),com.hypixel.hytale.server.core.entity.UUIDComponent.generateVersion3UUID());
            value.asDocument().clear();value.asDocument().putAll(registry.serialize(holder));
        }
        var after=snapshots.save(operation+"-after.json",placed.after().bounds(),document);
        var before=placed.before();
        var manifest=new BsonDocument("before",new BsonString(before.file().reference())).append("beforeHash",new BsonString(before.file().sha256()))
            .append("after",new BsonString(after.file().reference())).append("afterHash",new BsonString(after.file().sha256()))
            .append("instance",new BsonString(instance.toString())).append("source",new BsonString("unlock:eternia:house/hearthstone_manor"));
        var saved=snapshots.files().write(operation+"-placement.json",manifest.toJson().getBytes(StandardCharsets.UTF_8));
        var journal=plugin.getServices().journal();var op=journal.prepare(operation,"PLACE_HOUSE",owner,plot.getPlotId().toString());
        op=journal.attachVerifiedSnapshot(operation,op.revision(),saved.reference(),saved.sha256());journal.advance(operation,op.revision(),JournalService.State.RECOVERY_REQUIRED);
        snapshots.apply(world,after,after.bounds().origin());
        if(!com.hexvane.eterniamod.housing.HousingAccess.locked(plugin,plot))throw new AssertionError("Interrupted house must lock its plot");
        try{FailedHouseRecovery.recover(plugin,world,plot,UUID.randomUUID());throw new AssertionError("Foreign actor recovered a house");}catch(IllegalStateException expected){}
        var cell=after.document().getArray("blocks").getFirst().asDocument().clone();
        var single=after.document().clone();single.remove("entities");single.put("blocks",new BsonArray(List.of(cell)));single.put("fluids",new BsonArray());
        var original=snapshots.save(operation+"-cell.json",after.bounds(),single);
        var changed=single.clone();var changedCell=changed.getArray("blocks").getFirst().asDocument();changedCell.remove("components");
        changedCell.put("name",new BsonString("Soil_Dirt".equals(cell.getString("name").getValue())?"Rock_Stone":"Soil_Dirt"));
        snapshots.apply(world,snapshots.save(operation+"-edited.json",after.bounds(),changed),after.bounds().origin());
        try{if(FailedHouseRecovery.recover(plugin,world,plot,actor))throw new AssertionError("Recovery accepted changed blocks");}catch(IllegalStateException expected){}
        if(plugin.getServices().provenance().find(instance).isPresent()||plot.hasBuilding())throw new AssertionError("Rejected recovery wrote house metadata");
        snapshots.apply(world,original,after.bounds().origin());
        if(!FailedHouseRecovery.recover(plugin,world,plot,actor)||FailedHouseRecovery.recover(plugin,world,plot,actor))throw new AssertionError("Legacy recovery must finish exactly once");
        if(!plot.hasBuilding()||plot.getBuilding().resolveRotationYaw()!=yaw||plot.getBuilding().getAnchorY()!=122
            ||!com.hexvane.eterniamod.housing.HousingAccess.can(plugin,plot,actor,"housing.door.use"))throw new AssertionError("Recovered house did not restore pose and owner door access");
        snapshots.verify(world,after,after.bounds().origin(),Set.of(instance),true);
        pack(plugin,world,plot,actor);
        snapshots.verify(world,before,before.bounds().origin(),Set.of(),true);

        // Crash after provenance was already saved, before house metadata was written.
        var restored=NativePlacementTransactions.place(plugin,world,plot,actor,"hearthstone_manor",origin,yaw,buffer,true);
        NativePlacementTransactions.lock(plugin,restored.operation());
        if(!FailedHouseRecovery.recover(plugin,world,plot,actor))throw new AssertionError("Recorded placement recovery failed");
        pack(plugin,world,plot,actor);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_MANOR_PASS: rotated Hearthstone Manor at Y=122, legacy and recorded recovery, unchanged entity contents, foreign actor and edited block rejection, owner door access and exact packing");
    }
    private static void pack(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor) throws Exception {
        var operation=NativePlacementTransactions.pickup(plugin,world,plot,actor,plot.getPlotId());
        plot.setBuilding(null);var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);manager.updatePlot(plot);manager.saveIfDirty();
        plugin.getServices().housing().updateBuildingPresent(NativePlacementTransactions.owner(plot),plot.getPlotId(),false);NativePlacementTransactions.complete(plugin,operation);
    }
}
