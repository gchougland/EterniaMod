package com.hexvane.eterniamod.pathtool;

import com.google.gson.Gson;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Pending records are the write-ahead authority for both terrain and infrastructure registration. */
final class SplineRoadStore {
    enum State{PENDING,ACTIVE,REMOVED}
    record Version(List<SplineGeometry.Node> nodes,int width,String style,String block,List<SplineGeometry.Cell> cells,Map<String,PlotRect> areas,SnapshotFiles.Saved before,SnapshotFiles.Saved after,List<SplineGeometry.Cell> connections,int clearance,List<SplineGeometry.Cell> above){
        Version(List<SplineGeometry.Node> nodes,int width,String style,String block,List<SplineGeometry.Cell> cells,Map<String,PlotRect> areas,SnapshotFiles.Saved before,SnapshotFiles.Saved after){this(nodes,width,style,block,cells,areas,before,after,List.of(),0,List.of());}
        Version(List<SplineGeometry.Node> nodes,int width,String style,String block,List<SplineGeometry.Cell> cells,Map<String,PlotRect> areas,SnapshotFiles.Saved before,SnapshotFiles.Saved after,List<SplineGeometry.Cell> connections){this(nodes,width,style,block,cells,areas,before,after,connections,0,List.of());}
        Version(List<SplineGeometry.Node> nodes,int width,String style,String block,List<SplineGeometry.Cell> cells,Map<String,PlotRect> areas,SnapshotFiles.Saved before,SnapshotFiles.Saved after,List<SplineGeometry.Cell> connections,int clearance){this(nodes,width,style,block,cells,areas,before,after,connections,clearance,List.of());}
        List<SplineGeometry.Cell> snapshotCells(){var mask=new LinkedHashSet<>(SparseRoadSnapshots.withClearance(cells,clearance));mask.addAll(above);return List.copyOf(mask);}
        Version{above=above==null?List.of():List.copyOf(above);if(clearance!=0&&clearance!=2)throw new IllegalArgumentException("Unknown road clearance");nodes=List.copyOf(nodes);cells=List.copyOf(cells);areas=Map.copyOf(areas);connections=connections==null?List.of():List.copyOf(connections);if(nodes.size()<2||nodes.size()>32||width<1||width>9||cells.isEmpty()||cells.size()+connections.size()>4096||style==null||!style.matches("[a-z0-9_-]{1,80}")||block==null||!block.matches("[A-Za-z0-9_-]{1,160}"))throw new IllegalArgumentException("Invalid road version");Objects.requireNonNull(before);Objects.requireNonNull(after);var all=new ArrayList<>(cells);all.addAll(connections);if(all.stream().map(SplineGeometry.Cell::column).distinct().count()!=all.size())throw new IllegalArgumentException("Duplicate road column");for(var cell:all)if(cell.y()<0||cell.y()>316)throw new IllegalArgumentException("Road ground height is invalid");var floors=new HashMap<SplineGeometry.Column,Integer>();cells.forEach(c->floors.put(c.column(),c.y()));if(above.size()>RoadClearance.MAX_BLOCKS||above.stream().distinct().count()!=above.size())throw new IllegalArgumentException("Invalid sparse road clearance");for(var c:above){var floor=floors.get(c.column());if(floor==null||c.y()<=floor+clearance||c.y()>=320)throw new IllegalArgumentException("Clearance must remain above owned road cells");}for(var connection:connections)if(!SplineGeometry.endpointConnection(connection.column(),nodes,width))throw new IllegalArgumentException("Only road endpoints can join an existing road");}
    }
    record Change(SnapshotFiles.Saved before,SnapshotFiles.Saved after,List<SplineGeometry.Cell> cells,Version next){Change{Objects.requireNonNull(before);Objects.requireNonNull(after);cells=List.copyOf(cells);if(cells.isEmpty()||cells.size()>2*(4096*3+RoadClearance.MAX_BLOCKS)||cells.stream().distinct().count()!=cells.size())throw new IllegalArgumentException("Invalid road change mask");}}
    record Road(UUID id,UUID actor,String world,long revision,State state,Version current,Change change){
        Road{Objects.requireNonNull(id);Objects.requireNonNull(actor);Objects.requireNonNull(state);if(world==null||world.isBlank()||world.length()>128||revision<1||state==State.PENDING&&change==null||state==State.ACTIVE&&current==null||state==State.REMOVED&&current!=null||state!=State.PENDING&&change!=null)throw new IllegalArgumentException("Invalid authored road record");if(state==State.PENDING){var union=new HashSet<SplineGeometry.Cell>();if(current!=null)union.addAll(current.snapshotCells());if(change.next()!=null)union.addAll(change.next().snapshotCells());if(!union.equals(new HashSet<>(change.cells())))throw new IllegalArgumentException("Pending road mask must contain exactly the old and new footprints");}}
        List<SplineGeometry.Cell> protectedCells(){return state==State.PENDING?change.cells():state==State.ACTIVE?current.cells():List.of();}
    }
    record Data(int version,List<Road> roads){}
    private static final Gson JSON=new Gson();private final SnapshotFiles files;private final Path pointer;private volatile Map<UUID,Road> roads=Map.of();private byte[] pointerBytes;
    SplineRoadStore(Path directory)throws IOException{
        files=new SnapshotFiles(directory);pointer=files.resolve("current.pointer");Files.createDirectories(directory);
        if(Files.exists(pointer)){if(Files.size(pointer)>4096)throw new IOException("Road journal pointer is oversized");pointerBytes=Files.readAllBytes(pointer);var saved=JSON.fromJson(new String(pointerBytes,StandardCharsets.UTF_8),SnapshotFiles.Saved.class);var data=JSON.fromJson(new String(files.read(saved),StandardCharsets.UTF_8),Data.class);if(data==null||data.version()!=1||data.roads()==null)throw new IOException("Unknown spline road journal");var loaded=new HashMap<UUID,Road>();for(var road:data.roads())if(loaded.put(road.id(),road)!=null)throw new IOException("Duplicate authored road");roads=Map.copyOf(loaded);}
        else try(var entries=Files.list(directory)){if(entries.findAny().isPresent())throw new IOException("Spline road journal pointer is missing; preserve generations for recovery");}
    }
    List<Road> all(){return roads.values().stream().sorted(Comparator.comparing(Road::id)).toList();}
    Road get(UUID id){return roads.get(id);}
    /** Immutable generation identity allows protection indexes to update only after an authority change. */
    Map<UUID,Road> projection(){return roads;}
    synchronized void put(long expectedRevision,Road value)throws IOException{
        var previous=roads.get(value.id());if((previous==null?0:previous.revision())!=expectedRevision||value.revision()!=expectedRevision+1)throw new IOException("Road changed after preview");
        if(previous==null&&value.state()!=State.PENDING||previous!=null&&!previous.world().equals(value.world()))throw new IOException("Road identity or first transition is invalid");
        if(previous!=null){if(previous.state()==State.REMOVED||previous.state()==State.ACTIVE&&(value.state()!=State.PENDING||!Objects.equals(value.current(),previous.current())))throw new IOException("Road transition would discard its saved version");if(previous.state()==State.PENDING&&(value.state()==State.PENDING||!Objects.equals(value.current(),previous.current())&&!Objects.equals(value.current(),previous.change().next())))throw new IOException("Road acknowledgement must use a reviewed saved version");}
        if(pointerBytes==null?Files.exists(pointer):!Files.exists(pointer)||!Arrays.equals(pointerBytes,Files.readAllBytes(pointer)))throw new IOException("Road authority changed outside this server");
        var next=new TreeMap<UUID,Road>(roads);next.put(value.id(),value);var saved=files.write("roads-"+UUID.randomUUID()+".json",JSON.toJson(new Data(1,List.copyOf(next.values()))).getBytes(StandardCharsets.UTF_8));
        byte[] bytes=JSON.toJson(saved).getBytes(StandardCharsets.UTF_8);var temporary=files.resolve("head-"+UUID.randomUUID()+".pending");
        try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);}
        Files.move(temporary,pointer,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);if(!Arrays.equals(bytes,Files.readAllBytes(pointer)))throw new IOException("Road journal pointer readback failed");files.read(saved);roads=Map.copyOf(next);pointerBytes=bytes;
    }
}
