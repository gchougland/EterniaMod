package com.hexvane.eterniamod.production;

import com.hexvane.eterniamod.production.ProductionRuntime.Station;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;

/**
 * The talent choice, a screen of its own: when a station reaches a milestone it opens with three large cards, one per
 * path, and the player keeps one. Closing it without choosing is fine; it opens again the next time the station's
 * screen is opened, for as long as a card is owed.
 */
final class ProductionTalentPage extends EterniaInteractiveCustomUIPage<ProductionTalentPage.Data> {
    private static final String[] PATH_NAMES = {"Bounty", "Fortune", "Vigor"};
    private static final String[] PATH_ITEMS = {"Furniture_Village_Crate", "Ore_Gold", "Tool_Pickaxe_Copper"};
    private static final String[] PATH_NOTES = {"More goods and bigger stockpiles", "Veins and rare finds", "Working the station yourself"};

    private final ProductionRuntime runtime;
    private final Station station;
    private List<Profession.Card> dealt = List.of();

    private ProductionTalentPage(ProductionRuntime runtime, PlayerRef player, Station station) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.runtime = runtime;
        this.station = station;
    }

    /** True when {@code station} owes its owner a card. */
    static boolean owed(ProductionRuntime runtime, Station station) {
        synchronized (station) { return ProductionMath.talentPicksOwed(runtime.rules, station.row.progress) > 0; }
    }

    /** Opens the choice with its fanfare. World thread. */
    static void open(ProductionRuntime runtime, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Station station) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        component.getPageManager().openCustomPage(ref, store, new ProductionTalentPage(runtime, player, station));
        ProductionActions.sound(player, "SFX_Discovery_Z1_Medium");
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/ProductionTalent.ui");
        var r = runtime.rules;
        Profession p = runtime.profession(station);
        int owed, milestone, tier, level;
        int[] held = new int[3];
        synchronized (station) {
            StationProgress s = station.row.progress;
            owed = ProductionMath.talentPicksOwed(r, s);
            milestone = s.talents.size() + 1;
            tier = ProductionMath.tier(r, s.level);
            level = milestone <= r.milestones().length ? r.milestones()[milestone - 1] : s.level;
            for (int i = 0; i < 3; i++) held[i] = Professions.held(p, s, Profession.PATHS.get(i));
        }
        dealt = p.deal(milestone);
        c.set("#TcHeadItem.ItemId", p.good(Math.min(milestone + 1, p.goods().size())).item());
        c.set("#TcTitle.TextSpans", Message.raw(p.station()));
        c.set("#TcKicker.TextSpans", Message.raw(p.name() + "  ·  Tier " + tier));
        c.set("#TcHeadline.TextSpans", Message.raw("Level " + level + " reached. Choose a talent!"));
        c.set("#TcSub.TextSpans", Message.raw("Keep one of these three cards. It stays with this station."
            + (owed > 1 ? " You have " + (owed - 1) + " more to choose after this one." : "")));
        for (int i = 0; i < 3; i++) {
            String card = "#TcCard" + i;
            boolean shown = i < dealt.size();
            c.set(card + ".Visible", shown);
            if (!shown) continue;
            var deal = dealt.get(i);
            int path = Math.max(0, Profession.PATHS.indexOf(deal.path()));
            c.set(card + " #TcItem.ItemId", PATH_ITEMS[path]);
            c.set(card + " #TcPath.TextSpans", Message.raw(PATH_NAMES[path]));
            c.set(card + " #TcName.TextSpans", Message.raw(deal.name()));
            c.set(card + " #TcText.TextSpans", Message.raw(deal.text()));
            int after = held[path] + 1;
            c.set(card + " #TcSet.TextSpans", Message.raw(after >= r.setSize() && held[path] < r.setSize() ? "Completes the " + PATH_NAMES[path] + " set!"
                : PATH_NAMES[path] + " card " + after + (after <= r.setSize() ? " of " + r.setSize() : "")));
            c.set(card + " #TcSetNote.TextSpans", Message.raw(PATH_NOTES[path] + ". Three cards of one path give a set bonus."));
            e.addEventBinding(CustomUIEventBindingType.Activating, card + " #TcTake", new EventData().append("Action", "Pick:" + deal.id()), false);
        }
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Close", new EventData().append("Action", "Close"), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        var world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            if (data.action.equals("Close")) { ProductionStationPage.openScreen(runtime, ref, store, playerRef, station, true); return; }
            if (!data.action.startsWith("Pick:")) return;
            String id = data.action.substring(5);
            String name = dealt.stream().filter(card -> card.id().equals(id)).map(Profession.Card::name).findFirst().orElse("");
            if (!runtime.pick(station, id)) { rebuild(); return; }
            ProductionActions.sound(playerRef, "SFX_Discovery_Z1_Short");
            ProductionHud.banner(playerRef, "TALENT KEPT", name, 3);
            runtime.visuals.celebrate(world, station);
            // More cards owed (several milestones at once): deal the next three. Otherwise back to the station.
            if (owed(runtime, station)) rebuild();
            else ProductionStationPage.openScreen(runtime, ref, store, playerRef, station, true);
        });
    }

    public static final class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add().build();
    }
}
