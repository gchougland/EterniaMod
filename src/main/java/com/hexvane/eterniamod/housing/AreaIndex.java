package com.hexvane.eterniamod.housing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A world's registered roads and plazas bucketed by 64 x 64 block squares, so "is this column protected" looks only at
 * the few areas near it instead of every area in the world. Built once per saved world plan; immutable.
 */
public final class AreaIndex {
    static final int BUCKET = 64;
    /** One registered area: its name, kind and rectangle. */
    public record Entry(String name, HousingInfrastructure.AreaKind kind, PlotRect rect) {}

    private final Map<Long, List<Entry>> buckets = new HashMap<>();

    public AreaIndex(Map<String, HousingInfrastructure.Area> areas) {
        areas.forEach((name, area) -> {
            var r = area.rect();
            var entry = new Entry(name, area.kind(), r);
            for (int bx = Math.floorDiv(r.x(), BUCKET); bx <= Math.floorDiv(r.endX() - 1, BUCKET); bx++)
                for (int bz = Math.floorDiv(r.z(), BUCKET); bz <= Math.floorDiv(r.endZ() - 1, BUCKET); bz++)
                    buckets.computeIfAbsent(key(bx, bz), k -> new ArrayList<>()).add(entry);
        });
    }

    private static long key(int bx, int bz) { return ((long) bx << 32) ^ (bz & 0xffffffffL); }

    /** Areas whose rectangle may contain column (x, z): every one that does, plus a few near misses. */
    public List<Entry> near(int x, int z) {
        return buckets.getOrDefault(key(Math.floorDiv(x, BUCKET), Math.floorDiv(z, BUCKET)), List.of());
    }

    /** True when any area of {@code kind} (or any kind, when null) contains column (x, z). */
    public boolean contains(int x, int z, HousingInfrastructure.AreaKind kind) {
        for (var e : near(x, z)) if ((kind == null || e.kind() == kind) && e.rect().contains(x, z)) return true;
        return false;
    }

    /** True when any area overlaps {@code rect}. */
    public boolean overlaps(PlotRect rect) {
        for (int bx = Math.floorDiv(rect.x(), BUCKET); bx <= Math.floorDiv(rect.endX() - 1, BUCKET); bx++)
            for (int bz = Math.floorDiv(rect.z(), BUCKET); bz <= Math.floorDiv(rect.endZ() - 1, BUCKET); bz++)
                for (var e : buckets.getOrDefault(key(bx, bz), List.of())) if (e.rect().overlaps(rect)) return true;
        return false;
    }
}
