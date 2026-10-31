package com.hexvane.eterniamod.setup;

import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.builtin.adventure.worldevents.system.WorldEventInitSystem;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;

/**
 * Native code only checks the world switch when it loads a world's own events: a global event started
 * from any other world still starts here, and so do commands and interactions. Every route ends in
 * this entity, so removing it here takes the normal removal path and rolls back anything it did.
 */
public final class DisabledWorldEvents extends RefSystem<EntityStore> {
    @Override public Query<EntityStore> getQuery(){return WorldEvent.getComponentType();}
    @Override public Set<Dependency<EntityStore>> getDependencies(){return Set.of(new SystemDependency<>(Order.AFTER,WorldEventInitSystem.class));}
    @Override public void onEntityAdded(Ref<EntityStore> ref,AddReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands) {
        if(!HubNaturalSpawns.eventsEnabled(store.getExternalData().getWorld().getWorldConfig()))commands.tryRemoveEntity(ref,RemoveReason.REMOVE);
    }
    @Override public void onEntityRemove(Ref<EntityStore> ref,RemoveReason reason,Store<EntityStore> store,CommandBuffer<EntityStore> commands) {}
}
