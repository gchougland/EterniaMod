package com.hexvane.eterniamod.memories;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemory;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemoryProvider;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Makes memories personal. Bench_Memories.json and Teleporter_Place.json in the asset pack point at what is registered here. */
public final class MemoryBootstrap {
    private MemoryBootstrap() {}

    public static PersonalMemories register(EterniaModPlugin plugin) {
        var memories=new PersonalMemories(plugin.getServices(),plugin.getLogger());
        EntityStore.REGISTRY.unregisterSystem(NPCMemory.GatherMemoriesSystem.class);
        EntityStore.REGISTRY.unregisterSystem(NPCMemory.KillMemoriesSystem.class);
        var systems=plugin.getEntityStoreRegistry();
        var ranges=new NPCMemoryProvider();
        systems.registerSystem(new PersonalMemorySystems.Gather(memories,ranges.getCollectionRadius()));
        systems.registerSystem(new PersonalMemorySystems.Kill(memories,ranges.getKillRange()));
        systems.registerSystem(new PersonalMemorySystems.CraftGate(memories));
        systems.registerSystem(new PersonalMemorySystems.WindowLevels(memories));
        OpenCustomUIInteraction.registerCustomPageSupplier(plugin,PersonalMemoriesPage.class,"EterniaMemories",(OpenCustomUIInteraction.CustomPageSupplier)(ref,accessor,player,context)->{
            var block=context.getTargetBlock();return block==null?null:new PersonalMemoriesPage(player,block,memories);
        });
        OpenCustomUIInteraction.registerCustomPageSupplier(plugin,PersonalMemoriesPage.Unlocked.class,"EterniaMemoriesUnlocked",(OpenCustomUIInteraction.CustomPageSupplier)(ref,accessor,player,context)->{
            var block=context.getTargetBlock();return block==null?null:new PersonalMemoriesPage.Unlocked(player,block,memories);
        });
        plugin.getCodecRegistry(Interaction.CODEC).register("EterniaMemoriesCondition",PersonalMemoriesCondition.class,PersonalMemoriesCondition.CODEC);
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class,event->SharedMemoryUnlock.ensure(plugin.getDataDirectory(),plugin.getLogger()));
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class,event->memories.forget(event.getPlayerRef().getUuid()));
        return memories;
    }
    public static void start(EterniaModPlugin plugin){SharedMemoryUnlock.ensure(plugin.getDataDirectory(),plugin.getLogger());}
}
