package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LootChestServiceTest {
    private static final UUID WORLD=UUID.randomUUID();
    private static LootChestService.Chest chest(){return new LootChestService.Chest(WORLD,10,64,-3,"instance","Drop_Goblin");}

    @Test void eachPlayerRollsTheirOwnCopyOnce(){
        var chests=new EterniaServices(new InMemoryStore()).lootChests();UUID a=UUID.randomUUID(),b=UUID.randomUUID();var rolls=new AtomicInteger();
        var first=chests.open(a,chest(),()->"roll-"+rolls.incrementAndGet());
        var other=chests.open(b,chest(),()->"roll-"+rolls.incrementAndGet());
        assertTrue(first.firstOpen());assertTrue(other.firstOpen());
        assertEquals("roll-1",first.contents());assertEquals("roll-2",other.contents());
        var again=chests.open(a,chest(),()->"roll-"+rolls.incrementAndGet());
        assertFalse(again.firstOpen());assertEquals("roll-1",again.contents());assertEquals(2,rolls.get());
    }
    @Test void takingItemsNeverChangesAnotherPlayersCopy(){
        var chests=new EterniaServices(new InMemoryStore()).lootChests();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        chests.open(a,chest(),()->"full");chests.open(b,chest(),()->"full");
        chests.save(a,chest(),"emptied");
        assertEquals("emptied",chests.open(a,chest(),()->"fresh").contents());
        assertEquals("full",chests.open(b,chest(),()->"fresh").contents());
    }
    @Test void anEmptiedChestStaysEmptyAcrossRestarts(){
        var storage=new InMemoryStore();UUID a=UUID.randomUUID();
        var before=new EterniaServices(storage).lootChests();before.open(a,chest(),()->"full");before.save(a,chest(),"emptied");
        var restarted=new EterniaServices(storage).lootChests();
        var reopened=restarted.open(a,chest(),()->{throw new AssertionError("must not roll again");});
        assertFalse(reopened.firstOpen());assertEquals("emptied",reopened.contents());
    }
    @Test void aRacingOpenKeepsTheFirstSavedRoll(){
        var chests=new EterniaServices(new InMemoryStore()).lootChests();UUID a=UUID.randomUUID();
        var loser=chests.open(a,chest(),()->{chests.open(a,chest(),()->"winner");return "loser";});
        assertFalse(loser.firstOpen());assertEquals("winner",loser.contents());
    }
    @Test void aDifferentChestOrPlacementIsAFreshCopy(){
        var chests=new EterniaServices(new InMemoryStore()).lootChests();UUID a=UUID.randomUUID();
        chests.open(a,chest(),()->"first");
        assertTrue(chests.open(a,new LootChestService.Chest(WORLD,11,64,-3,"instance","Drop_Goblin"),()->"beside").firstOpen());
        assertTrue(chests.open(a,new LootChestService.Chest(UUID.randomUUID(),10,64,-3,"instance","Drop_Goblin"),()->"elsewhere").firstOpen());
        assertTrue(chests.open(a,new LootChestService.Chest(WORLD,10,64,-3,"replaced","Drop_Goblin"),()->"replaced").firstOpen());
    }
    @Test void savingACopyThatWasNeverRolledFails(){
        var chests=new EterniaServices(new InMemoryStore()).lootChests();
        assertThrows(DomainException.class,()->chests.save(UUID.randomUUID(),chest(),"items"));
    }
}
