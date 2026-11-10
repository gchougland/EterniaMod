package com.hexvane.eterniamod.town;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.claimmap.ClaimMapAssets;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The planned town on one page: a map picture of the roads and bridges it would build, what it would do, and the
 * choice to build every road now or let the town grow as homes are claimed.
 */
public final class TownRoadPlanPage extends EterniaInteractiveCustomUIPage<TownRoadPlanPage.Data> {
    private static final AtomicInteger PICTURES = new AtomicInteger();
    private final TownRoadNetwork.Preview preview;
    private final World world;
    private final int lead;
    private String picture = "";

    private TownRoadPlanPage(PlayerRef player, World world, TownRoadNetwork.Preview preview, int lead) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.preview = preview;
        this.world = world;
        this.lead = lead;
    }

    /** Sends the plan picture, then opens the page once the client has it. World thread. */
    public static void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, World world, TownRoadNetwork.Preview preview, byte[] png, int lead) {
        var component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        var page = new TownRoadPlanPage(player, world, preview, lead);
        if (png != null) {
            page.picture = "Icons/ItemsGenerated/EterniaMod_TownPlan_" + player.getUuid().toString().replace("-", "") + "_" + (PICTURES.incrementAndGet() & 1) + ".png";
            ClaimMapAssets.push(packId(), player, page.picture, png);
        }
        // Open one tick later, after the client has the new picture.
        world.execute(() -> { if (ref.isValid()) component.getPageManager().openCustomPage(ref, store, page); });
    }

    private static String packId() { return new PluginIdentifier(EterniaModPlugin.get().getManifest()).toString(); }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/TownRoadPlanPage.ui");
        c.set("#Title.Text", preview.mode() == TownRoadNetwork.Mode.PORTAL ? "Place this portal town?" : "Build these town roads?");
        c.set("#Description.Text", preview.summary());
        c.set("#Map.AssetPath", picture);
        c.set("#BuildAll.Text", preview.mode() == TownRoadNetwork.Mode.PORTAL ? "Build the town and every road" : "Build every road now");
        c.set("#Grow.Text", preview.mode() == TownRoadNetwork.Mode.PORTAL ? "Build the town, grow the roads" : "Grow as homes are claimed");
        for (String action : new String[] {"BuildAll", "Grow", "Cancel", "Close"})
            e.addEventBinding(CustomUIEventBindingType.Activating, "#" + action, new EventData().append("Action", action), false);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            switch (data.action) {
                case "BuildAll", "Grow" -> {
                    close();
                    try {
                        TownRoadNetwork.build(EterniaModPlugin.get(), world, preview, data.action.equals("BuildAll"), lead, playerRef);
                        playerRef.sendMessage(Message.raw("Building. Progress appears in chat."));
                    } catch (RuntimeException failure) { playerRef.sendMessage(Message.raw(failure.getMessage())); }
                }
                default -> close();
            }
        });
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        super.onDismiss(ref, store);
        for (int slot = 0; slot < 2; slot++) {
            try { com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.removeCommonAssetByName(packId(), "Icons/ItemsGenerated/EterniaMod_TownPlan_" + playerRef.getUuid().toString().replace("-", "") + "_" + slot + ".png"); }
            catch (RuntimeException ignored) { /* Never sent. */ }
        }
    }

    public static final class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add().build();
    }
}
