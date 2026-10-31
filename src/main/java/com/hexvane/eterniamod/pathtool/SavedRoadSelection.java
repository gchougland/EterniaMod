package com.hexvane.eterniamod.pathtool;

import java.util.*;
import static com.hexvane.eterniamod.pathtool.SplineRoadStore.*;

/** Exact road surfaces drive picking and overlays, never the empty interior of a curve. */
final class SavedRoadSelection {
    static final double RANGE = 96;
    record Edge(double x,double y,double z,double endX,double endZ) {}
    private SavedRoadSelection() {}
    static List<SplineGeometry.Cell> surface(Road road) {
        if (road.state() == State.REMOVED) return List.of();
        if (road.state() == State.PENDING) return road.protectedCells();
        var cells = new ArrayList<>(road.current().cells());
        cells.addAll(road.current().connections());
        return List.copyOf(cells);
    }
    static Road aimed(List<Road> roads,String world,int x,int z) {
        // Prefer the road that owns the paving at a shared junction.
        return roads.stream().filter(r -> r.world().equals(world) && r.state()!=State.REMOVED)
            .filter(r -> surface(r).stream().anyMatch(c -> c.x()==x && c.z()==z))
            .sorted(Comparator.<Road>comparingInt(r -> r.protectedCells().stream().anyMatch(c -> c.x()==x && c.z()==z)?0:1).thenComparing(Road::id))
            .findFirst().orElse(null);
    }
    static List<Road> nearby(List<Road> roads,String world,double x,double z) {
        return roads.stream().filter(r -> r.world().equals(world) && r.state()!=State.REMOVED)
            .filter(r -> distance(r,x,z)<=RANGE*RANGE).sorted(Comparator.<Road>comparingDouble(r -> distance(r,x,z)).thenComparing(Road::id)).toList();
    }
    private static double distance(Road road,double x,double z) {
        return surface(road).stream().mapToDouble(c -> Math.pow(c.x()+.5-x,2)+Math.pow(c.z()+.5-z,2)).min().orElse(Double.POSITIVE_INFINITY);
    }
    static List<Edge> edges(Road road,double x,double z) {
        var cells=surface(road);var columns=new HashSet<SplineGeometry.Column>();cells.forEach(c -> columns.add(c.column()));
        var edges=new ArrayList<Edge>();
        var drawn=new HashSet<SplineGeometry.Column>();
        for(var c:cells) {
            if(Math.pow(c.x()+.5-x,2)+Math.pow(c.z()+.5-z,2)>RANGE*RANGE||!drawn.add(c.column()))continue;
            double y=c.y()+1.08;
            if(!columns.contains(new SplineGeometry.Column(c.x()-1,c.z())))edges.add(new Edge(c.x(),y,c.z(),c.x(),c.z()+1));
            if(!columns.contains(new SplineGeometry.Column(c.x()+1,c.z())))edges.add(new Edge(c.x()+1,y,c.z(),c.x()+1,c.z()+1));
            if(!columns.contains(new SplineGeometry.Column(c.x(),c.z()-1)))edges.add(new Edge(c.x(),y,c.z(),c.x()+1,c.z()));
            if(!columns.contains(new SplineGeometry.Column(c.x(),c.z()+1)))edges.add(new Edge(c.x(),y,c.z()+1,c.x()+1,c.z()+1));
        }
        return List.copyOf(edges);
    }
}
