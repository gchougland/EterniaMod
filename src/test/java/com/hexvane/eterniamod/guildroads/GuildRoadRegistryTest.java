package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.guildroads.GuildRoadRegistry.*;

class GuildRoadRegistryTest {
    @TempDir Path directory;
    private Road road(){var pointer=new SnapshotFiles.Saved("native.json",SnapshotFiles.hash(new byte[]{1}));return new Road(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"housing",new PlotRect(0,0,12,1),20,"Rock_Stone_Cobble",pointer,pointer,UUID.randomUUID(),State.ADDING);}
    @Test void pendingAndRemovedStateSurviveVerifiedRegistryRestart()throws Exception{
        var registry=new GuildRoadRegistry(directory);var road=road();registry.put(road);
        assertEquals(road,new GuildRoadRegistry(directory).find(road.id()).orElseThrow());
        var removed=road.state(State.REMOVED,UUID.randomUUID());registry.put(removed);
        assertEquals(removed,new GuildRoadRegistry(directory).find(road.id()).orElseThrow());
    }
    @Test void aMissingHeadCannotSilentlyResetExistingRoadAuthority()throws Exception{
        var registry=new GuildRoadRegistry(directory);registry.put(road());Files.delete(directory.resolve("current.pointer"));
        assertThrows(IOException.class,()->new GuildRoadRegistry(directory));
    }
    @Test void tamperedGenerationIsRejectedByItsHeadHash()throws Exception{
        var registry=new GuildRoadRegistry(directory);registry.put(road());
        Path generation;try(var entries=Files.list(directory)){generation=entries.filter(p->p.getFileName().toString().startsWith("registry-")).findFirst().orElseThrow();}
        Files.writeString(generation," ",StandardOpenOption.APPEND);
        assertThrows(IOException.class,()->new GuildRoadRegistry(directory));
    }
    @Test void propertyConsentIsBoundToExactGuildAndSurvivesRoadUpdates()throws Exception{
        var registry=new GuildRoadRegistry(directory);UUID property=UUID.randomUUID(),guild=UUID.randomUUID();
        assertFalse(registry.easement(property,guild));registry.easement(property,guild,true);registry.put(road());
        var restarted=new GuildRoadRegistry(directory);assertTrue(restarted.easement(property,guild));
        assertFalse(restarted.easement(property,UUID.randomUUID()));assertFalse(restarted.easement(UUID.randomUUID(),guild));
        restarted.easement(property,guild,false);assertFalse(new GuildRoadRegistry(directory).easement(property,guild));
    }
}
