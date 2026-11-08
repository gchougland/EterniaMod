package com.hexvane.eterniamod.stats;

import java.time.Instant;
import java.util.*;

/**
 * In-memory stat changes waiting to be saved. Game events only add here (cheap, no storage); a flush drains every
 * pending change and writes them in one batch. A flush that fails puts its changes back so nothing is lost.
 */
public final class StatsTally {
    /** One player in one world. */
    public record Slot(UUID player, String world) {
        public Slot { Objects.requireNonNull(player); world = StatsFields.part(world); }
    }
    /** A boss victory, kept with its moment so the earliest one wins the server first. */
    public record BossKill(UUID player, String world, String boss, String difficulty, Instant at) {
        public BossKill { Objects.requireNonNull(player); Objects.requireNonNull(boss); Objects.requireNonNull(difficulty); Objects.requireNonNull(at); }
    }
    /** Changes for one slot. Mutable only inside the tally's lock; drained copies are never shared with it again. */
    public static final class Delta {
        private final Map<String, Long> counts = new TreeMap<>();
        private final List<BossKill> bosses = new ArrayList<>();
        public Map<String, Long> counts() { return Collections.unmodifiableMap(counts); }
        public List<BossKill> bosses() { return Collections.unmodifiableList(bosses); }
        void add(String field, long amount) { counts.merge(field, amount, StatsTally::sum); }
        void merge(Delta other) { other.counts.forEach(this::add); bosses.addAll(other.bosses); }
        boolean isEmpty() { return counts.isEmpty() && bosses.isEmpty(); }
    }

    private final Map<Slot, Delta> pending = new HashMap<>();

    public void add(UUID player, String world, String field, long amount) {
        if (amount <= 0) return;
        Objects.requireNonNull(field);
        synchronized (this) { pending.computeIfAbsent(new Slot(player, world), s -> new Delta()).add(field, amount); }
    }

    /** Counts a creature by kind and in the total. */
    public void kill(UUID player, String world, String role) {
        synchronized (this) {
            var delta = pending.computeIfAbsent(new Slot(player, world), s -> new Delta());
            delta.add(StatsFields.KILLS, 1); delta.add(StatsFields.kill(role), 1);
        }
    }

    /** Counts a death, its cause and, when known, who landed the blow. */
    public void death(UUID player, String world, String cause, String killer) {
        synchronized (this) {
            var delta = pending.computeIfAbsent(new Slot(player, world), s -> new Delta());
            delta.add(StatsFields.DEATHS, 1); delta.add(StatsFields.death(cause), 1);
            if (killer != null) delta.add(StatsFields.slainBy(killer), 1);
        }
    }

    public void boss(BossKill kill) {
        synchronized (this) {
            var delta = pending.computeIfAbsent(new Slot(kill.player(), kill.world()), s -> new Delta());
            delta.add(StatsFields.BOSSES, 1); delta.add(StatsFields.boss(kill.boss(), kill.difficulty()), 1);
            delta.bosses.add(kill);
        }
    }

    /** Takes every pending change; the tally is empty afterwards. */
    public synchronized Map<Slot, Delta> drain() {
        if (pending.isEmpty()) return Map.of();
        var drained = new LinkedHashMap<>(pending);
        pending.clear();
        return drained;
    }

    /** Puts back changes from a flush that failed. Newer changes made meanwhile are kept as well. */
    public synchronized void restore(Map<Slot, Delta> changes) {
        changes.forEach((slot, delta) -> { if (!delta.isEmpty()) pending.computeIfAbsent(slot, s -> new Delta()).merge(delta); });
    }

    public synchronized boolean isEmpty() { return pending.isEmpty(); }

    /** How many slots are waiting. */
    public synchronized int size() { return pending.size(); }

    private static long sum(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }
}
