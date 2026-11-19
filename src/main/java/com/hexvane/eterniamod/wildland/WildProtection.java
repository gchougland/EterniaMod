package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps claimed land in adventure worlds safe: only the owner, their guild (by role) and friends they shared it with may
 * break, place, open chests or use things there. Unclaimed land stays open, as on any survival server. Staff in
 * Creative mode with builder rights pass. Explosions and other damage nobody caused leave claims alone.
 */
public final class WildProtection {
    private static final long NOTICE_MILLIS = 2500;
    private static final Map<UUID, Long> NOTICES = new ConcurrentHashMap<>();

    private WildProtection() {}

    public static void register(EterniaModPlugin plugin) {
        var registry = plugin.getEntityStoreRegistry();
        registry.registerSystem(new Break(plugin));
        registry.registerSystem(new Place(plugin));
        registry.registerSystem(new Damage(plugin));
        registry.registerSystem(new Use(plugin));
        registry.registerSystem(new EnvironmentDamage(plugin));
        com.hexvane.eterniamod.customization.HousingInteractionGuard.guard((world, actor, commands, x, y, z) -> {
            if (!covered(plugin, world)) return false;
            WildLandIndex.Land land = WildLandIndex.at(world.getName(), x, z);
            if (land == null) return false;
            PlayerRef player = actor == null || !actor.isValid() ? null : commands.getComponent(actor, PlayerRef.getComponentType());
            Player component = actor == null || !actor.isValid() ? null : commands.getComponent(actor, Player.getComponentType());
            if (player != null && staff(component, player.getUuid())) return false;
            boolean ok = player != null && WildAccess.allowed(land, player.getUuid(), () -> WildLandIndex.standing(plugin.getServices(), player.getUuid()), WildPerm.BUILD);
            if (!ok && player != null) notice(player, land);
            return !ok;
        });
    }

    /** Claims are kept in this world: an adventure world the settings cover. */
    public static boolean covered(EterniaModPlugin plugin, World world) {
        return plugin.getInfrastructure().world(world.getName()).map(p -> p.role().equals("adventure")).orElse(false)
            && WildLandSettings.current().coversWorld(world.getName());
    }

    private static boolean staff(Player player, UUID id) {
        return player != null && player.getGameMode() == GameMode.Creative && com.hexvane.eterniamod.setup.SetupAccess.allowed(id);
    }

    /** True when the player at {@code index} may not do {@code perm} at column {@code x, z}. Tells them whose land it is. */
    static boolean denied(EterniaModPlugin plugin, ArchetypeChunk<EntityStore> chunk, int index, Store<EntityStore> store, int x, int z, WildPerm perm) {
        World world = store.getExternalData().getWorld();
        if (!covered(plugin, world)) return false;
        WildLandIndex.Land land = WildLandIndex.at(world.getName(), x, z);
        if (land == null) return false;
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (player == null) return true;
        if (staff(chunk.getComponent(index, Player.getComponentType()), player.getUuid())) return false;
        if (WildAccess.allowed(land, player.getUuid(), () -> WildLandIndex.standing(plugin.getServices(), player.getUuid()), perm)) return false;
        notice(player, land);
        return true;
    }

    private static void notice(PlayerRef player, WildLandIndex.Land land) {
        long now = System.currentTimeMillis();
        Long last = NOTICES.get(player.getUuid());
        if (last != null && now - last < NOTICE_MILLIS) return;
        NOTICES.put(player.getUuid(), now);
        player.sendMessage(Message.raw("This land belongs to " + land.ownerName() + ". Ask them to add you as a friend."));
    }

    /** Which permission a use of a block needs: chests and other storage, or doors, benches and the rest. */
    static WildPerm perm(String housingCapability) {
        return "housing.container.open".equals(housingCapability) ? WildPerm.CONTAINERS : WildPerm.USE;
    }

    private static final class Break extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        private final EterniaModPlugin plugin;
        Break(EterniaModPlugin plugin) { super(BreakBlockEvent.class); this.plugin = plugin; }
        @Override public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> b, BreakBlockEvent e) {
            var p = e.getTargetBlock();
            if (denied(plugin, c, i, s, p.x, p.z, WildPerm.BUILD)) e.setCancelled(true);
        }
    }

    private static final class Damage extends EntityEventSystem<EntityStore, DamageBlockEvent> {
        private final EterniaModPlugin plugin;
        Damage(EterniaModPlugin plugin) { super(DamageBlockEvent.class); this.plugin = plugin; }
        @Override public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> b, DamageBlockEvent e) {
            var p = e.getTargetBlock();
            if (denied(plugin, c, i, s, p.x, p.z, WildPerm.BUILD)) e.setCancelled(true);
        }
    }

    /** Placing checks every block a large piece fills, so nothing reaches over the edge into someone's land. */
    private static final class Place extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        private final EterniaModPlugin plugin;
        Place(EterniaModPlugin plugin) { super(PlaceBlockEvent.class); this.plugin = plugin; }
        @Override public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> b, PlaceBlockEvent e) {
            var origin = e.getTargetBlock();
            if (denied(plugin, c, i, s, origin.x, origin.z, WildPerm.BUILD)) { e.setCancelled(true); return; }
            if (!covered(plugin, s.getExternalData().getWorld())) return;
            var item = e.getItemInHand();
            var block = item == null ? null : BlockType.getAssetMap().getAsset(item.getBlockKey());
            var hitbox = block == null ? null : BlockBoundingBoxes.getAssetMap().getAsset(block.getHitboxTypeIndex());
            if (hitbox == null) return;
            boolean[] refused = {false};
            FillerBlockUtil.forEachFillerBlock(hitbox.get(e.getRotation().index()), (dx, dy, dz) -> {
                if (!refused[0] && (dx != 0 || dz != 0) && denied(plugin, c, i, s, origin.x + dx, origin.z + dz, WildPerm.BUILD)) refused[0] = true;
            });
            if (refused[0]) e.setCancelled(true);
        }
    }

    private static final class Use extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
        private final EterniaModPlugin plugin;
        Use(EterniaModPlugin plugin) { super(UseBlockEvent.Pre.class); this.plugin = plugin; }
        @Override public Query<EntityStore> getQuery() { return Query.any(); }
        @Override public void handle(int i, ArchetypeChunk<EntityStore> c, Store<EntityStore> s, CommandBuffer<EntityStore> b, UseBlockEvent.Pre e) {
            World world = s.getExternalData().getWorld();
            if (!covered(plugin, world)) return;
            var p = e.getTargetBlock();
            if (WildLandIndex.at(world.getName(), p.x, p.z) == null) return;
            for (String capability : com.hexvane.eterniamod.housing.HousingUseCapabilities.required(s, e)) {
                if (denied(plugin, c, i, s, p.x, p.z, perm(capability))) { e.setCancelled(true); return; }
            }
        }
    }

    /** Explosions and other damage with nobody behind it do not touch claimed land. */
    private static final class EnvironmentDamage extends WorldEventSystem<EntityStore, DamageBlockEvent> {
        private final EterniaModPlugin plugin;
        EnvironmentDamage(EterniaModPlugin plugin) { super(DamageBlockEvent.class); this.plugin = plugin; }
        @Override public void handle(Store<EntityStore> s, CommandBuffer<EntityStore> b, DamageBlockEvent e) {
            World world = s.getExternalData().getWorld();
            if (!covered(plugin, world)) return;
            var p = e.getTargetBlock();
            if (WildLandIndex.at(world.getName(), p.x, p.z) != null) e.setCancelled(true);
        }
    }
}
