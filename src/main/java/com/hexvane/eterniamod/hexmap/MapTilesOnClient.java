package com.hexvane.eterniamod.hexmap;

import com.hypixel.hytale.common.fastutil.HLongSet;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.WorldMapTracker;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The map tiles the engine's {@link WorldMapTracker} has already sent to a player. The engine keeps this set private
 * and has no getter, so it is read under the tracker's own lock, exactly as Aetherhaven's {@code WorldMapTrackerCompat}
 * does on 0.7.x. This is not a deprecated API. If a game update renames the field, the set reads as empty and the map
 * overlay simply draws nothing (logged once); nothing else depends on it.
 */
final class MapTilesOnClient {
    private static final Logger LOG = Logger.getLogger(MapTilesOnClient.class.getName());
    @Nullable private static final Field LOADED;
    @Nullable private static final Field LOCK;

    static {
        Field loaded = null, lock = null;
        try {
            loaded = WorldMapTracker.class.getDeclaredField("loaded");
            loaded.setAccessible(true);
            lock = WorldMapTracker.class.getDeclaredField("loadedLock");
            lock.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException missing) {
            LOG.log(Level.WARNING, "World map tracker changed; hex plot map overlay is off", missing);
            loaded = null;
            lock = null;
        }
        LOADED = loaded;
        LOCK = lock;
    }

    private MapTilesOnClient() {}

    static boolean available() { return LOADED != null && LOCK != null; }

    /** A copy of the chunk indices of map tiles on the player's client (empty when unknown). Any thread. */
    @Nonnull
    static LongSet of(@Nonnull Player player) {
        if (!available()) return new LongOpenHashSet();
        WorldMapTracker tracker = player.getWorldMapTracker();
        if (tracker == null) return new LongOpenHashSet();
        try {
            ReentrantReadWriteLock lock = (ReentrantReadWriteLock) LOCK.get(tracker);
            lock.readLock().lock();
            try {
                HLongSet loaded = (HLongSet) LOADED.get(tracker);
                return loaded == null ? new LongOpenHashSet() : new LongOpenHashSet(loaded);
            } finally {
                lock.readLock().unlock();
            }
        } catch (IllegalAccessException | ClassCastException failure) {
            return new LongOpenHashSet();
        }
    }
}
