package com.hexvane.eterniamod.boundary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure bounded sampling; rectangles use half-open block bounds. */
public final class BoundaryGeometry {
    public static final double FULL_DISTANCE = 3.0;
    public static final double FAR_DISTANCE = 8.0;
    public static final int MAX_EMITTERS_PER_VIEWER = 32;
    private BoundaryGeometry() {}

    public record Rectangle(int minX, int minZ, int maxX, int maxZ, int groundY) {}
    public record Sample(double x, double z, int groundY, double distance, boolean horizontal) { public Sample(double x,double z,int groundY,double distance){this(x,z,groundY,distance,true);} }

    public static double edgeDistance(double x, double z, Rectangle rectangle) {
        double dx = Math.max(Math.max(rectangle.minX - x, 0), x - rectangle.maxX);
        double dz = Math.max(Math.max(rectangle.minZ - z, 0), z - rectangle.maxZ);
        if (dx != 0 || dz != 0) return Math.hypot(dx, dz);
        return Math.min(Math.min(x - rectangle.minX, rectangle.maxX - x), Math.min(z - rectangle.minZ, rectangle.maxZ - z));
    }

    public static double intensity(double distance) {
        double t = Math.max(0, Math.min(1, (distance - FULL_DISTANCE) / (FAR_DISTANCE - FULL_DISTANCE)));
        return 1 - t * t * (3 - 2 * t);
    }

    public static List<Sample> nearby(List<Rectangle> rectangles, double x, double z) {
        Map<String, Sample> points = new LinkedHashMap<>();
        for (Rectangle rectangle : rectangles) {
            if (rectangle.minX >= rectangle.maxX || rectangle.minZ >= rectangle.maxZ
                || edgeDistance(x, z, rectangle) >= FAR_DISTANCE) continue;
            sampleHorizontal(points, rectangle.minX, rectangle.maxX, rectangle.minZ, rectangle.groundY, x, z);
            sampleHorizontal(points, rectangle.minX, rectangle.maxX, rectangle.maxZ, rectangle.groundY, x, z);
            sampleVertical(points, rectangle.minZ, rectangle.maxZ, rectangle.minX, rectangle.groundY, x, z);
            sampleVertical(points, rectangle.minZ, rectangle.maxZ, rectangle.maxX, rectangle.groundY, x, z);
        }
        List<Sample> sorted = new ArrayList<>(points.values());
        sorted.sort(Comparator.comparingDouble(Sample::distance).thenComparingDouble(Sample::x).thenComparingDouble(Sample::z));
        return List.copyOf(sorted.subList(0, Math.min(MAX_EMITTERS_PER_VIEWER, sorted.size())));
    }

    private static void sampleHorizontal(Map<String, Sample> points, int min, int max, int fixed, int ground, double x, double z) {
        long start = Math.max((long) min, (long) Math.floor(x - FAR_DISTANCE));
        long end = Math.min((long) max - 1, (long) Math.ceil(x + FAR_DISTANCE));
        for (long i = start; i <= end; i++) add(points, i + .5, fixed, ground, x, z,true);
    }

    private static void sampleVertical(Map<String, Sample> points, int min, int max, int fixed, int ground, double x, double z) {
        long start = Math.max((long) min, (long) Math.floor(z - FAR_DISTANCE));
        long end = Math.min((long) max - 1, (long) Math.ceil(z + FAR_DISTANCE));
        for (long i = start; i <= end; i++) add(points, fixed, i + .5, ground, x, z,false);
    }

    private static void add(Map<String, Sample> points, double px, double pz, int ground, double x, double z, boolean horizontal) {
        double distance = Math.hypot(px - x, pz - z);
        if (distance < FAR_DISTANCE) points.putIfAbsent(px + ":" + pz, new Sample(px, pz, ground, distance,horizontal));
    }
}
