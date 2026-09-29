package com.hexvane.eterniamod.housing.relocation;

import static org.junit.jupiter.api.Assertions.*;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

class FarmingSnapshotComparisonTest {
    @Test void ignoresOnlyFarmingClockAndDoesNotModifySavedData() {
        var expected=BsonDocument.parse("{blocks:[{name:'Plant_Cactus_1',components:{Components:{FarmingBlock:{}}}}]}");
        var actual=BsonDocument.parse("{blocks:[{name:'Plant_Cactus_1',components:{Components:{FarmingBlock:{LastTickGameTime:'0001-01-02T00:22:48Z'}}}}]}");
        var original=actual.clone();
        assertEquals(NativeSnapshotStore.comparisonDocument(expected),NativeSnapshotStore.comparisonDocument(actual));
        assertEquals(original,actual);
        var reloaded=BsonDocument.parse("{blocks:[{name:'Plant_Cactus_1'}]}");
        assertEquals(NativeSnapshotStore.comparisonDocument(expected),NativeSnapshotStore.comparisonDocument(reloaded));
        for(String changed:new String[]{
            "{blocks:[{name:'Plant_Cactus_2',components:{Components:{FarmingBlock:{}}}}]}",
            "{blocks:[{name:'Plant_Cactus_1',components:{Components:{FarmingBlock:{GrowthProgress:1}}}}]}",
            "{blocks:[{name:'Plant_Cactus_1',components:{Components:{FarmingBlock:{},ItemContainer:{Items:[1]}}}}]}"})
            assertNotEquals(NativeSnapshotStore.comparisonDocument(expected),NativeSnapshotStore.comparisonDocument(BsonDocument.parse(changed)));
    }
}
