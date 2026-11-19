package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.claimmap.ClaimMapAssets;
import com.hexvane.eterniamod.claimmap.ClaimMapGeometry;
import com.hexvane.eterniamod.claimmap.ClaimMapPage;
import com.hexvane.eterniamod.claimmap.ClaimMapRenderer;
import com.hexvane.eterniamod.claimmap.ClaimMapScreen;
import com.hexvane.eterniamod.claimmap.ClaimMapTiles;
import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.WildLandService;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The land map in adventure worlds: the same picture and buttons as the housing Claim Map, for plain block claims.
 * Click an open hexagon to claim it or add it to your land, click one of yours to give it up, and switch to the guild
 * tab to do the same for your guild. Every field is touched on the world thread.
 */
public final class WildMapPage extends EterniaInteractiveCustomUIPage<ClaimMapPage.Data> {
    private static final ExecutorService PAINTERS = Executors.newFixedThreadPool(1, Thread.ofPlatform().daemon(true).name("eternia-land-map-", 0).factory());
    /** Rows of the legend that only mean something for housing plots. */
    private static final List<String> HOUSING_ROWS = List.of("#LegendFrontageRow", "#LegendHouseRow", "#LegendStreetRow", "#LegendProductionRow");

    private final EterniaModPlugin plugin;
    private final World world;
    private ClaimMapGeometry wanted, shown, tilesAsked;
    private HexCoord selected;
    private boolean guildTab, confirming, working, resultOk, painting, paintAgain;
    private Message result;
    private String picture;
    private int pictureSlot;
    private long pictureRequest;
    private Runnable stopListening;
    private UUID guild;
    private String guildName;
    private boolean mayManageGuild;
    private long myCoins, guildCoins;

