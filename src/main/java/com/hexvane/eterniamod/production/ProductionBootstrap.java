package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;

/** Wires idle production into the server. One call in plugin setup, one in shutdown. */
public final class ProductionBootstrap {
    private static volatile ProductionRuntime runtime;

    private ProductionBootstrap() {}

    public static void register(EterniaModPlugin plugin) {
        plugin.getCodecRegistry(Interaction.CODEC).register("EterniaStationUse", EterniaStationUse.class, EterniaStationUse.CODEC);
        plugin.getEntityStoreRegistry().registerSystem(new StationGuard.Break());
        plugin.getEntityStoreRegistry().registerSystem(new StationGuard.Place());
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> ProductionHud.forget(event.getPlayerRef().getUuid()));
        runtime = new ProductionRuntime(plugin);
        plugin.getLogger().atInfo().log("Eternia production ready (%d professions, %d stations)", runtime.professions.all().size(), runtime.stations().size());
    }

    public static void close() {
        var current = runtime;
        runtime = null;
        if (current != null) current.close();
    }
}
