package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexClaimRules.Kind;
import com.hexvane.eterniamod.hexplots.HexClaimRules.Reason;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.HexShape;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hypixel.hytale.server.core.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the claim map says and allows, worked out from the land, the player's account and what they clicked. Pure: no
 * world, no I/O, so the page, the tests and the pictures all go through this one class.
 */
public final class ClaimMapLogic {
    private ClaimMapLogic() {}

    /** Choosing a start plot, adding one hexagon to a plot, or setting a packed home down again. */
    public enum Mode { CLAIM, EXPAND, PACKED }

    /**
     * Account facts loaded off the world thread when the page opens. {@code personal} and {@code estate} are the slot
     * states of the player's own plot and their guild's estate (null when none); {@code packedPersonal} and
     * {@code packedEstate} are the saved cells of a packed plot.
     */
    public record Account(UUID player, UUID guild, boolean mayClaimEstate, int members, boolean upgradedPersonal, boolean upgradedEstate,
                          HousingService.State personal, HousingService.State estate, PlotCells packedPersonal, PlotCells packedEstate) {
        public static Account newcomer(UUID player) { return new Account(player, null, false, 0, false, false, null, null, null, null); }
    }

    /** Everything the page is showing right now. {@code result} is the last commit's message. */
    public record Situation(Mode mode, HousingRules.Scope scope, UUID plotId, Account account, HexLandView view,
                            Function<UUID, String> names, ClaimMapGeometry shown, HexCoord selected, int option,
                            boolean confirming, boolean working, Message result, boolean resultOk,
                            String picture, boolean loading, HexPlotSettings settings, boolean localTesting,
                            Function<Set<HexCoord>, HexClaimRules.Result> packedRule, Set<HexCoord> packedSpots) {
        /** Packed mode: the move engine's check when wired, else the map's own same shape, same turn check. */
        public Situation {
            packedSpots = packedSpots == null ? Set.of() : Set.copyOf(packedSpots);
        }
    }

    /** One way to lay the start shape over the clicked cell, and what the rules say about it. */
    public record Option(Set<HexCoord> cells, HexClaimRules.Result result) {}

    public static Kind kind(HousingRules.Scope scope) {
        return switch (scope) {
            case PUBLIC -> Kind.PUBLIC;
            case GUILD_ROOT -> Kind.GUILD_ESTATE;
            case GUILD_MEMBER -> Kind.GUILD_MEMBER;
        };
    }

    /** Whether the plot this scope claims is the larger, upgraded one. */
    public static boolean upgraded(Account a, HousingRules.Scope scope) {
        return a != null && (scope == HousingRules.Scope.GUILD_ROOT ? a.upgradedEstate() : a.upgradedPersonal());
    }

    /** The shape a new plot of this scope must have. */
    public static HexShape startShape(Account a, HousingRules.Scope scope, HexPlotSettings settings) {
        return settings.startShape(kind(scope), upgraded(a, scope));
    }

    /** Account level reason this player cannot start a plot of this scope now, or null when they can. */
    public static HexClaimRules.Result accountReason(Account a, HousingRules.Scope scope) {
        if (a == null) return null;
        boolean estate = scope == HousingRules.Scope.GUILD_ROOT;
        if ((estate || scope == HousingRules.Scope.GUILD_MEMBER) && a.guild() == null) return HexClaimRules.Result.of(Reason.ANCHOR);
        HousingService.State slot = estate ? a.estate() : a.personal();
        if (slot == HousingService.State.ACTIVE) return HexClaimRules.Result.of(estate ? Reason.GUILD_SLOT : Reason.HAS_PLOT);
        if (slot == HousingService.State.PACKED) return HexClaimRules.Result.of(Reason.PACKED);
        if (slot != null) return HexClaimRules.Result.of(Reason.RECOVERY);
        if (estate && !a.mayClaimEstate()) return HexClaimRules.Result.of(Reason.PERMISSION);
        return null;
    }

    /** Who is looking, with the kind of plot they could start now (null intent: just looking). */
    public static HexLandView.Viewer viewer(Situation s) {
        UUID player = s.account() == null ? null : s.account().player();
        UUID guild = s.account() == null ? null : s.account().guild();
        Kind intent = switch (s.mode()) {
            case CLAIM -> s.account() != null && accountReason(s.account(), s.scope()) == null ? kind(s.scope()) : null;
            case PACKED -> kind(s.scope());
            case EXPAND -> null;
        };
        return new HexLandView.Viewer(player, guild, intent);
    }

