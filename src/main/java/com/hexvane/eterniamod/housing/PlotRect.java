package com.hexvane.eterniamod.housing;

/** Block columns in [x, x + width) by [z, z + depth). Y is deliberately unbounded. */
public record PlotRect(int x, int z, int width, int depth) {
    public PlotRect {
        if (width < 1 || depth < 1 || width > 4096 || depth > 4096) throw new IllegalArgumentException("Invalid rectangle size");
        Math.addExact(x, width); Math.addExact(z, depth);
    }
    public int endX() { return x + width; }
    public int endZ() { return z + depth; }
    public long area() { return (long) width * depth; }
    public boolean contains(int bx, int bz) { return bx >= x && bx < endX() && bz >= z && bz < endZ(); }
    public boolean contains(PlotRect other) { return other.x >= x && other.z >= z && other.endX() <= endX() && other.endZ() <= endZ(); }
    public boolean overlaps(PlotRect other) { return x < other.endX() && other.x < endX() && z < other.endZ() && other.z < endZ(); }
    public double gap(PlotRect other) {
        double dx = Math.max(0L, Math.max((long) x - other.endX(), (long) other.x - endX()));
        double dz = Math.max(0L, Math.max((long) z - other.endZ(), (long) other.z - endZ()));
        return Math.hypot(dx, dz);
    }
    public boolean containsWithSetback(PlotRect other, int setback) {
        return other.x >= (long)x + setback && other.z >= (long)z + setback
            && other.endX() <= (long)endX() - setback && other.endZ() <= (long)endZ() - setback;
    }
    public static PlotRect centered(int x, int z, int width, int depth) {
        return new PlotRect(Math.subtractExact(x, (width - 1) / 2), Math.subtractExact(z, (depth - 1) / 2), width, depth);
    }
}
