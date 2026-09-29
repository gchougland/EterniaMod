package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.*;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.*;

/** Sparse, full-height clearing: no plant/rubble whitelist and no two-block cutoff. */
final class RoadClearance {
    static final int MAX_BLOCKS=32768;
    record Result(List<SplineGeometry.Cell> blocks,Map<SplineGeometry.Column,String> invalid){}
    private record Section(BlockSection blocks,FluidSection fluids){}
    /** Editing a cleared road must not restore half of a previously removed multiblock. */
    static void validateSavedObjects(org.bson.BsonDocument document,com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore.Bounds bounds,List<SplineGeometry.Cell> ground){
        var floors=new HashMap<SplineGeometry.Column,Integer>();ground.forEach(c->floors.put(c.column(),c.y()));
        for(var value:document.getArray("blocks")){
            var b=value.asDocument();if(b.getInt32("filler",new org.bson.BsonInt32(0)).getValue()!=0)continue;
            var boxes=FillerBlockUtil.multiCellFootprint(BlockType.getAssetMap().getIndex(b.getString("name").getValue()),b.getInt32("rotation",new org.bson.BsonInt32(0)).getValue());if(boxes==null)continue;
            int x=b.getInt32("x").getValue()+bounds.minX(),y=b.getInt32("y").getValue()+bounds.minY(),z=b.getInt32("z").getValue()+bounds.minZ();
            var floor=floors.get(new SplineGeometry.Column(x,z));boolean cleared=floor!=null&&y>floor;
            FillerBlockUtil.forEachFillerBlock(boxes,(dx,dy,dz)->{var bottom=floors.get(new SplineGeometry.Column(x+dx,z+dz));if(cleared!=(bottom!=null&&y+dy>bottom))throw new IllegalStateException("The edited road would restore half an object; widen or move the curve to keep it together");});
        }
    }
    static void requireLoaded(World world,List<SplineGeometry.Cell> cells){
        var checked=new HashSet<String>();boolean ready=true;
        for(var c:cells){int x=c.x()>>5,y=c.y()>>5,z=c.z()>>5;if(!checked.add(x+","+y+","+z))continue;var ref=world.getChunkStore().getChunkSectionReference(x,y,z);if(ref==null||!ref.isValid()){world.getChunkStore().getChunkSectionReferenceAsync(x,y,z);ready=false;}}
        if(!ready)throw new IllegalStateException("Loading the saved road and cleared objects; retry this action in a moment");
    }
    static Result scan(World world,List<SplineGeometry.Cell> ground){
        var result=new ArrayList<SplineGeometry.Cell>();var invalid=new HashMap<SplineGeometry.Column,String>();
        var cache=new HashMap<String,Section>();var missing=new HashSet<String>();
        var floors=new HashMap<SplineGeometry.Column,Integer>();ground.forEach(c->floors.put(c.column(),c.y()));
        for(var cell:ground){
            for(int sy=cell.y()>>5;sy<ChunkUtil.HEIGHT/ChunkUtil.SIZE;sy++){
                String key=(cell.x()>>5)+","+sy+","+(cell.z()>>5);
                if(!cache.containsKey(key)&&!missing.contains(key)){
                    var ref=world.getChunkStore().getChunkSectionReference(cell.x()>>5,sy,cell.z()>>5);
                    if(ref==null||!ref.isValid()){
                        // Never block the world thread waiting for its own load task.
                        world.getChunkStore().getChunkSectionReferenceAsync(cell.x()>>5,sy,cell.z()>>5);
                        missing.add(key);
                    }else cache.put(key,new Section(ref.getStore().getComponent(ref,BlockSection.getComponentType()),ref.getStore().getComponent(ref,FluidSection.getComponentType())));
                }
                var section=cache.get(key);
                if(section==null||section.blocks()==null){invalid.put(cell.column(),"Loading the full road clearance; the preview will update shortly");continue;}
                if(section.blocks().isSolidAir()&&(section.fluids()==null||section.fluids().isEmpty()))continue;
                for(int y=Math.max(cell.y()+1,sy*32);y<(sy+1)*32;y++){
                    if(section.fluids()!=null&&section.fluids().getFluidId(cell.x(),y,cell.z())!=0)invalid.put(cell.column(),"Route the road around water or lava");
                    if(section.blocks().get(cell.x(),y,cell.z())==BlockType.EMPTY_ID)continue;
                    result.add(new SplineGeometry.Cell(cell.x(),y,cell.z()));
                    if(result.size()>MAX_BLOCKS){invalid.put(cell.column(),"This clearance is too large; build the road in shorter sections");return new Result(List.copyOf(result),Map.copyOf(invalid));}
                }
            }
        }
        // A successful road must not leave half a bush, rubble assembly or other
        // multiblock sticking into it. Include the complete object in the footprint.
        for(var cell:result){
            int filler=ChunkSectionBlockUtil.filler(world,cell.x(),cell.y(),cell.z());
            int x=cell.x()-FillerBlockUtil.unpackX(filler),y=cell.y()-FillerBlockUtil.unpackY(filler),z=cell.z()-FillerBlockUtil.unpackZ(filler);
            var base=new SplineGeometry.Column(x,z);Integer floor=floors.get(base);
            if(floor==null||y<=floor){invalid.put(cell.column(),"The road edge cuts through an object; widen or move the curve to include it completely");continue;}
            var boxes=FillerBlockUtil.multiCellFootprint(ChunkSectionBlockUtil.blockId(world,x,y,z),ChunkSectionBlockUtil.rotationIndex(world,x,y,z));
            if(boxes!=null)FillerBlockUtil.forEachFillerBlock(boxes,(dx,dy,dz)->{
                Integer bottom=floors.get(new SplineGeometry.Column(x+dx,z+dz));
                if(bottom==null||y+dy<=bottom||y+dy>=ChunkUtil.HEIGHT)invalid.put(cell.column(),"The road edge cuts through an object; widen or move the curve to include it completely");
            });
        }
        return new Result(List.copyOf(result),Map.copyOf(invalid));
    }
}
