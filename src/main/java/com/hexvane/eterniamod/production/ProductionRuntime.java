package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hexvane.eterniamod.production.ProductionMath.Haul;
import com.hexvane.eterniamod.production.ProductionMath.Mods;
import com.hexvane.eterniamod.production.ProductionMath.Tick;
import com.hexvane.eterniamod.production.ProductionStore.PlotRow;
import com.hexvane.eterniamod.production.ProductionStore.StationRow;
import com.hexvane.eterniamod.prop.PropDefinition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Production while the server runs. Every station lives in memory here; a worker lets time pass for the stations whose
 * owner is online (in any world), saves what changed, and asks each housing world to refresh what players can see.
 * A station's numbers are guarded by its own lock, so the worker and a player's click never interleave.
 */
public final class ProductionRuntime implements AutoCloseable {
    public static final String PROP_PREFIX = "station_";
    static final double STEP_SECONDS = 0.25;
    private static volatile ProductionRuntime active;

    final EterniaModPlugin plugin;
    final ProductionRules rules = ProductionRules.DEFAULT;
    final Professions professions;
    final StationVisuals visuals;
    private final ProductionStore store;
    private final Map<UUID, Station> stations = new ConcurrentHashMap<>();
    private final Map<UUID, PlotState> plots = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("eternia-production").factory());
    private long lastStep = System.nanoTime();
    private int steps;
    /** Moves the stations' moving parts many times a second, apart from the slower step that does the sums. */
    private final ScheduledExecutorService mover = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("eternia-production-motion").factory());
    private volatile Set<String> motionWorlds = Set.of();
    private final Set<String> motionQueued = ConcurrentHashMap.newKeySet();

    /** Asks each housing world with players in it to move its stations' parts, unless it has not done the last one yet. */
    private void motion() {
        try {
            Universe universe = Universe.get();
            if (universe == null) return;
            for (String name : motionWorlds) {
                if (!motionQueued.add(name)) continue;
                World world = universe.getWorld(name);
                if (world == null) { motionQueued.remove(name); continue; }
                try {
                    world.execute(() -> {
                        motionQueued.remove(name);
                        visuals.motion(world);
                    });
                } catch (RuntimeException closing) {
                    motionQueued.remove(name);
                }
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().atWarning().withCause(failure).log("Production motion failed");
        }
    }

    /** One station: its saved row plus what only matters while the server runs. Lock it to read or change progress. */
    public static final class Station {
        public final StationRow row;
        volatile boolean dirty;
        /** Where it stands, filled in by the world it is in. */
        volatile int x, y, z;
        volatile Rotation yaw = Rotation.None;
        volatile boolean located;
        /** Seconds a golden moment still has to run, 0 for none. */
        volatile double golden;
        volatile boolean toldFull;
        /** Set when a cycle finished since the visuals last looked, so the bucket tips and ore pops. */
        volatile boolean pulse, veinFlash;
        volatile boolean running;
        volatile boolean busy;

        Station(StationRow row) { this.row = row; }

        public UUID id() { return row.id; }
        public StationProgress progress() { return row.progress; }
    }

    /** A plot's production record plus its golden moment timer. Lock it to read or change. */
    public static final class PlotState {
        public final PlotRow row;
        volatile boolean dirty;
        double goldenIn;

        PlotState(PlotRow row) { this.row = row; }

        public PlotProduction plot() { return row.plot; }
    }

    ProductionRuntime(EterniaModPlugin plugin) {
        this.plugin = plugin;
        this.professions = new Professions(ProductionRuntime.class.getClassLoader());
        this.store = new ProductionStore(plugin.getServices().store());
        this.visuals = new StationVisuals(this);
        for (StationRow row : store.stations()) stations.put(row.id, new Station(row));
        for (PlotRow row : store.plots()) plots.put(row.id, new PlotState(row));
        active = this;
        worker.scheduleWithFixedDelay(this::step, 1000, (long) (STEP_SECONDS * 1000), TimeUnit.MILLISECONDS);
        mover.scheduleAtFixedRate(this::motion, 1500, StationVisuals.MOTION_MILLIS, TimeUnit.MILLISECONDS);
    }

    public static ProductionRuntime get() { return active; }

    public ProductionRules rules() { return rules; }

    // ---- lookups ----

    public static String professionOf(String propId) {
        return propId != null && propId.startsWith(PROP_PREFIX) ? propId.substring(PROP_PREFIX.length()) : null;
    }

    public Profession profession(Station s) { return professions.get(s.row.progress.profession); }

    public List<Station> stations() { return List.copyOf(stations.values()); }

    public List<Station> stationsOf(UUID plotId) {
        var out = new ArrayList<Station>();
        for (Station s : stations.values()) if (s.row.placed && plotId.equals(s.row.plot)) out.add(s);
        return out;
    }

    public PlotState plot(UUID plotId) {
        return plots.computeIfAbsent(plotId, id -> { var row = new PlotRow(); row.id = id; return new PlotState(row); });
    }

    /** The placed station whose space holds this block, or null. */
    public Station at(World world, int x, int y, int z) {
        for (Station s : stations.values()) {
            if (!s.row.placed || !s.located || !world.getName().equals(s.row.world)) continue;
            var layout = profession(s).layout();
            if (Math.abs(x - s.x) <= layout.radius() && Math.abs(z - s.z) <= layout.radius()
                && y >= s.y - layout.depth() - 2 && y <= s.y + layout.height()) return s;
        }
        return null;
    }

    public Mods mods(Station s) {
        PlotState plot = s.row.plot == null ? null : plot(s.row.plot);
        return Professions.mods(rules, profession(s), s.row.progress, plot == null ? null : plot.row.plot.estate, 1);
    }

    public static long today() { return Math.floorDiv(System.currentTimeMillis(), 86_400_000L); }

    /** May this player collect, upgrade and work the station? The plot's owner, or anyone allowed to build on it. */
    public boolean manages(World world, Station s, UUID player) {
        if (Owner.player(player).key().equals(s.row.owner)) return true;
        HubPlotRecord plot = s.row.plot == null ? null : EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).getPlot(s.row.plot);
        return plot != null && HousingAccess.can(plugin, plot, player, "housing.prop.place");
    }

    // ---- placing and packing ----

    /** World thread, from the placement code once a station stands. */
    public static void placed(EterniaModPlugin plugin, World world, HubPlotRecord plot, UUID instanceId, PropDefinition def, PlayerRef actor) {
        ProductionRuntime runtime = active;
        String profession = professionOf(def.getId());
        if (runtime == null || profession == null || runtime.professions.get(profession) == null) return;
        Station s = runtime.stations.computeIfAbsent(instanceId, id -> { var row = new StationRow(); row.id = id; row.progress.profession = profession; return new Station(row); });
        synchronized (s) {
            s.row.owner = HousingAccess.owner(plot) == null ? "" : HousingAccess.owner(plot).key();
            s.row.plot = plot.getPlotId();
            s.row.world = world.getName();
            s.row.placed = true;
            s.row.tierShown = 1;
            s.located = false;
            s.dirty = true;
        }
        runtime.visuals.locate(world, s);
        runtime.save(s);
        // A station that was packed at a higher tier and placed turned comes back as the first look: put its own back.
        runtime.visuals.showTier(world, s, actor == null ? null : actor.getUuid());
        runtime.visuals.placedEffect(world, s);
    }

    /** World thread, once a station has been packed away. Its progress is kept for when it is placed again. */
    public static void packed(World world, UUID instanceId) {
        ProductionRuntime runtime = active;
        Station s = runtime == null ? null : runtime.stations.get(instanceId);
        if (s == null) return;
        synchronized (s) { s.row.placed = false; s.located = false; s.dirty = true; }
        runtime.visuals.hide(world, s);
        runtime.save(s);
    }

    // ---- the worker ----

    private void step() {
        try {
            long now = System.nanoTime();
            double seconds = Math.min(5, (now - lastStep) / 1e9);
            lastStep = now;
            Universe universe = Universe.get();
            if (universe == null) return;
            var online = new HashSet<UUID>();
            var busyWorlds = new HashSet<String>();
            for (PlayerRef player : universe.getPlayers()) {
                if (player.getWorldUuid() == null) continue;
                online.add(player.getUuid());
                World world = universe.getWorld(player.getWorldUuid());
                if (world != null) busyWorlds.add(world.getName());
            }
            for (Station s : stations.values()) {
                if (!s.row.placed) { s.running = false; continue; }
                boolean runs = runs(s, online, busyWorlds);
                s.running = runs;
                if (!runs && s.golden <= 0) continue;
                Tick tick = null;
                synchronized (s) {
                    if (s.golden > 0) s.golden = Math.max(0, s.golden - seconds);
                    if (runs) tick = ProductionMath.advance(rules, profession(s), s.row.progress, mods(s), seconds, random);
                }
                if (tick == null) continue;
                if (tick.cycles() > 0) { s.dirty = true; s.pulse = true; }
                if (tick.veinStruck()) s.veinFlash = true;
                if (tick.becameFull()) ProductionFeedback.full(this, s);
                if (!s.row.progress.full) s.toldFull = false;
            }
            golden(seconds, online);
            var moving = new java.util.HashSet<String>();
            for (String name : busyWorlds) {
                if (!plugin.getInfrastructure().isHousing(name)) continue;
                World world = universe.getWorld(name);
                if (world != null) { moving.add(name); world.execute(() -> visuals.refresh(world, seconds)); }
            }
            motionWorlds = Set.copyOf(moving);
            if (steps % 4 == 0) ProductionJournalPage.tickOpen();
            if (++steps % 120 == 0) flush();
        } catch (RuntimeException failure) {
            plugin.getLogger().atWarning().withCause(failure).log("Production step failed");
        }
    }

    /** A player's station runs while they are online anywhere. A guild's runs while anyone is in its world. */
    private boolean runs(Station s, Set<UUID> online, Set<String> busyWorlds) {
        String owner = s.row.owner;
        if (owner.startsWith("PLAYER:")) {
            try { return online.contains(UUID.fromString(owner.substring(7))); } catch (IllegalArgumentException bad) { return false; }
        }
        return busyWorlds.contains(s.row.world);
    }

    /** Golden moments: while a plot's owner is online, now and then one of its stations sparkles for a short while. */
    private void golden(double seconds, Set<UUID> online) {
        var byPlot = new java.util.HashMap<UUID, List<Station>>();
        for (Station s : stations.values()) if (s.row.placed && s.running && s.located) byPlot.computeIfAbsent(s.row.plot, id -> new ArrayList<>()).add(s);
        for (var entry : byPlot.entrySet()) {
            PlotState plot = plot(entry.getKey());
            synchronized (plot) {
                if (plot.goldenIn <= 0) plot.goldenIn = nextGolden(plot);
                if (entry.getValue().stream().anyMatch(s -> s.golden > 0)) continue;
                plot.goldenIn -= seconds;
                if (plot.goldenIn > 0) continue;
                plot.goldenIn = nextGolden(plot);
            }
            Station lucky = entry.getValue().get(random.nextInt(entry.getValue().size()));
            if (visuals.playersNear(lucky)) lucky.golden = rules.goldenWindowSeconds();
        }
    }

    private double nextGolden(PlotState plot) {
        double quicker = 1 - 0.04 * plot.row.plot.estate[1];
        return (rules.goldenMinSeconds() + random.nextDouble() * (rules.goldenMaxSeconds() - rules.goldenMinSeconds())) * quicker;
    }

    // ---- what players do (world thread) ----

    public record Collected(Haul haul, int streak, int sealsPaid) {}

    public Collected collect(Station s) {
        PlotState plot = plot(s.row.plot);
        boolean first;
        int streak, seals;
        Haul haul;
        synchronized (plot) {
            synchronized (s) {
                if (s.row.progress.stockpile < 1 && s.row.progress.waitingFinds.isEmpty()) return null;
                first = plot.row.plot.lastCollectDay != today();
                if (first) plot.row.plot.countCollect(today());
                streak = plot.row.plot.streak;
                haul = ProductionMath.collect(rules, profession(s), s.row.progress, mods(s), streak, first);
                s.toldFull = false;
                s.dirty = true;
            }
            deal(plot, s);
            seals = plot.row.plot.progress("collect", haul.amount());
            plot.dirty = true;
        }
        save(s);
        save(plot);
        return new Collected(haul, streak, seals);
    }

    /** Hand work. Returns the goods popped, 0 when the day's energy is spent. */
    public double work(Station s) {
        PlotState plot = plot(s.row.plot);
        double goods;
        synchronized (plot) {
            if (!plot.row.plot.spendEnergy(rules, today())) return 0;
            synchronized (s) { goods = ProductionMath.handWork(rules, s.row.progress, mods(s)); s.dirty = true; }
            deal(plot, s);
            plot.row.plot.progress("hand", 1);
            plot.dirty = true;
        }
        return goods;
    }

    public int energyLeft(Station s) {
        PlotState plot = plot(s.row.plot);
        synchronized (plot) { return plot.row.plot.energyLeft(rules, today()); }
    }

    /** Buys levels. Returns {levels bought, milestones crossed}. */
    public int[] upgrade(Station s, int count) {
        int bought, crossed;
        synchronized (s) {
            int before = ProductionMath.milestonesPassed(rules, s.row.progress.level);
            int peak = ProductionMath.milestonesPassed(rules, s.row.progress.peakLevel);
            bought = ProductionMath.buyLevels(rules, s.row.progress, count);
            crossed = ProductionMath.milestonesPassed(rules, s.row.progress.level) - before;
            if (bought > 0) s.dirty = true;
            int newPeak = ProductionMath.milestonesPassed(rules, s.row.progress.peakLevel) - peak;
            if (newPeak > 0) award(s, newPeak);
        }
        if (bought > 0) {
            PlotState plot = plot(s.row.plot);
            synchronized (plot) { deal(plot, s); plot.row.plot.progress("levels", bought); plot.dirty = true; }
            save(s);
        }
        return new int[] {bought, crossed};
    }

    private void award(Station s, int seals) {
        PlotState plot = plot(s.row.plot);
        synchronized (plot) { plot.row.plot.seals += seals; plot.dirty = true; }
    }

    public boolean tool(Station s, int tool) {
        boolean ok;
        synchronized (s) { ok = ProductionMath.buyTool(rules, s.row.progress, tool); if (ok) s.dirty = true; }
        if (ok) save(s);
        return ok;
    }

    /** Keeps one of the cards dealt for the oldest milestone still owed. */
    public boolean pick(Station s, String cardId) {
        synchronized (s) {
            if (ProductionMath.talentPicksOwed(rules, s.row.progress) <= 0) return false;
            int milestone = s.row.progress.talents.size() + 1;
            var card = profession(s).card(cardId);
            if (card == null || card.milestone() != milestone) return false;
            s.row.progress.talents.add(cardId);
            s.dirty = true;
        }
        save(s);
        return true;
    }

    public double reshuffleCost(Station s) {
        synchronized (s) { return Math.ceil(ProductionMath.perCycle(rules, s.row.progress, mods(s)) * rules.reshuffleCostCycles() * (1 + s.row.progress.reshuffles)); }
    }

    /** Hands every card back for a fee, so they can be chosen again. */
    public boolean reshuffle(Station s) {
        synchronized (s) {
            double cost = reshuffleCost(s);
            if (s.row.progress.talents.isEmpty() || cost > s.row.progress.purse) return false;
            s.row.progress.purse -= cost;
            s.row.progress.talents.clear();
            s.row.progress.reshuffles++;
            s.dirty = true;
        }
        save(s);
        return true;
    }

    public boolean rebuild(Station s) {
        boolean ok;
        synchronized (s) { ok = ProductionMath.rebuild(rules, s.row.progress); if (ok) s.dirty = true; }
        if (ok) { award(s, 3); save(s); }
        return ok;
    }

    public boolean estate(UUID plotId, int branch) {
        PlotState plot = plot(plotId);
        boolean ok;
        synchronized (plot) { ok = plot.row.plot.buyEstate(branch); if (ok) plot.dirty = true; }
        if (ok) save(plot);
        return ok;
    }

    /** The surprise a sparkling station gives when touched: "windfall", "frenzy" or "find". Null when it is not sparkling. */
    public String catchGolden(Station s) {
        if (s.golden <= 0) return null;
        s.golden = 0;
        String kind;
        Profession p = profession(s);
        synchronized (s) {
            int roll = random.nextInt(3);
            if (roll == 0) {
                s.row.progress.stockpile += ProductionMath.perMinute(rules, p, s.row.progress, mods(s)) * rules.windfallMinutes();
                kind = "windfall";
            } else if (roll == 1 || p.finds().isEmpty()) {
                s.row.progress.frenzySeconds = rules.frenzySeconds();
                kind = "frenzy";
            } else {
                var missing = p.finds().stream().filter(f -> !s.row.progress.finds.contains(f.id())).toList();
                var pool = missing.isEmpty() ? p.finds() : missing;
                s.row.progress.waitingFinds.add(pool.get(random.nextInt(pool.size())).id());
                kind = "find";
            }
            s.dirty = true;
        }
        PlotState plot = plot(s.row.plot);
        synchronized (plot) { deal(plot, s); plot.row.plot.goldenCaught++; plot.row.plot.progress("golden", 1); plot.dirty = true; }
        return kind;
    }

    /** Makes sure today's orders are dealt. Call with the plot locked. */
    private void deal(PlotState plot, Station s) {
        double best = 0;
        for (Station other : stationsOf(s.row.plot)) best = Math.max(best, ProductionMath.perMinute(rules, profession(other), other.row.progress, mods(other)));
        plot.row.plot.dealOrders(today(), best);
    }

    public void dealOrders(Station s) {
        PlotState plot = plot(s.row.plot);
        synchronized (plot) { deal(plot, s); }
    }

    // ---- saving ----

    void save(Station s) {
        s.dirty = false;
        worker.execute(() -> {
            try { synchronized (s) { store.save(s.row); } }
            catch (RuntimeException failure) { s.dirty = true; plugin.getLogger().atWarning().withCause(failure).log("Station %s was not saved", s.row.id); }
        });
    }

    void save(PlotState plot) {
        plot.dirty = false;
        worker.execute(() -> {
            try { synchronized (plot) { store.save(plot.row); } }
            catch (RuntimeException failure) { plot.dirty = true; plugin.getLogger().atWarning().withCause(failure).log("Plot production %s was not saved", plot.row.id); }
        });
    }

    private void flush() {
        for (Station s : stations.values()) if (s.dirty) { s.dirty = false; try { synchronized (s) { store.save(s.row); } } catch (RuntimeException failure) { s.dirty = true; } }
        for (PlotState p : plots.values()) if (p.dirty) { p.dirty = false; try { synchronized (p) { store.save(p.row); } } catch (RuntimeException failure) { p.dirty = true; } }
    }

    /** Finds stations standing in a world's plots that this runtime does not know to be placed (a plot moved or restored). */
    void reconcile(World world) {
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        var seen = new HashSet<UUID>();
        for (HubPlotRecord plot : manager.listPlots()) {
            for (HubPlotProp prop : plot.getProps()) {
                String profession = professionOf(prop.getPropId());
                if (profession == null || professions.get(profession) == null || prop.getInstanceId() == null) continue;
                seen.add(prop.getInstanceId());
                Station s = stations.computeIfAbsent(prop.getInstanceId(), id -> { var row = new StationRow(); row.id = id; row.progress.profession = profession; return new Station(row); });
                if (!s.row.placed || !world.getName().equals(s.row.world) || !plot.getPlotId().equals(s.row.plot)) {
                    synchronized (s) {
                        s.row.placed = true; s.row.world = world.getName(); s.row.plot = plot.getPlotId();
                        s.row.owner = HousingAccess.owner(plot) == null ? "" : HousingAccess.owner(plot).key();
                        s.located = false; s.dirty = true;
                    }
                }
                visuals.place(s, prop);
            }
        }
        for (Station s : stations.values()) {
            if (s.row.placed && world.getName().equals(s.row.world) && !seen.contains(s.row.id)) {
                synchronized (s) { s.row.placed = false; s.located = false; s.dirty = true; }
                visuals.hide(world, s);
            }
        }
    }

    @Override public void close() {
        if (active == this) active = null;
        mover.shutdown();
        worker.shutdown();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        flush();
        visuals.close();
    }
}
