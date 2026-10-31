package com.hexvane.eterniamod.loot;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.util.*;
import org.bson.BsonDocument;

/** Stored form of one player's copy of a chest, and how a fresh copy is filled. */
final class LootChestContents {
    private LootChestContents() {}
    static String encode(SimpleItemContainer container) {
        return SimpleItemContainer.CODEC.encode(container,new ExtraInfo()).toJson();
    }
    static SimpleItemContainer decode(String contents) {
        return SimpleItemContainer.CODEC.decode(BsonDocument.parse(contents),new ExtraInfo());
    }
    /** Scatters stacks the way the engine's own chest roll does, one to a random slot. Stacks past the capacity are left out, as they are there. */
    static SimpleItemContainer fill(List<ItemStack> stacks,short capacity,Random random) {
        var container=new SimpleItemContainer(capacity);
        var slots=new ArrayList<Short>(capacity);
        for(short slot=0;slot<capacity;slot++)slots.add(slot);
        Collections.shuffle(slots,random);
        for(int i=0;i<stacks.size()&&i<slots.size();i++)container.addItemStackToSlot(slots.get(i),stacks.get(i));
        return container;
    }
}
