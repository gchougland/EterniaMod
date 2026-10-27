package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.setup.ManagedHubServices;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Session-only terrain editing. This never grants rights over owned or journalled structures. */
public final class HubBuildMode {
    static final Sessions SESSIONS = new Sessions(SetupAccess::allowed);
    private HubBuildMode() {}

    static final class Sessions {
        private final ConcurrentHashMap<UUID, UUID> worlds = new ConcurrentHashMap<>();
        private final Predicate<UUID> authorized;
        Sessions(Predicate<UUID> authorized) { this.authorized = authorized; }
        void enable(UUID player, UUID world) {
            if (!authorized.test(player)) throw new IllegalStateException("WorldEditor permission is required to build in the Hub.");
            worlds.put(player, world);
        }
        boolean allows(UUID player, UUID world) {
            if (player == null || !world.equals(worlds.get(player))) return false;
            if (!authorized.test(player)) { worlds.remove(player); return false; }
            return true;
        }
        boolean any(UUID world) { return worlds.keySet().stream().anyMatch(player -> allows(player, world)); }
        void forget(UUID player) { worlds.remove(player); }
        void clear() { worlds.clear(); }
    }

    static void register(EterniaModPlugin plugin) {
        SESSIONS.clear();
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> forget(event.getPlayerRef().getUuid()));
        plugin.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class, event -> {
            var player = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (player != null) forget(player.getUuid());
        });
    }

    private static void forget(UUID player) {
        SESSIONS.forget(player);
        HousingWorldPolicy.refreshAll();
    }

    public static void set(EterniaModPlugin plugin, PlayerRef player, World world, boolean enabled) {
        SetupAccess.require(player.getUuid());
        if (enabled) {
            if (!isHub(plugin, world)) throw new IllegalArgumentException("Choose this world's Hub role in /e admin setup first.");
            SESSIONS.enable(player.getUuid(), world.getWorldConfig().getUuid());
        } else SESSIONS.forget(player.getUuid());
        try { HousingWorldPolicy.refresh(world); }
        catch (RuntimeException failure) { SESSIONS.forget(player.getUuid()); throw failure; }
        plugin.getLogger().atInfo().log("Administrator %s set Hub building %s in %s", player.getUuid(), enabled, world.getName());
    }

    private static boolean isHub(EterniaModPlugin plugin, World world) {
        return plugin.getInfrastructure().world(world.getName()).map(plan -> plan.role().equals("hub")).orElse(false);
    }
    public static boolean enabled(EterniaModPlugin plugin, UUID player, World world) {
        return isHub(plugin, world) && SESSIONS.allows(player, world.getWorldConfig().getUuid());
    }
    static boolean any(EterniaModPlugin plugin, World world) {
        return isHub(plugin, world) && SESSIONS.any(world.getWorldConfig().getUuid());
    }

    public static boolean canEdit(EterniaModPlugin plugin, UUID player, World world, int x, int z) {
        if (!enabled(plugin, player, world)) return false;
        var cell = new PlotRect(x, z, 1, 1);
        if (plugin.getInfrastructure().structureRoads(world.getName()).stream().anyMatch(r -> r.contains(x, z))
                || com.hexvane.eterniamod.setup.paving.RoadPaving.protectedColumn(world.getName(), x, z)
                || com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingColumn(world.getName(), x, z)
                || ManagedHubServices.intersectsManaged(world.getName(), cell)) return false;
        if (EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).findPlotContainingHorizontal(x, z) != null) return false;
        return !plugin.getServices().housing().hasReservedGround(world.getName(),x,z);
    }

    /** Runs only inside the disposable native acceptance server, on its world thread. */
    public static void nativeSmoke(EterniaModPlugin plugin, World world) throws Exception {
        if (!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE")) || !plugin.getRuntimeConfig().local()
                || !world.getName().equals("eternia_smoke")) throw new IllegalStateException("Isolated Hub building fixture required");
        var before=plugin.getInfrastructure().world(world.getName()).orElseThrow();
        var snapshot=plugin.getInfrastructure().snapshot();
        plugin.getInfrastructure().saveWorld(snapshot.revision(),world.getName(),before.withRole("hub",true));
        var actor=UUID.randomUUID();
        var permission=java.util.Set.of(com.hypixel.hytale.server.core.permissions.HytalePermissions.BUILDER_TOOLS_EDITOR.getId());
        var permissions=com.hypixel.hytale.server.core.permissions.PermissionsModule.get();
        try {
            permissions.addUserPermission(actor,permission);SESSIONS.enable(actor,world.getWorldConfig().getUuid());
            HousingWorldPolicy.refresh(world);
            check(world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed(),"Admin placement remains disabled");
            check(world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(null),"Admin breaking remains disabled");
            var builderRules=HousingWorldPolicy.clientRules(world.getGameplayConfig().toPacket(),enabled(plugin,actor,world));
            var visitorRules=HousingWorldPolicy.clientRules(world.getGameplayConfig().toPacket(),enabled(plugin,UUID.randomUUID(),world));
            check(builderRules.worldConfig.allowBlockBreaking&&builderRules.worldConfig.allowBlockPlacement,"Builder native rules remained locked");
            check(!visitorRules.worldConfig.allowBlockBreaking&&!visitorRules.worldConfig.allowBlockPlacement,"Visitor inherited a builder's native rules");
            check(!builderRules.worldConfig.allowBlockGathering&&!visitorRules.worldConfig.allowBlockGathering,"Client gathering was unlocked");
            check(!world.getGameplayConfig().getWorldConfig().isBlockGatheringAllowed(),"Gathering was unlocked");
            check(world.getWorldConfig().getDisabledFluidTickers().contains("Fluid") && HousingInteractionGuard.blocked(world),"Fluid protection was unlocked");
            check(canEdit(plugin,actor,world,40,80),"Unclaimed terrain must be editable");
            check(canEdit(plugin,actor,world,6,9),"Public plaza ground must be editable");
            check(!canEdit(plugin,UUID.randomUUID(),world,40,80),"Another player inherited admin building");
            check(!canEdit(plugin,actor,world,1,20),"A registered road became editable");
            // Verify the native asset loader used the guarded subclasses, including inline bucket actions.
            int guarded=0;
            for (var interaction:com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction.getAssetMap().getAssetMap().values()) {
                if (interaction instanceof com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceFluidInteraction) {
                    check(interaction instanceof HousingInteractionGuard.PlaceFluid,"Unguarded fluid placement asset");guarded++;
                }
                if (interaction instanceof com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.RefillContainerInteraction) {
                    check(interaction instanceof HousingInteractionGuard.RefillContainer,"Unguarded bucket refill asset");guarded++;
                }
            }
            check(guarded>0,"No native fluid assets tested");
            permissions.removeUserPermission(actor,permission);
            check(!canEdit(plugin,actor,world,40,80),"Revoked permission retained build access");
            HousingWorldPolicy.refresh(world);
            check(!world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed(),"Last builder leaving did not restore world lock");
            check(!world.getGameplayConfig().getWorldConfig().isBlockBreakingAllowed(null),"Last builder leaving did not restore breaking lock");
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_HUB_BUILD_PASS: scoped admin access, plaza edits, road protection, permission revocation, native fluid guards and policy restoration");
        } finally {
            SESSIONS.forget(actor);permissions.removeUserPermission(actor,permission);
            var current=plugin.getInfrastructure().snapshot();
            plugin.getInfrastructure().saveWorld(current.revision(),world.getName(),before);HousingWorldPolicy.refresh(world);
        }
    }
    private static void check(boolean valid,String message) { if(!valid)throw new IllegalStateException(message); }
}
