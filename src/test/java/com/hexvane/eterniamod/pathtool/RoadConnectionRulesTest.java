package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.pathtool.SplineRoadStore.*;

class RoadConnectionRulesTest {
    @Test void intersectionsAndLongSharedEdgesAreValidSavedRoads(){
        var nodes=List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(.5,1,40.5));
        var owned=List.of(new SplineGeometry.Cell(0,0,0),new SplineGeometry.Cell(0,0,40));
        var shared=new ArrayList<SplineGeometry.Cell>();for(int z=1;z<40;z++)shared.add(new SplineGeometry.Cell(0,0,z));
        var v=new Version(nodes,1,"stone","Rock_Stone_Cobble",owned,Map.of(),new SnapshotFiles.Saved("before.json","a".repeat(64)),new SnapshotFiles.Saved("after.json","b".repeat(64)),shared);
        assertEquals(39,v.connections().size());
        assertEquals(v,new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(v),Version.class));
    }
    private final List<SplineGeometry.Cell> cells=List.of(new SplineGeometry.Cell(0,0,0),new SplineGeometry.Cell(1,0,0),new SplineGeometry.Cell(2,0,0));
    private Version version(List<SplineGeometry.Cell> mask,String block){return new Version(List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(2.5,1,.5)),1,"stone",block,mask,Map.of(),new SnapshotFiles.Saved("before.json","a".repeat(64)),new SnapshotFiles.Saved("after.json","b".repeat(64)));}
    @Test void parentRemovalOrRepaintMustWaitForBranchDetachment(){var old=version(cells,"Rock_Stone_Cobble");var connections=List.of(cells.getFirst());assertThrows(IllegalStateException.class,()->RoadConnectionRules.preserveSurface(old,null,connections));assertThrows(IllegalStateException.class,()->RoadConnectionRules.preserveSurface(old,version(cells,"Rock_Stone_Brick"),connections));assertThrows(IllegalStateException.class,()->RoadConnectionRules.preserveSurface(old,version(cells.subList(1,3),"Rock_Stone_Cobble"),connections));assertDoesNotThrow(()->RoadConnectionRules.preserveSurface(old,null,List.of()));}
    @Test void unrelatedParentEditsRetainConnectionSurface(){var old=version(cells,"Rock_Stone_Cobble");var extended=new ArrayList<>(cells);extended.add(new SplineGeometry.Cell(3,0,0));assertDoesNotThrow(()->RoadConnectionRules.preserveSurface(old,version(extended,"Rock_Stone_Cobble"),List.of(cells.getFirst())));}
    @Test void legacyRegistrationCannotAbandonConnectionProtection(){var supported=new HousingInfrastructure.WorldPlan("housing",List.of(new PlotRect(0,0,3,1)),List.of(),null);var plaza=new HousingInfrastructure.WorldPlan("housing",List.of(),List.of(new PlotRect(0,0,3,1)),null);var missing=new HousingInfrastructure.WorldPlan("housing",List.of(),List.of(),null);assertDoesNotThrow(()->RoadConnectionRules.preserveProtection(plaza,List.of(cells.getFirst())));assertDoesNotThrow(()->RoadConnectionRules.preserveProtection(supported,List.of(cells.getFirst())));assertThrows(IllegalStateException.class,()->RoadConnectionRules.preserveProtection(missing,List.of(cells.getFirst())));}
    @Test void readOnlyConnectionCannotAlsoBeOwned(){assertThrows(IllegalArgumentException.class,()->new Version(List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(2.5,1,.5)),1,"stone","Rock_Stone_Cobble",cells,Map.of(),new SnapshotFiles.Saved("before.json","a".repeat(64)),new SnapshotFiles.Saved("after.json","b".repeat(64)),List.of(cells.getFirst())));}
}
