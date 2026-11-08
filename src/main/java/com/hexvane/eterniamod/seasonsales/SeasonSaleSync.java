package com.hexvane.eterniamod.seasonsales;

import com.google.gson.*;
import com.hexvane.eterniamod.authoring.AuthoringDrafts;
import com.hexvane.eterniamod.domain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Brings season sale windows into the game at startup.
 * Sources, later wins: {@code startsOn}/{@code endsOn} in plugin-data {@code Seasons/*.json}, then the season
 * designer's drafts in {@code Authoring/Seasons/} (what an admin last saved). Seasons named by neither keep the
 * window already stored. A bad entry is skipped with a warning; it never stops the server. */
public final class SeasonSaleSync {
    private SeasonSaleSync(){}
    public static int sync(EterniaServices services,Path data,Consumer<String> warn){
        var windows=new TreeMap<String,String[]>();
        Path folder=data.resolve("Seasons");
        if(Files.isDirectory(folder))try(var files=Files.list(folder)){
            for(Path file:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted().toList())try{
                if(Files.isSymbolicLink(file)||Files.size(file)>1024*1024)continue;
                var json=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
                if(!json.has("id")||!(json.has("startsOn")||json.has("endsOn")))continue;
                windows.put(json.get("id").getAsString(),new String[]{string(json,"startsOn"),string(json,"endsOn")});
            }catch(IOException|RuntimeException e){warn.accept("Season sale dates in "+file.getFileName()+" could not be read: "+e.getMessage());}
        }catch(IOException e){warn.accept("Season folder could not be listed: "+e.getMessage());}
        for(var draft:AuthoringDrafts.of(data).seasons())if(draft.id!=null&&!draft.id.isBlank())windows.put(draft.id,new String[]{draft.startsOn,draft.endsOn});
        int applied=0;
        for(var entry:windows.entrySet())try{services.seasons().setSaleWindow(entry.getKey(),SeasonService.SaleWindow.parse(entry.getValue()[0],entry.getValue()[1]));applied++;}
            catch(RuntimeException e){warn.accept("Season sale dates for "+entry.getKey()+" were skipped: "+e.getMessage());}
        return applied;
    }
    private static String string(JsonObject json,String name){var value=json.get(name);return value==null||value.isJsonNull()?"":value.getAsString();}
}
