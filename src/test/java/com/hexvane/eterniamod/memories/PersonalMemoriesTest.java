package com.hexvane.eterniamod.memories;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.MemoryService;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.persistence.LocalFileStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalMemoriesTest {
    private static MemoryService.Entry entry(String id){return new MemoryService.Entry(id,"server.npcRoles."+id+".name","server.map.region.Zone1",1000);}

    @Test void recordingOnlyAffectsThePlayerWhoRecorded() {
        var memories=new EterniaServices(new InMemoryStore()).memories();UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        memories.record(first,List.of(entry("Kweebec"),entry("Trork")));
        assertEquals(Set.of("Kweebec","Trork"),memories.recorded(first).keySet());
        assertTrue(memories.recorded(second).isEmpty());
        memories.record(second,List.of(entry("Trork")));
        assertEquals(Set.of("Trork"),memories.recorded(second).keySet());
        assertEquals(2,memories.recorded(first).size());
    }
    @Test void recordingAgainKeepsTheFirstMemory() {
        var memories=new EterniaServices(new InMemoryStore()).memories();UUID player=UUID.randomUUID();
        memories.record(player,List.of(entry("Kweebec")));
        memories.record(player,List.of(new MemoryService.Entry("Kweebec","other","other",9)));
        assertEquals(entry("Kweebec"),memories.recorded(player).get("Kweebec"));
    }
    @Test void memoriesSurviveARestart(@TempDir Path directory) throws Exception {
        Path file=directory.resolve("store.json");UUID player=UUID.randomUUID();
        try(var store=new LocalFileStore(file)){new EterniaServices(store).memories().record(player,List.of(entry("Kweebec")));}
        try(var store=new LocalFileStore(file)){assertEquals(entry("Kweebec"),new EterniaServices(store).memories().recorded(player).get("Kweebec"));}
    }
    @Test void levelsAndRecipesFollowThePlayersOwnCount() {
        int[] thresholds={2,5};
        assertEquals(1,MemoryService.level(0,thresholds));assertEquals(1,MemoryService.level(1,thresholds));
        assertEquals(2,MemoryService.level(2,thresholds));assertEquals(3,MemoryService.level(5,thresholds));
        assertEquals(1,MemoryService.level(99,null));
        assertTrue(MemoryService.canCraft(1,1));assertFalse(MemoryService.canCraft(2,1));assertTrue(MemoryService.canCraft(2,2));
    }
    @Test void aMemoryCanOnlyBeCollectedOnce() {
        assertTrue(MemoryService.collectable("Kweebec",Set.of("Trork"),List.of()));
        assertFalse(MemoryService.collectable("Kweebec",Set.of("Kweebec"),List.of()));
        assertFalse(MemoryService.collectable("Kweebec",Set.of(),List.of("Kweebec")));
    }
    @Test void sharedListSettlesOnlyWhenItHoldsJustEnoughStandIns() {
        assertEquals(50,SharedMemoryUnlock.highestThreshold(List.of(new int[]{5,50},new int[]{10})));
        String standIn=SharedMemoryUnlock.STAND_IN_PREFIX;
        assertTrue(SharedMemoryUnlock.settled(List.of(standIn+0,standIn+1),2));
        assertFalse(SharedMemoryUnlock.settled(List.of(standIn+0),2));
        assertFalse(SharedMemoryUnlock.settled(List.of(standIn+0,standIn+1,"Kweebec"),2));
    }
}
