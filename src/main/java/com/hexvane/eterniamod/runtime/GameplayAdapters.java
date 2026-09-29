package com.hexvane.eterniamod.runtime;

import com.google.gson.*;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.activities.ActivityBootstrap;
import com.hexvane.eterniamod.commerce.TebexFulfillment;
import com.hexvane.eterniamod.domain.SeasonService;
import com.sun.net.httpserver.HttpHandler;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Configured native event and commerce entry points; secrets never enter content files. */
public final class GameplayAdapters implements AutoCloseable {
    private final ActivityBootstrap activities;
    private final TebexFulfillment tebex;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "eternia-commerce-reconcile"); thread.setDaemon(true); return thread;
    });

    public GameplayAdapters(EterniaModPlugin plugin, Map<String,String> environment) {
        Path data = plugin.getDataDirectory();
        var activity = read(data.resolve("activity-xp.json"), "{\"worlds\":[],\"kills\":{},\"mining\":{},\"harvesting\":{}}");
        var settings = new Gson().fromJson(activity, ActivityBootstrap.Settings.class);
        var coins = parseCoinRewards(read(data.resolve("activity-coins.json"), "{\"KILL\":{},\"MINE\":{},\"HARVEST\":{}}"));
        plugin.getServices().activitySources().configureCoinRewards(coins);
        settings = new ActivityBootstrap.Settings(settings.worlds(),
            withCoinTargets(settings.kills(), coins.getOrDefault(SeasonService.ActivityKind.KILL,Map.of())),
            withCoinTargets(settings.mining(), coins.getOrDefault(SeasonService.ActivityKind.MINE,Map.of())),
            withCoinTargets(settings.harvesting(), coins.getOrDefault(SeasonService.ActivityKind.HARVEST,Map.of())));
        for (String world : settings.worlds()) {
            if (!plugin.getInfrastructure().world(world).map(w -> w.role().equals("adventure")).orElse(false))
                throw new IllegalArgumentException("Activity XP world must have the adventure role: " + world);
        }
        var mappings = new TreeMap<String,Integer>();
        read(data.resolve("tebex-packages.json"), "{}").entrySet().forEach(e -> mappings.put(e.getKey(), e.getValue().getAsBigDecimal().intValueExact()));
        String secret = environment.getOrDefault("ETERNIA_TEBEX_WEBHOOK_SECRET", "");
        if (!mappings.isEmpty() && secret.isBlank()) throw new IllegalArgumentException("Mapped Tebex packages require ETERNIA_TEBEX_WEBHOOK_SECRET");
        if (!secret.isBlank() && plugin.getRuntimeConfig().bridgeToken().isBlank()) throw new IllegalArgumentException("Tebex requires the authenticated private game bridge");
        tebex = secret.isBlank() ? null : new TebexFulfillment(plugin.getServices(), mappings, secret, worker);
        activities = new ActivityBootstrap(plugin, plugin.getServices(), settings);
        worker.scheduleWithFixedDelay(() -> {
            try { plugin.getServices().trades().expire(); }
            catch (RuntimeException failure) { plugin.getLogger().atWarning().log("Trade expiry remains pending: %s", failure.getClass().getSimpleName()); }
        }, 10, 10, TimeUnit.SECONDS);
        if (tebex != null) {
            tebex.registerConsole(plugin);
            worker.scheduleWithFixedDelay(() -> {
                try { tebex.reconcile(); }
                catch (RuntimeException failure) { plugin.getLogger().atWarning().log("Tebex reconciliation remains pending: %s", failure.getClass().getSimpleName()); }
            }, 5, 5, TimeUnit.SECONDS);
        }
    }
    public HttpHandler webhookHandler() { return tebex == null ? null : tebex.webhookHandler(); }
    static Map<SeasonService.ActivityKind,Map<String,Long>> parseCoinRewards(JsonObject object) {
        var result=new EnumMap<SeasonService.ActivityKind,Map<String,Long>>(SeasonService.ActivityKind.class);
        for(var kind:object.entrySet()) {
            if(!Set.of("KILL","MINE","HARVEST").contains(kind.getKey())||!kind.getValue().isJsonObject())throw new IllegalArgumentException("activity-coins.json accepts only KILL, MINE and HARVEST target maps");
            var targets=new TreeMap<String,Long>();
            for(var entry:kind.getValue().getAsJsonObject().entrySet()) {
                if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Activity coin rewards must be JSON integers");
                long amount=entry.getValue().getAsBigDecimal().longValueExact();
                if(entry.getKey().isBlank()||entry.getKey().length()>200||amount<0||amount>100000)throw new IllegalArgumentException("Invalid activity coin target or amount");
                targets.put(entry.getKey(),amount);
            }
            if(targets.size()>10000)throw new IllegalArgumentException("Too many activity coin targets");
            result.put(SeasonService.ActivityKind.valueOf(kind.getKey()),Map.copyOf(targets));
        }
        return Map.copyOf(result);
    }
    /** Coin-only targets still use the same success-qualified native event path, with zero base XP. */
    static Map<String,Long> withCoinTargets(Map<String,Long> xp,Map<String,Long> coins) {
        var targets=new TreeMap<>(xp);coins.keySet().forEach(target->targets.putIfAbsent(target,0L));return Map.copyOf(targets);
    }
    private static JsonObject read(Path path, String initial) {
        try {
            if (!Files.exists(path)) { Files.createDirectories(path.getParent()); Files.writeString(path, initial + System.lineSeparator(), StandardOpenOption.CREATE_NEW); }
            if (Files.isSymbolicLink(path) || Files.size(path) > 1024 * 1024) throw new IOException("Configuration exceeds limit or is a link: " + path.getFileName());
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public void close() {
        activities.close(); worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
    }
}
