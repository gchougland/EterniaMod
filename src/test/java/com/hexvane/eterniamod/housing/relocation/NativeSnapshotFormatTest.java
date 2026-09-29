package com.hexvane.eterniamod.housing.relocation;
import org.bson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeSnapshotFormatTest {
    @Test void onlyExplicitZeroEmptyFluidsAtCapturedCellsAreCanonicalized(){
        var source=BsonDocument.parse("{blocks:[{x:0,y:0,z:0,name:'Rock_Stone'},{x:1,y:0,z:0,name:'Empty'}],fluids:[{x:0,y:0,z:0,name:'Empty',level:0},{x:1,y:0,z:0,name:'Water',level:7}]}");
        var canonical=NativeSnapshotStore.canonicalEmptyFluids(source);assertEquals(2,source.getArray("fluids").size());assertEquals(1,canonical.getArray("fluids").size());assertEquals("Water",canonical.getArray("fluids").get(0).asDocument().getString("name").getValue());assertEquals(canonical,NativeSnapshotStore.canonicalEmptyFluids(canonical));
        var orphan=BsonDocument.parse("{blocks:[],fluids:[{x:0,y:0,z:0,name:'Empty',level:0}]}");assertThrows(IllegalStateException.class,()->NativeSnapshotStore.canonicalEmptyFluids(orphan));
    }
}
