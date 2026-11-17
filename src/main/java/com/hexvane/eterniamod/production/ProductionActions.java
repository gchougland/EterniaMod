package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.production.ProductionMath.Haul;
import com.hexvane.eterniamod.production.ProductionMath.Piece;
import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3d;

/**
 * What happens when a player uses a station, and how it answers back: goods flying out one at a time with a rising
 * note, the haul counter, banners for rare finds and golden moments. World thread.
 */
final class ProductionActions {
    /** What flies out for a sack, a crate and a chest. All vanilla items. */
    private static final String[] BUNDLES = {null, "Furniture_Ancient_Sack", "Furniture_Village_Crate", "Rock_Gold_Brick"};
    static final int NOTES = 12;

    private ProductionActions() {}

    static void use(ProductionRuntime runtime, World world, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station s, String action) {
        boolean manages = runtime.manages(world, s, player.getUuid());
        if (s.golden > 0 && manages) { golden(runtime, world, ref, store, player, s); return; }
        switch (action) {
            case "WORK" -> { if (manages) work(runtime, ref, store, player, s); else notOwner(player); }
            case "COLLECT" -> { if (manages) collect(runtime, world, ref, store, player, s); else notOwner(player); }
            default -> ProductionStationPage.open(runtime, ref, store, player, s, manages);
        }
    }

    private static void notOwner(PlayerRef player) {
        ProductionHud.banner(player, "NOT YOUR STATION", "Only its owner can do that", 2.5);
    }

    // ---- hand work ----

    private static void work(ProductionRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station s) {
        Profession p = runtime.profession(s);
        double goods = runtime.work(s);
        if (goods <= 0) {
            ProductionHud.banner(player, "OUT OF ENERGY", "Your arms need a rest until tomorrow", 2.5);
            return;
        }
        double rush;
        int tier;
        synchronized (s) { rush = s.row.progress.rush; tier = ProductionMath.tier(runtime.rules, s.row.progress.level); }
        var marks = runtime.visuals.marks(s);
        Vector3d from = marks.vein() == null ? runtime.visuals.pitAt(s) : new Vector3d(s.x + marks.vein().x + 0.5, s.y + marks.vein().y + 1.1, s.z + marks.vein().z + 0.5);
        mote(store, ref, p.good(tier).item(), from, 0.35f);
        ParticleUtil.spawnParticleEffect("Block_Break_Dust", from, store);
        note(player, 1 + (int) Math.round(rush * (NOTES - 1)));
        ProductionHud.rush(player, rush, runtime.energyLeft(s));
        ProductionHud.haul(player, "+" + ProductionMath.format(goods), p.unit() + " by hand", "", 1.2);
    }

    // ---- golden moments ----

    private static void golden(ProductionRuntime runtime, World world, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station s) {
        String kind = runtime.catchGolden(s);
        if (kind == null) return;
        Vector3d at = runtime.visuals.heapAt(s).add(0, 0.8, 0);
        ParticleUtil.spawnParticleEffect("Teleport", at, store);
        ParticleUtil.spawnParticleEffect(StationVisuals.SPARKLE, at, store);
        sound(player, "SFX_Discovery_Z1_Medium");
        switch (kind) {
            case "windfall" -> ProductionHud.banner(player, "GOLDEN MOMENT", "Windfall! Ten minutes of work at once", 4);
            case "frenzy" -> ProductionHud.banner(player, "GOLDEN MOMENT", "Frenzy! Seven times the output", 4);
            default -> ProductionHud.banner(player, "GOLDEN MOMENT", "Lucky find! Something rare is in the stockpile", 4);
        }
    }

    // ---- collecting ----

