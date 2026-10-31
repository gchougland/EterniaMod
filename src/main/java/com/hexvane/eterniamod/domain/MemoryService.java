package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Memories each player has recorded for themself. Nothing here is shared between players. */
public final class MemoryService extends DomainSupport {
    public record Entry(String id,String title,String location,long capturedAt) {
        public Entry {text(id,"memory id",200);title=title==null?"":title;location=location==null?"":location;}
    }
    private static final String NAMESPACE="player_memory";
    private static final String TITLE="title:",LOCATION="location:",AT="at:";
    MemoryService(TransactionalStore store,Clock clock,Supplier<UUID> ids){super(store,clock,ids);}

    public Map<String,Entry> recorded(UUID player) {
        Objects.requireNonNull(player);
        return store.transaction(tx->tx.find(NAMESPACE,player.toString()).map(MemoryService::entries).orElseGet(Map::of));
    }
    /** Adds what the player does not have yet and returns all of their memories. The first recording of a memory is kept. */
    public Map<String,Entry> record(UUID player,Collection<Entry> memories) {
        Objects.requireNonNull(player);Objects.requireNonNull(memories);
        return store.transaction(tx->{
            var existing=tx.find(NAMESPACE,player.toString());
            var fields=new TreeMap<>(existing.map(TransactionalStore.Row::fields).orElseGet(Map::of));boolean changed=false;
            for(Entry memory:memories) {
                if(fields.containsKey(AT+memory.id))continue;
                fields.put(TITLE+memory.id,memory.title);fields.put(LOCATION+memory.id,memory.location);fields.put(AT+memory.id,Long.toString(memory.capturedAt));changed=true;
            }
            if(!changed)return existing.map(MemoryService::entries).orElseGet(Map::of);
            return entries(tx.save(NAMESPACE,player.toString(),existing.map(TransactionalStore.Row::revision).orElse(0L),fields));
        });
    }
    private static Map<String,Entry> entries(TransactionalStore.Row row) {
        var entries=new HashMap<String,Entry>();
        for(var field:row.fields().entrySet()) {
            if(!field.getKey().startsWith(AT))continue;
            String id=field.getKey().substring(AT.length());
            entries.put(id,new Entry(id,row.value(TITLE+id),row.value(LOCATION+id),Long.parseLong(field.getValue())));
        }
        return Map.copyOf(entries);
    }

    /** Level for a number of recorded memories. Level 1 is the start and index 0 of the thresholds is level 2. */
    public static int level(int recordedCount,int[] amountPerLevel) {
        if(amountPerLevel==null)return 1;
        for(int i=amountPerLevel.length-1;i>=0;i--)if(recordedCount>=amountPerLevel[i])return i+2;
        return 1;
    }
    public static boolean canCraft(int requiredLevel,int playerLevel){return requiredLevel<=1||playerLevel>=requiredLevel;}
    public static boolean collectable(String id,Collection<String> recorded,Collection<String> carried){return !recorded.contains(id)&&!carried.contains(id);}
}
