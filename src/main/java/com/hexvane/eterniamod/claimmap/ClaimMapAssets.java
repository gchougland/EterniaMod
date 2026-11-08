package com.hexvane.eterniamod.claimmap;

import com.hypixel.hytale.common.util.ArrayUtil;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.setup.AssetFinalize;
import com.hypixel.hytale.protocol.packets.setup.AssetInitialize;
import com.hypixel.hytale.protocol.packets.setup.AssetPart;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sends one player their claim map picture as a runtime common asset, the way Gibson's Aetherhaven sends its town
 * expansion thumbnails (proven on 0.7.x): register the PNG under the plugin's pack, write the asset packets straight
 * to that player, then ask the client to rebuild its common assets. {@code CommonAssetModule.sendAssetsToPlayer} is
 * not used: in game it sends a world load progress packet that disconnects the client.
 *
 * <p>Each player has two picture names used in turn, so the page's {@code AssetPath} always changes when a new
 * picture arrives and the old one is never shown by mistake. Both names are removed when the page closes.
 */
public final class ClaimMapAssets {
    private ClaimMapAssets() {}

    /** Common asset path of a player's picture, slot 0 or 1 (relative to {@code Common/}). */
    public static String assetPath(UUID player, int slot) {
        return "Icons/ItemsGenerated/EterniaMod_ClaimMap_" + player.toString().replace("-", "") + "_" + (slot & 1) + ".png";
    }

    /** Registers and sends the picture to this player only. Call on the player's world thread. */
    public static void push(String packId, PlayerRef player, String assetPath, byte[] png) {
        CommonAsset asset = new BytesAsset(assetPath, png);
        CommonAssetRegistry.addCommonAsset(packId, asset);
        PacketHandler handler = player.getPacketHandler();
        byte[][] parts = ArrayUtil.split(png, CommonAssetModule.MAX_FRAME);
        ToClientPacket[] packets = new ToClientPacket[2 + parts.length];
        packets[0] = new AssetInitialize(asset.toPacket(), png.length);
        for (int i = 0; i < parts.length; i++) packets[1 + i] = new AssetPart(parts[i]);
        packets[packets.length - 1] = new AssetFinalize();
        handler.write(packets);
        handler.writeNoCache(new RequestCommonAssetsRebuild());
    }

    /** Drops both of a player's pictures from the server's asset list (page closed). */
    public static void forget(String packId, UUID player) {
        for (int slot = 0; slot < 2; slot++) {
            try {
                CommonAssetRegistry.removeCommonAssetByName(packId, assetPath(player, slot));
            } catch (RuntimeException ignored) {
                // Never registered, or the server is shutting down.
            }
        }
    }

    private static final class BytesAsset extends CommonAsset {
        private final byte[] bytes;

        BytesAsset(String name, byte[] bytes) {
            super(name, bytes);
            this.bytes = bytes;
        }

        @Override
        protected CompletableFuture<byte[]> getBlob0() { return CompletableFuture.completedFuture(bytes); }
    }
}
