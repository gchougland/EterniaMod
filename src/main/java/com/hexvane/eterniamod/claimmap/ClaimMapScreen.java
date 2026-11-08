package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Every command the claim map page sends, in one place and free of world state, so tests and pictures use exactly the
 * page's own commands. {@link #open} lays the page out once; {@link #show} fills it from a {@link Panel}.
 */
public final class ClaimMapScreen {
    public static final String DOCUMENT = "EterniaMod/ClaimMapPage.ui";
    static final String CELLS = "#Cells";
    static final int PICKS = 7;
    static final int MARK_WIDTH = 92, MARK_HEIGHT = 102;
    static final String KEY = "eternia_hexplots.map.";

    private ClaimMapScreen() {}

    /** A button the page listens to: selector and the action it sends. */
    public record Binding(String selector, String action, String slot) {}

    /** Every action button (cells come from {@link #cellBindings}). */
    public static final List<Binding> ACTIONS = List.of(
        new Binding("#PanNorth", "PanNorth", null), new Binding("#PanSouth", "PanSouth", null),
        new Binding("#PanWest", "PanWest", null), new Binding("#PanEast", "PanEast", null),
        new Binding("#Recentre", "Recentre", null), new Binding("#ShapePrev", "ShapePrev", null),
        new Binding("#ShapeNext", "ShapeNext", null), new Binding("#Claim", "Claim", null),
        new Binding("#Back", "Back", null), new Binding("#ScopeMine", "ScopeMine", null),
        new Binding("#ScopeGuild", "ScopeGuild", null), new Binding("#Close", "Close", null));

    /** Appends the page and one transparent hexagon button per brick. The layout is the same for every window. */
    public static void open(UICommandBuilder c, ClaimMapGeometry g) {
        c.append(DOCUMENT);
        for (ClaimMapGeometry.Slot slot : g.slots()) {
            c.append(CELLS, switch (slot.kind()) {
                case FULL -> "EterniaMod/ClaimMapCell.ui";
                case WEST_HALF -> "EterniaMod/ClaimMapCellWest.ui";
                case EAST_HALF -> "EterniaMod/ClaimMapCellEast.ui";
            });
            c.setObject(CELLS + "[" + slot.index() + "].Anchor", box(slot.left(), slot.top(), slot.width(), slot.height()));
        }
        c.set("#MapTitle.TextSpans", text("title"));
        c.set("#RecentreText.TextSpans", text("recentre"));
        c.set("#CostLabel.TextSpans", text("cost.label"));
        c.set("#LegendTitle.TextSpans", text("legend"));
        c.set("#MapLoadingText.TextSpans", text("loading"));
        for (CellState state : CellState.values()) c.set("#Legend" + camel(state) + ".TextSpans", HexLand.stateName(state));
    }

    public static List<Binding> cellBindings() {
        int count = ClaimMapGeometry.slotCount();
        var out = new java.util.ArrayList<Binding>(count);
        for (int i = 0; i < count; i++) out.add(new Binding(CELLS + "[" + i + "]", "Cell", Integer.toString(i)));
        return out;
    }

    /** Where a hexagon mark goes, in map pixels (its centre). */
    public record Mark(double x, double y) {}

    /**
     * Everything the page shows besides the map picture. Texts are player messages; null hides an optional line.
     *
     * @param picture        the map picture's common asset path, or null while the first one is being drawn
     * @param swatch         state shown beside the title, or null for none
     * @param selected       outline of the clicked hexagon, or null
     * @param picks          the hexagons that would be claimed (shape preview), at most {@link #PICKS}
     * @param picksValid     false draws the preview in the refusal colour
     */
    public record Panel(Message kicker, boolean scopeTabs, boolean guildScope, Message scopeMine, Message scopeGuild,
                        Message sideKicker, CellState swatch, Message title, Message body,
                        Message shapeName, Message shapeText, boolean shapePrev, boolean shapeNext,
                        Message cost, Message ownLabel, Message own, Message status,
                        Message claim, boolean claimEnabled, Message back,
                        String picture, boolean loading, Mark selected, List<Mark> picks, boolean picksValid, Message legendFree) {
        public Panel {
            Objects.requireNonNull(kicker);
            Objects.requireNonNull(title);
            Objects.requireNonNull(claim);
            picks = List.copyOf(picks);
        }
    }

