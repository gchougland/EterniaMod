package com.hexvane.eterniamod.housing.relocation;

import java.util.List;
import org.bson.*;

/** Pure snapshot geometry, shared by larger-plot restoration and bounds regression tests. */
public final class PlotSnapshotTransform {
    private PlotSnapshotTransform() {}
    public record Inset(int x,int z) {}
    public static Inset centeredInset(int oldWidth,int oldDepth,int newWidth,int newDepth) {
        if(oldWidth<=0||oldDepth<=0||newWidth<oldWidth||newDepth<oldDepth)throw new IllegalArgumentException("Packed plots may retain or increase each dimension; shrinking or turning rectangles needs a separate migration");
        int x=newWidth-oldWidth,z=newDepth-oldDepth;
        if(x%2!=0||z%2!=0)throw new IllegalArgumentException("Expanded plots require a whole-block symmetric inset");
        return new Inset(x/2,z/2);
    }
    public static BsonDocument translate(BsonDocument source,int dx,int dy,int dz,int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        BsonDocument result=source.clone();
        for(String type:List.of("blocks","fluids"))for(var value:result.getArray(type,new BsonArray())){
            var cell=value.asDocument();int x=Math.addExact(cell.getInt32("x").getValue(),dx),y=Math.addExact(cell.getInt32("y").getValue(),dy),z=Math.addExact(cell.getInt32("z").getValue(),dz);
            requireInside(x,y,z,minX,minY,minZ,maxX,maxY,maxZ);
            cell.put("x",new BsonInt32(x));cell.put("y",new BsonInt32(y));cell.put("z",new BsonInt32(z));
        }
        for(var value:result.getArray("entities",new BsonArray())){
            var position=value.asDocument().getDocument("Components").getDocument("Transform").getDocument("Position");
            double x=position.getNumber("X").doubleValue()+dx,y=position.getNumber("Y").doubleValue()+dy,z=position.getNumber("Z").doubleValue()+dz;
            requireInside(x,y,z,minX,minY,minZ,maxX,maxY,maxZ);
            position.put("X",new BsonDouble(x));position.put("Y",new BsonDouble(y));position.put("Z",new BsonDouble(z));
        }
        return result;
    }
    private static void requireInside(double x,double y,double z,int minX,int minY,int minZ,int maxX,int maxY,int maxZ){if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||x<minX||x>=maxX||y<minY||y>=maxY||z<minZ||z>=maxZ)throw new IllegalStateException("Moved content would cross the destination bounds or world height limit");}
}
