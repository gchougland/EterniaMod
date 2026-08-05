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
        buildingCatalog = BuildingCatalog.load(getClass().getClassLoader(), dataDirectory.resolve("Buildings"));
        propCatalog = PropCatalog.load(getClass().getClassLoader(), dataDirectory.resolve("Props"));
        registerModCommonAssetDelivery();
        getCommandRegistry().registerCommand(new EterniaModCommand());
        BuildingPlacementBootstrap.register(this);
        PropPlacementBootstrap.register(this);
        getLogger().atInfo().log("EterniaMod v%s loaded.", getManifest().getVersion().toString());
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
