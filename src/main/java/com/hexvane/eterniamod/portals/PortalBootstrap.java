package com.hexvane.eterniamod.portals;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.AccountService;
import com.hexvane.eterniamod.domain.PortalService;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3i;

/**
 * Personal portals on the vanilla Teleporter pad. The pad's asset (an override of vanilla {@code Teleporter.json})
 * swaps vanilla's Teleporter component for {@link EterniaPortalBlock} and runs {@link EterniaPortalUse} on use and
 * walk-in. Placing a pad makes a portal for the placer; removing it in any way removes the portal. Also installs the
 * personal map markers ({@link PersonalMapMarkers}).
 */
public final class PortalBootstrap {
    /** The vanilla pad's item and block id. Its state variants are named {@code *Teleporter_State_...}. */
    public static final String BLOCK = "Teleporter";
    private static final long NOTICE_MILLIS = 2500;

    private static volatile EterniaModPlugin plugin;
    private static volatile PortalIndex index;
    private static volatile PortalArrivals arrivals;
    private static volatile PacketFilter inbound;
    private static final Map<UUID, Long> notices = new ConcurrentHashMap<>();

    private PortalBootstrap() {}

    public static void register(EterniaModPlugin owner) {
        plugin = owner;
        index = new PortalIndex(owner.getServices());
        arrivals = new PortalArrivals(System::currentTimeMillis);
        EterniaPortalBlock.register(owner.getChunkStoreRegistry());
        owner.getChunkStoreRegistry().registerSystem(new Placed());
        owner.getChunkStoreRegistry().registerSystem(new Removed());
        owner.getCodecRegistry(Interaction.CODEC).register("EterniaPortal", EterniaPortalUse.class, EterniaPortalUse.CODEC);
        owner.getEntityStoreRegistry().registerSystem(new BreakGuard());
        owner.getEntityStoreRegistry().registerSystem(new DamageGuard());
        // Vanilla adds its own providers on AddWorldEvent; StartWorldEvent comes after, so ours replace theirs either way.
        owner.getEventRegistry().registerGlobal(AddWorldEvent.class, e -> PersonalMapMarkers.install(e.getWorld(), index));
        owner.getEventRegistry().registerGlobal(StartWorldEvent.class, e -> PersonalMapMarkers.install(e.getWorld(), index));
        owner.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> {
            UUID id = e.getPlayerRef().getUuid(); index.forget(id); arrivals.forget(id); notices.remove(id); PersonalMapMarkers.forget(id);
        });
        owner.getEventRegistry().registerGlobal(PlayerReadyEvent.class, e -> {
            var ref = e.getPlayerRef();
            if (ref == null || !ref.isValid()) return;
            var id = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (id != null) PersonalMapMarkers.remember(e.getPlayer(), id.getUuid());
        });
        inbound = PacketAdapters.registerInbound((PlayerPacketFilter) PersonalMapMarkers::personalOnly);
    }

    /** After every plugin's setup: worlds already running get the personal map, and pad owners are loaded. */
    public static void start() {
        index.loadOwners();
        for (World world : Universe.get().getWorlds().values()) world.execute(() -> PersonalMapMarkers.install(world, index));
    }

    public static void close() {
        var filter = inbound; inbound = null;
        if (filter != null) PacketAdapters.deregisterInbound(filter);
    }

    static PortalIndex index() { return index; }
    static PortalArrivals arrivals() { return arrivals; }
    static EterniaModPlugin plugin() { return plugin; }

    public static boolean isPortalBlock(String blockId) {
        return blockId != null && (blockId.equals(BLOCK) || blockId.startsWith("*" + BLOCK + "_State_"));
    }

    /** The pad's main cell: the pad is three wide with an arch, and its other cells are fillers pointing back to it. */
    static Vector3i origin(World world, Vector3i p) {
        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(p.x, p.y, p.z);
        var section = sectionRef == null || !sectionRef.isValid() ? null : world.getChunkStore().getStore().getComponent(sectionRef, BlockSection.getComponentType());
        int filler = section == null ? 0 : section.getFiller(p.x, p.y, p.z);
        if (filler == 0) return new Vector3i(p);
        return new Vector3i(p.x - FillerBlockUtil.unpackX(filler), p.y - FillerBlockUtil.unpackY(filler), p.z - FillerBlockUtil.unpackZ(filler));
    }

    static String blockId(World world, Vector3i p) {
        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(p.x, p.y, p.z);
        if (sectionRef == null || !sectionRef.isValid()) return null;
        var section = world.getChunkStore().getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return null;
        var block = BlockType.getAssetMap().getAsset(section.get(ChunkUtil.indexBlock(p.x, p.y, p.z)));
        return block == null ? null : block.getId();
    }

    /** Staff may change and break anyone's portal. */
    static boolean staff(UUID player) { return com.hexvane.eterniamod.setup.SetupAccess.allowed(player); }

    /** World thread: a player used or walked onto the pad at {@code pad}. Adds it to their list and opens the menu. */
    static void open(World world, Ref<EntityStore> ref, PlayerRef player, Vector3i target, boolean walkedIn) {
        var pad = origin(world, target);
        if (!isPortalBlock(blockId(world, pad))) return;
        if (walkedIn && arrivals.justArrived(player.getUuid(), world.getName(), pad)) return;
        var current = ref.getStore().getComponent(ref, Player.getComponentType());
        if (walkedIn && current != null && current.getPageManager().getCustomPage() instanceof PortalPage open && open.showing(PortalService.id(world.getName(), pad.x, pad.y, pad.z))) return;
        UUID placedBy = null;
        var blockRef = BlockModule.getBlockEntity(world, pad.x, pad.y, pad.z);
        if (blockRef != null && blockRef.isValid()) {
            var chunks = world.getChunkStore().getStore();
            var placed = chunks.getComponent(blockRef, PlacedByInteractionComponent.getComponentType());
            if (placed != null) placedBy = placed.getWhoPlacedUuid();
            if (chunks.getComponent(blockRef, EterniaPortalBlock.getComponentType()) == null) convert(chunks, blockRef);
        }
        var services = plugin.getServices();
        String worldName = world.getName();
        UUID actor = player.getUuid(), placer = placedBy;
        String id = PortalService.id(worldName, pad.x, pad.y, pad.z);
        SocialUiBootstrap.supply(services, () -> {
            var portal = services.portals().find(id).orElse(null);
            if (portal == null) {
                String name = placer == null ? null : services.accounts().find(placer).map(AccountService.Account::displayName).orElse(null);
                portal = services.portals().adopt(worldName, pad.x, pad.y, pad.z, placer, name);
                index.put(portal);
            }
            var visit = services.portals().visit(actor, id);
            if (visit == PortalService.Visit.ADDED) index.changed();
            return new Object[] {portal, visit};
        }).whenComplete((result, error) -> world.execute(() -> {
            if (!ref.isValid()) return;
            if (error != null) { player.sendMessage(Message.raw("This portal is not working right now. Try again in a moment.")); return; }
            var portal = (PortalService.Portal) result[0]; var visit = (PortalService.Visit) result[1];
            if (visit == PortalService.Visit.PRIVATE) {
                notice(player, "This portal is private. Ask " + (portal.ownerName().isBlank() ? "its owner" : portal.ownerName()) + " to let you use it.");
                return;
            }
            var store = ref.getStore();
            var component = store.getComponent(ref, Player.getComponentType());
            if (component == null) return;
            component.getPageManager().openCustomPage(ref, store, new PortalPage(player, portal, visit, staff(actor)));
        }));
    }

    /**
     * A pad placed before portals carries vanilla's Teleporter component and no {@link EterniaPortalBlock}. Swap them so
     * breaking it later removes the portal, and so vanilla removes the pad's global warp (and its map marker for
     * everyone) itself when its component goes. World thread, outside any tick.
     */
    private static void convert(Store<ChunkStore> chunks, Ref<ChunkStore> blockRef) {
        chunks.putComponent(blockRef, EterniaPortalBlock.getComponentType(), new EterniaPortalBlock());
        if (com.hypixel.hytale.builtin.adventure.teleporter.TeleporterPlugin.get() != null) {
            var vanilla = com.hypixel.hytale.builtin.adventure.teleporter.component.Teleporter.getComponentType();
            if (chunks.getComponent(blockRef, vanilla) != null) chunks.tryRemoveComponent(blockRef, vanilla);
        }
        var info = chunks.getComponent(blockRef, BlockModule.BlockStateInfo.getComponentType());
        if (info != null) info.markNeedsSaving(chunks);
    }

    static void notice(PlayerRef player, String text) {
        long now = System.currentTimeMillis();
        Long last = notices.get(player.getUuid());
        if (last != null && now - last < NOTICE_MILLIS) return;
        notices.put(player.getUuid(), now);
        player.sendMessage(Message.raw(text));
    }

    private static Vector3i position(BlockModule.BlockStateInfo info, ComponentAccessor<ChunkStore> accessor) {
        var p = new Vector3i();
        return info.fillWorldPos(accessor, p) ? p : null;
    }

    /** A pad was placed by a player: it becomes their portal. */
    private static final class Placed extends RefChangeSystem<ChunkStore, PlacedByInteractionComponent> {
        private final Query<ChunkStore> query = Query.and(PlacedByInteractionComponent.getComponentType(), EterniaPortalBlock.getComponentType(), BlockModule.BlockStateInfo.getComponentType());
        @Override public ComponentType<ChunkStore, PlacedByInteractionComponent> componentType() { return PlacedByInteractionComponent.getComponentType(); }
        @Override public Query<ChunkStore> getQuery() { return query; }
        @Override public void onComponentAdded(Ref<ChunkStore> ref, PlacedByInteractionComponent placed, Store<ChunkStore> store, CommandBuffer<ChunkStore> commands) {
            var info = commands.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            var p = info == null ? null : position(info, commands);
            UUID owner = placed.getWhoPlacedUuid();
            if (p == null || owner == null) return;
            String world = store.getExternalData().getWorld().getName();
            var services = plugin.getServices();
            SocialUiBootstrap.supply(services, () -> {
                String name = services.accounts().find(owner).map(AccountService.Account::displayName).orElse(null);
                return services.portals().place(owner, name, world, p.x, p.y, p.z);
            }).whenComplete((portal, error) -> {
                if (error != null) { plugin.getLogger().atWarning().withCause(error).log("Could not record a new portal"); return; }
                index.put(portal);
                var online = Universe.get().getPlayer(owner);
                if (online != null) online.sendMessage(Message.raw(portal.name() + " is ready. Walk onto it to travel or to change its settings."));
            });
        }
        @Override public void onComponentSet(Ref<ChunkStore> ref, PlacedByInteractionComponent old, PlacedByInteractionComponent now, Store<ChunkStore> store, CommandBuffer<ChunkStore> commands) {}
        @Override public void onComponentRemoved(Ref<ChunkStore> ref, PlacedByInteractionComponent component, Store<ChunkStore> store, CommandBuffer<ChunkStore> commands) {}
    }

    /** A pad is gone (broken, replaced, blown up). Unloading a chunk does not count. */
    private static final class Removed extends RefSystem<ChunkStore> {
        private final Query<ChunkStore> query = Query.and(EterniaPortalBlock.getComponentType(), BlockModule.BlockStateInfo.getComponentType());
        @Override public Query<ChunkStore> getQuery() { return query; }
        @Override public void onEntityAdded(Ref<ChunkStore> ref, AddReason reason, Store<ChunkStore> store, CommandBuffer<ChunkStore> commands) {}
        @Override public void onEntityRemove(Ref<ChunkStore> ref, RemoveReason reason, Store<ChunkStore> store, CommandBuffer<ChunkStore> commands) {
            if (reason != RemoveReason.REMOVE) return;
            var info = commands.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
            var p = info == null ? null : position(info, commands);
            if (p == null) return;
            String id = PortalService.id(store.getExternalData().getWorld().getName(), p.x, p.y, p.z);
            var services = plugin.getServices();
            SocialUiBootstrap.supply(services, () -> { services.portals().remove(id); return null; })
                .whenComplete((v, error) -> { if (error == null) index.removed(id); else plugin.getLogger().atWarning().withCause(error).log("Could not remove a broken portal"); });
        }
    }

    /** Only the owner and staff in Creative mode may break a portal. */
    static boolean refused(ArchetypeChunk<EntityStore> chunk, int i, Store<EntityStore> store, BlockType block, Vector3i target) {
        if (block == null || !isPortalBlock(block.getId())) return false;
        var world = store.getExternalData().getWorld();
        var p = origin(world, target);
        var owner = index.owner(PortalService.id(world.getName(), p.x, p.y, p.z));
        if (owner.isEmpty()) return false;
        var player = chunk.getComponent(i, PlayerRef.getComponentType());
        if (player == null) return true;
        if (owner.get().equals(player.getUuid())) return false;
        var component = chunk.getComponent(i, Player.getComponentType());
        if (component != null && component.getGameMode() == GameMode.Creative && staff(player.getUuid())) return false;
        notice(player, "This portal belongs to someone else. Only its owner can break it.");
        return true;
    }

    private static final class BreakGuard extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        BreakGuard() { super(BreakBlockEvent.class); }
        @Override public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> b, BreakBlockEvent e) {
            if (refused(c, i, s, e.getBlockType(), e.getTargetBlock())) e.setCancelled(true);
        }
    }

    private static final class DamageGuard extends EntityEventSystem<EntityStore, DamageBlockEvent> {
        DamageGuard() { super(DamageBlockEvent.class); }
        @Override public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> b, DamageBlockEvent e) {
            if (refused(c, i, s, e.getBlockType(), e.getTargetBlock())) e.setCancelled(true);
        }
    }
}
