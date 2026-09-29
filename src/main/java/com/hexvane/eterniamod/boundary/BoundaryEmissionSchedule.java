package com.hexvane.eterniamod.boundary;

import java.util.HashMap;
import java.util.Map;

/** Scan movement frequently, but let each emitter finish before renewing it. */
final class BoundaryEmissionSchedule {
    private static final double SCAN_SECONDS = .1;
    private double now, nextScan;
    private final Map<Emitter, Double> nextEmission = new HashMap<>();

    boolean advance(double dt) {
        now += dt;
        if (now < nextScan) return false;
        nextScan = now + SCAN_SECONDS;
        nextEmission.values().removeIf(deadline -> deadline <= now);
        return true;
    }

    boolean ready(String world, double x, double z) {
        return !nextEmission.containsKey(new Emitter(world, x, z));
    }

    void emitted(String world, double x, double z) {
        nextEmission.put(new Emitter(world, x, z), now + BoundaryFogSystem.REFRESH_SECONDS);
    }

    private record Emitter(String world, double x, double z) {}
}
