package com.hexvane.eterniamod.runtime;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Administrator chosen join spots: where brand new players appear, and where introduced players without a plot appear. */
public final class JoinSpawns {
    public enum Kind { FIRST_JOIN, NEWCOMER }
    public record Spot(String world,double x,double y,double z,float yaw) {
        public Spot {
            if(world==null||world.isBlank()||world.length()>128)throw new IllegalArgumentException("Join spots need a world");
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||!Float.isFinite(yaw))throw new IllegalArgumentException("Join spot coordinates must be finite");
        }
    }
    private record FileData(int version,Spot firstJoin,Spot newcomer) {}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private volatile FileData data=new FileData(1,null,null);
    public JoinSpawns(Path file){this.file=Objects.requireNonNull(file);}
    public synchronized void load() throws IOException {
        if(!Files.exists(file))return;
        if(Files.isSymbolicLink(file)||Files.size(file)>65_536)throw new IOException("Unsafe join spot file");
        try{
            var parsed=JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),FileData.class);
            if(parsed==null||parsed.version()!=1)throw new IllegalArgumentException("Invalid join spot schema");
            // Records revalidate on construction so hand edits cannot smuggle non finite values in.
            data=new FileData(1,copy(parsed.firstJoin()),copy(parsed.newcomer()));
        }catch(RuntimeException failure){throw new IOException("Invalid join spots: "+failure.getMessage(),failure);}
    }
    public Optional<Spot> get(Kind kind){var current=data;return Optional.ofNullable(kind==Kind.FIRST_JOIN?current.firstJoin():current.newcomer());}
    public synchronized void set(Kind kind,Spot spot) throws IOException {
        var next=kind==Kind.FIRST_JOIN?new FileData(1,spot,data.newcomer()):new FileData(1,data.firstJoin(),spot);
        Files.createDirectories(file.getParent());
        Path pending=file.resolveSibling(file.getFileName()+".pending-"+UUID.randomUUID());
        try{
            Files.writeString(pending,JSON.toJson(next),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,StandardOpenOption.SYNC);
            Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pending);}
        data=next;
    }
    private static Spot copy(Spot spot){return spot==null?null:new Spot(spot.world(),spot.x(),spot.y(),spot.z(),spot.yaw());}
}
