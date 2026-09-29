package com.hexvane.eterniamod.housing;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicRoadNetworkTest {
    @Test void claimsMeasureFromCenterlineAndDoNotNeedToOverlapTheRoad(){
        var road=new PlotRect(-2,0,5,160);var portal=new PlotRect(-20,-20,40,20);
        var network=PublicRoadNetwork.rectangles(List.of(road));
        assertTrue(network.near(new PlotRect(-28,50,24,24))); // edge -4, center .5: 4.5
        assertFalse(network.near(new PlotRect(-29,50,24,24))); // edge -5: 5.5
        assertTrue(network.near(new PlotRect(5,50,24,24)));
        assertFalse(network.near(new PlotRect(6,50,24,24)));
        var claim=new HousingRules.Candidate(new PlotRect(-28,50,24,24),HousingRules.Scope.PUBLIC,null,false,0);
        assertTrue(HousingRules.claim(claim,List.of(),List.of(road),List.of(portal),network).valid());
    }
    @Test void onlyUnbrokenPublicRoadConnectionsCarryPortalAnchors(){
        var portal=new PlotRect(-5,-10,10,10);var first=new PlotRect(-2,0,5,30);var second=new PlotRect(-2,30,5,60);var lot=new PlotRect(5,60,24,24);
        assertTrue(PublicRoadNetwork.rectangles(List.of(first,second)).anchored(lot,List.of(portal)));
        assertFalse(PublicRoadNetwork.rectangles(List.of(new PlotRect(-2,0,5,29),second)).anchored(lot,List.of(portal)));
        assertFalse(PublicRoadNetwork.rectangles(List.of(second)).anchored(lot,List.of(portal)));
    }
    @Test void diagonalDistanceUsesTheSegmentNotItsBoundingRectangle(){
        var line=List.of(new PublicRoadNetwork.Point(0,0),new PublicRoadNetwork.Point(100,100));
        assertEquals(Math.sqrt(50),PublicRoadNetwork.distance(new PlotRect(20,0,10,10),line),1e-8);
        assertEquals(0,PublicRoadNetwork.distance(new PlotRect(20,20,10,10),line));
    }
    @Test void theReportedOverlappingLotHasAValidNineBlockHousePosition(){
        assertTrue(HousingRules.structure(new PlotRect(-21,40,24,24),new PlotRect(-16,47,9,9),List.of(new PlotRect(-2,22,5,52))).valid());
        assertEquals("roadSetback",HousingRules.structure(new PlotRect(-21,40,24,24),new PlotRect(-15,47,9,9),List.of(new PlotRect(-2,22,5,52))).code());
    }
}
