package com.hexvane.eterniamod.pathtool;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.pathtool.SplineGeometry.*;

class SplineGeometryTest {
    private final List<Node> bend=List.of(new Node(.5,1,.5),new Node(.5,1,10.5),new Node(10.5,1,10.5));
    @Test void centerlineInterpolatesNodesWithRealCurvature(){var sample=sample(bend);assertEquals(bend.getFirst(),sample.getFirst());assertEquals(bend.getLast(),sample.getLast());assertTrue(sample.contains(bend.get(1)));assertTrue(sample.stream().anyMatch(p->p.x()<-.1&&p.z()>2&&p.z()<9));}
    @Test void widthIsSweptAndCurveInteriorStaysEmpty(){var straight=footprint(List.of(new Node(.5,1,.5),new Node(.5,1,10.5)),5);var middle=straight.stream().filter(h->h.column().z()==5).map(h->h.column().x()).toList();assertEquals(List.of(-2,-1,0,1,2),middle);var columns=footprint(bend,3).stream().map(Hint::column).toList();assertFalse(columns.contains(new Column(5,5)));assertFalse(rectangles(columns).stream().anyMatch(r->r.contains(5,5)));}
    @Test void rectangleCompressionPreservesEveryCellWithoutOverlap(){var columns=new HashSet<>(footprint(bend,5).stream().map(Hint::column).toList());var expanded=new HashSet<Column>();for(var rect:rectangles(columns))for(int x=rect.x();x<rect.endX();x++)for(int z=rect.z();z<rect.endZ();z++)assertTrue(expanded.add(new Column(x,z)));assertEquals(columns,expanded);}
    @Test void supportsSparseRowsAndNegativeCoordinates(){var columns=List.of(new Column(-3,-4),new Column(-2,-4),new Column(-3,-3),new Column(-2,-3),new Column(4,-3),new Column(-3,0));var rects=rectangles(columns);assertEquals(6,rects.stream().mapToLong(r->r.area()).sum());assertEquals(3,rects.size());assertFalse(rects.stream().anyMatch(r->r.contains(0,-3)));assertFalse(rects.stream().anyMatch(r->r.contains(-3,-2)));}
    @Test void boundedGeometryRejectsDegenerateOrOversizedInputs(){assertThrows(IllegalArgumentException.class,()->sample(List.of(new Node(0,1,0),new Node(0,1,0))));assertThrows(IllegalArgumentException.class,()->footprint(bend,10));assertThrows(IllegalArgumentException.class,()->sample(List.of(new Node(0,1,0),new Node(100,1,0))));assertThrows(IllegalArgumentException.class,()->new Node(Double.NaN,1,0));}
    @Test void rayPickSelectsClosestVisibleNode(){var nodes=List.of(new Node(0,1,4),new Node(0,1,8));assertEquals(0,pick(nodes,0,1.4,0,0,0,1));assertEquals(-1,pick(nodes,0,1.4,0,0,0,-1));assertEquals(-1,pick(nodes,4,1.4,0,0,0,1));}
    @Test void sharedRoadCellsAreLimitedToEndpointJunctions(){var nodes=List.of(new Node(.5,1,.5),new Node(.5,1,40.5));assertTrue(endpointConnection(new Column(0,3),nodes,3));assertTrue(endpointConnection(new Column(0,39),nodes,3));assertFalse(endpointConnection(new Column(0,20),nodes,3));assertFalse(endpointConnection(new Column(12,0),nodes,3));}
    @Test void playgroundCurveStaysClearOfPlazaAndSellerFootprint(){var nodes=List.of(new Node(3.5,1,23.5),new Node(3.5,1,65.5),new Node(3.5,1,105.5),new Node(30.5,1,125.5));var columns=footprint(nodes,5).stream().map(Hint::column).toList();assertTrue(columns.stream().noneMatch(c->c.x()>=-20&&c.x()<20&&c.z()>=-20&&c.z()<20));assertTrue(columns.stream().noneMatch(c->c.x()>=8&&c.x()<32&&c.z()>=22&&c.z()<46));assertTrue(rectangles(columns).stream().anyMatch(r->r.gap(new com.hexvane.eterniamod.housing.PlotRect(8,22,24,24))<=5));}
}
