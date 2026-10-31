package com.hexvane.eterniamod.housing.relocation;

import static org.junit.jupiter.api.Assertions.*;
import org.bson.*;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

class EntitySnapshotComparisonTest {
    private static BsonDocument entity(double y) {
        var doc=BsonDocument.parse("{entities:[{Components:{Transform:{Position:{X:9.5,Y:0.0,Z:2.5},Rotation:{Yaw:3.1415927410125732}},UUID:{UUID:'original'},Item:{Item:{Id:'Potion',Quantity:1}}}}]}");
        position(doc).put("Y",new BsonDouble(y));return doc;
    }
    private static BsonDocument position(BsonDocument doc){return doc.getArray("entities").get(0).asDocument().getDocument("Components").getDocument("Transform").getDocument("Position");}
    @Test void worldTranslationRoundingDoesNotRejectManorDecorations() {
        var expected=entity(2.3999999999999986);var actual=entity(2.4000000000000057);var original=expected.clone();
        var origin=new Vector3i(-1787,121,-113);
        assertNotEquals(expected,actual);
        assertEquals(NativeSnapshotStore.comparisonDocument(expected,origin),NativeSnapshotStore.comparisonDocument(actual,origin));
        assertEquals(original,expected);
        // No blanket epsilon: at the origin, those two doubles are still distinct.
        assertNotEquals(NativeSnapshotStore.comparisonDocument(expected,new Vector3i()),NativeSnapshotStore.comparisonDocument(actual,new Vector3i()));
    }
    @Test void realMovementAndOtherSavedStateStillFailComparison() {
        var expected=entity(2.3999999999999986);var origin=new Vector3i(-1787,121,-113);
        var moved=entity(2.400001);
        assertNotEquals(NativeSnapshotStore.comparisonDocument(expected,origin),NativeSnapshotStore.comparisonDocument(moved,origin));
        for(String key:new String[]{"UUID","Item","Rotation"}) {
            var changed=expected.clone();var components=changed.getArray("entities").get(0).asDocument().getDocument("Components");
            if(key.equals("Rotation"))components.getDocument("Transform").getDocument(key).put("Yaw",new BsonDouble(0));
            else components.put(key,new BsonDocument("changed",BsonBoolean.TRUE));
            assertNotEquals(NativeSnapshotStore.comparisonDocument(expected,origin),NativeSnapshotStore.comparisonDocument(changed,origin));
        }
    }
}
