package com.hexvane.eterniamod;

import com.hexvane.eterniamod.bootstrap.BuildingPlacementBootstrap;
import com.hexvane.eterniamod.bootstrap.PropPlacementBootstrap;
import com.hexvane.eterniamod.building.BuildingCatalog;
import com.hexvane.eterniamod.prop.PropCatalog;
import com.hexvane.eterniamod.command.EterniaModCommand;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetPackRegisterEvent;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.events.SendCommonAssetsEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EterniaModPlugin extends JavaPlugin {
    private static EterniaModPlugin instance;
    private BuildingCatalog buildingCatalog;
    private PropCatalog propCatalog;
    private com.hexvane.eterniamod.housing.HousingInfrastructure infrastructure;
    private com.hexvane.eterniamod.boundary.BoundaryFogSystem boundaryFog;
    private com.hexvane.eterniamod.runtime.EterniaRuntime runtime;
    private com.hexvane.eterniamod.housing.NativeClaimCoordinator claims;
    private com.hexvane.eterniamod.runtime.NativeTravelService travel;
    private com.hexvane.eterniamod.runtime.PlayerLifecycle lifecycle;
    private com.hexvane.eterniamod.runtime.NativeMenuActions menuActions;
    private com.hexvane.eterniamod.socialui.SocialUiBootstrap.Registration socialUi;
    private com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator relocation;
    private com.hexvane.eterniamod.housing.relocation.GuildDepartureWorker departures;
    private com.hexvane.eterniamod.inventory.NativeItemEscrow itemEscrow;
    private com.hexvane.eterniamod.collections.CollectionRuntime collectionRuntime;
    private com.hexvane.eterniamod.runtime.GameplayAdapters gameplayAdapters;
    private com.hexvane.eterniamod.housing.VoluntaryPlotMover plotMover;
    private com.hexvane.eterniamod.customization.CustomizationService customization;
    private com.hexvane.eterniamod.discovery.DiscoveryBootstrap discoveries;
    private AutoCloseable managedHubServices;

    public com.hexvane.eterniamod.housing.HousingInfrastructure getInfrastructure() { return infrastructure; }
    public com.hexvane.eterniamod.domain.EterniaServices getServices() { return runtime.services(); }
    public com.hexvane.eterniamod.runtime.RuntimeConfig getRuntimeConfig() { return runtime.config(); }
    public com.hexvane.eterniamod.runtime.EterniaRuntime getRuntime() { return runtime; }
    public com.hexvane.eterniamod.housing.NativeClaimCoordinator getClaims() { return claims; }
    public com.hexvane.eterniamod.runtime.NativeTravelService getTravel() { return travel; }
    public com.hexvane.eterniamod.runtime.NativeMenuActions getMenuActions() { return menuActions; }
    public com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator getRelocation() { return relocation; }
    public com.hexvane.eterniamod.inventory.NativeItemEscrow getItemEscrow() { return itemEscrow; }
    public com.hexvane.eterniamod.collections.CollectionRuntime getCollectionRuntime() { return collectionRuntime; }
    public com.hexvane.eterniamod.housing.VoluntaryPlotMover getPlotMover() { return plotMover; }

    public EterniaModPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Nullable
    public static EterniaModPlugin get() {
        return instance;
    }

    @Nonnull
    public BuildingCatalog getBuildingCatalog() {
        return buildingCatalog;
    }

    @Nonnull
    public PropCatalog getPropCatalog() {
        return propCatalog;
    }

    @Override
    protected void setup() {
        instance = this;
        Path dataDirectory = getDataDirectory();
        var serverEnvironment = com.hexvane.eterniamod.runtime.ServerSettings.load(dataDirectory, System.getenv());
        getLogger().atInfo().log("Eternia settings: %s; database connection from %s",
            dataDirectory.resolve(com.hexvane.eterniamod.runtime.ServerSettings.FILE_NAME).toAbsolutePath(),
            com.hexvane.eterniamod.runtime.ServerSettings.source("ETERNIA_DATABASE_URL", System.getenv(), serverEnvironment));
        runtime = new com.hexvane.eterniamod.runtime.EterniaRuntime(dataDirectory,
            com.hexvane.eterniamod.runtime.RuntimeConfig.from(serverEnvironment));
        infrastructure = new com.hexvane.eterniamod.housing.HousingInfrastructure(dataDirectory.resolve("housing-infrastructure.json"));
        try { infrastructure.load(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        claims = new com.hexvane.eterniamod.housing.NativeClaimCoordinator(this);
        travel = new com.hexvane.eterniamod.runtime.NativeTravelService(this);
        relocation = new com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator(this);
        plotMover = new com.hexvane.eterniamod.housing.VoluntaryPlotMover(this);
        com.hexvane.eterniamod.inventory.InventoryReceipts.register(getEntityStoreRegistry());
        itemEscrow = new com.hexvane.eterniamod.inventory.NativeItemEscrow(this);
        buildingCatalog = BuildingCatalog.load(getClass().getClassLoader(), dataDirectory.resolve("Buildings"));
        propCatalog = PropCatalog.load(getClass().getClassLoader(), dataDirectory.resolve("Props"));
        registerModCommonAssetDelivery();
        getCommandRegistry().registerCommand(new EterniaModCommand());
        BuildingPlacementBootstrap.register(this);
        PropPlacementBootstrap.register(this);
        boundaryFog = com.hexvane.eterniamod.boundary.BoundaryBootstrap.register(this,
            (world,x,z) -> infrastructure.protectedColumn(world.getName(),x,z));
        com.hexvane.eterniamod.housing.HousingProtection.register(this);
        lifecycle = new com.hexvane.eterniamod.runtime.PlayerLifecycle(this);
        menuActions = new com.hexvane.eterniamod.runtime.NativeMenuActions(this);
        socialUi = com.hexvane.eterniamod.socialui.SocialUiBootstrap.register(this,getServices(),menuActions);
        collectionRuntime = com.hexvane.eterniamod.collections.CollectionBootstrap.register(this,getServices());
        gameplayAdapters = new com.hexvane.eterniamod.runtime.GameplayAdapters(this,serverEnvironment);
        customization = com.hexvane.eterniamod.customization.CustomizationBootstrap.register(this);
        try { com.hexvane.eterniamod.guildroads.GuildRoads.startup(this); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        try { com.hexvane.eterniamod.setup.paving.RoadPaving.startup(this); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        try { com.hexvane.eterniamod.pathtool.SplineRoadTool.startup(this); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        try { managedHubServices = com.hexvane.eterniamod.setup.ManagedHubServices.register(this); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        try { discoveries = new com.hexvane.eterniamod.discovery.DiscoveryBootstrap(this,getServices(),infrastructure,dataDirectory.resolve("discoveries.json")); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        com.hexvane.eterniamod.runtime.NativeSmoke.register(this);
        departures = new com.hexvane.eterniamod.housing.relocation.GuildDepartureWorker(this,
            error -> getLogger().atWarning().withCause(error).log("Guild property return needs recovery"));
        departures.start();
        try { runtime.startBridge(gameplayAdapters.webhookHandler(),gameplayAdapters.tebexPackages()); } catch (java.io.IOException e) { runtime.close(); throw new java.io.UncheckedIOException(e); }
        getLogger().atInfo().log("EterniaMod v%s loaded.", getManifest().getVersion().toString());
    }

    @Override
    protected void shutdown() {
        if (boundaryFog != null) boundaryFog.close();
        if (socialUi != null) socialUi.close();
        if (departures != null) departures.close();
        if (plotMover != null) plotMover.close();
        com.hexvane.eterniamod.guildroads.GuildRoads.close();
        com.hexvane.eterniamod.setup.paving.RoadPaving.close();
        com.hexvane.eterniamod.pathtool.SplineRoadTool.close();
        if (managedHubServices != null) try { managedHubServices.close(); }
        catch (Exception e) { getLogger().atWarning().withCause(e).log("Managed Hub services could not close cleanly"); }
        if (customization != null) customization.close();
        if (discoveries != null) discoveries.close();
        if (collectionRuntime != null) collectionRuntime.close();
        if (gameplayAdapters != null) gameplayAdapters.close();
        if (lifecycle != null) lifecycle.close();
        if (runtime != null) runtime.close();
        instance = null;
    }

    @Override
    protected void start() {
        if (!getManifest().includesAssetPack()) {
            return;
        }
        String packId = new PluginIdentifier(getManifest()).toString();
        AssetPack pack = AssetModule.get().getAssetPack(packId);
        if (pack == null) {
            getLogger().atWarning().log("Asset pack %s not found in AssetModule", packId);
            return;
        }
        HytaleServer.get()
            .getEventBus()
            .<Void, AssetPackRegisterEvent>dispatchFor(AssetPackRegisterEvent.class)
            .dispatch(new AssetPackRegisterEvent(pack));
        CommonAssetModule commonAssets = CommonAssetModule.get();
        if (commonAssets != null) {
            commonAssets.loadCommonAssets(pack, System.nanoTime());
            if (Universe.get().getPlayerCount() > 0) {
                Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
            }
        }
        Path housePrefab = PrefabResolveUtil.resolvePrefabPath("House.prefab.json");
        var catalogErrors=com.hexvane.eterniamod.catalog.CatalogAssetValidator.validateResolvedPrefabs(buildingCatalog,propCatalog);
        if(!catalogErrors.isEmpty())throw new IllegalStateException("Invalid Eternia catalog assets: "+String.join("; ",catalogErrors));
        com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().whenComplete((unused,failure)->{
            if(failure!=null)getLogger().atSevere().withCause(failure).log("Housing world policy could not be applied to an already loaded world");
        });
        if (housePrefab == null) {
            getLogger().atWarning().log("House.prefab.json was not resolved — building placement preview will not work");
        } else {
            getLogger().atInfo().log("Resolved House.prefab.json at %s", housePrefab);
        }
    }

    /**
     * Ensures joining clients receive this mod's {@code Common/} blobs (UI layout, icons). Without this, the client
     * may never acknowledge the custom page and server-side UI events (birds-eye toggle, nudge buttons) are ignored.
     */
    private void registerModCommonAssetDelivery() {
        if (!getManifest().includesAssetPack()) {
            return;
        }
        getEventRegistry()
            .registerAsyncGlobal(
                EventPriority.LAST,
                SendCommonAssetsEvent.class,
                future -> future.thenApply(this::pushModCommonAssetsToJoiningClient)
            );
    }

    @Nonnull
    private SendCommonAssetsEvent pushModCommonAssetsToJoiningClient(@Nonnull SendCommonAssetsEvent event) {
        CommonAssetModule module = CommonAssetModule.get();
        if (module == null) {
            return event;
        }
        String packId = new PluginIdentifier(getManifest()).toString();
        List<CommonAsset> packAssets = CommonAssetRegistry.getCommonAssetsStartingWith(packId, "");
        if (packAssets.isEmpty()) {
            getLogger()
                .atWarning()
                .log(
                    "No common assets registered for pack %s — BuildingPlacementPage UI may not work. Rebuild and restart the server.",
                    packId
                );
            return event;
        }
        module.sendAssetsToPlayer(event.getPacketHandler(), packAssets, true);
        com.hexvane.eterniamod.placement.BuildingPlacementDebugLog.commonAssetsPushed(packAssets.size(), packId);
        return event;
    }
}
