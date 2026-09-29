package com.hexvane.eterniamod.housing.relocation;
import org.bson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class PlotSnapshotTransformTest {
    @Test void personalAndGuildUpgradesCenterTheRetainedFootprint(){assertEquals(new PlotSnapshotTransform.Inset(4,4),PlotSnapshotTransform.centeredInset(24,24,32,32));assertEquals(new PlotSnapshotTransform.Inset(8,8),PlotSnapshotTransform.centeredInset(48,48,64,64));assertThrows(IllegalArgumentException.class,()->PlotSnapshotTransform.centeredInset(32,32,24,24));assertThrows(IllegalArgumentException.class,()->PlotSnapshotTransform.centeredInset(24,24,25,26));}
    @Test void blockFluidAndEntityCoordinatesMoveTogetherWithoutChangingOwnershipOrInput(){
        var source=BsonDocument.parse("{blocks:[{x:0,y:20,z:23,name:'Rock_Stone'}],fluids:[{x:0,y:20,z:23,name:'Water',level:7}],entities:[{Components:{Transform:{Position:{X:0.5,Y:21.0,Z:23.5}},EterniaPlacedInstance:{InstanceId:'unchanged'}}}]}");
        var copy=source.clone();var moved=PlotSnapshotTransform.translate(source,4,10,4,0,0,0,32,320,32);
        assertEquals(copy,source);assertEquals(4,moved.getArray("blocks").get(0).asDocument().getInt32("x").getValue());assertEquals(30,moved.getArray("fluids").get(0).asDocument().getInt32("y").getValue());
        var entity=moved.getArray("entities").get(0).asDocument().getDocument("Components");assertEquals(27.5,entity.getDocument("Transform").getDocument("Position").getDouble("Z").getValue());assertEquals("unchanged",entity.getDocument("EterniaPlacedInstance").getString("InstanceId").getValue());
        assertThrows(IllegalStateException.class,()->PlotSnapshotTransform.translate(source,4,300,4,0,0,0,32,320,32));
        assertThrows(IllegalStateException.class,()->PlotSnapshotTransform.translate(source,4,0,20,0,0,0,32,320,32));
    }
}
