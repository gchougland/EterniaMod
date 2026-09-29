package com.hexvane.eterniamod.customization;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CustomizationCatalog {
    public record Palette(String id,String name,String houseId,Map<String,String> blocks) {
        public Palette {safeId(id);safeId(houseId);if(name==null||name.isBlank()||blocks==null||blocks.isEmpty()||blocks.size()>64)throw new IllegalArgumentException("Invalid palette");blocks=Map.copyOf(blocks);blocks.forEach((from,to)->{blockId(from);blockId(to);});}
    }
    public record PathStyle(String id,String name,String blockId) {public PathStyle {safeId(id);if(name==null||name.isBlank())throw new IllegalArgumentException("Missing path name");CustomizationCatalog.blockId(blockId);}}
    private final List<Palette> palettes;private final List<PathStyle> paths;
    public CustomizationCatalog(Path data){palettes=load(data,"Palettes",Palette.class);paths=load(data,"PathStyles",PathStyle.class);}
    public List<Palette> palettes(){return palettes;}public List<PathStyle> paths(){return paths;}
    private static void safeId(String id){if(id==null||!id.matches("[a-z0-9_-]{1,80}"))throw new IllegalArgumentException("Invalid customization id");}
    private static void blockId(String id){if(id==null||!id.matches("[A-Za-z0-9_*-]{1,160}"))throw new IllegalArgumentException("Invalid block id");}
    private static <T>List<T> load(Path data,String folder,Class<T> type) {
        TreeMap<String,T> records=new TreeMap<>();Gson json=new Gson();String root="Server/EterniaMod/"+folder+"/";
        try(var stream=CustomizationCatalog.class.getClassLoader().getResourceAsStream(root+"catalog.index")) {
            if(stream==null)throw new IOException("Missing customization index: "+folder);
            for(String name:new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().map(String::trim).filter(line->!line.isEmpty()&&!line.startsWith("#")).sorted().toList()) {
                if(!name.matches("[a-z0-9_-]+\\.json"))throw new IOException("Invalid customization file");
                try(var input=CustomizationCatalog.class.getClassLoader().getResourceAsStream(root+name)){if(input==null)throw new IOException("Missing "+name);T value=json.fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),type);if(records.putIfAbsent(id(value),value)!=null)throw new IOException("Duplicate customization id");}
            }
            Path directory=data.resolve(folder);Files.createDirectories(directory);Set<String> localIds=new HashSet<>();
            try(var files=Files.list(directory)){for(Path path:files.filter(file->file.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if(Files.isSymbolicLink(path)||Files.size(path)>65_536)throw new IOException("Unsafe customization file");
                T value=json.fromJson(Files.readString(path),type);if(!localIds.add(id(value)))throw new IOException("Duplicate local customization id");records.put(id(value),value);
            }}
            return List.copyOf(records.values());
        }catch(IOException|RuntimeException error){throw new IllegalStateException("Could not load "+folder+": "+error.getMessage(),error);}
    }
    private static String id(Object value){if(value instanceof Palette p)return p.id();if(value instanceof PathStyle p)return p.id();throw new IllegalArgumentException("Missing definition");}
}
