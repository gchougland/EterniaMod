package com.hexvane.eterniamod.stats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns position samples (about one a second) into blocks travelled. A sample in another world, after a teleport, or
 * further than the fastest honest movement allows only moves the baseline: travel by waygate, respawn or command
 * never counts. Fractions of a block carry over to the next sample.
 */
public final class TravelTracker {
    /** Faster than any mount, glide or fall; anything beyond is a teleport. */
    public static final double MAX_BLOCKS_PER_SECOND = 40;
    /** Standing still still wobbles a little; ignore it. */
    public static final double MIN_STEP = 0.05;
    /** Samples further apart than this restart the baseline (a lag spike or a long pause). */
    public static final long MAX_GAP_NANOS = 10_000_000_000L;

    private record Sample(String world, double x, double y, double z, long nanos, double carry) {}
    private final Map<UUID, Sample> last = new ConcurrentHashMap<>();

    /** Returns whole blocks travelled since the previous sample of this player. */
    public long sample(UUID player, String world, double x, double y, double z, long nanos) {
        var before = last.get(player);
        if (before == null || !before.world().equals(world) || nanos <= before.nanos() || nanos - before.nanos() > MAX_GAP_NANOS) {
            last.put(player, new Sample(world, x, y, z, nanos, 0));
            return 0;
        }
        double dx = x - before.x(), dy = y - before.y(), dz = z - before.z();
        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double seconds = (nanos - before.nanos()) / 1e9;
        if (!Double.isFinite(step) || step > MAX_BLOCKS_PER_SECOND * Math.max(seconds, 0.05)) {
            last.put(player, new Sample(world, x, y, z, nanos, before.carry()));
            return 0;
        }
        double total = before.carry() + (step < MIN_STEP ? 0 : step);
        long whole = (long) Math.floor(total);
        last.put(player, new Sample(world, x, y, z, nanos, total - whole));
        return whole;
    }

    /** A teleport: the next sample starts a fresh baseline. */
    public void reset(UUID player) {
        var before = last.get(player);
        if (before != null) last.put(player, new Sample("", before.x(), before.y(), before.z(), before.nanos(), before.carry()));
    }

    public void forget(UUID player) { last.remove(player); }
}
