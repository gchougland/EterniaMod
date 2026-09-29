package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.housing.HousingCustody;
import com.hexvane.eterniamod.hub.*;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bson.*;

/** Roll back an unrecorded block-only prop, only when the world still matches a saved endpoint.
 * Never infer custody from location or discard an edited block/container/entity. World-thread only. */
public final class FailedPropRecovery {
    private FailedPropRecovery() {}
    /** Loading is asynchronous; never join chunk work from its owning world thread. */
    public static java.util.concurrent.CompletableFuture<Void> loadPending(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor) throws Exception {
        world.getEntityStore().getStore().assertThread();
        if(!plot.isOwnedBy(actor)&&(plot.getGuildOwnerUuid()==null||!plugin.getServices().guilds().can(actor,plot.getGuildOwnerUuid(),HousingCustody.PACK)))throw new IllegalStateException("Plot packaging permission is required.");
        var snapshots=NativePlacementTransactions.snapshots(plugin);
        var columns=new ArrayList<java.util.concurrent.CompletableFuture<?>>();
        var bounds=new ArrayList<NativeSnapshotStore.Bounds>();
        for(var op:plugin.getServices().journal().unfinished())if(op.owner().equals(HousingAccess.owner(plot))&&op.kind().equals("PLACE_PROP")) {
            var manifest=BsonDocument.parse(new String(snapshots.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
            var b=snapshots.load(new SnapshotFiles.Saved(manifest.getString("before").getValue(),manifest.getString("beforeHash").getValue())).bounds();bounds.add(b);
            for(int x=Math.floorDiv(b.minX(),32);x<=Math.floorDiv(b.maxX()-1,32);x++)for(int z=Math.floorDiv(b.minZ(),32);z<=Math.floorDiv(b.maxZ()-1,32);z++)columns.add(world.getChunkAsync(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(x,z)));
        }
        return java.util.concurrent.CompletableFuture.allOf(columns.toArray(java.util.concurrent.CompletableFuture[]::new)).thenCompose(v->{
            var sections=new ArrayList<java.util.concurrent.CompletableFuture<?>>();
            for(var b:bounds)for(int x=Math.floorDiv(b.minX(),32);x<=Math.floorDiv(b.maxX()-1,32);x++)for(int z=Math.floorDiv(b.minZ(),32);z<=Math.floorDiv(b.maxZ()-1,32);z++)for(int y=Math.floorDiv(b.minY(),32);y<=Math.floorDiv(b.maxY()-1,32);y++)sections.add(world.getChunkStore().getChunkSectionReferenceAsync(x,y,z));
            return java.util.concurrent.CompletableFuture.allOf(sections.toArray(java.util.concurrent.CompletableFuture[]::new));
        }).orTimeout(20,java.util.concurrent.TimeUnit.SECONDS);
    }
    public static int recover(EterniaModPlugin plugin,World world,HubPlotRecord plot,UUID actor) throws Exception {
        world.getEntityStore().getStore().assertThread();
        Owner owner=HousingAccess.owner(plot);
        boolean permitted=plot.isOwnedBy(actor)||plot.getGuildOwnerUuid()!=null&&plugin.getServices().guilds().can(actor,plot.getGuildOwnerUuid(),HousingCustody.PACK);
        if(!permitted)throw new IllegalStateException("You do not have permission to package items on this plot.");
        var slot=plugin.getServices().housing().find(owner).orElseThrow();
        var location=plugin.getServices().housing().location(owner).orElseThrow();
        if(slot.state()!=HousingService.State.ACTIVE||!slot.propertyId().equals(plot.getPlotId())||!location.worldId().equals(world.getName()))return 0;
        var pending=plugin.getServices().journal().unfinished().stream().filter(o->o.owner().equals(owner)).toList();
        // A second operation might own the same cells. Never recover through another lock.
        if(pending.size()!=1)return 0;
        var op=pending.getFirst();
        if(!op.kind().equals("PLACE_PROP")||op.state()!=JournalService.State.RECOVERY_REQUIRED||!op.detail().equals(plot.getPlotId().toString()))return 0;
        var snapshots=NativePlacementTransactions.snapshots(plugin);
        var manifest=BsonDocument.parse(new String(snapshots.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
        UUID instance=UUID.fromString(manifest.getString("instance").getValue());
        if(plugin.getServices().provenance().find(instance).isPresent()||plot.getProps().stream().anyMatch(p->p.getInstanceId().equals(instance)))return 0;
        var reservation=plugin.getServices().ownership().findReservation(manifest.getString("source").getValue()).orElseThrow();
        if(!reservation.owner().equals(owner)||reservation.quantity()!=1||!reservation.contentId().startsWith("eternia:prop/")||!Set.of("RESERVED","RELEASED").contains(reservation.state()))return 0;
        if(owner.kind()==Owner.Kind.GUILD&&!plugin.getServices().guilds().can(actor,owner.id(),HousingCustody.RESERVE))return 0;
        var before=snapshots.load(new SnapshotFiles.Saved(manifest.getString("before").getValue(),manifest.getString("beforeHash").getValue()));
        var after=snapshots.load(new SnapshotFiles.Saved(manifest.getString("after").getValue(),manifest.getString("afterHash").getValue()));
        if(!before.bounds().equals(after.bounds())||!before.document().getArray("entities",new BsonArray()).isEmpty()||!after.document().getArray("entities",new BsonArray()).isEmpty())return 0;
        var bounds=before.bounds();
        for(int x=bounds.minX();x<bounds.maxX();x++)for(int z=bounds.minZ();z<bounds.maxZ();z++)
            if(!plot.getFootprint().containsHorizontal(x,z)||plugin.getInfrastructure().protectedColumn(world.getName(),x,z))return 0;
        var actual=snapshots.capture(world,bounds,Set.of(),true,false);
        var comparison=NativeSnapshotStore.comparisonDocument(actual);
        boolean restored=comparison.equals(NativeSnapshotStore.comparisonDocument(before.document()));
        if(!restored&&!comparison.equals(NativeSnapshotStore.comparisonDocument(after.document())))
        {
            var diagnostic=snapshots.files().write("recovery-check-"+UUID.randomUUID()+".json",new BsonDocument("before",before.document()).append("after",after.document()).append("actual",actual).toJson().getBytes(StandardCharsets.UTF_8));
            throw new IllegalStateException("The decoration no longer matches its saved placement. Nothing was removed. Operation "+op.id()+"; comparison saved as "+diagnostic.reference()+".");
        }
        if(!restored)snapshots.apply(world,before,bounds.origin());
        snapshots.verify(world,before,bounds.origin(),Set.of(),true);
        NativePlacementTransactions.flush(world,bounds);
        // Both steps are idempotent after a crash; retry sees the verified before image.
        plugin.getServices().ownership().releaseReservation(reservation.id());
        plugin.getServices().journal().advance(op.id(),op.revision(),JournalService.State.CANCELLED);
        plugin.getLogger().atInfo().log("Recovered interrupted prop %s on property %s; returned its reserved quantity",op.id(),plot.getPlotId());
        return 1;
    }
}
