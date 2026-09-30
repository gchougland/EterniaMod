package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.asset.type.model.BlockyModelBoundsParser;
import org.joml.Vector3d;

/** Uses Hytale's real transform/bounds parser after native common assets load. */
public final class NativeProwlSmoke {
    private NativeProwlSmoke() {}
    public static void validate(EterniaModPlugin plugin) {
        if(!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))||!plugin.getRuntimeConfig().local()||plugin.getRuntimeConfig().postgres())throw new IllegalStateException("Prowl acceptance requires isolated native smoke");
        // Fixed reference for the edited artwork approved on 2026-09-29, independently
        // calculated from its visible vertices (model units / 32). Do not derive these
        // from the live model: the check must detect collapsed or altered geometry.
        // The detailed named-node reference lives in src/test/resources/prowl.
        var expectedMin=new Vector3d(-0.7773400745777176,-0.029262638863902596,-0.468475);
        var expectedMax=new Vector3d(0.8193860527524979,3.8125,0.45057044079381275);
        var actual=BlockyModelBoundsParser.computeBounds("NPC/Eternia/Prowl/Prowl_PlayerRig.blockymodel");
        if(actual==null||expectedMin.distance(actual.min)>1e-4||expectedMax.distance(actual.max)>1e-4)throw new IllegalStateException("Prowl native bounds differ from approved artwork: min="+expectedMin+", max="+expectedMax+", rig="+actual);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_PROWL_BOUNDS_PASS: native parser matches approved Prowl artwork and proportions");
    }
}
