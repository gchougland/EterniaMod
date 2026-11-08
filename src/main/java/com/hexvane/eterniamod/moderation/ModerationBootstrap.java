package com.hexvane.eterniamod.moderation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent;
import com.hypixel.hytale.server.core.io.ConnectionRegistry;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Wires bans into the server: the login check, removing an online player, and the expiry sweep. */
public final class ModerationBootstrap implements AutoCloseable {
    private static final long SWEEP_SECONDS = 60;
    private static volatile ModerationBootstrap active;

    private final EterniaModPlugin plugin;
    private final ModerationService service;
    private final ScheduledExecutorService worker =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("eternia-moderation").factory());
    private volatile boolean closed;

    private ModerationBootstrap(EterniaModPlugin plugin, BanLadder ladder) {
        this.plugin = plugin;
        this.service = new ModerationService(plugin.getServices().store(), Clock.systemUTC(), () -> ladder);
    }

    /** Called once from the plugin's setup. */
    public static ModerationBootstrap register(EterniaModPlugin plugin) {
        BanLadder ladder;
        try { ladder = BanLadder.load(plugin.getDataDirectory(), ModerationBootstrap.class.getClassLoader()); }
        catch (IOException | RuntimeException failure) {
            plugin.getLogger().atWarning().withCause(failure).log("The ban ladder could not be read; using 1d, 3d, 7d, 30d, perm");
            ladder = new BanLadder(java.util.List.of(BanDuration.parse("1d").orElseThrow(), BanDuration.parse("3d").orElseThrow(),
                BanDuration.parse("7d").orElseThrow(), BanDuration.parse("30d").orElseThrow(), BanDuration.PERMANENT));
        }
        var bootstrap = new ModerationBootstrap(plugin, ladder);
        AccessControlModule access = AccessControlModule.get();
        if (access != null) {
            // The engine asks every provider during login and refuses the connection when any of them gives a reason.
            access.registerAccessProvider(bootstrap::refusal);
        } else {
            plugin.getLogger().atWarning().log("Access control is unavailable; Eternia bans are checked on the connect event instead");
            plugin.getEventRegistry().register(PlayerSetupConnectEvent.class, event -> {
                Optional<Message> reason;
                try { reason = bootstrap.refusal(event.getUuid()).join(); }
                catch (RuntimeException failure) { reason = Optional.of(Message.raw(BanNotice.FALLBACK.get("notice.title"))); }
                if (reason.isPresent()) { event.setReason(reason.get()); event.setCancelled(true); }
            });
        }
        bootstrap.worker.scheduleWithFixedDelay(bootstrap::sweep, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
        active = bootstrap;
        plugin.getLogger().atInfo().log("Moderation ready: ban ladder %s", ladder.steps().stream().map(BanDuration::compact).toList());
        return bootstrap;
    }

    /** The running moderation service, or null before setup and after shutdown. */
    public static ModerationBootstrap get() { return active; }

    public ModerationService service() { return service; }

    /** Runs moderation work off the world and network threads. */
    <T> CompletableFuture<T> supply(Supplier<T> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Moderation is shutting down"));
        return CompletableFuture.supplyAsync(work, worker);
    }

    /** The login decision. A failure to read the bans is an error, which the engine turns into a refusal. */
    CompletableFuture<Optional<Message>> refusal(UUID player) {
        if (closed) return CompletableFuture.completedFuture(Optional.empty());
        return supply(() -> {
            Instant now = Instant.now();
            return BanNotice.refusal(service.active(player), now, ModerationBootstrap::lang).map(Message::raw);
        });
    }

    /** Removes a player who is online right now with the same words they would see at login. */
    void disconnect(ModerationService.Ban ban) {
        ConnectionRegistry.get().disconnect(ban.player(), Message.raw(BanNotice.text(ban, Instant.now(), ModerationBootstrap::lang)));
    }

    private void sweep() {
        try {
            int ended = service.expireDue();
            if (ended > 0) plugin.getLogger().atInfo().log("%d ban(s) ended on their own", ended);
        } catch (RuntimeException failure) {
            plugin.getLogger().atWarning().withCause(failure).log("Could not check for bans that have ended");
        }
    }

    static String lang(String key) {
        I18nModule i18n = I18nModule.get();
        return i18n == null ? null : i18n.getMessage("en-US", key);
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        if (active == this) active = null;
    }

    /** Called from the plugin's shutdown. */
    public static void shutdown() {
        var current = active;
        if (current != null) current.close();
    }
}
