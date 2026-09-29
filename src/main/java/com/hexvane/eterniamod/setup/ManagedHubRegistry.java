package com.hexvane.eterniamod.setup;

import com.google.gson.Gson;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Pure placement authority. Pending records are saved before native writes and remain recoverable after restart. */
final class ManagedHubRegistry {
    enum Kind { NPC, PORTAL }
    enum State { PLACING, ACTIVE, MOVING, REMOVING, REMOVED }
    static final Map<String,String> ROLES=Arrays.stream(HubNpcIdentity.values()).collect(java.util.stream.Collectors.toUnmodifiableMap(HubNpcIdentity::role,HubNpcIdentity::label));
    record Pose(int x,int y,int z,float yaw){
        Pose {if(y<1||y>315||!Float.isFinite(yaw)||Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000)throw new IllegalArgumentException("Unsupported service location");}
        PlotRect footprint(){return new PlotRect(x-1,z-1,3,3);}
    }
    record Entry(UUID id,Kind kind,String world,String role,Pose pose,Pose source,State state,long revision,UUID editor){
        Entry {Objects.requireNonNull(id);Objects.requireNonNull(kind);Objects.requireNonNull(pose);Objects.requireNonNull(state);Objects.requireNonNull(editor);
            if(world==null||world.isBlank()||world.length()>200||revision<1||kind==Kind.NPC&&!ROLES.containsKey(role)||kind==Kind.PORTAL&&!"Eternia_World_Portal".equals(role))throw new IllegalArgumentException("Invalid managed service metadata");
            if(state==State.MOVING&&(kind!=Kind.NPC||source==null)||state!=State.MOVING&&source!=null)throw new IllegalArgumentException("Invalid managed service operation");
        }
        String name(){return kind==Kind.PORTAL?"World portal":ROLES.get(role);}
        boolean live(){return state!=State.REMOVED;}
        List<PlotRect> footprints(){return source==null?List.of(pose.footprint()):List.of(pose.footprint(),source.footprint());}
    }
    record Document(int version,List<Entry> entries){}
    private static final Gson JSON=new Gson();
    private final SnapshotFiles snapshots;
    private final Path pointer;
    private volatile Map<UUID,Entry> entries=Map.of();
    ManagedHubRegistry(Path directory)throws IOException{
        snapshots=new SnapshotFiles(directory);pointer=snapshots.resolve("current.pointer");Files.createDirectories(pointer.getParent());
        if(Files.exists(pointer)){
            if(Files.size(pointer)>4096)throw new IOException("Managed hub pointer is invalid");
            var saved=JSON.fromJson(Files.readString(pointer),SnapshotFiles.Saved.class);var doc=JSON.fromJson(new String(snapshots.read(saved),StandardCharsets.UTF_8),Document.class);
            if(doc==null||doc.version()!=1||doc.entries()==null||doc.entries().size()>10000)throw new IOException("Managed hub registry is invalid");
            var loaded=new HashMap<UUID,Entry>();for(var entry:doc.entries())if(loaded.put(entry.id(),entry)!=null)throw new IOException("Duplicate managed service identity");entries=Map.copyOf(loaded);
        }else try(var files=Files.list(pointer.getParent())){if(files.findAny().isPresent())throw new IOException("Managed hub registry pointer is missing. Retain files for recovery.");}
    }
    List<Entry> entries(){return entries.values().stream().sorted(Comparator.comparing(Entry::world).thenComparing(Entry::name).thenComparing(Entry::id)).toList();}
    Optional<Entry> find(UUID id){return Optional.ofNullable(entries.get(id));}
    synchronized Entry create(Kind kind,String world,String role,Pose pose,UUID editor)throws IOException{
        if(entries.size()>=10000||entries.values().stream().filter(Entry::live).count()>=1024)throw new IllegalStateException("Managed service capacity reached");
        var entry=new Entry(UUID.randomUUID(),kind,world,role,pose,null,State.PLACING,1,editor);save(entry);return entry;
    }
    synchronized Entry begin(UUID id,long expected,State operation,Pose destination,UUID editor)throws IOException{
        var old=require(id,expected);if(old.state()!=State.ACTIVE)throw new IllegalStateException("Finish the pending setup operation first");
        if(operation!=State.MOVING&&operation!=State.REMOVING)throw new IllegalArgumentException("Unsupported service operation");
        var next=new Entry(id,old.kind(),old.world(),old.role(),operation==State.MOVING?Objects.requireNonNull(destination):old.pose(),operation==State.MOVING?old.pose():null,operation,Math.addExact(old.revision(),1),editor);
        save(next);return next;
    }
    synchronized Entry complete(UUID id,long expected)throws IOException{
        var old=require(id,expected);if(old.state()==State.ACTIVE||old.state()==State.REMOVED)throw new IllegalStateException("This setup operation already finished");
        var next=new Entry(id,old.kind(),old.world(),old.role(),old.pose(),null,old.state()==State.REMOVING?State.REMOVED:State.ACTIVE,Math.addExact(old.revision(),1),old.editor());save(next);return next;
    }
    Entry require(UUID id,long expected){var entry=find(id).orElseThrow(()->new IllegalStateException("Managed service no longer exists"));if(entry.revision()!=expected)throw new IllegalStateException("This service changed. Reopen its setup menu.");return entry;}
    private void save(Entry entry)throws IOException{
        var next=new TreeMap<UUID,Entry>(entries);next.put(entry.id(),entry);var saved=snapshots.write("services-"+UUID.randomUUID()+".json",JSON.toJson(new Document(1,List.copyOf(next.values()))).getBytes(StandardCharsets.UTF_8));
        byte[] bytes=JSON.toJson(saved).getBytes(StandardCharsets.UTF_8);Path pending=snapshots.resolve("head-"+UUID.randomUUID()+".pending");
        try(var channel=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        Files.move(pending,pointer,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!Arrays.equals(bytes,Files.readAllBytes(pointer)))throw new IOException("Managed hub registry readback failed");snapshots.read(saved);entries=Map.copyOf(next);
    }
}