    private WildMapPage(PlayerRef player, EterniaModPlugin plugin, World world, ClaimMapGeometry window, boolean guildTab) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ClaimMapPage.Data.CODEC);
        this.plugin = plugin;
        this.world = world;
        this.wanted = window;
        this.guildTab = guildTab;
    }

    /** Opens the land map centred on the player. False when land cannot be claimed in this world. World thread. */
    public static boolean open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, boolean guildTab) {
        World world = store.getExternalData().getWorld();
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null || !WildProtection.covered(plugin, world)) return false;
        double[] at = position(ref, store);
        var window = ClaimMapGeometry.centredOn(WildLandIndex.lattice(), at[0], at[1]);
        component.getPageManager().openCustomPage(ref, store, new WildMapPage(player, plugin, world, window, guildTab));
        return true;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        ClaimMapScreen.open(c, wanted);
        bindHome(e);
        showWallet(c, ref, store);
        for (ClaimMapScreen.Binding b : ClaimMapScreen.ACTIONS) e.addEventBinding(CustomUIEventBindingType.Activating, b.selector(), new EventData().append("Action", b.action()), false);
        for (ClaimMapScreen.Binding b : ClaimMapScreen.cellBindings()) {
            e.addEventBinding(CustomUIEventBindingType.Activating, b.selector(), new EventData().append("Action", b.action()).append("Slot", b.slot()), false);
            e.addEventBinding(CustomUIEventBindingType.MouseEntered, b.selector(), new EventData().append("Action", "Hover").append("Slot", b.slot()), false);
            e.addEventBinding(CustomUIEventBindingType.MouseExited, b.selector(), new EventData().append("Action", "Unhover").append("Slot", b.slot()), false);
        }
        c.set("#MapTitle.TextSpans", Message.raw("Land map"));
        for (String row : HOUSING_ROWS) c.set(row + ".Visible", false);
        c.set("#LegendMine.TextSpans", Message.raw("Your land"));
        c.set("#LegendMyGuild.TextSpans", Message.raw("Your guild's land"));
        c.set("#LegendReserved.TextSpans", Message.raw("Too close to others"));
        c.set("#LegendOtherPlayer.TextSpans", Message.raw("Another player's land"));
        c.set("#LegendOtherGuild.TextSpans", Message.raw("Another guild's land"));
        c.set("#LegendFree.TextSpans", Message.raw("Open land"));
        ClaimMapScreen.show(c, WildMapLogic.panel(view(), shown, picture, true));
        if (stopListening == null) stopListening = WildLand.listen(this::landChanged);
        loadAccount(ref);
        paint(ref, store);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, ClaimMapPage.Data data) {
        if (data.action == null || isDismissed()) return;
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            try {
                act(ref, store, data);
            } catch (RuntimeException failure) {
                plugin.getLogger().atWarning().withCause(failure).log("Land map action failed");
                working = false;
                confirming = false;
                result = Message.raw(WildRules.Reason.CHANGED.text(0, null));
                resultOk = false;
                refreshPanel();
            }
        });
    }

    private void act(Ref<EntityStore> ref, Store<EntityStore> store, ClaimMapPage.Data data) {
        switch (data.action) {
            case "Hover", "Unhover" -> { hover(data.action.equals("Hover"), parse(data.slot)); return; }
            case "Close" -> { close(); return; }
            case "Cell" -> {
                if (working || shown == null) return;
                HexCoord cell = shown.cellOfSlot(parse(data.slot));
                if (cell == null) return;
                selected = cell;
                confirming = false;
                result = null;
            }
            case "PanNorth" -> pan(ref, store, 0, -ClaimMapGeometry.PAN_ROWS);
            case "PanSouth" -> pan(ref, store, 0, ClaimMapGeometry.PAN_ROWS);
            case "PanWest" -> pan(ref, store, -ClaimMapGeometry.PAN_COLUMNS, 0);
            case "PanEast" -> pan(ref, store, ClaimMapGeometry.PAN_COLUMNS, 0);
            case "Recentre" -> {
                double[] at = position(ref, store);
                wanted = ClaimMapGeometry.centredOn(wanted.lattice(), at[0], at[1]);
                paint(ref, store);
            }
            case "ScopeMine", "ScopeGuild" -> {
                boolean next = data.action.equals("ScopeGuild");
                if (working || next == guildTab) return;
                guildTab = next;
                confirming = false;
                result = null;
                paint(ref, store);
            }
            case "Back" -> {
                if (confirming) { confirming = false; break; }
                var mine = view().mine();
                if (mine != null) { WildFriendsMenu.open(plugin, ref, store, playerRef, mine.id()); return; }
            }
            case "Claim" -> {
                if (working) return;
                var v = view();
                var action = WildMapLogic.action(v);
                if (action == WildMapLogic.Action.NONE) return;
                if (action == WildMapLogic.Action.ADD && WildMapLogic.addReason(v) != null) return;
                if (!confirming) { confirming = true; result = null; }
                else commit(ref, store, v, action);
            }
            default -> { return; }
        }
        refreshPanel();
    }

    private void commit(Ref<EntityStore> ref, Store<EntityStore> store, WildMapLogic.View v, WildMapLogic.Action action) {
        working = true;
        confirming = false;
        result = null;
        HexCoord cell = selected;
        UUID me = playerRef.getUuid();
        var future = action == WildMapLogic.Action.ADD
            ? WildLand.add(plugin, world.getName(), v.owner(), me, cell)
            : WildLand.release(plugin, world.getName(), v.mine().id(), me, cell);
        future.whenComplete((done, failure) -> world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            working = false;
            if (failure != null || done == null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null ? ce.getCause() : failure;
                result = Message.raw(cause instanceof com.hexvane.eterniamod.domain.DomainException d && d.getMessage() != null ? d.getMessage() : WildRules.Reason.CHANGED.text(0, null));
                resultOk = false;
            } else if (!done.ok()) {
                result = Message.raw(done.message());
                resultOk = false;
            } else {
                resultOk = true;
                result = Message.raw(action == WildMapLogic.Action.ADD
                    ? (done.coins() == 0 ? "It is yours now." : "It is yours now. You paid " + WildMapLogic.coins(done.coins()) + ".")
                    : (done.coins() == 0 ? "You gave it up." : "You gave it up and got back " + WildMapLogic.coins(done.coins()) + "."));
                selected = null;
            }
            loadAccount(ref);
            paint(ref, ref.getStore());
            refreshPanel();
        }));
    }

    private void landChanged(String changedWorld) {
        if (isDismissed() || changedWorld != null && !changedWorld.equals(world.getName())) return;
        try {
            world.execute(() -> {
                if (isDismissed()) return;
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) return;
                paint(ref, ref.getStore());
                refreshPanel();
            });
        } catch (RuntimeException closing) {
            // The world is shutting down.
        }
    }

    /** Your Coins, your guild, its Coins and whether your role may manage its land. Off the world thread. */
    private void loadAccount(Ref<EntityStore> ref) {
        var services = plugin.getServices();
        UUID me = playerRef.getUuid();
        WildLandIndex.forget(me);
        com.hexvane.eterniamod.socialui.SocialUiBootstrap.supply(services, () -> services.batch(() -> {
            Optional<GuildService.Standing> standing = services.guilds().standing(me);
            UUID g = standing.map(GuildService.Standing::guildId).orElse(null);
            String name = g == null ? null : services.guilds().find(g).map(GuildService.Guild::name).orElse(null);
            long mine = services.economy().balance(Owner.player(me)).available();
            long theirs = g == null ? 0 : services.economy().balance(Owner.guild(g)).available();
            boolean manage = standing.map(s -> s.capabilities().contains(WildLandService.MANAGE)).orElse(false);
            return new Account(g, name, mine, theirs, manage);
        })).whenComplete((loaded, failure) -> world.execute(() -> {
            if (isDismissed() || !ref.isValid() || loaded == null) return;
            guild = loaded.guild();
            guildName = loaded.guildName();
            myCoins = loaded.myCoins();
            guildCoins = loaded.guildCoins();
            mayManageGuild = loaded.manage();
            if (guild == null) guildTab = false;
            paint(ref, ref.getStore());
            refreshPanel();
        }));
    }

    private WildMapLogic.View view() {
        return new WildMapLogic.View(playerRef.getUuid(), guild, guildName, guildTab, mayManageGuild, WildLandIndex.world(world.getName()),
            WildLandSettings.current(), guildTab ? guildCoins : myCoins, WildLand.blocked(plugin, world.getName()), selected, confirming, working, result, resultOk);
    }

    private void paint(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (painting) { paintAgain = true; return; }
        painting = true;
        paintAgain = false;
        long request = ++pictureRequest;
        ClaimMapGeometry g = wanted;
        var v = view();
        double[] at = position(ref, store);
        var scene = new ClaimMapRenderer.Scene(g, WildMapLogic.states(v, g), WildMapLogic.plots(v, g), List.of(), at[0], at[1]);
        var maps = world.getWorldMapManager();
        var tiles = ClaimMapTiles.inMemory(maps, g);
        String packId = new PluginIdentifier(plugin.getManifest()).toString();
        PAINTERS.execute(() -> {
            byte[] png = null;
            try {
                png = ClaimMapRenderer.png(ClaimMapRenderer.render(scene, tiles.source()));
            } catch (RuntimeException failure) {
                plugin.getLogger().atWarning().withCause(failure).log("Land map picture failed");
            }
            byte[] done = png;
            world.execute(() -> {
                painting = false;
                if (isDismissed()) return;
                if (done != null && request == pictureRequest) {
                    String path = ClaimMapAssets.assetPath(playerRef.getUuid(), ++pictureSlot);
                    ClaimMapAssets.push(packId, playerRef, path, done);
                    world.execute(() -> {
                        if (isDismissed() || request != pictureRequest) return;
                        shown = g;
                        picture = path;
                        if (selected != null && !g.shows(selected)) selected = null;
                        refreshPanel();
                    });
                }
                Ref<EntityStore> live = playerRef.getReference();
                if (live == null || !live.isValid()) return;
                if (paintAgain) { paint(live, live.getStore()); return; }
                if (!tiles.missing().isEmpty() && !g.equals(tilesAsked)) {
                    tilesAsked = g;
                    ClaimMapTiles.request(maps, tiles.missing()).whenComplete((ignored, error) -> world.execute(() -> {
                        Ref<EntityStore> again = playerRef.getReference();
                        if (!isDismissed() && again != null && again.isValid() && g.equals(wanted)) paint(again, again.getStore());
                    }));
                }
            });
        });
    }

    private void pan(Ref<EntityStore> ref, Store<EntityStore> store, int columns, int rows) {
        wanted = wanted.panned(columns, rows);
        paint(ref, store);
    }

    private int hovered = -1;

    private void hover(boolean entered, int slot) {
        if (shown == null || isDismissed()) return;
        if (!entered && slot != hovered) return;
        hovered = entered ? slot : -1;
        HexCoord cell = entered ? shown.cellOfSlot(slot) : null;
        var c = new UICommandBuilder();
        ClaimMapScreen.hover(c, cell == null ? null : new ClaimMapScreen.Mark(shown.centre(cell).x(), shown.centre(cell).y()));
        sendUpdate(c, null, false);
    }

    private void refreshPanel() {
        if (isDismissed()) return;
        boolean loading = shown == null || painting && !wanted.equals(shown);
        var c = new UICommandBuilder();
        ClaimMapScreen.show(c, WildMapLogic.panel(view(), shown, picture, loading));
        sendUpdate(c, null, false);
    }

    private static double[] position(Ref<EntityStore> ref, Store<EntityStore> store) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null) return new double[] {0, 0};
        return new double[] {t.getPosition().x, t.getPosition().z};
    }

    private static int parse(String slot) {
        try { return slot == null ? -1 : Integer.parseInt(slot.trim()); } catch (NumberFormatException bad) { return -1; }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (stopListening != null) stopListening.run();
        stopListening = null;
        ClaimMapAssets.forget(new PluginIdentifier(plugin.getManifest()).toString(), playerRef.getUuid());
        super.onDismiss(ref, store);
    }

    private record Account(UUID guild, String guildName, long myCoins, long guildCoins, boolean manage) {}
}
