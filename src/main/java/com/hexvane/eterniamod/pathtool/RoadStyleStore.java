package com.hexvane.eterniamod.pathtool;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Separate from placed roads: editing a palette never silently repaints existing terrain. */
final class RoadStyleStore {
    private record Data(int version,List<RoadStyle> styles){}
    private static final Gson JSON=new Gson();
    private final Path file;private final List<RoadStyle> defaults;private List<RoadStyle> saved=List.of();private byte[] loaded;
    RoadStyleStore(Path file,List<RoadStyle> defaults)throws IOException{
        this.file=file;this.defaults=List.copyOf(defaults);
        if(Files.exists(file)){if(Files.size(file)>2_000_000)throw new IOException("Road styles file is too large");loaded=Files.readAllBytes(file);try{var data=JSON.fromJson(new String(loaded,StandardCharsets.UTF_8),Data.class);if(data==null||data.version()!=1||data.styles()==null||data.styles().size()>128||data.styles().stream().map(RoadStyle::id).distinct().count()!=data.styles().size())throw new IllegalArgumentException();saved=List.copyOf(data.styles());}catch(RuntimeException e){throw new IOException("Saved road styles could not be read",e);}}
    }
    synchronized List<RoadStyle> all(){var values=new LinkedHashMap<String,RoadStyle>();defaults.forEach(s->values.put(s.id(),s));saved.forEach(s->values.put(s.id(),s));return List.copyOf(values.values());}
    synchronized void save(RoadStyle value,long expected)throws IOException{
        var previous=all().stream().filter(s->s.id().equals(value.id())).findFirst().orElse(null);
        if((previous==null?0:previous.revision())!=expected||value.revision()!=expected+1)throw new IOException("Someone changed this style. Reopen it before saving");
        if(all().stream().anyMatch(s->!s.id().equals(value.id())&&s.name().equalsIgnoreCase(value.name())))throw new IOException("Another style already has that name");
        if(loaded==null?Files.exists(file):!Files.exists(file)||!Arrays.equals(loaded,Files.readAllBytes(file)))throw new IOException("Saved styles changed outside this editor. Restart before editing them");
        var next=new ArrayList<>(saved);int index=-1;for(int i=0;i<next.size();i++)if(next.get(i).id().equals(value.id()))index=i;if(index<0)next.add(value);else next.set(index,value);if(next.size()>128)throw new IOException("This server already has 128 saved styles");
        var bytes=JSON.toJson(new Data(1,next)).getBytes(StandardCharsets.UTF_8);Files.createDirectories(file.getParent());var temporary=file.resolveSibling(file.getFileName()+"."+UUID.randomUUID()+".pending");
        try{try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
        saved=List.copyOf(next);loaded=bytes;
    }
}
