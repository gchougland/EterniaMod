package com.hexvane.eterniamod.setup;

import com.google.gson.Gson;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.setup.ManagedHubRegistry.*;

class ManagedHubRegistryTest {
    @TempDir Path directory;
    private final UUID builder=UUID.randomUUID();
    private final Pose start=new Pose(12,70,12,0);

    @Test void interruptedNpcMovesRetainBothFootprintsAndStableIdentityAcrossRestart()throws Exception {
        var registry=new ManagedHubRegistry(directory);
        var placing=registry.create(Kind.NPC,"hub","Eternia_Greeter",start,builder);
        assertEquals(placing,new ManagedHubRegistry(directory).find(placing.id()).orElseThrow());
        var active=registry.complete(placing.id(),placing.revision());
        var destination=new Pose(20,70,16,1.5f);
        var moving=registry.begin(active.id(),active.revision(),State.MOVING,destination,builder);
        var restarted=new ManagedHubRegistry(directory);
        var pending=restarted.find(active.id()).orElseThrow();
        assertEquals(moving,pending);
        assertEquals(List.of(destination.footprint(),start.footprint()),pending.footprints());
        var moved=restarted.complete(pending.id(),pending.revision());
        assertEquals(active.id(),moved.id());
        assertEquals("Eternia_Greeter",moved.role());
        assertEquals(State.ACTIVE,moved.state());
        assertNull(moved.source());
        assertEquals(List.of(destination.footprint()),moved.footprints());
        var removing=restarted.begin(moved.id(),moved.revision(),State.REMOVING,null,builder);
        var afterRemovalRestart=new ManagedHubRegistry(directory);
        assertEquals(removing,afterRemovalRestart.find(moved.id()).orElseThrow());
        var removed=afterRemovalRestart.complete(removing.id(),removing.revision());
        assertFalse(removed.live());
        assertEquals(removed,new ManagedHubRegistry(directory).find(removed.id()).orElseThrow());
    }

    @Test void staleOrRepeatedConfirmationsCannotReplacePendingWorkOrReviveRemovedServices()throws Exception {
        var registry=new ManagedHubRegistry(directory);
        var placing=registry.create(Kind.NPC,"hub","Eternia_Housing",start,builder);
        assertThrows(IllegalStateException.class,()->registry.begin(placing.id(),placing.revision(),State.REMOVING,null,builder));
        var active=registry.complete(placing.id(),placing.revision());
        assertThrows(IllegalStateException.class,()->registry.complete(placing.id(),placing.revision()));
        assertThrows(IllegalStateException.class,()->registry.complete(active.id(),active.revision()));
        assertThrows(IllegalStateException.class,()->registry.begin(active.id(),placing.revision(),State.REMOVING,null,builder));
        var removing=registry.begin(active.id(),active.revision(),State.REMOVING,null,builder);
        assertThrows(IllegalStateException.class,()->registry.begin(removing.id(),removing.revision(),State.MOVING,new Pose(30,70,30,0),builder));
        var removed=registry.complete(removing.id(),removing.revision());
        assertThrows(IllegalStateException.class,()->registry.begin(removed.id(),removed.revision(),State.MOVING,start,builder));
        assertEquals(removed,new ManagedHubRegistry(directory).find(removed.id()).orElseThrow());
    }

    @Test void corruptedSnapshotOrMissingHeadFailsWithoutInventingAnEmptyRegistry()throws Exception {
        var registry=new ManagedHubRegistry(directory);
        registry.create(Kind.PORTAL,"hub","Eternia_World_Portal",start,builder);
        Path pointer=directory.resolve("current.pointer");
        var saved=new Gson().fromJson(Files.readString(pointer),SnapshotFiles.Saved.class);
        Files.writeString(directory.resolve(saved.reference()),"{}");
        assertThrows(IOException.class,()->new ManagedHubRegistry(directory));
        Files.delete(pointer);
        assertThrows(IOException.class,()->new ManagedHubRegistry(directory));
    }

    @Test void invalidServiceMetadataAndDuplicateDurableIdentitiesAreRejected()throws Exception {
        var registry=new ManagedHubRegistry(directory);
        assertThrows(IllegalArgumentException.class,()->registry.create(Kind.NPC,"hub","Unmanaged_Role",start,builder));
        assertThrows(IllegalArgumentException.class,()->new Pose(0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new Pose(0,70,0,Float.NaN));
        var portal=registry.create(Kind.PORTAL,"hub","Eternia_World_Portal",start,builder);
        var active=registry.complete(portal.id(),portal.revision());
        assertThrows(IllegalArgumentException.class,()->registry.begin(active.id(),active.revision(),State.MOVING,new Pose(20,70,20,0),builder));
        assertEquals(active,new ManagedHubRegistry(directory).find(active.id()).orElseThrow());
        var json=new Gson();var snapshots=new SnapshotFiles(directory);
        var duplicate=snapshots.write("duplicate-identities.json",json.toJson(new Document(1,List.of(active,active))).getBytes(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("current.pointer"),json.toJson(duplicate));
        assertThrows(IOException.class,()->new ManagedHubRegistry(directory));
    }
}
