package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.housing.HousingInfrastructure;
import java.util.*;
import static com.hexvane.eterniamod.pathtool.SplineRoadStore.*;

/** A branch borrows a surface; its parent cannot remove or repaint that surface until it detaches. */
final class RoadConnectionRules {
    private RoadConnectionRules(){}
    static List<SplineGeometry.Cell> connections(Road road){var cells=new HashSet<SplineGeometry.Cell>();if(road.current()!=null)cells.addAll(road.current().connections());if(road.change()!=null&&road.change().next()!=null)cells.addAll(road.change().next().connections());return List.copyOf(cells);}
    static void preserveSurface(Version before,Version after,Collection<SplineGeometry.Cell> dependents){
        if(before==null)return;var beforePaint=RoadStyle.surface(before.nodes(),before.width(),before.cells(),before.palette(),before.block());var afterPaint=after==null?Map.<SplineGeometry.Column,String>of():RoadStyle.surface(after.nodes(),after.width(),after.cells(),after.palette(),after.block());var old=new HashMap<SplineGeometry.Column,SplineGeometry.Cell>();before.cells().forEach(c->old.put(c.column(),c));var next=new HashMap<SplineGeometry.Column,SplineGeometry.Cell>();if(after!=null)after.cells().forEach(c->next.put(c.column(),c));
        for(var cell:dependents)if(old.containsKey(cell.column())&&(after==null||!cell.equals(old.get(cell.column()))||!cell.equals(next.get(cell.column()))||!Objects.equals(beforePaint.get(cell.column()),afterPaint.get(cell.column()))))throw new IllegalStateException("Another road joins this surface. Detach or remove the connected road before removing, moving or repainting these junction cells.");
    }
    static void preserveProtection(HousingInfrastructure.WorldPlan after,Collection<SplineGeometry.Cell> connections){for(var cell:connections)if(after.areas().values().stream().noneMatch(a->a.rect().contains(cell.x(),cell.z())))throw new IllegalStateException("Another road needs this registered junction. Detach or remove the connected road before unregistering its connection surface.");}
}
