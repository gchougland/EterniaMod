package com.hexvane.eterniamod.collections;

/** Pure movement bounds shared by spawning and the cosmetic tick. */
public final class PetMotion {
    public record Point(double x,double z) {}
    /** Follow from the pet's current position, keeping a small resting distance without orbiting on turns. */
    public static Point follow(double petX,double petZ,double ownerX,double ownerZ,double yaw) {
        double dx=ownerX-petX,dz=ownerZ-petZ,distance=Math.hypot(dx,dz),gap=1.8;
        if(distance>12)return behind(ownerX,ownerZ,yaw);
        if(distance<=gap)return new Point(petX,petZ);
        return new Point(ownerX-dx/distance*gap,ownerZ-dz/distance*gap);
    }
    public static Point behind(double x,double z,double yaw){return new Point(x+Math.sin(yaw)*1.8,z+Math.cos(yaw)*1.8);}
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
