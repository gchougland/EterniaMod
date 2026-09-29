package com.hexvane.eterniamod.runtime;

import com.google.gson.JsonParser;
import com.hexvane.eterniamod.domain.SeasonService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityCoinConfigurationTest {
    @Test void coinOnlyTargetKeepsZeroBaseXpWhileExistingXpRemainsUnchanged(){
        var coins=GameplayAdapters.parseCoinRewards(JsonParser.parseString("{\"KILL\":{\"Skeleton\":2,\"Goblin\":1}}").getAsJsonObject());
        assertEquals(Map.of("Skeleton",10L,"Goblin",0L),GameplayAdapters.withCoinTargets(Map.of("Skeleton",10L),coins.get(SeasonService.ActivityKind.KILL)));
        assertTrue(GameplayAdapters.parseCoinRewards(JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
    }
    @Test void fractionalNegativeStringAndUnsupportedKindAreRejected(){
        for(String json:new String[]{"{\"KILL\":{\"Skeleton\":1.5}}","{\"MINE\":{\"Ore\":-1}}","{\"HARVEST\":{\"Crop\":\"3\"}}","{\"ACQUIRE\":{\"Item\":1}}","{\"KILL\":{\"Skeleton\":100001}}"})
            assertThrows(RuntimeException.class,()->GameplayAdapters.parseCoinRewards(JsonParser.parseString(json).getAsJsonObject()));
    }
}
