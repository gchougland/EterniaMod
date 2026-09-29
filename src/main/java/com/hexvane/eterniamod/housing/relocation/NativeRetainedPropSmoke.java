package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bson.*;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;

/** Optional diagnosis from an explicitly staged COPY, only called by the isolated native smoke. */
public final class NativeRetainedPropSmoke {
    private NativeRetainedPropSmoke() {}
    public static void run(EterniaModPlugin plugin) throws Exception {
        String staged=System.getenv("ETERNIA_RETAINED_PROP_FIXTURE");if(staged==null||staged.isBlank())return;
        Path source=Path.of(staged).toAbsolutePath().normalize();
        if(!source.startsWith(Path.of(System.getenv("ETERNIA_WORKSPACE_ROOT")).resolve("build").toAbsolutePath().normalize()))throw new IllegalStateException("Retained fixture must be staged under build");
        var target=Universe.get().getWorldsPath().resolve("retained_prop_case");
        try(var files=Files.walk(source.resolve("world"))){for(var file:files.toList()){
            Path destination=target.resolve(source.resolve("world").relativize(file));
            if(Files.isDirectory(file))Files.createDirectories(destination);else Files.copy(file,destination);
        }}
        var world=Universe.get().loadWorld("retained_prop_case").join();
        var snapshots=new NativeSnapshotStore(source);var after=snapshots.load(new SnapshotFiles.Saved("after.json",SnapshotFiles.hash(Files.readAllBytes(source.resolve("after.json")))));
        var b=after.bounds();com.hexvane.eterniamod.runtime.TravelLanding.load(world,new org.joml.Vector3d(b.minX(),b.minY(),b.minZ()),4).join();
        var done=new CompletableFuture<Void>();world.execute(()->{try{
            var actual=snapshots.capture(world,b,Set.of(),true,false);
            Files.writeString(source.resolve("comparison.json"),new BsonDocument("expected",after.document()).append("actual",actual).toJson());
            plugin.getLogger().atInfo().log("ETERNIA_RETAINED_PROP_COMPARISON: matches=%s",NativeSnapshotStore.comparisonDocument(actual).equals(NativeSnapshotStore.comparisonDocument(after.document())));
            if(!NativeSnapshotStore.comparisonDocument(actual).equals(NativeSnapshotStore.comparisonDocument(after.document())))throw new IllegalStateException("Retained cactus cells differ from their placement");
            var owner=Owner.player(UUID.randomUUID());var property=UUID.randomUUID();var claim=UUID.randomUUID();var services=plugin.getServices();
            services.housing().beginClaim(owner,property,claim,null,new HousingService.ClaimLocation(world.getName(),b.minX()-2,b.minZ()-2,24,24,HousingService.ClaimScope.PUBLIC,null,false));
            var claimOp=services.journal().find(claim).orElseThrow();services.journal().advance(claim,claimOp.revision(),JournalService.State.WORLD_APPLIED);services.housing().activate(owner,claim);
            var plot=new HubPlotRecord(property,world.getName(),HubPlotFootprint.forCreate(b.minX()-2,b.minX()+21,b.minZ()-2,b.minZ()+21,b.minY()),owner.id());
            var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);manager.addPlot(plot);manager.saveIfDirty();
            services.ownership().grant(new OwnershipService.GrantRequest("retained-cactus",owner,"eternia:prop/cacti",OwnershipService.Kind.QUANTITY,1,null));
            var opId=UUID.randomUUID();var reservation=services.ownership().reserve(owner,"eternia:prop/cacti",1,"retained-cactus-reservation");
            var before=snapshots.load(new SnapshotFiles.Saved("before.json",SnapshotFiles.hash(Files.readAllBytes(source.resolve("before.json")))));
            var nativeStore=NativePlacementTransactions.snapshots(plugin);
            var savedBefore=nativeStore.save(opId+"-before.json",b,before.document());var savedAfter=nativeStore.save(opId+"-after.json",b,after.document());
            var manifest=new BsonDocument("before",new BsonString(savedBefore.file().reference())).append("beforeHash",new BsonString(savedBefore.file().sha256()))
                .append("after",new BsonString(savedAfter.file().reference())).append("afterHash",new BsonString(savedAfter.file().sha256()))
                .append("instance",new BsonString(UUID.randomUUID().toString())).append("source",new BsonString(reservation.id()));
            var file=nativeStore.files().write(opId+"-placement.json",manifest.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var op=services.journal().prepare(opId,"PLACE_PROP",owner,property.toString());op=services.journal().attachVerifiedSnapshot(opId,op.revision(),file.reference(),file.sha256());services.journal().advance(opId,op.revision(),JournalService.State.RECOVERY_REQUIRED);
            FailedPropRecovery.loadPending(plugin,world,plot,owner.id()).whenComplete((v,error)->world.execute(()->{try{
                if(error!=null)throw new CompletionException(error);
                if(FailedPropRecovery.recover(plugin,world,plot,owner.id())!=1||services.ownership().available(owner,"eternia:prop/cacti")!=1||com.hexvane.eterniamod.housing.HousingAccess.locked(plugin,plot))throw new IllegalStateException("Retained recovery did not return custody");
                plugin.getLogger().atInfo().log("ETERNIA_RETAINED_PROP_RECOVERY_PASS: saved player cactus cells loaded, original ground restored, one catalog quantity returned, plot unlocked");done.complete(null);
            }catch(Throwable failure){done.completeExceptionally(failure);}}));
        }catch(Throwable error){done.completeExceptionally(error);}});done.join();
    }
}