    /** Cells a plot could grow into now (expand mode), else empty. */
    public static Set<HexCoord> growth(Situation s) {
        if (s.mode() != Mode.EXPAND || s.plotId() == null) return Set.of();
        HexLandView.PlotInfo plot = s.view().plots().get(s.plotId());
        if (plot == null) return Set.of();
        return s.view().expansionOptions(s.plotId(), HexLandView.kindOf(plot), s.settings());
    }

    /** The state of a cell as this page paints it: in expand mode only the plot's growth cells are green. */
    public static CellState state(Situation s, HexLandView.Viewer viewer, Set<HexCoord> growth, HexCoord cell) {
        CellState state = s.view().cell(cell, viewer, null).state();
        if (s.mode() == Mode.PACKED && !s.packedSpots().isEmpty()) {
            // Green only where the packed home fits as it is (the move engine's list of destinations).
            if (s.packedSpots().contains(cell)) return CellState.FRONTAGE_FREE;
            return state == CellState.FRONTAGE_FREE ? CellState.FREE : state;
        }
        if (s.mode() != Mode.EXPAND) return state;
        if (growth.contains(cell)) return CellState.FRONTAGE_FREE;
        return state == CellState.FRONTAGE_FREE ? CellState.FREE : state;
    }

    /** States and plot ids for every cell of a window, for the picture. */
    public static ClaimMapRenderer.Scene scene(Situation s, ClaimMapGeometry g, List<com.hexvane.eterniamod.housing.PlotRect> roads, Double px, Double pz) {
        HexLandView.Viewer viewer = viewer(s);
        Set<HexCoord> growth = growth(s);
        Map<HexCoord, CellState> states = new HashMap<>();
        Map<HexCoord, UUID> plots = new HashMap<>();
        for (HexCoord cell : g.visibleCells()) {
            states.put(cell, state(s, viewer, growth, cell));
            HexLandView.PlotInfo plot = s.view().plotAt(cell);
            if (plot != null) plots.put(cell, plot.plotId());
        }
        return new ClaimMapRenderer.Scene(g, states, plots, roads, px, pz);
    }

    /** The player's own plot in this view for this scope (their plot, or their guild's estate), or null. */
    public static HexLandView.PlotInfo ownPlot(HexLandView view, Account a, HousingRules.Scope scope) {
        if (a == null) return null;
        for (HexLandView.PlotInfo plot : view.plots().values()) {
            if (scope == HousingRules.Scope.GUILD_ROOT) {
                if (plot.ownerKind() == HexLandView.OwnerKind.GUILD && plot.ownerId() != null && plot.ownerId().equals(a.guild())) return plot;
            } else if (plot.ownerKind() == HexLandView.OwnerKind.PLAYER && a.player().equals(plot.ownerId())) return plot;
        }
        return null;
    }

    /** Ways to place the plot over the clicked cell, those the rules allow first. */
    public static List<Option> options(Situation s) {
        HexCoord cell = s.selected();
        if (cell == null || s.mode() == Mode.EXPAND) return List.of();
        HexLandView view = s.view();
        List<Set<HexCoord>> layouts = new ArrayList<>();
        if (s.mode() == Mode.PACKED) {
            PlotCells packed = packedCells(s);
            if (packed == null) return List.of();
            // A packed home keeps its exact shape and turn: only slide it so one of its cells sits on the click.
            for (HexCoord member : packed.cells()) {
                HexCoord shift = cell.minus(member);
                Set<HexCoord> placed = new LinkedHashSet<>();
                for (HexCoord c : packed.cells()) placed.add(c.plus(shift));
                layouts.add(Set.copyOf(placed));
            }
        } else {
            HexShape shape = startShape(s.account(), s.scope(), s.settings());
            layouts.addAll(shape == HexShape.SINGLE ? List.of(Set.of(cell)) : shape.placementsCovering(cell));
        }
        List<Option> valid = new ArrayList<>(), refused = new ArrayList<>();
        for (Set<HexCoord> layout : layouts) {
            HexClaimRules.Result r = s.mode() == Mode.PACKED ? (s.packedRule() != null ? s.packedRule().apply(layout) : packedCheck(s, layout)) : claimCheck(s, layout);
            (r.valid() ? valid : refused).add(new Option(layout, r));
        }
        // Keep a stable order inside each group so the arrows step predictably.
        valid.addAll(refused);
        return List.copyOf(valid);
    }

