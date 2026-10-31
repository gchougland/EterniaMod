package com.hexvane.eterniamod.memories;

import com.hypixel.hytale.builtin.adventure.memories.MemoriesGameplayConfig;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemory;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Vanilla's bench checks read one shared list, so it is filled with stand-in entries that clear every level.
 * Eternia then applies each player's own level. The stand-ins never match a real memory, so they block nothing.
 * Real entries found in the list cannot be traced to a player, so they are saved to a file and removed.
 */
final class SharedMemoryUnlock {
    static final String STAND_IN_PREFIX="eternia_level_unlock_";
    private SharedMemoryUnlock() {}

    static int highestThreshold(Collection<int[]> thresholds) {
        int highest=0;
        for(int[] amounts:thresholds)if(amounts!=null)for(int amount:amounts)highest=Math.max(highest,amount);
        return highest;
    }
    static boolean settled(Collection<String> sharedIds,int needed) {
        long standIns=sharedIds.stream().filter(id->id.startsWith(STAND_IN_PREFIX)).count();
        return standIns==sharedIds.size()&&standIns>=needed;
    }
    static void ensure(Path dataDirectory,HytaleLogger logger) {
        var plugin=MemoriesPlugin.get();
        var thresholds=GameplayConfig.getAssetMap().getAssetMap().values().stream().map(MemoriesGameplayConfig::get).filter(java.util.Objects::nonNull).map(MemoriesGameplayConfig::getMemoriesAmountPerLevel).toList();
        int needed=highestThreshold(thresholds);
        var shared=plugin.getRecordedMemories();
        var ids=shared.stream().map(Memory::getId).toList();
        if(settled(ids,needed))return;
        var real=ids.stream().filter(id->!id.startsWith(STAND_IN_PREFIX)).toList();
        if(!real.isEmpty())backUp(dataDirectory.resolve("shared-memories-before-personal.txt"),real,logger);
        plugin.clearRecordedMemories();
        var standIns=new PlayerMemories();standIns.setMemoriesCapacity(needed);
        for(int i=0;i<needed;i++)standIns.recordMemory(new NPCMemory(STAND_IN_PREFIX+i,"server.memories.general.title"));
        plugin.recordPlayerMemories(standIns);
    }
    private static void backUp(Path file,List<String> ids,HytaleLogger logger) {
        try{if(!Files.exists(file))Files.write(file,ids);}
        catch(IOException e){logger.atWarning().withCause(e).log("Could not save the old shared memories list");}
        logger.atInfo().log("Removed %d shared memories; each player now collects their own",ids.size());
    }
}
