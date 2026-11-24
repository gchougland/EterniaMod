package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.*;
import java.util.concurrent.*;

/** Every five minutes, records how many players are online for the analytics reports. */
public final class TelemetrySampler implements AutoCloseable {
    public static final int PERIOD_SECONDS = 300;
    private final EterniaModPlugin plugin;
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Eternia-telemetry"); t.setDaemon(true); return t; });
    private volatile ScheduledFuture<?> task;

    public TelemetrySampler(EterniaModPlugin plugin) { this.plugin = plugin; }

    public void start() {
        task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> work.execute(this::sample), 60, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    private void sample() {
        try {
            var universe = Universe.get(); var services = plugin.getServices();
            if (universe == null || services == null) return;
            var online = new ArrayList<UUID>();
            for (PlayerRef p : List.copyOf(universe.getPlayers())) online.add(p.getUuid());
            services.telemetry().sample(online);
        } catch (RuntimeException failure) {
            plugin.getLogger().atWarning().withCause(failure).log("Could not record the online player count");
        }
    }

    @Override public void close() {
        var running = task; if (running != null) running.cancel(false);
        work.shutdown();
    }
}