    static HexClaimRules.Result claimCheck(Situation s, Set<HexCoord> cells) {
        HexLandView view = s.view();
        Account a = s.account();
        UUID guild = a == null ? null : a.guild();
        boolean hasEstate = guild != null && view.plots().values().stream().anyMatch(p -> p.ownerKind() == HexLandView.OwnerKind.GUILD && guild.equals(p.ownerId()));
        var candidate = new HexClaimRules.Candidate(kind(s.scope()), upgraded(a, s.scope()), a == null ? 0 : a.members(), hasEstate, s.localTesting());
        Set<HexCoord> anchors = s.scope() == HousingRules.Scope.GUILD_MEMBER ? view.guildLand(guild) : Set.of();
        return HexClaimRules.claim(view.land(), candidate, cells, anchors, s.settings());
    }

    /** A packed home's cells must all be free, and touch a road (or the guild's land for a member plot). */
    static HexClaimRules.Result packedCheck(Situation s, Set<HexCoord> cells) {
        var land = s.view().land();
        for (HexCoord c : cells) {
            if (land.street().contains(c)) return HexClaimRules.Result.of(Reason.STREET);
            if (land.owners().containsKey(c)) return HexClaimRules.Result.of(Reason.TAKEN);
            if (land.reserved().contains(c)) return HexClaimRules.Result.of(Reason.RESERVED);
            if (land.blocked().contains(c)) return HexClaimRules.Result.of(Reason.BLOCKED);
        }
        if (s.scope() == HousingRules.Scope.GUILD_MEMBER) {
            Set<HexCoord> anchors = s.view().guildLand(s.account() == null ? null : s.account().guild());
            boolean touches = cells.stream().anyMatch(c -> c.neighbors().stream().anyMatch(anchors::contains));
            return touches ? HexClaimRules.Result.of(Reason.ALLOWED) : HexClaimRules.Result.of(Reason.ANCHOR);
        }
        return cells.stream().anyMatch(land::frontage) ? HexClaimRules.Result.of(Reason.ALLOWED) : HexClaimRules.Result.of(Reason.ROAD);
    }

    static PlotCells packedCells(Situation s) {
        Account a = s.account();
        if (a == null) return null;
        return s.scope() == HousingRules.Scope.GUILD_ROOT ? a.packedEstate() : a.packedPersonal();
    }

    /** The cells a Claim press would commit now, or empty. */
    public static Set<HexCoord> chosen(Situation s) {
        if (s.selected() == null) return Set.of();
        if (s.mode() == Mode.EXPAND) return growth(s).contains(s.selected()) ? Set.of(s.selected()) : Set.of();
        List<Option> options = options(s);
        if (options.isEmpty()) return Set.of();
        Option o = options.get(Math.floorMod(s.option(), options.size()));
        return o.result().valid() && accountReason(s) == null ? o.cells() : Set.of();
    }

    private static HexClaimRules.Result accountReason(Situation s) {
        if (s.mode() == Mode.EXPAND) return null;
        HexClaimRules.Result r = accountReason(s.account(), s.scope());
        // In packed mode the packed slot is the point; any other account reason still stops the claim.
        if (s.mode() == Mode.PACKED && r != null && r.reason() == Reason.PACKED) return null;
        return r;
    }

    /** Cells to outline in the world: the picked layout, or (adding a hexagon) every cell the plot can grow into. */
    public static Set<HexCoord> focus(Situation s) {
        if (s.selected() != null) {
            if (s.mode() == Mode.EXPAND) return growth(s).contains(s.selected()) ? Set.of(s.selected()) : Set.of();
            List<Option> options = options(s);
            return options.isEmpty() ? Set.of() : options.get(Math.floorMod(s.option(), options.size())).cells();
        }
        return s.mode() == Mode.EXPAND ? growth(s) : Set.of();
    }

