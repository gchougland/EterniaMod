package com.hexvane.eterniamod.runtime;

import com.google.gson.Gson;
import com.hexvane.eterniamod.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

/** Released definitions are immutable, except a collection item's name and asset. New files add content without code changes. */
public final class GameplayContent {
    private GameplayContent(){}
    public static void load(EterniaServices services,Path data) {
        var registrations=new java.util.ArrayList<Runnable>();
        load("Seasons",SeasonService.Definition.class,data,services.seasons()::register,registrations);
        load("Collections",CollectionService.Definition.class,data,services.collection()::register,registrations);
        load("Products",CommerceService.Product.class,data,services.commerce()::register,registrations);
        load("CrownShop",PremiumService.Item.class,data,services.premium()::register,registrations);
        for(int i=0;i<registrations.size();i+=25){var chunk=registrations.subList(i,Math.min(i+25,registrations.size()));services.batch(()->{chunk.forEach(Runnable::run);return null;});}
    }
    private static <T>void load(String group,Class<T> type,Path data,Consumer<T> register,java.util.List<Runnable> registrations) {
        Gson gson=new Gson();ClassLoader loader=GameplayContent.class.getClassLoader();String root="Server/EterniaMod/"+group+"/";
        try(var index=loader.getResourceAsStream(root+"catalog.index")) {
            if(index!=null)for(String name:new String(index.readAllBytes(),StandardCharsets.UTF_8).lines().filter(l->!l.isBlank()&&!l.startsWith("#")).toList()) {
                if(!name.matches("[a-z0-9_-]+\\.json"))throw new IOException("Invalid content index path");
                try(var input=loader.getResourceAsStream(root+name)){
                    if(input==null)throw new IOException("Missing bundled definition: "+root+name);
                    T definition=gson.fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),type);
                    registrations.add(()->registerFrom("bundled "+root+name,definition,register));
                }
            }
            Path directory=data.resolve(group);Files.createDirectories(directory);
            try(var files=Files.list(directory)){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if(Files.isSymbolicLink(file)||Files.size(file)>1024*1024)throw new IOException("Unsafe or oversized content definition: "+file.getFileName());
                T definition=gson.fromJson(Files.readString(file),type);
                registrations.add(()->registerFrom(file.toAbsolutePath().toString(),definition,register));
            }}
        }catch(IOException e){throw new UncheckedIOException("Content could not be registered: "+group,e);}
    }
    private static <T>void registerFrom(String origin,T definition,Consumer<T> register) {
        try { register.accept(definition); }
        catch(DomainException e) { throw new DomainException(e.code(),"Content registration failed from "+origin+": "+e.getMessage(),e); }
    }
}
