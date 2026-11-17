package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * The production HUD: the haul counter while goods fly, a banner for big moments, the Rush meter while working by
 * hand, and a small tracker of the plot's stations. It shows only what is happening and goes away on its own.
 * World thread only.
 */
final class ProductionHud extends CustomUIHud {
    static final String ID = "eternia:production";
    static final int SLOTS = 6, BAR = 216, RUSH_BAR = 232;
    private static final Map<UUID, View> VIEWS = new ConcurrentHashMap<>();
    private String sent = "";

    /** What one player should see right now. Times are {@link System#currentTimeMillis()} deadlines, 0 for hidden. */
    static final class View {
        String haulNumber = "", haulNote = "", haulWord = "";
        long haulUntil;
        String bannerTitle = "", bannerText = "";
        long bannerUntil;
        double rush;
        int energy;
        long rushUntil;
        List<Slot> slots = List.of();
    }

    record Slot(String name, String state, double fill, boolean full) {}

    private ProductionHud(PlayerRef player) { super(player, ID, 0); }

    @Override
    protected void build(@Nonnull UICommandBuilder commands) { commands.append("EterniaMod/ProductionHud.ui"); }

    static View view(UUID player) { return VIEWS.computeIfAbsent(player, id -> new View()); }

    static void forget(UUID player) { VIEWS.remove(player); }

    static void haul(PlayerRef player, String number, String note, String word, double seconds) {
        View v = view(player.getUuid());
        v.haulNumber = number; v.haulNote = note; v.haulWord = word;
        v.haulUntil = System.currentTimeMillis() + (long) (seconds * 1000);
        push(player);
    }

    static void banner(PlayerRef player, String title, String text, double seconds) {
        View v = view(player.getUuid());
        v.bannerTitle = title; v.bannerText = text;
        v.bannerUntil = System.currentTimeMillis() + (long) (seconds * 1000);
        push(player);
    }

    static void rush(PlayerRef player, double rush, int energy) {
        View v = view(player.getUuid());
        v.rush = rush; v.energy = energy;
        v.rushUntil = System.currentTimeMillis() + 6000;
        push(player);
    }

    /** Every refresh: the tracker for players standing on a plot whose stations they look after, and expiry of the rest. */
    static void refresh(ProductionRuntime runtime, World world) {
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, runtime.plugin);
        for (PlayerRef player : world.getPlayerRefs()) {
            var position = player.getTransform().getPosition();
            var plot = manager.plotAtColumn((int) Math.floor(position.x), (int) Math.floor(position.z));
            var slots = new ArrayList<Slot>();
            if (plot != null) for (Station s : runtime.stationsOf(plot.getPlotId())) {
                if (slots.size() >= SLOTS || !runtime.manages(world, s, player.getUuid())) continue;
                Profession p = runtime.profession(s);
                synchronized (s) {
                    var mods = runtime.mods(s);
                    double cap = ProductionMath.cap(runtime.rules, p, s.row.progress, mods);
                    double fill = cap <= 0 ? 0 : Math.clamp(s.row.progress.stockpile / cap, 0, 1);
                    boolean full = s.row.progress.full;
                    String state = s.golden > 0 ? "Golden moment" : full ? "Full" : s.row.progress.veinLeft > 0 ? "Vein struck"
                        : (int) Math.floor(fill * 100) + "%";
                    slots.add(new Slot(p.station(), state, fill, full || s.golden > 0));
                    View v = view(player.getUuid());
                    if (v.rushUntil > System.currentTimeMillis()) v.rush = s.row.progress.rush;
                }
            }
            View v = view(player.getUuid());
            v.slots = slots;
            push(player);
        }
    }

    private static void push(PlayerRef player) {
        var ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Player component = ref.getStore().getComponent(ref, Player.getComponentType());
        if (component == null) return;
        View v = view(player.getUuid());
        long now = System.currentTimeMillis();
        boolean haul = v.haulUntil > now, banner = v.bannerUntil > now, rush = v.rushUntil > now;
        var huds = component.getHudManager();
        ProductionHud hud = huds.getCustomHud(ID) instanceof ProductionHud existing ? existing : null;
        if (!haul && !banner && !rush && v.slots.isEmpty()) {
            if (hud != null) { huds.removeCustomHud(player, ID); }
            return;
        }
        if (hud == null) { hud = new ProductionHud(player); huds.addCustomHud(player, hud); }
        String signature = (haul ? v.haulNumber + "|" + v.haulNote + "|" + v.haulWord : "") + "#" + (banner ? v.bannerTitle + "|" + v.bannerText : "")
            + "#" + (rush ? Math.round(v.rush * 20) + "|" + v.energy : "") + "#" + v.slots;
        if (signature.equals(hud.sent)) return;
        hud.sent = signature;
        hud.update(false, commands(v, haul, banner, rush));
    }

    /** Package private for the render check. */
    static UICommandBuilder commands(View v, boolean haul, boolean banner, boolean rush) {
        var c = new UICommandBuilder();
        c.set("#ProdHaul.Visible", haul);
        if (haul) {
            c.set("#HaulNumber.TextSpans", Message.raw(v.haulNumber));
            c.set("#HaulNote.TextSpans", Message.raw(v.haulNote));
            c.set("#HaulWord.TextSpans", Message.raw(v.haulWord));
        }
        c.set("#ProdBanner.Visible", banner);
        if (banner) {
            c.set("#BannerTitle.TextSpans", Message.raw(v.bannerTitle));
            c.set("#BannerText.TextSpans", Message.raw(v.bannerText));
        }
        c.set("#ProdRush.Visible", rush);
        if (rush) {
            c.set("#RushEnergy.TextSpans", Message.raw("Energy " + v.energy));
            c.setObject("#RushFill.Anchor", UiAnchors.size((int) Math.round(RUSH_BAR * Math.clamp(v.rush, 0, 1)), 8));
        }
        for (int i = 0; i < SLOTS; i++) {
            String slot = "#Slot" + i;
            boolean on = i < v.slots.size();
            c.set(slot + ".Visible", on);
            if (!on) continue;
            Slot s = v.slots.get(i);
            c.set(slot + " #Name.TextSpans", Message.raw(s.name()));
            c.set(slot + " #State.TextSpans", Message.raw(s.state()).color(s.full() ? "#d8b66b" : "#b5c3b8"));
            int width = (int) Math.round(BAR * s.fill());
            c.setObject(slot + " #Fill.Anchor", UiAnchors.size(s.full() ? 0 : width, 6));
            c.setObject(slot + " #FillFull.Anchor", UiAnchors.size(s.full() ? Math.max(width, s.full() && s.fill() < 0.05 ? BAR : width) : 0, 6));
        }
        return c;
    }
}
