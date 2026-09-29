package com.hexvane.eterniamod.discovery;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.DiscoveryService.Definition;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** A file-owned registry; neither client fields nor physical item metadata define rewards. */
public final class DiscoveryRegistry {
    public record FileData(int version,List<Definition> discoveries) {}
    private record Location(String world,int x,int y,int z) {}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Map<Location,Definition> definitions;
    public DiscoveryRegistry(Collection<Definition> definitions){
        if(definitions.size()>10000)throw new IllegalArgumentException("At most 10000 authored discoveries");
        var locations=new HashMap<Location,Definition>();var ids=new HashSet<String>();
        for(var definition:definitions){Objects.requireNonNull(definition);if(!ids.add(definition.id()))throw new IllegalArgumentException("Duplicate discovery id: "+definition.id());
            if(locations.putIfAbsent(new Location(definition.world(),definition.x(),definition.y(),definition.z()),definition)!=null)throw new IllegalArgumentException("Two discoveries occupy the same block");}
        this.definitions=Map.copyOf(locations);
    }
    public static DiscoveryRegistry load(Path file)throws IOException{
        if(!Files.exists(file)){Files.createDirectories(file.toAbsolutePath().getParent());Files.writeString(file,JSON.toJson(new FileData(1,List.of())),StandardOpenOption.CREATE_NEW);}
        if(Files.size(file)>1024*1024)throw new IOException("Discovery configuration is larger than 1 MiB");
        try {var data=JSON.fromJson(Files.readString(file),FileData.class);if(data==null||data.version!=1||data.discoveries==null)throw new IllegalArgumentException("Expected discovery schema version 1");return new DiscoveryRegistry(data.discoveries);}
        catch(RuntimeException failure){throw new IOException("Invalid discovery configuration",failure);}
    }
    public Optional<Definition> at(String world,int x,int y,int z){return Optional.ofNullable(definitions.get(new Location(world,x,y,z)));}
    public Collection<Definition> all(){return definitions.values();}
}