    /** Sends a whole panel (every field, so any earlier state is replaced). */
    public static void show(UICommandBuilder c, Panel p) {
        c.set("#MapKicker.TextSpans", p.kicker());
        c.set("#ScopeTabs.Visible", p.scopeTabs());
        if (p.scopeTabs()) {
            c.set("#ScopeMine.TextSpans", p.scopeMine());
            c.set("#ScopeGuild.TextSpans", p.scopeGuild());
            // The active tab is shown pressed (its disabled look is the brass "selected" surface).
            c.set("#ScopeMine.Disabled", !p.guildScope());
            c.set("#ScopeGuild.Disabled", p.guildScope());
        }
        c.set("#SideKicker.TextSpans", orBlank(p.sideKicker()));
        for (CellState state : CellState.values()) c.set("#Sw" + camel(state) + ".Visible", state == p.swatch());
        c.set("#CellTitle.TextSpans", p.title());
        c.set("#CellBody.TextSpans", orBlank(p.body()));
        boolean shape = p.shapeName() != null;
        c.set("#ShapeBox.Visible", shape);
        if (shape) {
            c.set("#ShapeName.TextSpans", p.shapeName());
            c.set("#ShapeText.TextSpans", orBlank(p.shapeText()));
            c.set("#ShapePrev.Disabled", !p.shapePrev());
            c.set("#ShapeNext.Disabled", !p.shapeNext());
        }
        c.set("#CostText.TextSpans", orBlank(p.cost()));
        c.set("#OwnLabel.TextSpans", orBlank(p.ownLabel()));
        c.set("#OwnText.TextSpans", orBlank(p.own()));
        c.set("#Status.TextSpans", orBlank(p.status()));
        c.set("#Claim.TextSpans", p.claim());
        c.set("#Claim.Disabled", !p.claimEnabled());
        c.set("#Back.Visible", p.back() != null);
        if (p.back() != null) c.set("#Back.TextSpans", p.back());
        if (p.legendFree() != null) c.set("#LegendFrontageFree.TextSpans", p.legendFree());
        if (p.picture() != null) c.set("#MapImage.AssetPath", p.picture());
        c.set("#MapLoading.Visible", p.loading());
        c.set("#Selected.Visible", p.selected() != null);
        if (p.selected() != null) c.setObject("#Selected.Anchor", markBox(p.selected()));
        for (int i = 0; i < PICKS; i++) {
            boolean on = i < p.picks().size();
            String shown = (p.picksValid() ? "#Pick" : "#Bad") + i, hidden = (p.picksValid() ? "#Bad" : "#Pick") + i;
            c.set(hidden + ".Visible", false);
            c.set(shown + ".Visible", on);
            if (on) c.setObject(shown + ".Anchor", markBox(p.picks().get(i)));
        }
    }

    /** Marks for cells of the window (cells off the window are left out). */
    public static List<Mark> marks(ClaimMapGeometry g, java.util.Collection<HexCoord> cells) {
        return cells.stream().filter(c -> g.onPicture(g.centre(c).x(), g.centre(c).y()))
            .sorted(HexCoord.READING_ORDER).map(c -> new Mark(g.centre(c).x(), g.centre(c).y())).limit(PICKS).toList();
    }

    public static Message text(String key) { return Message.translation(KEY + key); }

    /** {@code MY_GUILD} to {@code MyGuild}, the id suffix used in the page. */
    static String camel(CellState state) {
        StringBuilder out = new StringBuilder();
        for (String part : state.name().toLowerCase(Locale.ROOT).split("_")) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return out.toString();
    }

    private static Message orBlank(Message m) { return m != null ? m : Message.raw(""); }

    private static Anchor markBox(Mark m) {
        return box((int) Math.round(m.x() - MARK_WIDTH / 2.0), (int) Math.round(m.y() - MARK_HEIGHT / 2.0), MARK_WIDTH, MARK_HEIGHT);
    }

    static Anchor box(int left, int top, int width, int height) {
        var a = new Anchor();
        a.setLeft(Value.of(left));
        a.setTop(Value.of(top));
        a.setWidth(Value.of(width));
        a.setHeight(Value.of(height));
        return a;
    }
}
