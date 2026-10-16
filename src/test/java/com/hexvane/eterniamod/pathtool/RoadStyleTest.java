package com.hexvane.eterniamod.pathtool;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoadStyleTest {
    private List<List<String>> columns(){var result=new ArrayList<List<String>>();for(int i=0;i<9;i++)result.add(new ArrayList<>(List.of("Block_"+i,"","","","","")));return result;}
    @Test void threeWideUsesMiddleColumnsAndTurnsWithTheRoad(){
        var straight=List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(.5,1,8.5));
        var cells=List.of(new SplineGeometry.Cell(-1,0,4),new SplineGeometry.Cell(0,0,4),new SplineGeometry.Cell(1,0,4));
        var surface=RoadStyle.surface(straight,3,cells,columns(),"Fallback");assertEquals("Block_3",surface.get(cells.get(0).column()));assertEquals("Block_4",surface.get(cells.get(1).column()));assertEquals("Block_5",surface.get(cells.get(2).column()));
        var turned=List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(8.5,1,.5));
        assertEquals("Block_3",RoadStyle.surface(turned,3,List.of(new SplineGeometry.Cell(4,0,1)),columns(),"Fallback").get(new SplineGeometry.Column(4,1)));
    }
    @Test void variationIsRepeatableAndRepeatedSlotsAreMoreCommon(){
        var columns=columns();columns.set(4,List.of("Common","Common","Common","Common","Common","Rare"));int common=0,rare=0;
        for(int x=-200;x<200;x++){var material=RoadStyle.material(columns,4,x,23,"Fallback");assertEquals(material,RoadStyle.material(columns,4,x,23,"Fallback"));if(material.equals("Common"))common++;else if(material.equals("Rare"))rare++;else fail(material);}
        assertTrue(common>rare*3);assertTrue(rare>30);
    }
    @Test void emptyColumnBorrowsNearbyAndLegacyUniformRoadsRemainUniform(){
        var columns=columns();columns.set(4,List.of("","","","","",""));assertEquals("Block_3",RoadStyle.material(columns,4,1,1,"Fallback"));assertEquals("Legacy",RoadStyle.material(List.of(),4,1,1,"Legacy"));
    }
    @Test void rejectsEmptyOrMalformedPalettesAndInvalidNames(){
        var columns=columns();columns.forEach(c->Collections.fill(c,""));assertThrows(IllegalArgumentException.class,()->new RoadStyle("test","Test",0,columns));
        assertThrows(IllegalArgumentException.class,()->RoadStyle.uniform("test","Bad\nName","Stone"));assertThrows(IllegalArgumentException.class,()->RoadStyle.uniform("test","Test","../../secret"));
    }
    @Test void styleAndSnapshotsAreIndependentOfEditorMutations(){var columns=columns();var style=new RoadStyle("test","Test",1,columns);columns.get(4).set(0,"Changed");assertEquals("Block_4",style.columns().get(4).getFirst());assertThrows(UnsupportedOperationException.class,()->style.columns().get(4).set(0,"Changed"));}
}
