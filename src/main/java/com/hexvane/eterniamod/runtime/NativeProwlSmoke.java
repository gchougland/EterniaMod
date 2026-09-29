package com.hexvane.eterniamod.runtime;

import com.google.gson.*;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.asset.common.*;
import com.hypixel.hytale.server.core.asset.type.model.BlockyModelBoundsParser;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Uses Hytale's real transform/bounds parser after native common assets load. */
public final class NativeProwlSmoke {
    private NativeProwlSmoke() {}
    public static void validate(EterniaModPlugin plugin) {
        if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||!plugin.getRuntimeConfig().local()||plugin.getRuntimeConfig().postgres())throw new IllegalStateException("Prowl acceptance requires isolated native smoke");
        var original=Objects.requireNonNull(CommonAssetRegistry.getByName("NPC/Eternia/Prowl/prowl_hytale.blockymodel"),"Missing original Prowl model");
        var reference=JsonParser.parseString(new String(original.getBlob().join(),StandardCharsets.UTF_8)).getAsJsonObject();
        for(var node:reference.getAsJsonArray("nodes"))retainVisibleDescendants(node.getAsJsonObject());
        byte[] bytes=reference.toString().getBytes(StandardCharsets.UTF_8);
        var referenceAsset=new CommonAsset("eternia-smoke/prowl-reference.blockymodel",bytes) {
            @Override protected CompletableFuture<byte[]> getBlob0(){return CompletableFuture.completedFuture(bytes);}
        };
        var expected=BlockyModelBoundsParser.computeBounds(referenceAsset);
        var actual=BlockyModelBoundsParser.computeBounds("NPC/Eternia/Prowl/Prowl_PlayerRig.blockymodel");
        if(expected==null||actual==null||expected.min.distance(actual.min)>1e-4||expected.max.distance(actual.max)>1e-4)throw new IllegalStateException("Prowl native bounds differ: reference="+expected+", rig="+actual);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_PROWL_BOUNDS_PASS: native parser matches original visible-descendant reference; source model remains untouched");
    }
    private static void retainVisibleDescendants(JsonObject node) {
        // The native bounds helper prunes an invisible shape's entire subtree.
        // Prowl has visible torso/head descendants under a hidden Belly mesh.
        // For this in-memory reference only, retain its transform as a shapeless
        // parent so the helper measures the meshes the renderer actually displays.
        var shape=node.getAsJsonObject("shape");
        if(shape!=null&&shape.has("visible")&&!shape.get("visible").getAsBoolean()) {
            shape.addProperty("type","none");shape.addProperty("visible",true);
        }
        if(node.has("children"))for(var child:node.getAsJsonArray("children"))retainVisibleDescendants(child.getAsJsonObject());
    }
}
