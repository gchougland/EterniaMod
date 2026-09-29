package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.housing.PlotRect;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.guildroads.GuildRoadPlanner.*;

class GuildRoadPlannerTest {
    @Test void fiveBlockGapIsAllowedOnlyBetweenActiveCommunityEndpoints(){
        var plots=List.of(new PlotRect(0,0,2,2),new PlotRect(7,0,2,2));
        var route=route(new Cell(1,0),new Cell(7,0),plots,List.of(),List.of());
        assertEquals(7,route.size());assertEquals(new PlotRect(1,0,7,1),rectangle(route));
        assertThrows(IllegalArgumentException.class,()->route(new Cell(1,0),new Cell(8,0),List.of(plots.getFirst(),new PlotRect(8,0,2,2)),List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->route(new Cell(1,0),new Cell(5,0),plots,List.of(),List.of()));
    }
    @Test void publicColumnsForeignPlotsAndHouseSetbackAreNeverBridged(){
        var zone=List.of(new PlotRect(-10,-10,60,60));var house=List.of(new PlotRect(0,10,10,10));
        assertEquals(8,route(new Cell(0,4),new Cell(7,4),zone,List.of(),house).size());
        assertThrows(IllegalArgumentException.class,()->route(new Cell(0,5),new Cell(7,5),zone,List.of(),house));
        assertThrows(IllegalArgumentException.class,()->route(new Cell(0,4),new Cell(7,4),zone,List.of(new PlotRect(4,4,1,1)),house));
    }
    @Test void editMaskIsStraightBoundedAndIndependentOfEndpointDirection(){
        var zone=List.of(new PlotRect(-500,-500,1000,1000));
        var forwards=route(new Cell(0,0),new Cell(127,0),zone,List.of(),List.of());
        var reverse=route(new Cell(127,0),new Cell(0,0),zone,List.of(),List.of());
        assertEquals(rectangle(forwards),rectangle(reverse));assertEquals(128,forwards.size());
        assertThrows(IllegalArgumentException.class,()->route(new Cell(0,0),new Cell(128,0),zone,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->route(new Cell(0,0),new Cell(1,1),zone,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->route(new Cell(0,0),new Cell(0,0),zone,List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->route(new Cell(Integer.MIN_VALUE,0),new Cell(Integer.MAX_VALUE,0),zone,List.of(),List.of()));
    }
}
