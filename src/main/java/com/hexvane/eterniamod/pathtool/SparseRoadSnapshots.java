package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.housing.relocation.*;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import org.bson.*;

/** Captures only selected ground rows: unrelated cells inside a curve's bounding box are untouched. */
final class SparseRoadSnapshots {
    private SparseRoadSnapshots(){}
    static List<SplineGeometry.Cell> withClearance(List<SplineGeometry.Cell> ground,int height){
        var cells=new ArrayList<SplineGeometry.Cell>();
        for(var cell:ground)for(int dy=0;dy<=height;dy++)cells.add(new SplineGeometry.Cell(cell.x(),cell.y()+dy,cell.z()));
        return List.copyOf(cells);
    }
    static NativeSnapshotStore.Bounds bounds(Collection<SplineGeometry.Cell> cells){return new NativeSnapshotStore.Bounds(cells.stream().mapToInt(SplineGeometry.Cell::x).min().orElseThrow(),cells.stream().mapToInt(SplineGeometry.Cell::y).min().orElseThrow(),cells.stream().mapToInt(SplineGeometry.Cell::z).min().orElseThrow(),cells.stream().mapToInt(SplineGeometry.Cell::x).max().orElseThrow()+1,cells.stream().mapToInt(SplineGeometry.Cell::y).max().orElseThrow()+1,cells.stream().mapToInt(SplineGeometry.Cell::z).max().orElseThrow()+1);}
    static BsonDocument capture(NativeSnapshotStore store,World world,List<SplineGeometry.Cell> cells){
        var bounds=bounds(cells);var sorted=cells.stream().distinct().sorted(Comparator.comparingInt(SplineGeometry.Cell::y).thenComparingInt(SplineGeometry.Cell::z).thenComparingInt(SplineGeometry.Cell::x)).toList();BsonDocument result=null;var blocks=new BsonArray();var fluids=new BsonArray();
        for(int i=0;i<sorted.size();){var start=sorted.get(i);int end=i+1;while(end<sorted.size()&&sorted.get(end).y()==start.y()&&sorted.get(end).z()==start.z()&&sorted.get(end).x()==sorted.get(end-1).x()+1)end++;
            var row=new NativeSnapshotStore.Bounds(start.x(),start.y(),start.z(),sorted.get(end-1).x()+1,start.y()+1,start.z()+1);var doc=store.capture(world,row,Set.of(),false,false);if(result==null)result=doc.clone();
            for(var value:doc.getArray("blocks")){var b=value.asDocument().clone();translate(b,row.minX()-bounds.minX(),row.minY()-bounds.minY(),row.minZ()-bounds.minZ());blocks.add(b);}
            for(var value:doc.getArray("fluids",new BsonArray())){var b=value.asDocument().clone();translate(b,row.minX()-bounds.minX(),row.minY()-bounds.minY(),row.minZ()-bounds.minZ());fluids.add(b);}i=end;
        }
        Objects.requireNonNull(result);result.put("blocks",blocks);result.put("fluids",fluids);result.remove("entities");if(blocks.size()!=sorted.size())throw new IllegalStateException("A protected or unavailable ground cell is missing from the road snapshot");return result;
    }
    static void translate(BsonDocument c,int x,int y,int z){c.put("x",new BsonInt32(c.getInt32("x").getValue()+x));c.put("y",new BsonInt32(c.getInt32("y").getValue()+y));c.put("z",new BsonInt32(c.getInt32("z").getValue()+z));}
    static String key(int x,int y,int z){return x+","+y+","+z;}
    static String key(BsonDocument cell){return key(cell.getInt32("x").getValue(),cell.getInt32("y").getValue(),cell.getInt32("z").getValue());}
    static Map<String,BsonDocument> index(BsonDocument doc){var out=new LinkedHashMap<String,BsonDocument>();for(var value:doc.getArray("blocks"))out.put(key(value.asDocument()),value.asDocument());return out;}
    static void overlay(BsonDocument target,NativeSnapshotStore.Bounds targetBounds,NativeSnapshotStore.Snapshot source){var map=index(target);for(var v:source.document().getArray("blocks")){var b=v.asDocument().clone();translate(b,source.bounds().minX()-targetBounds.minX(),source.bounds().minY()-targetBounds.minY(),source.bounds().minZ()-targetBounds.minZ());map.put(key(b),b);}target.put("blocks",new BsonArray(new ArrayList<>(map.values())));}
    static BsonDocument select(BsonDocument source,NativeSnapshotStore.Bounds sourceBounds,List<SplineGeometry.Cell> cells){var target=bounds(cells);var keys=new HashSet<String>();for(var cell:cells)keys.add(key(cell.x()-sourceBounds.minX(),cell.y()-sourceBounds.minY(),cell.z()-sourceBounds.minZ()));var result=source.clone();var blocks=new BsonArray();for(var v:source.getArray("blocks")){var b=v.asDocument().clone();if(keys.contains(key(b))){translate(b,sourceBounds.minX()-target.minX(),sourceBounds.minY()-target.minY(),sourceBounds.minZ()-target.minZ());blocks.add(b);}}result.put("blocks",blocks);result.put("fluids",new BsonArray());result.remove("entities");if(blocks.size()!=cells.size())throw new IllegalStateException("Road snapshot selection is incomplete");return result;}
    static boolean sameCell(BsonDocument a,BsonDocument b){if(a==null||b==null)return false;a=a.clone();b=b.clone();a.remove("support");b.remove("support");return a.equals(b);}
    static void requireSame(BsonDocument actual,BsonDocument before,BsonDocument alternate){Map<String,BsonDocument> map=index(actual),expected=index(before);if(!map.keySet().equals(expected.keySet())||!actual.getArray("fluids",new BsonArray()).isEmpty())throw new IllegalStateException("Road terrain or fluids changed; make a new preview or retain the recovery lock");var other=alternate==null?Map.<String,BsonDocument>of():index(alternate);for(var e:expected.entrySet())if(!sameCell(map.get(e.getKey()),e.getValue())&&!sameCell(map.get(e.getKey()),other.get(e.getKey())))throw new IllegalStateException("Unexpected road terrain edit; saved snapshots have been retained");}
}
