package com.hexvane.eterniamod.nameplate;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entityui.UIComponentList;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Players' nameplates, drawn as coloured floating text instead of the game's plain white nameplate.
 *
 * <p>The game's nameplate is one plain white line, so each line here is a still, coloured piece of the game's floating
 * combat text, which the server can send in any colour. Each line hangs on its own invisible anchor attached above the
 * player's head (one text style per anchor: an update shows on every style an entity has). The text lasts ten seconds
 * on screen, so it is sent again to everyone who can see it before it runs out. When the lines change, the anchors are
 * replaced, so old text never lingers under new text. Nothing here needs any new asset at runtime.
 */
public final class NameplateRuntime {
    private static final String ANCHOR_MODEL = "Eternia_Nameplate_Anchor";
    /** Where the anchors sit, above the player's feet. */
    private static final float HEAD_HEIGHT = 2.3f;
    /** How far apart the line anchors sit, in blocks; the lines themselves are spaced by their text styles. */
    private static final float LINE_SPACING = 0.06f;
    private static final long CHECK_MILLIS = 400;
    /** Each line lasts 10 seconds on screen; sent again a little before. */
    private static final long RESEND_MILLIS = 8500;
    /** How often titles and guilds are looked up again. */
    private static final long PLAN_MILLIS = 4000;

    /** What each online player's nameplate should show, refreshed off the world thread. */
    private static final Map<UUID, List<NameplateLines.Line>> PLANS = new ConcurrentHashMap<>();
    /** What each player's anchors show now, and where they are. */
    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();
    /** Per viewer: when each anchor's text was last sent to them. */
    private static final Map<UUID, Map<Ref<EntityStore>, Long>> SENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CHECKED = new ConcurrentHashMap<>();
    private static volatile long plannedAt;

    private record Anchor(NameplateLines.Line line, Ref<EntityStore> ref) {}
    private record Shown(String world, List<NameplateLines.Line> lines, List<Anchor> anchors) {}

    private NameplateRuntime() {}

