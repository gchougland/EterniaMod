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
    @Test void everyWidthStaysInsidePlotAndLeavesOneEmptyBlockAroundStructures() {
        var plot=new PlotRect(0,0,32,32);var road=new PlotRect(32,0,3,32);var house=new PlotRect(12,0,6,18);
        for(int width=1;width<=3;width++) {
            var plan=PathPlanner.plan(plot,new PathPlanner.Cell(5,5),List.of(road),width,c->true,List.of(house));
            assertEquals(width,plan.width());assertTrue(plan.blocks().size()>=plan.centerline().size());
            for(var c:plan.blocks()) {
                assertTrue(plot.contains(c.x(),c.z()));assertFalse(road.contains(c.x(),c.z()));
                assertFalse(new PlotRect(11,-1,8,20).contains(c.x(),c.z()),"Missing padding: "+c);
            }
            var cells=new HashSet<>(plan.blocks());var reached=new HashSet<PathPlanner.Cell>();var queue=new ArrayDeque<PathPlanner.Cell>();queue.add(plan.blocks().getFirst());
            while(!queue.isEmpty()){var c=queue.remove();if(!reached.add(c))continue;for(var d:List.of(new PathPlanner.Cell(c.x()+1,c.z()),new PathPlanner.Cell(c.x()-1,c.z()),new PathPlanner.Cell(c.x(),c.z()+1),new PathPlanner.Cell(c.x(),c.z()-1)))if(cells.contains(d)&&!reached.contains(d))queue.add(d);}
            assertEquals(cells,reached,"Paving must remain connected");
        }
    }
    @Test void widthCannotClipAnObstacleOrSqueezeThroughOneBlockCorridor() {
        var plot=new PlotRect(0,0,24,24);var road=List.of(new PlotRect(24,0,3,24));
        assertThrows(IllegalArgumentException.class,()->PathPlanner.plan(plot,new PathPlanner.Cell(2,5),road,3,c->c.z()==5,List.of()));
        assertThrows(IllegalArgumentException.class,()->PathPlanner.plan(plot,new PathPlanner.Cell(2,5),road,4,c->true,List.of()));
        assertThrows(IllegalArgumentException.class,()->PathPlanner.plan(plot,new PathPlanner.Cell(5,5),road,1,c->true,List.of(new PlotRect(6,5,4,4))));
    }
    @Test void roomyDetourSoftensItsCorners() {
        var plan=PathPlanner.plan(new PlotRect(0,0,32,32),new PathPlanner.Cell(5,5),List.of(new PlotRect(32,0,3,32)),1,c->true,List.of(new PlotRect(12,0,6,18)));
        int turns=0;var line=plan.centerline();
        for(int i=2;i<line.size();i++){var a=line.get(i-2);var b=line.get(i-1);var c=line.get(i);if(b.x()-a.x()!=c.x()-b.x()||b.z()-a.z()!=c.z()-b.z())turns++;}
        assertTrue(turns>=4,"A roomy bend should use several small steps instead of two hard corners");
    }
    @Test void hexPlotPathsStayOnThePlotsHexagons() {
        var lattice=com.hexvane.eterniamod.hexplots.HexLattice.DEFAULT;
        var cells=com.hexvane.eterniamod.hexplots.PlotCells.of(lattice,List.of(com.hexvane.eterniamod.hexplots.HexCoord.ORIGIN));
        var box=cells.bounds();
        // A road beside the cell's north tip: the straight route along the box would cut through a neighbour's corner.
        var road=new PlotRect(box.x()-10,box.z()-3,box.width()+20,2);
        var start=new PathPlanner.Cell((int)lattice.centerX(com.hexvane.eterniamod.hexplots.HexCoord.ORIGIN)+8,(int)lattice.centerZ(com.hexvane.eterniamod.hexplots.HexCoord.ORIGIN));
        var plan=PathPlanner.plan(cells::containsColumn,start,List.of(road),1,cell->true,List.of());
        assertFalse(plan.blocks().isEmpty());
        for(var cell:plan.blocks())assertTrue(cells.containsColumn(cell.x(),cell.z()),"path block "+cell+" left the hexagon");
        assertFalse(cells.containsColumn(box.x(),box.z()),"the box corner is a neighbour's land");
    }

    @org.junit.jupiter.api.Test void longPathsFollowGentleStepsButNotCliffs(){
        var land=new PlotRect(0,0,300,20);var road=new PlotRect(296,0,4,20);
        // Two hundred and ninety blocks long, rising one block every ten.
        var plan=PathPlanner.planOnGround(land::contains,new PathPlanner.Cell(5,10),java.util.List.of(road),3,c->c.x()/10,java.util.List.of());
        org.junit.jupiter.api.Assertions.assertTrue(plan.centerline().getLast().x()>=289);
        // A two block cliff across the whole strip cannot be paved.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->PathPlanner.planOnGround(land::contains,new PathPlanner.Cell(5,10),java.util.List.of(road),3,c->c.x()<150?0:2,java.util.List.of()));
    }
}
