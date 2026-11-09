package com.hexvane.eterniamod.housing.relocation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

class ObjectEditCheckTest {
    private static BsonDocument cell(int x, String name, int rotation) {
        return new BsonDocument("x", new BsonInt32(x)).append("y", new BsonInt32(0)).append("z", new BsonInt32(0))
            .append("name", new BsonString(name)).append("rotation", new BsonInt32(rotation));
    }

    private static BsonDocument snapshot(BsonDocument... cells) {
        return new BsonDocument("blocks", new BsonArray(List.of(cells)));
    }

    @Test
    void stateChangesAreNotEdits() {
        var placed = snapshot(cell(0, "Furniture_Village_Door", 1), cell(1, "*Plant_Crop_Wheat_Block_State_Definitions_Stage1", 0));
        var now = snapshot(cell(0, "*Furniture_Village_Door_State_Definitions_OpenDoorIn", 1), cell(1, "*Plant_Crop_Wheat_Block_State_Definitions_StageFinal", 0));
        assertDoesNotThrow(() -> NativeRelocationCoordinator.requireSameBlocks(now, placed, placed));
    }

    @Test
    void replacedBlockIsAnEditAndSaysWhere() {
        var placed = snapshot(cell(0, "Wood_Oak_Planks", 0));
        var now = snapshot(cell(0, "Rock_Stone", 0));
        var where = new NativeSnapshotStore.Bounds(10, 64, 20, 11, 65, 21);
        var edited = assertThrows(NativeRelocationCoordinator.ObjectEdited.class,
            () -> NativeRelocationCoordinator.requireSameBlocks(now, placed, placed, where));
        assertEquals(10, edited.x);
        assertEquals(64, edited.y);
        assertEquals(20, edited.z);
        assertTrue(edited.getMessage().contains("10 64 20"));
    }

    @Test
    void turnedPlainBlockIsStillAnEdit() {
        var placed = snapshot(cell(0, "Wood_Oak_Planks", 0));
        var now = snapshot(cell(0, "Wood_Oak_Planks", 2));
        assertThrows(NativeRelocationCoordinator.ObjectEdited.class, () -> NativeRelocationCoordinator.requireSameBlocks(now, placed, placed));
    }

    @Test
    void baseBlockOfAState() {
        assertEquals("Door", NativeRelocationCoordinator.baseBlock("*Door_State_Definitions_Open"));
        assertEquals("Door", NativeRelocationCoordinator.baseBlock("Door"));
    }
}
