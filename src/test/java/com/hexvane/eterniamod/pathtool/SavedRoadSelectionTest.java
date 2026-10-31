package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.pathtool.SplineRoadStore.*;

class SavedRoadSelectionTest {
    private Road road(String world,List<SplineGeometry.Cell> cells,List<SplineGeometry.Cell> connections){
        var version=new Version(List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(20.5,1,20.5)),3,"stone","Rock_Stone_Cobble",cells,Map.of(),new SnapshotFiles.Saved("before.json","a".repeat(64)),new SnapshotFiles.Saved("after.json","b".repeat(64)),connections);
        return new Road(UUID.randomUUID(),UUID.randomUUID(),world,2,State.ACTIVE,version,null);
    }
    @Test void selectionUsesExactSurfaceAndPrefersTheOwnerAtAJunction(){
        var shared=new SplineGeometry.Cell(0,0,0);var owner=road("Hub",List.of(shared,new SplineGeometry.Cell(20,0,20)),List.of());
        var branch=road("Hub",List.of(new SplineGeometry.Cell(-1,0,0)),List.of(shared));
        assertEquals(owner,SavedRoadSelection.aimed(List.of(branch,owner),"Hub",0,0));
        assertEquals(branch,SavedRoadSelection.aimed(List.of(branch,owner),"Hub",-1,0));
        assertNull(SavedRoadSelection.aimed(List.of(owner),"Hub",10,10));
        assertNull(SavedRoadSelection.aimed(List.of(owner),"Adventure",0,0));
    }
    @Test void overlaysOmitInternalEdgesAndKeepSavedGroundHeight(){
        var r=road("Hub",List.of(new SplineGeometry.Cell(0,3,0),new SplineGeometry.Cell(1,3,0)),List.of());
        var edges=SavedRoadSelection.edges(r,0,0);
        assertEquals(6,edges.size());assertTrue(edges.stream().allMatch(e -> Math.abs(e.y()-4.08)<1e-9));
        assertTrue(SavedRoadSelection.edges(r,1000,1000).isEmpty());
        assertEquals(List.of(r),SavedRoadSelection.nearby(List.of(r),"Hub",0,0));
        assertTrue(SavedRoadSelection.nearby(List.of(r),"Hub",1000,1000).isEmpty());
        var removed=new Road(r.id(),r.actor(),r.world(),4,State.REMOVED,null,null);
        assertTrue(SavedRoadSelection.nearby(List.of(removed),"Hub",0,0).isEmpty());
    }
}
