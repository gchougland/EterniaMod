package com.hexvane.eterniamod.discovery;

import com.google.gson.Gson;
import com.hexvane.eterniamod.domain.DiscoveryService.Definition;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable local examples, additionally bound to their native world UUID. */
final class LocalDiscoveryRegistry {
    record Entry(UUID worldId,Definition definition){Entry{Objects.requireNonNull(worldId);Objects.requireNonNull(definition);if(!definition.id().startsWith("local-playground-"))throw new IllegalArgumentException("Not a local discovery id");}}
    private record Data(int version,List<Entry> entries){}
    private record Envelope(String payload,String sha256){}
    private static final Gson JSON=new Gson();
    private final Path file;
    private volatile Map<String,Entry> entries=Map.of();
    LocalDiscoveryRegistry(Path file)throws IOException{
        this.file=file.toAbsolutePath().normalize();checkPath();
        if(Files.exists(file))try{
            if(Files.size(file)>1024*1024)throw new IOException("Local discovery registry exceeds its limit");
            var envelope=JSON.fromJson(Files.readString(file),Envelope.class);
            if(envelope==null||envelope.payload()==null||!SnapshotFiles.hash(envelope.payload().getBytes(StandardCharsets.UTF_8)).equals(envelope.sha256()))throw new IOException("Local discovery checksum mismatch");
            var data=JSON.fromJson(envelope.payload(),Data.class);if(data==null||data.version()!=1||data.entries()==null||data.entries().size()>100)throw new IOException("Invalid local discovery schema");
            new DiscoveryRegistry(data.entries().stream().map(Entry::definition).toList());var next=new TreeMap<String,Entry>();for(var e:data.entries())next.put(e.definition().id(),e);entries=Map.copyOf(next);
        }catch(RuntimeException error){throw new IOException("Invalid local discovery registry",error);}
    }
    Collection<Entry> all(){return entries.values();}
    Optional<Definition> at(UUID worldId,String world,int x,int y,int z){return entries.values().stream().filter(e->e.worldId().equals(worldId)).map(Entry::definition).filter(d->d.world().equals(world)&&d.x()==x&&d.y()==y&&d.z()==z).findFirst();}
    synchronized void ensure(Entry entry)throws IOException{
        var old=entries.get(entry.definition().id());if(old!=null){if(!old.equals(entry))throw new IllegalArgumentException("This local discovery already has a different saved identity or reward");return;}
        if(entries.size()>=100)throw new IllegalStateException("Too many local discoveries");var next=new TreeMap<>(entries);next.put(entry.definition().id(),entry);
        new DiscoveryRegistry(next.values().stream().map(Entry::definition).toList());
        String payload=JSON.toJson(new Data(1,List.copyOf(next.values())));byte[] bytes=JSON.toJson(new Envelope(payload,SnapshotFiles.hash(payload.getBytes(StandardCharsets.UTF_8)))).getBytes(StandardCharsets.UTF_8);
        checkPath();Files.createDirectories(file.getParent());Path pending=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".pending");
        try(var channel=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
        Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!Arrays.equals(bytes,Files.readAllBytes(file)))throw new IOException("Local discovery save did not verify");entries=Map.copyOf(next);
    }
    private void checkPath()throws IOException{if(Files.isSymbolicLink(file)||Files.isSymbolicLink(file.getParent()))throw new IOException("Local discovery storage cannot be a symbolic link");}
}
