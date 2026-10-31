package com.hexvane.eterniamod.loot;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LootChestBlockCodecTest {
    @Test void theMarkerSurvivesBeingSavedWithTheChunk(){
        var encoded=LootChestBlock.CODEC.encode(new LootChestBlock("Drop","instance"),new ExtraInfo());
        var decoded=LootChestBlock.CODEC.decode(BsonDocument.parse(encoded.toJson()),new ExtraInfo());
        assertEquals("Drop",decoded.getDroplist());assertEquals("instance",decoded.getInstance());
    }
}
