package com.hexvane.eterniamod.inventory;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Borrow components only for synchronous disk-provider serialization on the world thread.
 * Like native PlayerSavingSystems, this must not clone live interaction/network components.
 * The verified disk provider encodes the holder before returning its asynchronous write future.
 */
public final class NativeInventorySnapshot {
    private NativeInventorySnapshot() {}
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Holder<EntityStore> forImmediateSave(Ref<EntityStore> ref, Store<EntityStore> store) {
        store.assertThread();
        var archetype = store.getArchetype(ref);
        Component[] components = new Component[archetype.length()];
        for (int i=archetype.getMinIndex(); i<archetype.length(); i++) {
            ComponentType type=archetype.get(i);
            if(type!=null) components[i]=store.getComponent(ref,type);
        }
        return EntityStore.REGISTRY.newHolder(archetype,components);
    }
}