    /** Coins the commit will take. */
    public static long price(Situation s) {
        if (s.mode() == Mode.EXPAND) {
            HexLandView.PlotInfo plot = s.plotId() == null ? null : s.view().plots().get(s.plotId());
            return plot == null ? 0 : s.settings().expansionPrice(plot.cells().size());
        }
        return s.mode() == Mode.PACKED ? 0 : s.settings().claimCoins();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The side panel
    // ------------------------------------------------------------------------------------------------------------

    public static ClaimMapScreen.Panel panel(Situation s) {
        ClaimMapGeometry g = s.shown();
        Message kicker = text(switch (s.mode()) {
            case EXPAND -> "kicker.expand";
            case PACKED -> "kicker.packed";
            case CLAIM -> switch (s.scope()) {
                case PUBLIC -> "kicker.claim";
                case GUILD_MEMBER -> "kicker.member";
                case GUILD_ROOT -> "kicker.estate";
            };
        });
        boolean tabs = s.mode() == Mode.CLAIM && s.scope() != HousingRules.Scope.GUILD_ROOT && s.account() != null && s.account().guild() != null;
        boolean estate = s.scope() == HousingRules.Scope.GUILD_ROOT;

        long price = price(s);
        Message cost = price <= 0 ? text("cost.free") : text("cost.coins").param("coins", coins(price));
        Message ownLabel = text(estate ? "own.labelGuild" : "own.label");
        Message own = ownText(s);

        Set<HexCoord> chosen = chosen(s);
        boolean ready = !chosen.isEmpty() && !s.working();
        Message claimText = text(switch (s.mode()) {
            case CLAIM -> s.confirming() ? "claim.confirm" : "claim";
            case EXPAND -> s.confirming() ? "claim.expandConfirm" : "claim.expand";
            case PACKED -> s.confirming() ? "claim.packedConfirm" : "claim.packed";
        });
        if (s.working()) claimText = text("claim.working");

        Message status;
        if (s.working()) status = text("status.working");
        else if (s.result() != null) status = s.result();
        else if (s.confirming() && ready) status = confirmText(s, chosen.size(), price);
        else status = null;

        Message sideKicker, title, body = null, shapeName = null, shapeText = null;
        CellState swatch = null;
        boolean prev = false, next = false;
        List<ClaimMapScreen.Mark> picks = List.of();
        boolean picksValid = true;
        ClaimMapScreen.Mark selected = null;
        if (s.selected() == null || g == null) {
            sideKicker = text("side.start");
            title = text("hint.title");
            HexClaimRules.Result blocked = accountReason(s);
            body = s.account() != null && blocked != null && s.mode() == Mode.CLAIM ? HexLand.message(blocked) : text(switch (s.mode()) {
                case CLAIM -> s.scope() == HousingRules.Scope.GUILD_MEMBER ? "hint.member" : "hint.claim";
                case EXPAND -> "hint.expand";
                case PACKED -> "hint.packed";
            });
            if (status == null && s.mode() == Mode.EXPAND && plotFull(s)) status = text("status.full");
            else if (status == null && g != null && s.account() != null && accountReason(s) == null && !anyGreen(s, g)) status = text("status.noneHere");
        } else {
            sideKicker = text("side.selected");
            HexLandView.Viewer viewer = viewer(s);
            HexLandView.CellInfo info = s.view().cell(s.selected(), viewer, s.names());
            CellState state = state(s, viewer, growth(s), s.selected());
            swatch = state;
            var centre = g.centre(s.selected());
            selected = new ClaimMapScreen.Mark(centre.x(), centre.y());
            switch (state) {
                case MINE -> { title = text("cell.mine"); body = text("body.mine"); }
                case MY_GUILD -> { title = owner(info, "cell.guild", "cell.myGuild"); body = text("body.myGuild"); }
                case OTHER_PLAYER -> { title = owner(info, "cell.player", "cell.neighbour"); body = HexLand.message(HexClaimRules.Result.of(Reason.TAKEN)); }
                case OTHER_GUILD -> { title = owner(info, "cell.guild", "cell.otherGuild"); body = HexLand.message(HexClaimRules.Result.of(Reason.TAKEN)); }
                case RESERVED -> { title = text("cell.reserved"); body = HexLand.message(HexClaimRules.Result.of(Reason.RESERVED)); }
                case STREET -> { title = text("cell.street"); body = HexLand.message(HexClaimRules.Result.of(Reason.STREET)); }
                default -> {
                    if (s.mode() == Mode.EXPAND) {
                        HexLandView.PlotInfo plot = s.plotId() == null ? null : s.view().plots().get(s.plotId());
                        if (state == CellState.FRONTAGE_FREE) {
                            title = text("cell.grow");
                            body = text("body.grow");
                            picks = ClaimMapScreen.marks(g, List.of(s.selected()));
                        } else {
                            HexClaimRules.Result r = plot == null ? HexClaimRules.Result.of(Reason.NO_PLOT)
                                : HexClaimRules.expand(s.view().land(), HexLandView.kindOf(plot), plot.cells().asSet(), s.selected(), s.settings());
                            title = text(r.reason() == Reason.BLOCKED ? "cell.blocked" : r.reason() == Reason.TOUCH ? "cell.farPlot" : "cell.open");
                            body = HexLand.message(r);
                        }
                    } else {
                        List<Option> options = options(s);
                        Option o = options.isEmpty() ? null : options.get(Math.floorMod(s.option(), options.size()));
                        HexClaimRules.Result blocked = accountReason(s);
                        boolean guildSide = s.scope() == HousingRules.Scope.GUILD_MEMBER;
                        if (o != null && o.result().valid()) {
                            title = text(guildSide ? "cell.frontageGuild" : "cell.frontage");
                            body = blocked != null ? HexLand.message(blocked) : text(guildSide ? "body.frontageGuild" : "body.frontage");
                        } else {
                            Reason why = o == null ? Reason.ROAD : o.result().reason();
                            title = text(switch (why) {
                                case ROAD -> "cell.far";
                                case ANCHOR -> "cell.farGuild";
                                case BLOCKED, ROAD_WORK -> "cell.blocked";
                                default -> "cell.open";
                            });
                            body = o == null ? text(guildSide ? "body.openGuild" : "body.open") : HexLand.message(o.result());
                        }
                        int size = o == null ? 1 : o.cells().size();
                        if (size > 1) {
                            HexShape shape = s.mode() == Mode.PACKED ? null : startShape(s.account(), s.scope(), s.settings());
                            shapeName = shape == null ? text("shape.packed").param("count", size)
                                : text("shape.name").param("shape", Message.translation("eternia_hexplots.shape." + shape.name().toLowerCase(Locale.ROOT))).param("count", size);
                            shapeText = text("shape.option").param("index", Math.floorMod(s.option(), options.size()) + 1).param("count", options.size());
                            prev = next = options.size() > 1;
                        }
                        if (o != null) {
                            picks = ClaimMapScreen.marks(g, o.cells());
                            picksValid = o.result().valid();
                        }
                    }
                }
            }
        }
        boolean claimEnabled = ready && (s.result() == null || !s.resultOk());
        return new ClaimMapScreen.Panel(kicker, tabs, s.scope() == HousingRules.Scope.GUILD_MEMBER, text("scope.mine"), text("scope.guild"),
            sideKicker, swatch, title, body, shapeName, shapeText, prev, next, cost, ownLabel, own, status,
            claimText, claimEnabled, s.confirming() && ready ? text("back") : null,
            s.picture(), s.loading(), selected, picks, picksValid,
            s.mode() == Mode.EXPAND ? text("legend.grow") : HexLand.stateName(CellState.FRONTAGE_FREE));
    }

    /** True when some hexagon on screen is green (claimable, or room to grow). */
    static boolean anyGreen(Situation s, ClaimMapGeometry g) {
        HexLandView.Viewer viewer = viewer(s);
        Set<HexCoord> growth = growth(s);
        for (var slot : g.slots()) if (state(s, viewer, growth, slot.cell()) == CellState.FRONTAGE_FREE) return true;
        return false;
    }

    private static boolean plotFull(Situation s) {
        HexLandView.PlotInfo plot = s.plotId() == null ? null : s.view().plots().get(s.plotId());
        return plot != null && plot.cells().size() >= s.settings().maxCells(HexLandView.kindOf(plot));
    }

    private static Message ownText(Situation s) {
        HexLandView.PlotInfo plot = s.mode() == Mode.EXPAND && s.plotId() != null ? s.view().plots().get(s.plotId()) : ownPlot(s.view(), s.account(), s.scope());
        if (plot != null) {
            int max = s.settings().maxCells(HexLandView.kindOf(plot));
            int count = plot.cells().size();
            return count == 1 ? text("own.one").param("max", max) : text("own.cells").param("count", count).param("max", max);
        }
        if (s.mode() == Mode.PACKED || accountReasonIs(s, Reason.PACKED)) return text("own.packed");
        return text("own.none");
    }

    private static boolean accountReasonIs(Situation s, Reason reason) {
        HexClaimRules.Result r = accountReason(s.account(), s.scope());
        return r != null && r.reason() == reason;
    }

    private static Message confirmText(Situation s, int count, long price) {
        return switch (s.mode()) {
            case EXPAND -> text("status.confirmExpand").param("coins", coins(price));
            case PACKED -> text("status.confirmPacked").param("count", count);
            case CLAIM -> price > 0 ? text("status.confirmCoins").param("count", count).param("coins", coins(price))
                : count == 1 ? text("status.confirmOne") : text("status.confirmFree").param("count", count);
        };
    }

    private static Message owner(HexLandView.CellInfo info, String named, String unnamed) {
        String name = info.ownerName();
        return name == null || name.isBlank() ? text(unnamed) : text(named).param("name", name);
    }

    static String coins(long amount) { return String.format(Locale.ROOT, "%,d", amount); }

    static Message text(String key) { return ClaimMapScreen.text(key); }
}