    public static void register(EterniaModPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new Tick());
        plugin.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, event -> {
            var player = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (player != null) remove(event.getWorld(), player.getUuid());
        });
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            UUID id = event.getPlayerRef().getUuid();
            PLANS.remove(id);
            SENT.remove(id);
            CHECKED.remove(id);
        });
    }

    /** Removes every anchor, on shutdown. */
    public static void shutdown() {
        var universe = Universe.get();
        if (universe == null) return;
        for (World world : List.copyOf(universe.getWorlds().values())) {
            if (!world.isAlive()) continue;
            for (UUID id : List.copyOf(SHOWN.keySet())) remove(world, id);
        }
    }

    private static void remove(World world, UUID player) {
        var shown = SHOWN.get(player);
        if (shown == null || !shown.world().equals(world.getName())) return;
        SHOWN.remove(player, shown);
        try { world.execute(() -> despawn(world.getEntityStore().getStore(), shown)); } catch (RuntimeException closing) { /* The world is stopping. */ }
    }

    private static void despawn(Store<EntityStore> store, Shown shown) {
        for (var anchor : shown.anchors()) if (anchor.ref().isValid() && anchor.ref().getStore() == store) store.removeEntity(anchor.ref(), RemoveReason.REMOVE);
    }

    /** Looks up every online player's titles and guild, off the world thread. */
    private static void refreshPlans(EterniaModPlugin plugin, boolean soon) {
        long now = System.currentTimeMillis();
        // A player still waiting for their first lines gets them within a second rather than at the next round.
        if (now - plannedAt < (soon ? 1000 : PLAN_MILLIS)) return;
        plannedAt = now;
        var services = plugin.getServices();
        var collections = plugin.getCollectionRuntime();
        SocialUiBootstrap.supply(services, () -> {
            var plans = new HashMap<UUID, List<NameplateLines.Line>>();
            for (PlayerRef player : List.copyOf(Universe.get().getPlayers())) {
                UUID id = player.getUuid();
                var look = collections == null ? null : collections.look(id);
                String guild = services.guilds().membership(id).flatMap(m -> services.guilds().find(m.guildId())).map(g -> g.name()).orElse(null);
                plans.put(id, NameplateLines.of(player.getUsername(), look == null ? "" : look.prefix(), look == null ? "" : look.suffix(),
                    look == null ? null : look.colour(), guild));
            }
            return plans;
        }).whenComplete((plans, failure) -> {
            if (failure != null) { plugin.getLogger().atWarning().withCause(failure).log("Nameplates could not be refreshed"); return; }
            PLANS.putAll(plans);
        });
    }

    /** Twice a second per player: keep their anchors right, keep the old nameplate hidden, and send them what they see. */
    static final class Tick extends EntityTickingSystem<EntityStore> {
        @Nonnull @Override
        public Query<EntityStore> getQuery() { return Query.and(Player.getComponentType(), PlayerRef.getComponentType()); }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            var player = chunk.getComponent(index, PlayerRef.getComponentType());
            var plugin = EterniaModPlugin.get();
            if (player == null || plugin == null) return;
            UUID id = player.getUuid();
            long now = System.currentTimeMillis();
            Long last = CHECKED.get(id);
            if (last != null && now - last < CHECK_MILLIS) return;
            CHECKED.put(id, now);
            refreshPlans(plugin, !PLANS.containsKey(id));
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            World world = store.getExternalData().getWorld();
            world.execute(() -> { if (ref.isValid()) step(ref, store, world, player, now); });
        }
    }

    private static void step(Ref<EntityStore> ref, Store<EntityStore> store, World world, PlayerRef player, long now) {
        UUID id = player.getUuid();
        hideGameNameplate(ref, store);
        // Nothing until the player's real lines are known: a stand in name replaced a moment later lingered on screen,
        // faded grey, for several seconds after login.
        var lines = PLANS.get(id);
        if (lines != null) {
            var shown = SHOWN.get(id);
            if (shown == null || !shown.world().equals(world.getName())) shown = new Shown(world.getName(), List.of(), List.of());
            var next = reconcile(ref, store, shown, lines);
            if (next != null) SHOWN.put(id, next);
        }
        send(ref, store, world, id, now);
    }

    /** The game sets the plain nameplate from the display name whenever it changes, so it is emptied again each check. */
    private static void hideGameNameplate(Ref<EntityStore> ref, Store<EntityStore> store) {
        var nameplate = store.getComponent(ref, Nameplate.getComponentType());
        if (nameplate != null && !nameplate.getText().isEmpty()) nameplate.setText("");
    }

    /**
     * Brings a player's anchors in line with {@code lines}, replacing only lines that changed: a removed line's text
     * stays on screen, fading, for a moment. Returns the new state, or null when the anchor model is missing.
     */
    private static Shown reconcile(Ref<EntityStore> player, Store<EntityStore> store, Shown shown, List<NameplateLines.Line> lines) {
        var asset = ModelAsset.getAssetMap().getAsset(ANCHOR_MODEL);
        var transform = store.getComponent(player, TransformComponent.getComponentType());
        if (asset == null || transform == null) return null;
        var kept = new java.util.ArrayList<Anchor>();
        boolean changed = false;
        for (var anchor : shown.anchors()) {
            if (anchor.ref().isValid() && lines.contains(anchor.line())) { kept.add(anchor); continue; }
            if (anchor.ref().isValid() && anchor.ref().getStore() == store) store.removeEntity(anchor.ref(), RemoveReason.REMOVE);
            changed = true;
        }
        for (var line : lines) {
            if (kept.stream().anyMatch(anchor -> anchor.line().equals(line))) continue;
            var anchor = spawn(player, store, asset, transform.getPosition(), line);
            if (anchor != null) { kept.add(anchor); changed = true; }
        }
        return changed ? new Shown(shown.world(), lines, List.copyOf(kept)) : shown;
    }

    /**
     * Where each line's anchor sits. Anchors at one point stand in each other's line of sight, and the client fades
     * floating text it cannot see (lines flickered grey as the player moved), so each line has its own height.
     */
    private static float height(NameplateLines.Line line) {
        return switch (line.style()) {
            case NameplateLines.TITLE_STYLE -> HEAD_HEIGHT + LINE_SPACING;
            case NameplateLines.GUILD_STYLE -> HEAD_HEIGHT - LINE_SPACING;
            default -> HEAD_HEIGHT;
        };
    }

    private static Anchor spawn(Ref<EntityStore> player, Store<EntityStore> store, ModelAsset asset, Vector3d at, NameplateLines.Line line) {
        {
            float height = height(line);
            var holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
            holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
            holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(at.x, at.y + height, at.z), new Rotation3f()));
            holder.addComponent(HeadRotation.getComponentType(), new HeadRotation(new Rotation3f()));
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(Model.createScaledModel(asset, 1f)));
            holder.addComponent(UIComponentList.getComponentType(), new UIComponentList(new String[] {line.style()}));
            holder.addComponent(MountedComponent.getComponentType(), new MountedComponent(player, new Vector3f(0, height, 0), MountController.Minecart));
            var anchor = store.addEntity(holder, AddReason.SPAWN);
            return anchor == null ? null : new Anchor(line, anchor);
        }
    }

    /** Sends this viewer the text of every other player's anchors they can see, when it is new to them or nearly gone. */
    private static void send(Ref<EntityStore> viewerRef, Store<EntityStore> store, World world, UUID viewer, long now) {
        var tracker = store.getComponent(viewerRef, EntityTrackerSystems.EntityViewer.getComponentType());
        if (tracker == null) return;
        var sent = SENT.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>());
        sent.keySet().removeIf(anchor -> !anchor.isValid());
        for (var entry : SHOWN.entrySet()) {
            if (entry.getKey().equals(viewer) || !entry.getValue().world().equals(world.getName())) continue;
            for (var anchor : entry.getValue().anchors()) {
                if (!anchor.ref().isValid() || !tracker.visible.contains(anchor.ref())) { sent.remove(anchor.ref()); continue; }
                Long at = sent.get(anchor.ref());
                // Newly seen: wait one check so the anchor and its text style reach the viewer before its text does.
                if (at == null) { sent.put(anchor.ref(), now - RESEND_MILLIS + CHECK_MILLIS); continue; }
                if (now - at < RESEND_MILLIS) continue;
                byte[] rgb = NameplateLines.rgb(anchor.line().colour());
                tracker.queueUpdate(anchor.ref(), new CombatTextUpdate(0f, anchor.line().text(), new Color(rgb[0], rgb[1], rgb[2])));
                sent.put(anchor.ref(), now);
            }
        }
    }
}
