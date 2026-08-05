package com.hexvane.eterniamod;

import com.hexvane.eterniamod.bootstrap.BuildingPlacementBootstrap;
import com.hexvane.eterniamod.building.BuildingCatalog;
import com.hexvane.eterniamod.command.EterniaModCommand;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.AssetPackRegisterEvent;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class EterniaModPlugin extends JavaPlugin {
    private static EterniaModPlugin instance;
    private BuildingCatalog buildingCatalog = BuildingCatalog.loadFromClasspath(getClass().getClassLoader());

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

    @Override
    protected void setup() {
        instance = this;
        buildingCatalog = BuildingCatalog.loadFromClasspath(getClass().getClassLoader());
        getCommandRegistry().registerCommand(new EterniaModCommand());
        BuildingPlacementBootstrap.register(this);
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
    }
}
