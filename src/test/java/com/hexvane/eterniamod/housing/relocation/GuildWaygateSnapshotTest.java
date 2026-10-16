package com.hexvane.eterniamod.housing.relocation;

import org.bson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuildWaygateSnapshotTest {
    private BsonDocument original(){
        var cells=new BsonArray();
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)cells.add(cell(x,0,z,"Rock_Stone_Brick"));
        cells.add(cell(1,1,1,"Eternia_Guild_Waygate").append("rotation",new BsonInt32(2)));
        return new BsonDocument("blocks",cells);
    }
    private BsonDocument cell(int x,int y,int z,String name){return new BsonDocument("x",new BsonInt32(x)).append("y",new BsonInt32(y)).append("z",new BsonInt32(z)).append("name",new BsonString(name));}
    @Test void packedOriginalBecomesOneGroundLevelPortalWithoutChangingSavedInput(){
        var source=original();var copy=source.clone();var migrated=GuildWaygateSnapshot.withoutPlinth(source).orElseThrow();
        assertEquals(copy,source);assertEquals(1,migrated.getArray("blocks").size());
        var portal=migrated.getArray("blocks").getFirst().asDocument();
        for(String axis:new String[]{"x","y","z"})assertEquals(0,portal.getInt32(axis).getValue());
        assertEquals(2,portal.getInt32("rotation").getValue());
    }
    @Test void refusesToDiscardUnexpectedBlocksComponentsOrEntities(){
        var changed=original();changed.getArray("blocks").getFirst().asDocument().put("name",new BsonString("Chest"));
        assertTrue(GuildWaygateSnapshot.withoutPlinth(changed).isEmpty());
        var components=original();components.getArray("blocks").getFirst().asDocument().put("components",new BsonDocument());
        assertTrue(GuildWaygateSnapshot.withoutPlinth(components).isEmpty());
        var entities=original().append("entities",new BsonArray(java.util.List.of(new BsonDocument())));
        assertTrue(GuildWaygateSnapshot.withoutPlinth(entities).isEmpty());
    }
}
