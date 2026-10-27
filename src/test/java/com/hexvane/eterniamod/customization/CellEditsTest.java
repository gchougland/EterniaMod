package com.hexvane.eterniamod.customization;

import org.bson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CellEditsTest {
    private static BsonDocument block(int x,String name){return BsonDocument.parse("{x:"+x+",y:0,z:0,name:'"+name+"',support:1}");}
    private static BsonDocument doc(){return new BsonDocument("blocks",new BsonArray(List.of(block(0,"Stone"),block(1,"Chest")))).append("fluids",new BsonArray(List.of(BsonDocument.parse("{x:1,y:0,z:0,name:'Water',level:5}")))).append("entities",new BsonArray());}
    @Test void sparseOverlayPreservesOtherOwnersCellsAndFluids() {
        var original=doc();original.getArray("blocks").get(1).asDocument().put("components",BsonDocument.parse("{inventory:{item:'Diamond'}}"));
        var changed=CellEdits.select(original,Set.of("0,0,0"));changed.getArray("blocks").getFirst().asDocument().put("name",new BsonString("Brick"));
        var after=original.clone();CellEdits.overlay(after,changed);
        assertEquals(original.getArray("blocks").get(1),after.getArray("blocks").get(1));assertEquals(original.getArray("fluids"),after.getArray("fluids"));assertFalse(changed.containsKey("entities"));
    }
    @Test void optimisticCheckIgnoresOnlySupportAndRejectsInventoryOrFluidChanges() {
        var expected=doc();var actual=expected.clone();actual.getArray("blocks").getFirst().asDocument().put("support",new BsonInt32(7));
        assertDoesNotThrow(()->CellEdits.requireSame(actual,expected));
        actual.getArray("blocks").get(1).asDocument().put("components",BsonDocument.parse("{inventory:{item:'Diamond'}}"));
        assertThrows(IllegalStateException.class,()->CellEdits.requireSame(actual,expected));
        var fluidEdit=expected.clone();fluidEdit.getArray("fluids").getFirst().asDocument().put("level",new BsonInt32(6));
        assertThrows(IllegalStateException.class,()->CellEdits.requireSame(fluidEdit,expected));
    }
}
