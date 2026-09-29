package com.hexvane.eterniamod.collections;

/** Pure movement bounds shared by spawning and the cosmetic tick. */
public final class PetMotion {
    public record Bounds(double minX,double minZ,double maxX,double maxZ) {
        public Bounds { if (!Double.isFinite(minX+minZ+maxX+maxZ) || maxX-minX<2 || maxZ-minZ<2) throw new IllegalArgumentException("Invalid pet property bounds"); }
        public double x(double value) { return Math.clamp(value,minX+1,maxX-1); }
        public double z(double value) { return Math.clamp(value,minZ+1,maxZ-1); }
        public boolean contains(double x,double z) { return x>=minX+1 && x<=maxX-1 && z>=minZ+1 && z<=maxZ-1; }
    }
    public static double blend(double from,double target,double dt) {
        if (!Double.isFinite(from+target+dt) || dt<=0) return from;
        return from+(target-from)*Math.min(1,Math.min(dt,.1)*3);
    }
    private PetMotion() {}
}
