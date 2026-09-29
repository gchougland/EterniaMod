package com.hexvane.eterniamod.setup.paving;

import com.google.gson.*;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Write-ahead native edit journal. Pending regions remain protected after any interrupted write. */
final class PavingRegistry {
    enum State { PENDING, COMPLETE, RESTORED }
    record Operation(UUID id,UUID actor,String world,PlotRect rect,int y,SnapshotFiles.Saved before,SnapshotFiles.Saved after,State state) {
        Operation {
            Objects.requireNonNull(id);Objects.requireNonNull(actor);Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(state);
            if(world==null||world.isBlank()||world.length()>128)throw new IllegalArgumentException("Invalid paving world");
            requireGeometry(rect,y);
        }
        Operation state(State value){return new Operation(id,actor,world,rect,y,before,after,value);}
    }
    record FileData(int version,Map<UUID,Operation> operations){}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;private Map<UUID,Operation> operations=Map.of();private byte[] last;
    PavingRegistry(Path directory)throws IOException {
        Files.createDirectories(directory);file=directory.resolve("operations.json");
        if(Files.exists(file)) {
            if(Files.isSymbolicLink(file)||Files.size(file)>16_777_216)throw new IOException("Unsafe paving journal");
            last=Files.readAllBytes(file);
            try{var data=JSON.fromJson(new String(last,StandardCharsets.UTF_8),FileData.class);if(data==null||data.version!=1||data.operations==null)throw new IllegalArgumentException("Invalid paving journal");
                data.operations.forEach((id,op)->{if(op==null||!id.equals(op.id))throw new IllegalArgumentException("Paving journal identity differs");});operations=Map.copyOf(data.operations);
            }catch(RuntimeException e){throw new IOException("Paving journal could not load",e);}
        }
    }
    synchronized List<Operation> all(){return List.copyOf(operations.values());}
    synchronized Operation get(UUID id){return operations.get(id);}
    synchronized void put(Operation op)throws IOException {
        if(last==null?Files.exists(file):!Files.exists(file)||!MessageDigest.isEqual(last,Files.readAllBytes(file)))throw new IOException("Paving journal changed outside this server");
        var previous=operations.get(op.id);if(previous!=null){
            if(!previous.state(op.state).equals(op))throw new IOException("Paving operation identity changed");
            if(previous.state==State.RESTORED&&op.state!=State.RESTORED||previous.state==State.COMPLETE&&op.state==State.RESTORED)throw new IOException("Invalid paving journal transition");
        }else if(op.state!=State.PENDING)throw new IOException("New paving must start pending before a world write");
        var next=new TreeMap<UUID,Operation>(operations);next.put(op.id,op);
        byte[] bytes=JSON.toJson(new FileData(1,next)).getBytes(StandardCharsets.UTF_8);
        Path pending=file.resolveSibling("operations.pending-"+UUID.randomUUID());
        try{try(var channel=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pending);}
        operations=Map.copyOf(next);last=bytes;
    }
    static PlotRect rectangle(int x,int z,int endX,int endZ,int y){
        long width=Math.abs((long)x-endX)+1,depth=Math.abs((long)z-endZ)+1;
        if(width>128||depth>128||width*depth>512)throw new IllegalArgumentException("Pave at most 512 ground blocks per segment, with each side at most 128 blocks");
        var result=new PlotRect(Math.min(x,endX),Math.min(z,endZ),(int)width,(int)depth);requireGeometry(result,y);return result;
    }
    static void requireGeometry(PlotRect rect,int y){if(rect==null||rect.width()>128||rect.depth()>128||rect.area()>512||y<0||y>316)throw new IllegalArgumentException("Paving requires a small level ground rectangle within supported world height");}
    static void requireBounds(PlotRect rect,int y,List<Integer> bounds){if(!bounds.equals(List.of(rect.x(),y,rect.z(),rect.endX(),y+1,rect.endZ())))throw new IllegalArgumentException("Paving snapshot bounds differ from its journal footprint");}
    static void requireClear(PlotRect candidate,Collection<PlotRect> forbidden){if(forbidden.stream().anyMatch(candidate::overlaps))throw new IllegalStateException("The road overlaps a plot, reserved claim, or protected road/portal. Choose unclaimed ground beside it.");}
}
