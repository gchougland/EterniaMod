package com.hexvane.eterniamod.memories;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.MemoryService;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesGameplayConfig;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemory;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * The world thread's view of each online player's recorded memories, in the set of the world group they are in now
 * (the group their inventory belongs to: Hub and housing worlds share one, each adventure world has its own). Nothing
 * counts until that set has loaded.
 */
public final class PersonalMemories {
    private final EterniaServices services;
    private final HytaleLogger logger;
    /** Keyed by player and set ({@code uuid|set}). */
    private final Map<String,Map<String,MemoryService.Entry>> loaded=new ConcurrentHashMap<>();
    private final Set<String> loading=ConcurrentHashMap.newKeySet();

    PersonalMemories(EterniaServices services,HytaleLogger logger){this.services=services;this.logger=logger;}

    /** Returns null and starts loading when the player's memories are not in memory yet. */
    @Nullable public Map<String,MemoryService.Entry> recorded(UUID player) {
        String set=set(player);String key=key(player,set);
        var memories=loaded.get(key);
        if(memories==null&&loading.add(key))CompletableFuture.runAsync(()->{
            try{loaded.put(key,services.memories().recorded(player,set));}
            catch(RuntimeException e){logger.atWarning().withCause(e).log("Memories could not load for %s",player);}
            finally{loading.remove(key);}
        });
        return memories;
    }
    private static String set(UUID player){return com.hexvane.eterniamod.inventory.WorldInventories.currentGroup(player);}
    private static String key(UUID player,String set){return player+"|"+set;}
    public int level(UUID player,GameplayConfig gameplayConfig) {
        var memories=recorded(player);var config=MemoriesGameplayConfig.get(gameplayConfig);
        return memories==null||config==null?1:MemoryService.level(memories.size(),config.getMemoriesAmountPerLevel());
    }
    /** Saves what the player carries. The carried memories are only cleared after the save succeeds. */
    public void recordCarried(UUID player,PlayerMemories carried) {
        var entries=new ArrayList<MemoryService.Entry>();
        for(Memory memory:carried.getRecordedMemories())entries.add(entry(memory));
        String set=set(player);
        loaded.put(key(player,set),services.memories().record(player,set,entries));
        carried.takeMemories(new HashSet<>());
    }
    void forget(UUID player){String prefix=player+"|";loaded.keySet().removeIf(key->key.startsWith(prefix));}

    /** Vanilla keeps the location private, so it is read and written through the memory's own saved form. */
    static MemoryService.Entry entry(Memory memory) {
        if(!(memory instanceof NPCMemory npc))return new MemoryService.Entry(memory.getId(),memory.getTitle(),"",System.currentTimeMillis());
        var saved=NPCMemory.CODEC.encode(npc,new ExtraInfo());
        String location=text(saved,"FoundLocationNameKey");
        if(location.isEmpty()&&npc.getFoundLocationZoneNameKey()!=null)location="server.map.region."+npc.getFoundLocationZoneNameKey();
        return new MemoryService.Entry(npc.getNpcRole(),npc.getTitle(),location,npc.getCapturedTimestamp());
    }
    static NPCMemory npcMemory(String id,String title,String location,long capturedAt) {
        var saved=new BsonDocument("NPCRole",new BsonString(id)).append("TranslationKey",new BsonString(title))
            .append("CapturedTimestamp",new BsonInt64(capturedAt)).append("FoundLocationNameKey",new BsonString(location));
        return NPCMemory.CODEC.decode(saved,new ExtraInfo());
    }
    private static String text(BsonDocument document,String key){var value=document.get(key);return value!=null&&value.isString()?value.asString().getValue():"";}
}