    private static void collect(ProductionRuntime runtime, World world, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station s) {
        Profession p = runtime.profession(s);
        Set<String> known;
        synchronized (s) { known = new HashSet<>(s.row.progress.finds); }
        var collected = runtime.collect(s);
        if (collected == null) {
            ProductionHud.banner(player, p.station().toUpperCase(), "Nothing to collect yet", 2);
            return;
        }
        Haul haul = collected.haul();
        List<Piece> pieces = haul.pieces();
        Vector3d base = runtime.visuals.heapAt(s);
        ParticleUtil.spawnParticleEffect("Block_Break_Dust", base, store);
        long at = 0;
        double running = 0;
        int n = pieces.size();
        for (int i = 0; i < n; i++) {
            Piece piece = pieces.get(i);
            running += piece.value();
            double total = running;
            int index = i;
            // Slow at first so each good is seen, then faster, so a big haul builds like a drum roll.
            at += n <= 1 ? 0 : Math.round(170 - 115.0 * i / (n - 1));
            later(world, ref, at, () -> {
                double angle = index * 2.39996, reach = 0.35 + 0.05 * (index % 5);
                Vector3d from = new Vector3d(base).add(Math.cos(angle) * reach, 0.5 + 0.9 * (index % 4) / 3.0, Math.sin(angle) * reach);
                String item = piece.size() == 0 ? p.good(piece.tier()).item() : BUNDLES[piece.size()];
                mote(store, ref, item, from, 0.5f);
                note(player, 1 + Math.min(NOTES - 1, index * NOTES / Math.max(1, n)));
                ProductionHud.haul(player, "+" + ProductionMath.format(total), "", "", 3);
            });
        }
        for (String findId : haul.finds()) {
            var find = p.find(findId);
            if (find == null) continue;
            at += 650;
            boolean fresh = !known.contains(findId);
            later(world, ref, at, () -> {
                Vector3d from = new Vector3d(base).add(0, 1.6, 0);
                ParticleUtil.spawnParticleEffect(StationVisuals.SPARKLE, from, store);
                mote(store, ref, find.item(), from, 0.9f);
                sound(player, "SFX_Discovery_Z1_Short");
                ProductionHud.banner(player, fresh ? "NEW RARE FIND" : "RARE FIND", find.name(), 3.5);
            });
        }
        String note = notes(haul, collected.streak(), collected.sealsPaid());
        later(world, ref, at + 350, () -> {
            ProductionHud.haul(player, "+" + ProductionMath.format(haul.amount()), note, ProductionMath.haulWord(haul), 4);
            sound(player, haul.record() || haul.fill() >= 0.9 ? "SFX_Discovery_Z1_Medium" : "SFX_Discovery_Z1_Short");
            if (haul.record()) {
                ParticleUtil.spawnParticleEffect("Teleport", new Vector3d(base).add(0, 2.5, 0), store);
                ParticleUtil.spawnParticleEffect(StationVisuals.SPARKLE, new Vector3d(base).add(0, 3, 0), store);
            }
        });
    }

    private static String notes(Haul haul, int streak, int seals) {
        var parts = new java.util.ArrayList<String>();
        if (haul.fullCart() >= 0.005) parts.add("Full Cart +" + Math.round(haul.fullCart() * 100) + "%");
        if (haul.streak() > 0) parts.add("Day " + streak + " streak +" + Math.round(haul.streak() * 100) + "%");
        if (haul.doubled()) parts.add("First collect doubled");
        if (seals > 0) parts.add("Order done, +" + seals + (seals == 1 ? " Seal" : " Seals"));
        return String.join("   ", parts);
    }

    // ---- the small pieces ----

    /** A good that flies from {@code from} to the player. It is only a picture: nothing lands in the inventory. */
    static void mote(Store<EntityStore> store, Ref<EntityStore> player, String item, Vector3d from, float seconds) {
        if (!player.isValid()) return;
        var holder = ItemComponent.generatePickedUpItem(new ItemStack(item), from, store, player);
        var pickup = holder.getComponent(PickupItemComponent.getComponentType());
        if (pickup != null) pickup.setInitialLifeTime(seconds);
        store.addEntity(holder, AddReason.SPAWN);
    }

    /** One of the twelve rising notes, heard only by this player. */
    static void note(PlayerRef player, int step) {
        sound(player, "SFX_Eternia_Haul_" + String.format("%02d", Math.clamp(step, 1, NOTES)));
    }

    static void sound(PlayerRef player, String event) {
        int index = SoundEvent.getAssetMap().getIndex(event);
        if (index != SoundEvent.EMPTY_ID && index != Integer.MIN_VALUE) SoundUtil.playSoundEvent2dToPlayer(player, index, SoundCategory.UI);
    }

    static void later(World world, Ref<EntityStore> player, long millis, Runnable work) {
        Runnable guarded = () -> world.execute(() -> { if (player.isValid()) work.run(); });
        if (millis <= 0) guarded.run();
        else HytaleServer.SCHEDULED_EXECUTOR.schedule(guarded, millis, TimeUnit.MILLISECONDS);
    }
}
