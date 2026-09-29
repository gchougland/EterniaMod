package com.hexvane.eterniamod.guildroads;

import com.google.gson.Gson;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable SHA-verified generations plus an fsynced atomic head; missing/corrupt authority never resets. */
final class GuildRoadRegistry {
    enum State { ADDING, ACTIVE, REMOVING, RECOVERY, REMOVED }
    record Road(UUID id,UUID guild,UUID rootProperty,String world,PlotRect rectangle,int groundY,String blockId,SnapshotFiles.Saved before,SnapshotFiles.Saved after,UUID operation,State state){
        Road {Objects.requireNonNull(id);Objects.requireNonNull(guild);Objects.requireNonNull(rootProperty);Objects.requireNonNull(operation);Objects.requireNonNull(state);Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(rectangle);
            if(world==null||world.isBlank()||world.length()>200||blockId==null||!blockId.matches("[A-Za-z0-9_-]{1,160}")||groundY<0||groundY>316||rectangle.area()<2||rectangle.area()>128||rectangle.width()!=1&&rectangle.depth()!=1)throw new IllegalArgumentException("Invalid guild road registry entry");
            if(!before.sha256().matches("[a-f0-9]{64}")||!after.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid road snapshot hash");
        }
        Road state(State next,UUID op){return new Road(id,guild,rootProperty,world,rectangle,groundY,blockId,before,after,op,next);}
    }
    record Index(int version,List<Road> roads,Map<String,String> easements){}
    private static final Gson JSON=new Gson();
    private final SnapshotFiles files;private final Path head;
    private volatile List<Road> roads=List.of();
    private volatile Map<String,String> easements=Map.of();
    GuildRoadRegistry(Path root)throws IOException{
        files=new SnapshotFiles(root);head=files.resolve("current.pointer");Files.createDirectories(head.getParent());
        if(Files.exists(head)){
            if(Files.size(head)>4096)throw new IOException("Invalid guild road pointer");
            var saved=JSON.fromJson(Files.readString(head),SnapshotFiles.Saved.class);
            var index=JSON.fromJson(new String(files.read(saved),StandardCharsets.UTF_8),Index.class);
            if(index==null||index.version()!=1||index.roads()==null)throw new IOException("Invalid guild road registry");
            var ids=new HashSet<UUID>();for(var road:index.roads())if(!ids.add(road.id()))throw new IOException("Duplicate guild road");roads=List.copyOf(index.roads());
            if(index.easements()!=null){index.easements().forEach((p,g)->{UUID.fromString(p);UUID.fromString(g);});easements=Map.copyOf(index.easements());}
        }else try(var entries=Files.list(head.getParent())){if(entries.findAny().isPresent())throw new IOException("Guild road registry head is missing; retain files for recovery");}
    }
    List<Road> all(){return roads;}
    Optional<Road> find(UUID id){return roads.stream().filter(r->r.id().equals(id)).findFirst();}
    boolean easement(UUID property,UUID guild){return guild.toString().equals(easements.get(property.toString()));}
    synchronized void easement(UUID property,UUID guild,boolean allowed)throws IOException{var next=new TreeMap<>(easements);if(allowed)next.put(property.toString(),guild.toString());else next.remove(property.toString());persist(roads,Map.copyOf(next));}
    synchronized void put(Road road)throws IOException{
        var updated=new TreeMap<String,Road>();roads.forEach(r->updated.put(r.id().toString(),r));updated.put(road.id().toString(),road);
        persist(List.copyOf(updated.values()),easements);
    }
    private void persist(List<Road> next,Map<String,String> consent)throws IOException{
        var saved=files.write("registry-"+UUID.randomUUID()+".json",JSON.toJson(new Index(1,next,consent)).getBytes(StandardCharsets.UTF_8));
        byte[] bytes=JSON.toJson(saved).getBytes(StandardCharsets.UTF_8);Path temporary=files.resolve("head-"+UUID.randomUUID()+".pending");
        try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        Files.move(temporary,head,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!Arrays.equals(bytes,Files.readAllBytes(head)))throw new IOException("Guild road pointer readback failed");
        files.read(saved);roads=next;easements=consent;
    }
}
