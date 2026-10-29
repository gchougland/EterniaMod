package com.hexvane.eterniamod.authoring;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Admin drafts for website content entries and season passes, kept in the plugin data folder. */
public final class AuthoringDrafts {
    public static final class Content {
        public String kind="prop",id="",displayName="",description="",prefabPath="",category="Furnishings",passId="",track="FREE";
        public boolean sold;public long crowns;public int level=1;public int websiteRevision;
    }
    public static final class Reward {
        public String track="FREE",contentId="",kind="QUANTITY",name="";public int level=1,index;public long quantity=1;
    }
    public static final class Season {
        public String id="",name="",description="",startsOn="",endsOn="";
        public List<Long> thresholds=new ArrayList<>();public List<Reward> rewards=new ArrayList<>();
        public boolean live;public int websiteRevision;
    }
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final java.util.concurrent.ConcurrentHashMap<Path,AuthoringDrafts> OPEN=new java.util.concurrent.ConcurrentHashMap<>();
    private final Path content,seasons;
    /** One instance per data folder, so its locks cover every page. */
    public static AuthoringDrafts of(Path data){return OPEN.computeIfAbsent(data.toAbsolutePath().normalize(),AuthoringDrafts::new);}
    private AuthoringDrafts(Path data){content=data.resolve("Authoring").resolve("Content");seasons=data.resolve("Authoring").resolve("Seasons");}

    public synchronized Optional<Content> content(String kind,String id){return read(content.resolve(kind+"-"+id+".json"),Content.class);}
    public synchronized void save(Content draft)throws IOException{write(content.resolve(draft.kind+"-"+draft.id+".json"),draft);}
    public synchronized Optional<Season> season(String id){return read(seasons.resolve(ContentAuthoring.seasonSlug(id)+".json"),Season.class);}
    public synchronized void save(Season draft)throws IOException{write(seasons.resolve(ContentAuthoring.seasonSlug(draft.id)+".json"),draft);}
    public synchronized List<Season> seasons(){
        var result=new ArrayList<Season>();if(!Files.isDirectory(seasons))return result;
        try(var files=Files.list(seasons)){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList())read(file,Season.class).ifPresent(result::add);}
        catch(IOException ignored){/* An unreadable folder shows no drafts. */}
        return result;
    }
    private static <T>Optional<T> read(Path file,Class<T> type){
        try{if(!Files.isRegularFile(file)||Files.size(file)>1024*1024)return Optional.empty();return Optional.ofNullable(GSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),type));}
        catch(IOException|RuntimeException e){return Optional.empty();}
    }
    static void write(Path file,Object value)throws IOException{
        Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"draft-",".tmp");
        try{Files.writeString(temp,GSON.toJson(value),StandardCharsets.UTF_8);
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
