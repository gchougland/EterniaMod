package com.hexvane.eterniamod.loot;

import org.junit.jupiter.api.Test;
import static com.hexvane.eterniamod.loot.LootChestRules.*;
import static org.junit.jupiter.api.Assertions.*;

class LootChestRulesTest {
    @Test void anUnrolledLootChestBecomesPersonal(){
        assertTrue(becomesPersonal("Drop",true,false,true,true));
    }
    @Test void everythingElseStaysShared(){
        assertFalse(becomesPersonal(null,true,false,true,true),"placed chests and chests rolled before this change have no droplist");
        assertFalse(becomesPersonal("Drop",false,false,true,true),"prefab editing worlds keep their droplists");
        assertFalse(becomesPersonal("Drop",true,true,true,true),"housing and guild worlds are untouched");
        assertFalse(becomesPersonal("Drop",true,false,false,true),"items already inside are not lost");
        assertFalse(becomesPersonal("Drop",true,false,true,false),"pots and other blocks that do not open keep the engine's loot");
    }
    @Test void markedChestsOpenPersonallyOutsideHousingOnly(){
        assertTrue(opensPersonally(true,false));
        assertFalse(opensPersonally(true,true));
        assertFalse(opensPersonally(false,false));
    }
    @Test void onlyCreativeCanBreakAPersonalChest(){
        assertTrue(breakDenied(true,false,false));
        assertFalse(breakDenied(true,false,true));
        assertFalse(breakDenied(false,false,false),"ordinary chests break as normal");
        assertFalse(breakDenied(true,true,false),"housing rules decide there");
    }
}
