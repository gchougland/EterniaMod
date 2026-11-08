package com.hexvane.eterniamod.stats;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.loot.LootChestBlock;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenContainerInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3i;

/**
 * Listens to the game for stats. Every listener only adds to the in-memory {@link StatsTally}; one worker thread
 * saves it every minute, when a player leaves, right after a boss falls, and at shutdown. Nothing here writes to the
 * entity store.
 */
public final class StatsRuntime implements AutoCloseable {
    static final long FLUSH_SECONDS = 60;
    /** Opening the same chest again this soon is the same visit. */
    static final long CHEST_REVISIT_NANOS = 30_000_000_000L;
    private static volatile StatsRuntime active;

    private final EterniaModPlugin plugin;
    private final EterniaServices services;
    private final StatsTally tally = new StatsTally();
    private final TravelTracker travel = new TravelTracker();
    private final BossCatalog bosses;
    private final Map<UUID, ChestVisit> lastChest = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("eternia-stats").factory());
    private volatile boolean closed;
    private final List<KillListener> killListeners = new CopyOnWriteArrayList<>();

    /** Told about every creature a player defeats, on the world thread, right after it is counted. Keep it cheap and never write to the entity store. */
    @FunctionalInterface public interface KillListener { void killed(UUID player, World world, String role); }
    public void onKill(KillListener listener) { killListeners.add(Objects.requireNonNull(listener)); }

    private record ChestVisit(String world, int x, int y, int z, long nanos) {}

    private StatsRuntime(EterniaModPlugin plugin, BossCatalog bosses) {
        this.plugin = plugin; this.services = plugin.getServices(); this.bosses = bosses;
    }

    public static StatsRuntime register(EterniaModPlugin plugin) {
        var runtime = new StatsRuntime(plugin, BossCatalog.load(StatsRuntime.class.getClassLoader()));
        runtime.services.stats().onlineCheck(id -> { var universe = Universe.get(); return universe != null && universe.getPlayer(id) != null; });
        var registry = plugin.getEntityStoreRegistry();
        registry.registerSystem(runtime.new CreatureDeaths());
        registry.registerSystem(runtime.new PlayerDeaths());
        registry.registerSystem(runtime.new LootChestOpen());
        registry.registerSystem(runtime.new ContainerOpen());
        registry.registerSystem(runtime.new Travel());
        registry.registerSystem(runtime.new TeleportReset());
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> runtime.left(event.getPlayerRef().getUuid()));
        runtime.worker.scheduleWithFixedDelay(runtime::flushQuietly, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
        active = runtime;
        plugin.getLogger().atInfo().log("Player stats tracking %d bosses", runtime.bosses.bosses().size());
        return runtime;
    }

    /** The running tracker, or null before setup and after shutdown. */
    public static StatsRuntime active() { return active; }

    public BossCatalog bosses() { return bosses; }
    StatsService servicesStats() { return services.stats(); }

    /** Saves what is pending, then reads a player's stats, off the world thread. */
    public CompletableFuture<Optional<StatsService.PlayerStats>> load(UUID player) {
        return CompletableFuture.supplyAsync(() -> { flushQuietly(); return services.stats().player(player); }, worker);
    }

    /** Saves what is pending, then reads anything else from the stats service, off the world thread. */
    public <T> CompletableFuture<T> read(java.util.function.Supplier<T> work) {
        return CompletableFuture.supplyAsync(() -> { flushQuietly(); return work.get(); }, worker);
    }

    private void left(UUID player) {
        travel.forget(player); lastChest.remove(player);
        if (!closed) try { worker.execute(this::flushQuietly); } catch (RejectedExecutionException ignored) { /* Shutting down: close() saves. */ }
    }

    private void flushQuietly() {
        try { flush(); } catch (RuntimeException failure) { plugin.getLogger().atWarning().withCause(failure).log("Player stats could not be saved yet; they stay pending"); }
    }

    /** Saves every pending change. A failed save puts the changes back for the next attempt. */
    synchronized void flush() {
        var changes = tally.drain();
        if (changes.isEmpty()) return;
        List<StatsService.First> claimed;
        try { claimed = services.stats().apply(changes); }
        catch (StatsService.FlushFailure failure) { tally.restore(failure.unsaved()); failure.claimed().forEach(this::announce); throw failure; }
        catch (RuntimeException failure) { tally.restore(changes); throw failure; }
        for (var first : claimed) announce(first);
    }

    private void announce(StatsService.First first) {
        var universe = Universe.get(); if (universe == null) return;
        var boss = bosses.boss(first.boss()).map(BossCatalog.Boss::name).orElse(StatsText.friendly(first.boss()));
        String name = first.name().isEmpty() ? StatsText.unknownPlayer() : first.name();
        var message = Message.translation(BossCatalog.NORMAL.equals(first.difficulty()) ? "eternia_stats.first.announce" : "eternia_stats.first.announceDifficulty")
            .param("player", name).param("boss", boss).param("difficulty", StatsText.difficulty(first.difficulty())).color("#d8b66b");
        for (var player : universe.getPlayers()) player.sendMessage(message);
    }

    private void bossFell() {
        if (!closed) try { worker.execute(this::flushQuietly); } catch (RejectedExecutionException ignored) {}
    }

    /** Creative play (building, testing) never counts toward stats, contests or firsts. */
    private static boolean counts(Store<EntityStore> store, Ref<EntityStore> ref) {
        var player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getGameMode() != GameMode.Creative;
    }

    private static DamageCause cause(Damage damage) {
        return damage == null ? null : DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
    }

    private static Ref<EntityStore> attacker(Damage damage) {
        return damage != null && damage.getSource() instanceof Damage.EntitySource source && source.getRef() != null && source.getRef().isValid() ? source.getRef() : null;
    }

    /** A creature fell: the player who landed the blow gets the kill; everyone close by shares a boss victory. */
    private final class CreatureDeaths extends DeathSystems.OnDeathSystem {
        @Override public Query<EntityStore> getQuery() { return NPCEntity.getComponentType(); }
        @Override public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
            if (closed) return;
            var npc = store.getComponent(ref, NPCEntity.getComponentType()); if (npc == null || npc.getRoleName() == null) return;
            World world = store.getExternalData().getWorld(); String role = npc.getRoleName();
            var info = death.getDeathInfo(); var attacker = attacker(info);
            UUID killer = null;
            if (attacker != null) {
                var player = store.getComponent(attacker, PlayerRef.getComponentType());
                if (player != null && counts(store, attacker)) killer = player.getUuid();
            }
            if (killer != null) {
                tally.kill(killer, world.getName(), role);
                for (var listener : killListeners) {
                    try { listener.killed(killer, world, role); }
                    catch (RuntimeException failure) { plugin.getLogger().atWarning().withCause(failure).log("A kill listener failed"); }
                }
            }
            var match = bosses.match(role); if (match.isEmpty()) return;
            var cause = cause(info); if (cause != null && "Command".equalsIgnoreCase(cause.getId())) return;
            var credited = new LinkedHashSet<UUID>(); if (killer != null) credited.add(killer);
            var at = store.getComponent(ref, TransformComponent.getComponentType());
            double range = bosses.creditRange();
            if (at != null && range > 0) for (var nearby : world.getPlayerRefs()) {
                var nearbyRef = nearby.getReference(); if (nearbyRef == null || !nearbyRef.isValid() || nearbyRef.getStore() != store) continue;
                var position = store.getComponent(nearbyRef, TransformComponent.getComponentType());
                if (position != null && position.getPosition().distanceSquared(at.getPosition()) <= range * range && counts(store, nearbyRef)) credited.add(nearby.getUuid());
            }
            if (credited.isEmpty()) return;
            Instant now = Instant.now();
            for (UUID player : credited) tally.boss(new StatsTally.BossKill(player, world.getName(), match.get().boss().id(), match.get().difficulty(), now));
            bossFell();
        }
    }

    /** A player fell: count it with its cause and, when a creature or player landed the blow, who. */
    private final class PlayerDeaths extends DeathSystems.OnDeathSystem {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
            if (closed) return;
            var player = store.getComponent(ref, PlayerRef.getComponentType()); if (player == null) return;
            var info = death.getDeathInfo();
            var cause = death.getDeathCause() != null ? death.getDeathCause() : cause(info);
            String killer = null; var attacker = attacker(info);
            if (attacker != null && !attacker.equals(ref)) {
                var npc = store.getComponent(attacker, NPCEntity.getComponentType());
                if (npc != null && npc.getRoleName() != null) killer = npc.getRoleName();
                else if (store.getComponent(attacker, PlayerRef.getComponentType()) != null) killer = "player";
            }
            tally.death(player.getUuid(), store.getExternalData().getWorld().getName(), cause == null ? "unknown" : cause.getId(), killer);
            travel.reset(player.getUuid());
        }
    }

    /** World loot chests: the loot chest system cancels the native open to show the player's own copy, so this one still looks at cancelled opens. */
    private final class LootChestOpen extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
        LootChestOpen() { super(UseBlockEvent.Pre.class); }
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override protected boolean shouldProcessEvent(UseBlockEvent.Pre event) { return true; }
        @Override public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> buffer, UseBlockEvent.Pre event) {
            if (closed || event.getInteractionType() != InteractionType.Use) return;
            World world = store.getExternalData().getWorld();
            if (plugin.getInfrastructure().isHousing(world.getName())) return;
            var position = base(world, event.getTargetBlock()); if (position == null || !lootChest(world, position)) return;
            opened(chunk, index, store, world, position, true);
        }
    }

    /** Every other container (chests, barrels, storage) once the game has let the player open it. */
    private final class ContainerOpen extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {
        ContainerOpen() { super(UseBlockEvent.Post.class); }
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> buffer, UseBlockEvent.Post event) {
            if (closed || event.getInteractionType() != InteractionType.Use || !opensAsContainer(event.getBlockType())) return;
            World world = store.getExternalData().getWorld();
            var position = base(world, event.getTargetBlock()); if (position == null || lootChest(world, position)) return;
            opened(chunk, index, store, world, position, false);
        }
    }

    private void opened(ArchetypeChunk<EntityStore> chunk, int index, Store<EntityStore> store, World world, Vector3i position, boolean loot) {
        var player = chunk.getComponent(index, PlayerRef.getComponentType()); if (player == null || !counts(store, chunk.getReferenceTo(index))) return;
        long now = System.nanoTime(); var visit = new ChestVisit(world.getName(), position.x, position.y, position.z, now);
        var before = lastChest.put(player.getUuid(), visit);
        if (before != null && before.world().equals(visit.world()) && before.x() == visit.x() && before.y() == visit.y() && before.z() == visit.z() && now - before.nanos() < CHEST_REVISIT_NANOS) return;
        tally.add(player.getUuid(), world.getName(), StatsFields.CHESTS, 1);
        if (loot) tally.add(player.getUuid(), world.getName(), StatsFields.LOOT_CHESTS, 1);
    }

    private static Vector3i base(World world, Vector3i target) {
        if (target == null) return null;
        var resolved = BlockOperations.resolveBaseBlockPosition(world.getChunkStore(), new BlockPosition(target.x, target.y, target.z));
        return resolved == null ? null : new Vector3i(resolved.x, resolved.y, resolved.z);
    }

    private static boolean lootChest(World world, Vector3i position) {
        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(position.x, position.y, position.z);
        if (sectionRef == null || !sectionRef.isValid()) return false;
        var entity = BlockModule.getBlockEntity(world.getChunkStore().getStore(), sectionRef, position.x, position.y, position.z);
        return entity != null && world.getChunkStore().getStore().getComponent(entity, LootChestBlock.getComponentType()) != null;
    }

    static boolean opensAsContainer(BlockType block) {
        if (block == null) return false;
        var interactions = block.getInteractions(); String id = interactions == null ? null : interactions.get(InteractionType.Use);
        var root = id == null ? null : RootInteraction.getAssetMap().getAsset(id);
        if (root == null || root.getInteractionIds() == null) return false;
        for (String each : root.getInteractionIds()) if (Interaction.getAssetMap().getAsset(each) instanceof OpenContainerInteraction) return true;
        return false;
    }

    /** About once a second: how far each player moved. Creative flight does not count. */
    private final class Travel extends DelayedEntitySystem<EntityStore> {
        private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(), Player.getComponentType(), TransformComponent.getComponentType());
        Travel() { super(1f); }
        @Override public Query<EntityStore> getQuery() { return query; }
        @Override public boolean isParallel(int archetypeChunkSize, int taskCount) { return false; }
        @Override public void tick(float dt, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
            if (closed) return;
            var player = chunk.getComponent(index, PlayerRef.getComponentType()); var mode = chunk.getComponent(index, Player.getComponentType());
            var transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (player == null || mode == null || transform == null) return;
            if (mode.getGameMode() == GameMode.Creative) { travel.reset(player.getUuid()); return; }
            var p = transform.getPosition(); String world = store.getExternalData().getWorld().getName();
            long blocks = travel.sample(player.getUuid(), world, p.x, p.y, p.z, System.nanoTime());
            if (blocks > 0) tally.add(player.getUuid(), world, StatsFields.BLOCKS, blocks);
        }
    }

    /** Any teleport (waygate, respawn, command) starts a fresh travel baseline. */
    private final class TeleportReset extends RefChangeSystem<EntityStore, Teleport> {
        @Override public Query<EntityStore> getQuery() { return PlayerRef.getComponentType(); }
        @Override public ComponentType<EntityStore, Teleport> componentType() { return Teleport.getComponentType(); }
        @Override public void onComponentAdded(Ref<EntityStore> ref, Teleport teleport, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) { reset(ref, buffer); }
        @Override public void onComponentSet(Ref<EntityStore> ref, Teleport previous, Teleport teleport, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) { reset(ref, buffer); }
        @Override public void onComponentRemoved(Ref<EntityStore> ref, Teleport teleport, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) { reset(ref, buffer); }
        private void reset(Ref<EntityStore> ref, CommandBuffer<EntityStore> buffer) {
            var player = buffer.getComponent(ref, PlayerRef.getComponentType()); if (player != null) travel.reset(player.getUuid());
        }
    }

    /** Stops listening and saves everything pending. Call before the store closes. */
    @Override public void close() {
        closed = true; if (active == this) active = null;
        worker.shutdown();
        try { if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
        flushQuietly();
        if (!tally.isEmpty()) plugin.getLogger().atSevere().log("Player stats for %d players could not be saved before shutdown", tally.size());
    }
}
