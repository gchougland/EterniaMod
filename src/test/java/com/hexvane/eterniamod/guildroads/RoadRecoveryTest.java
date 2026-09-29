package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.guildroads.GuildRoadRegistry.*;

class RoadRecoveryTest {
    private Road road(){var saved=new SnapshotFiles.Saved("native.json",SnapshotFiles.hash(new byte[]{1}));return new Road(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"housing",new PlotRect(1,1,8,1),0,"Rock_Stone_Cobble",saved,saved,UUID.randomUUID(),State.ACTIVE);}
    private JournalService.Operation operation(Road road,String kind,JournalService.State state){return new JournalService.Operation(road.operation(),kind,Owner.guild(road.guild()),state,1,"manifest.json",SnapshotFiles.hash(new byte[]{2}),road.rootProperty().toString());}
    @Test void removalManifestBeforeRegistryWriteAcceptsOnlyItsCompletedIdenticalAdd(){
        var prior=road();var removal=prior.state(State.REMOVING,UUID.randomUUID());
        var add=operation(prior,"GUILD_ROAD_ADD",JournalService.State.COMPLETED);var remove=operation(removal,"GUILD_ROAD_REMOVE",JournalService.State.SNAPSHOT_READY);
        assertTrue(RoadRecovery.removalPredecessor(prior,removal,remove,add));
        assertFalse(RoadRecovery.removalPredecessor(prior,removal,remove,operation(prior,"GUILD_ROAD_ADD",JournalService.State.WORLD_APPLIED)));
        assertFalse(RoadRecovery.removalPredecessor(prior,removal,remove,operation(prior,"GUILD_ROAD_REMOVE",JournalService.State.COMPLETED)));
        var different=new Road(removal.id(),removal.guild(),removal.rootProperty(),removal.world(),new PlotRect(20,1,8,1),removal.groundY(),removal.blockId(),removal.before(),removal.after(),removal.operation(),removal.state());
        assertFalse(RoadRecovery.removalPredecessor(prior,different,remove,add));
    }
    @Test void failedTerminalJournalAndFailedRegistryRepairStillProtectRemovedColumns(){
        var road=road();var removed=road.state(State.REMOVED,UUID.randomUUID());var recovery=removed.state(State.RECOVERY,removed.operation());
        assertTrue(RoadRecovery.protectedRoads(List.of(removed),Map.of()).isEmpty());
        var protectedRoads=RoadRecovery.protectedRoads(List.of(removed),Map.of(road.id(),recovery));
        assertEquals(List.of(recovery),protectedRoads);assertTrue(protectedRoads.getFirst().rectangle().contains(3,1));
        assertEquals(List.of(recovery),RoadRecovery.protectedRoads(List.of(recovery),Map.of(road.id(),recovery)));
    }
}
