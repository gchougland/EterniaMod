package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PathPlannerTest {
    @Test void detoursAroundStructuresAndNeverTouchesRoadOrNeighbor() {
        var plot=new PlotRect(0,0,24,24);var road=new PlotRect(20,-10,3,50);
        var obstacle=new PlotRect(11,0,3,16);
        var start=new PathPlanner.Cell(5,5);
        var route=PathPlanner.route(plot,start,List.of(road),cell->!obstacle.contains(cell.x(),cell.z()));
        assertEquals(start,route.getFirst());assertEquals(19,route.getLast().x());
        for(int i=0;i<route.size();i++) {
            var cell=route.get(i);assertTrue(plot.contains(cell.x(),cell.z()));assertFalse(road.contains(cell.x(),cell.z()));assertFalse(obstacle.contains(cell.x(),cell.z()));
            if(i>0){var before=route.get(i-1);assertEquals(1,Math.abs(before.x()-cell.x())+Math.abs(before.z()-cell.z()));}
        }
        assertEquals(route,PathPlanner.route(plot,start,List.of(road),cell->!obstacle.contains(cell.x(),cell.z())));
    }
    @Test void rejectsRoadOriginAndDisconnectedFarRoad() {
        var plot=new PlotRect(0,0,24,24);var start=new PathPlanner.Cell(2,2);
        assertThrows(IllegalArgumentException.class,()->PathPlanner.route(plot,start,List.of(new PlotRect(2,0,1,24)),cell->true));
        assertThrows(IllegalArgumentException.class,()->PathPlanner.route(plot,start,List.of(new PlotRect(100,0,2,24)),cell->true));
        assertThrows(IllegalArgumentException.class,()->PathPlanner.route(plot,start,List.of(new PlotRect(24,0,2,24)),cell->cell.x()<4));
    }
    @Test void stopsOnOwnSideWhenRoadIsFiveColumnsAway() {
        var plot=new PlotRect(0,0,24,24);var road=new PlotRect(29,0,2,24);
        var route=PathPlanner.route(plot,new PathPlanner.Cell(10,10),List.of(road),cell->true);
        assertEquals(new PathPlanner.Cell(23,10),route.getLast());assertTrue(route.size()<=128);
    }
}
