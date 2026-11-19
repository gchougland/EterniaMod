package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Account;
import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Mode;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hexvane.eterniamod.housing.PlotRect;
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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Claim Map: a picture of the land around the player with the hexagon grid on it. Click a hexagon to see what it
 * is; claim a start plot, add a hexagon to a plot, or set a packed home down, with a confirm step. Hex worlds only;
 * open it through {@link ClaimMapRoutes}.
 *
 * <p>Threads: every field is touched on the world thread. Account data loads on the service executor, map pictures
 * are painted on a small worker pool, and both hand back with {@code world.execute}. A newer picture request makes an
 * older one drop its result ({@link #pictureRequest}).
 */
public final class ClaimMapPage extends EterniaInteractiveCustomUIPage<ClaimMapPage.Data> {
    private static final ExecutorService PAINTERS = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).name("eternia-claim-map-", 0).factory());

    /** How long a picked layout keeps glowing without a new click (renewed on every change). */
    private static final long FOCUS_MILLIS = 10 * 60 * 1000L;
    private final EterniaModPlugin plugin;
    private final World world;
    private Mode mode;
    private HousingRules.Scope scope;
    private UUID plotId;
    private Account account;
    private ClaimMapGeometry wanted;
    private ClaimMapGeometry shown;
    private HexCoord selected;
    private int option;
    private boolean confirming, working, resultOk;
    private Message result;
    private String picture;
    private int pictureSlot;
    private long pictureRequest;
    private boolean painting, paintAgain;
    private ClaimMapGeometry tilesAsked;
    private Runnable stopListening;
    private Set<HexCoord> packedSpots = Set.of();
    /** The kind the player picked for the hexagon they are adding; null takes the suggested one. */
    private com.hexvane.eterniamod.production.PlotRoles.Role addRole;
    private static final int PACKED_RADIUS = 7, PACKED_LIMIT = 60;

    private ClaimMapPage(PlayerRef player, EterniaModPlugin plugin, World world, Mode mode, HousingRules.Scope scope, UUID plotId, ClaimMapGeometry window) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.plugin = plugin;
        this.world = world;
        this.mode = mode;
        this.scope = scope;
        this.plotId = plotId;
        this.wanted = window;
    }

    /**
     * Opens the map on the player's world, centred on them (or on {@code plotId} when given). Returns false when the
     * world has no hexagon grid. World thread.
     */
    static boolean open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, Mode mode, HousingRules.Scope scope, UUID plotId) {
        World world = store.getExternalData().getWorld();
        HexLandView view = HexLand.view(plugin, world).orElse(null);
        Player component = store.getComponent(ref, Player.getComponentType());
        if (view == null || component == null) return false;
        ClaimMapGeometry window;
        HexLandView.PlotInfo plot = plotId == null ? null : view.plots().get(plotId);
        if (plot != null) {
            var b = plot.cells().bounds();
            window = ClaimMapGeometry.centredOn(view.lattice(), b.x() + b.width() / 2.0, b.z() + b.depth() / 2.0);
        } else {
            double[] at = position(ref, store);
            window = ClaimMapGeometry.centredOn(view.lattice(), at[0], at[1]);
        }
        // Growing a guild estate reads as guild land throughout the page.
        if ((mode == Mode.EXPAND || mode == Mode.SELL) && plot != null && plot.ownerKind() == HexLandView.OwnerKind.GUILD) scope = HousingRules.Scope.GUILD_ROOT;
        component.getPageManager().openCustomPage(ref, store, new ClaimMapPage(player, plugin, world, mode, scope, plotId, window));
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
            // A brick has no tips, so a whole hexagon outline follows the mouse instead of a button highlight.
            e.addEventBinding(CustomUIEventBindingType.MouseEntered, b.selector(), new EventData().append("Action", "Hover").append("Slot", b.slot()), false);
            e.addEventBinding(CustomUIEventBindingType.MouseExited, b.selector(), new EventData().append("Action", "Unhover").append("Slot", b.slot()), false);
        }
        HexLandView view = HexLand.view(plugin, world).orElse(null);
        if (view != null) {
            var situation = situation(view);
            ClaimMapScreen.show(c, ClaimMapLogic.panel(situation));
            ClaimMapRoles.show(c, roleView(view, situation));
        }
        if (stopListening == null) stopListening = HexLandEvents.listen(this::landChanged);
        loadAccount(ref, store, view);
        paint(ref, store);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Events
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            try {
                act(ref, store, data);
            } catch (RuntimeException failure) {
                plugin.getLogger().atWarning().withCause(failure).log("Claim map action failed");
                working = false;
                confirming = false;
                result = HexLand.message(com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.CHANGED));
                resultOk = false;
                refreshPanel();
            }
        });
    }

    private void act(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        switch (data.action) {
            case "Hover", "Unhover" -> { hover(data.action.equals("Hover"), parse(data.slot)); return; }
            case "Close" -> { close(); return; }
            case "Cell" -> {
                if (working || shown == null) return;
                HexCoord cell = shown.cellOfSlot(parse(data.slot));
                if (cell == null) return;
                if (!cell.equals(selected)) { option = 0; addRole = null; }
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
            case "ShapePrev" -> { option--; confirming = false; result = null; }
            case "ShapeNext" -> { option++; confirming = false; result = null; }
            case "Back" -> confirming = false;
            case "RoleProduction", "RoleHouse" -> {
                if (working || mode != Mode.EXPAND) return;
                addRole = data.action.equals("RoleHouse") ? com.hexvane.eterniamod.production.PlotRoles.Role.HOUSE : com.hexvane.eterniamod.production.PlotRoles.Role.PRODUCTION;
                confirming = false;
                result = null;
            }
            case "ScopeMine", "ScopeGuild" -> {
                if (mode != Mode.CLAIM || working) return;
                HousingRules.Scope next = data.action.equals("ScopeGuild") ? HousingRules.Scope.GUILD_MEMBER : HousingRules.Scope.PUBLIC;
                if (next == scope) return;
                scope = next;
                option = 0;
                confirming = false;
                result = null;
                paint(ref, store);
            }
            case "Claim" -> {
                if (working) return;
                HexLandView view = view();
                if (view == null) return;
                var situation = situation(view);
                Set<HexCoord> cells = ClaimMapLogic.chosen(situation);
                if (cells.isEmpty()) return;
                // Adding a hexagon: the kind the panel shows must be one this plot may still add.
                if (mode == Mode.EXPAND && roleView(view, situation).blocked()) return;
                if (!confirming) {
                    confirming = true;
                    result = null;
                } else {
                    commit(ref, store, view, cells);
                }
            }
            default -> { return; }
        }
        refreshPanel();
    }

    private void pan(Ref<EntityStore> ref, Store<EntityStore> store, int columns, int rows) {
        wanted = wanted.panned(columns, rows);
        paint(ref, store);
    }

    private void commit(Ref<EntityStore> ref, Store<EntityStore> store, HexLandView view, Set<HexCoord> cells) {
        working = true;
        confirming = false;
        result = null;
        var commits = HexClaimCommits.current();
        HexCoord adding = mode == Mode.EXPAND ? cells.iterator().next() : null;
        UUID addingTo = plotId;
        if (adding != null) {
            var roles = roles(view);
            if (roles != null) com.hexvane.eterniamod.production.HexRoles.want(addingTo, adding, ClaimMapRoles.picked(roles, adding, addRole, maxHouse()));
        }
        var future = mode == Mode.EXPAND
            ? commits.expandPlot(playerRef, world, plotId.toString(), cells.iterator().next())
            : mode == Mode.SELL
            ? commits.sellCell(playerRef, world, plotId.toString(), cells.iterator().next())
            : commits.claimCells(playerRef, world, PlotCells.of(view.lattice(), cells), ClaimMapLogic.kind(scope));
        future.whenComplete((done, failure) -> world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            working = false;
            if (failure != null || done == null) {
                result = HexLand.message(com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.CHANGED));
                resultOk = false;
            } else {
                result = done.message();
                resultOk = done.ok();
            }
            if (!resultOk && adding != null) com.hexvane.eterniamod.production.HexRoles.forget(addingTo, adding);
            if (resultOk) {
                addRole = null;
                // The land is theirs now: reload what they own (a new plot turns the map into "add a hexagon").
                selected = null;
                option = 0;
                loadAccount(ref, store, view());
            }
            paint(ref, store);
            refreshPanel();
        }));
    }

    /** Land changed somewhere (a neighbour claimed, roads moved): redraw if it touches what is on screen. */
    private void landChanged(HexLandEvents.Change change) {
        if (isDismissed() || !change.world().equals(world.getName())) return;
        try {
            world.execute(() -> {
                if (isDismissed()) return;
                ClaimMapGeometry g = shown != null ? shown : wanted;
                boolean onScreen = change.wholeWorld() || change.cells().stream().anyMatch(new HashSet<>(g.visibleCells())::contains);
                if (!onScreen) return;
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) return;
                if (change.cause() == HexLandEvents.Cause.LATTICE) {
                    HexLandView view = view();
                    if (view == null) { close(); return; }
                    double[] at = position(ref, ref.getStore());
                    wanted = ClaimMapGeometry.centredOn(view.lattice(), at[0], at[1]);
                    shown = null;
                    selected = null;
                }
                confirming = false;
                paint(ref, ref.getStore());
                refreshPanel();
            });
        } catch (RuntimeException closing) {
            // The world is shutting down.
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Account data
    // ---------------------------------------------------------------------------------------------------------------

    private void loadAccount(Ref<EntityStore> ref, Store<EntityStore> store, HexLandView view) {
        EterniaServices services = plugin.getServices();
        UUID player = playerRef.getUuid();
        List<UUID> owners = new ArrayList<>();
        if (view != null) for (var plot : view.plots().values()) owners.add(plot.ownerId());
        com.hexvane.eterniamod.socialui.SocialUiBootstrap.supply(services, () -> {
            HexLand.loadNames(services, owners);
            return loadAccount(services, player);
        }).whenComplete((loaded, failure) -> world.execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            if (failure != null) {
                plugin.getLogger().atWarning().withCause(failure).log("Claim map account check failed");
                account = Account.newcomer(player);
            } else {
                account = loaded;
            }
            settleMode();
            paint(ref, store);
            refreshPanel();
        }));
    }

    static Account loadAccount(EterniaServices services, UUID player) {
        return services.batch(() -> {
            UUID guild = services.guilds().membership(player).map(GuildService.Membership::guildId).orElse(null);
            Owner me = Owner.player(player);
            var personal = services.housing().find(me).orElse(null);
            var estate = guild == null ? null : services.housing().find(Owner.guild(guild)).orElse(null);
            boolean mayClaim = guild != null && services.guilds().can(player, guild, "housing.claim");
            int members = guild == null ? 0 : services.guilds().roster(player).size();
            boolean upPersonal = services.ownership().owns(me, "eternia:plot/personal_32");
            boolean upEstate = guild != null && services.ownership().owns(Owner.guild(guild), "eternia:plot/guild_64");
            PlotCells packedPersonal = packed(services, me, personal);
            PlotCells packedEstate = guild == null ? null : packed(services, Owner.guild(guild), estate);
            return new Account(player, guild, mayClaim, members, upPersonal, upEstate,
                personal == null ? null : personal.state(), estate == null ? null : estate.state(), packedPersonal, packedEstate);
        });
    }

    private static PlotCells packed(EterniaServices services, Owner owner, HousingService.Slot slot) {
        if (slot == null || slot.state() != HousingService.State.PACKED) return null;
        return services.housing().location(owner).map(HousingService.ClaimLocation::cells).orElse(null);
    }

    /**
     * Once the account is known, pick the mode that fits: someone who already has a plot here is shown where it can
     * grow, and someone with a packed home sets it down.
     */
    private void settleMode() {
        HexLandView view = view();
        if (view == null || account == null || mode == Mode.EXPAND || mode == Mode.SELL) return;
        var reason = ClaimMapLogic.accountReason(account, scope);
        if (reason == null) { if (mode == Mode.PACKED) mode = Mode.CLAIM; return; }
        switch (reason.reason()) {
            case HAS_PLOT, GUILD_SLOT -> {
                HexLandView.PlotInfo own = ClaimMapLogic.ownPlot(view, account, scope);
                if (own != null) {
                    mode = Mode.EXPAND;
                    plotId = own.plotId();
                    selected = null;
                }
            }
            case PACKED -> {
                // Setting a packed home down: claimCells routes a packed owner to the move engine's restore (integration
                // branch); previews use ClaimMapRestore when wired.
                mode = Mode.PACKED;
                selected = null;
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The picture
    // ---------------------------------------------------------------------------------------------------------------

    /** Paints a new picture of {@link #wanted}. Coalesces: at most one painting at a time, plus one waiting. */
    private void paint(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (painting) { paintAgain = true; return; }
        HexLandView view = view();
        if (view == null) return;
        painting = true;
        paintAgain = false;
        long request = ++pictureRequest;
        ClaimMapGeometry g = wanted;
        findPackedSpots(g);
        var plan = plugin.getInfrastructure().world(world.getName()).orElse(null);
        List<PlotRect> roads = new ArrayList<>();
        if (plan != null) { roads.addAll(plan.roads()); roads.addAll(plan.portals()); }
        double[] at = position(ref, store);
        var scene = ClaimMapLogic.scene(situation(view), g, roads, at[0], at[1]);
        var marks = roleMarks(view);
        var maps = world.getWorldMapManager();
        var tiles = ClaimMapTiles.inMemory(maps, g);
        String packId = new PluginIdentifier(plugin.getManifest()).toString();
        PAINTERS.execute(() -> {
            byte[] png = null;
            try {
                var image = ClaimMapRenderer.render(scene, tiles.source());
                ClaimMapRoleArt.paint(image, g, marks);
                png = ClaimMapRenderer.png(image);
            } catch (RuntimeException failure) {
                plugin.getLogger().atWarning().withCause(failure).log("Claim map picture failed");
            }
            byte[] done = png;
            world.execute(() -> {
                painting = false;
                if (isDismissed()) return;
                if (done != null && request == pictureRequest) {
                    String path = ClaimMapAssets.assetPath(playerRef.getUuid(), ++pictureSlot);
                    ClaimMapAssets.push(packId, playerRef, path, done);
                    // Show it one tick later, after the client has the new picture.
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
                // Chunks the world map had not drawn yet: ask once per window, then paint again with them.
                if (!tiles.missing().isEmpty() && !g.equals(tilesAsked)) {
                    tilesAsked = g;
                    ClaimMapTiles.request(maps, tiles.missing()).whenComplete((ignored, error) -> world.execute(() -> {
                        Ref<EntityStore> again = playerRef.getReference();
                        if (!isDismissed() && again != null && again.isValid() && g.equals(wanted)) paint(again, again.getStore());
                    }));
                }
            });
        });
        refreshPanel();
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The slot the mouse is over, or -1. */
    private int hovered = -1;

    /** Moves the whole hexagon outline under the mouse, or hides it when the mouse leaves that hexagon. */
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
        HexLandView view = view();
        if (view == null || isDismissed()) return;
        var situation = situation(view);
        var c = new UICommandBuilder();
        ClaimMapScreen.show(c, ClaimMapLogic.panel(situation));
        ClaimMapRoles.show(c, roleView(view, situation));
        sendUpdate(c, null, false);
        focus(situation);
    }

    /** The hexagons being looked at also glow in the world: the picked layout, or where a plot can grow. */
    private void focus(ClaimMapLogic.Situation situation) {
        Set<HexCoord> cells = ClaimMapLogic.focus(situation);
        if (cells.isEmpty()) ClaimMapFocus.current().clear(playerRef.getUuid());
        else ClaimMapFocus.current().show(playerRef.getUuid(), world.getName(), cells, FOCUS_MILLIS);
    }

    private ClaimMapLogic.Situation situation(HexLandView view) {
        boolean loading = shown == null || painting && wanted != shown && !wanted.equals(shown);
        return new ClaimMapLogic.Situation(mode, scope, plotId, account, view, HexLand.names(), shown, selected, option,
            confirming, working, result, resultOk, picture, loading, HexPlotSettings.current(), plugin.getRuntimeConfig().local(),
            packedRule(), packedSpots);
    }

    private HexLandView view() { return HexLand.view(plugin, world).orElse(null); }

    // ---------------------------------------------------------------------------------------------------------------
    // House and production hexagons
    // ---------------------------------------------------------------------------------------------------------------

    /** The plot this page is about: the one being grown or sold, else the player's own for the scope. Null for none. */
    private com.hexvane.eterniamod.hub.HubPlotRecord ownRecord(HexLandView view) {
        UUID id = (mode == Mode.EXPAND || mode == Mode.SELL) && plotId != null ? plotId : null;
        if (id == null) {
            HexLandView.PlotInfo own = ClaimMapLogic.ownPlot(view, account, scope);
            id = own == null ? null : own.plotId();
        }
        return id == null ? null : com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).getPlot(id);
    }

    private com.hexvane.eterniamod.production.PlotRoles roles(HexLandView view) {
        return com.hexvane.eterniamod.production.HexRoles.of(plugin, ownRecord(view));
    }

    private int maxHouse() {
        HexLandView view = view();
        var record = view == null ? null : ownRecord(view);
        return record == null ? 1 : com.hexvane.eterniamod.production.HexRoles.maxHouse(record);
    }

    private ClaimMapRoles.View roleView(HexLandView view, ClaimMapLogic.Situation situation) {
        var record = ownRecord(view);
        var roles = com.hexvane.eterniamod.production.HexRoles.of(plugin, record);
        return roles == null ? ClaimMapRoles.View.NONE : ClaimMapRoles.view(situation, roles, addRole, com.hexvane.eterniamod.production.HexRoles.maxHouse(record));
    }

    /** What each hexagon of the player's own land is (their plot and their guild's estate), for the picture. */
    private java.util.Map<HexCoord, com.hexvane.eterniamod.production.PlotRoles.Role> roleMarks(HexLandView view) {
        var out = new java.util.HashMap<HexCoord, com.hexvane.eterniamod.production.PlotRoles.Role>();
        var manager = com.hexvane.eterniamod.hub.EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        UUID me = playerRef.getUuid(), guild = account == null ? null : account.guild();
        for (HexLandView.PlotInfo plot : view.plots().values()) {
            boolean mine = plot.ownerKind() == HexLandView.OwnerKind.PLAYER ? me.equals(plot.ownerId()) : guild != null && guild.equals(plot.ownerId());
            if (!mine && !plot.plotId().equals(plotId)) continue;
            var roles = com.hexvane.eterniamod.production.HexRoles.of(plugin, manager.getPlot(plot.plotId()));
            if (roles != null) out.putAll(roles.all());
        }
        return out;
    }

    /** The packed home's owner: the guild for an estate, else the player. */
    private Owner packedOwner() {
        return scope == HousingRules.Scope.GUILD_ROOT && account != null && account.guild() != null ? Owner.guild(account.guild()) : Owner.player(playerRef.getUuid());
    }

    private java.util.function.Function<Set<HexCoord>, com.hexvane.eterniamod.hexplots.HexClaimRules.Result> packedRule() {
        ClaimMapRestore restore = ClaimMapRestore.current();
        if (mode != Mode.PACKED || restore == null) return null;
        Owner owner = packedOwner();
        return cells -> restore.check(world, owner, playerRef.getUuid(), scope, cells);
    }

    /** Packed mode: every cell of every place the home fits near the window's middle (move engine), for the picture. */
    private void findPackedSpots(ClaimMapGeometry g) {
        packedSpots = Set.of();
        ClaimMapRestore restore = ClaimMapRestore.current();
        if (mode != Mode.PACKED || restore == null) return;
        var middle = g.cellAtPixel(g.pixelWidth() / 2.0, g.pixelHeight() / 2.0);
        var lattice = g.lattice();
        Set<HexCoord> cells = new HashSet<>();
        for (PlotCells spot : restore.destinations(world, packedOwner(), playerRef.getUuid(), scope,
                (int) Math.floor(lattice.centerX(middle)), (int) Math.floor(lattice.centerZ(middle)), PACKED_RADIUS, PACKED_LIMIT))
            cells.addAll(spot.cells());
        packedSpots = Set.copyOf(cells);
    }

    private static double[] position(Ref<EntityStore> ref, Store<EntityStore> store) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null) return new double[] {0, 0};
        return new double[] {t.getPosition().x, t.getPosition().z};
    }

    private static int parse(String slot) {
        try {
            return slot == null ? -1 : Integer.parseInt(slot.trim());
        } catch (NumberFormatException bad) {
            return -1;
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (stopListening != null) stopListening.run();
        ClaimMapFocus.current().clear(playerRef.getUuid());
        stopListening = null;
        ClaimMapAssets.forget(new PluginIdentifier(plugin.getManifest()).toString(), playerRef.getUuid());
        super.onDismiss(ref, store);
    }

    public static final class Data {
        public String action;
        public String slot;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("Slot", Codec.STRING), (d, v) -> d.slot = v, d -> d.slot).add()
            .build();
    }
}
