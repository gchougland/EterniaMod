package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Mode;
import com.hexvane.eterniamod.claimmap.ClaimMapLogic.Situation;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.production.PlotRoles;
import com.hexvane.eterniamod.production.PlotRoles.Role;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The house and production side of the Claim Map's panel. A plot has two kinds of hexagon with their own limits, so
 * adding one asks which kind it should be, a hexagon the player owns says which kind it is, and "your land" counts the
 * two apart. Worked out from the same {@link Situation} as the rest of the panel and sent after it, so these lines
 * replace the plain ones. No world state.
 */
final class ClaimMapRoles {
    private ClaimMapRoles() {}

    /**
     * What the panel shows about kinds. {@code box} is the chooser shown while adding a hexagon; the texts replace the
     * panel's own when not null; {@code blocked} keeps the add button off.
     */
    record View(boolean box, Role picked, Message production, Message house, Message note,
                Message title, Message body, Message own, Message claim, Message status, boolean blocked, Message cost) {
        static final View NONE = new View(false, null, null, null, null, null, null, null, null, null, false, null);

        View(boolean box, Role picked, Message production, Message house, Message note,
             Message title, Message body, Message own, Message claim, Message status, boolean blocked) {
            this(box, picked, production, house, note, title, body, own, claim, status, blocked, null);
        }

        View withCost(Message cost) { return new View(box, picked, production, house, note, title, body, own, claim, status, blocked, cost); }
    }

    /** What the next hexagon costs this plot: its first production hexagon is free for everyone. */
    static long price(Situation s, PlotRoles roles) {
        return s.settings().expansionPrice(roles.cells().size(), roles.count(Role.PRODUCTION) == 0);
    }

    /** The kind a new hexagon at the selected spot would get: the player's pick, or production while that is allowed. */
    static Role picked(PlotRoles roles, HexCoord cell, Role chosen, int maxHouse) {
        return chosen != null ? chosen : roles.suggested(cell, maxHouse);
    }

    static View view(Situation s, PlotRoles roles, Role chosen, int maxHouse) {
        if (roles == null) return View.NONE;
        View view = plain(s, roles, chosen, maxHouse);
        if (s.mode() != Mode.EXPAND) return view;
        long price = price(s, roles);
        return view.withCost(price <= 0 ? text("cost.free") : text("cost.coins").param("coins", s.settings().priceText(price)));
    }

    private static View plain(Situation s, PlotRoles roles, Role chosen, int maxHouse) {
        int houses = roles.count(Role.HOUSE), production = roles.count(Role.PRODUCTION);
        Message own = text("role.own").param("house", houses).param("maxHouse", Math.max(maxHouse, houses))
            .param("production", production).param("maxProduction", PlotRoles.MAX_PRODUCTION);
        int stations = s.settings().generatorsPerCell();
        HexCoord cell = s.selected();
        if (cell == null || s.shown() == null) {
            Message hint = s.mode() == Mode.EXPAND && production == 0 ? text("role.hint.first") : null;
            return new View(false, null, null, null, null, hint == null ? null : text("role.hint.firstTitle"), hint, own, null, null, false);
        }
        if (roles.cells().contains(cell)) {
            // One of their own hexagons: say which kind it is. Selling keeps its own words for hexagons that can go.
            if (s.mode() == Mode.SELL && ClaimMapLogic.sellable(s).contains(cell)) return new View(false, null, null, null, null, null, null, own, null, null, false);
            boolean house = roles.of(cell) == Role.HOUSE;
            return new View(false, null, null, null, null, text(house ? "role.cell.house" : "role.cell.production"),
                house ? text("role.body.house") : text("role.body.production").param("count", stations), own, null, null, false);
        }
        if (s.mode() != Mode.EXPAND || !ClaimMapLogic.growth(s).contains(cell)) return new View(false, null, null, null, null, null, null, own, null, null, false);
        Role picked = picked(roles, cell, chosen, maxHouse);
        String refused = roles.canAddAs(cell, picked, maxHouse);
        String kind = picked == Role.PRODUCTION ? "production" : "house";
        Message note = refused != null ? text("role.no." + refused)
            : picked == Role.PRODUCTION ? text("role.note.production").param("count", stations) : text("role.note.house");
        Message claim = s.working() || s.confirming() ? null : text("role.claim." + kind);
        Message status = null;
        if (s.confirming() && !s.working() && s.result() == null && refused == null) {
            long price = price(s, roles);
            status = price <= 0 ? text("role.confirmFree." + kind) : text("role.confirm." + kind).param("coins", s.settings().priceText(price));
        }
        return new View(true, picked,
            text("role.production").param("count", production).param("max", PlotRoles.MAX_PRODUCTION),
            text("role.house").param("count", houses).param("max", Math.max(maxHouse, houses)),
            note, null, null, own, claim, status, refused != null);
    }

    /** Sends the view. Call right after {@link ClaimMapScreen#show}. */
    static void show(UICommandBuilder c, View v) {
        c.set("#RoleBox.Visible", v.box());
        if (v.box()) {
            c.set("#RoleTitle.TextSpans", text("role.title"));
            c.set("#RoleProductionText.TextSpans", v.production());
            c.set("#RoleHouseText.TextSpans", v.house());
            c.set("#RoleProductionOn.Visible", v.picked() == Role.PRODUCTION);
            c.set("#RoleHouseOn.Visible", v.picked() == Role.HOUSE);
            c.set("#RoleNote.TextSpans", v.note());
        }
        if (v.title() != null) c.set("#CellTitle.TextSpans", v.title());
        if (v.body() != null) c.set("#CellBody.TextSpans", v.body());
        if (v.own() != null) c.set("#OwnText.TextSpans", v.own());
        if (v.claim() != null) c.set("#Claim.TextSpans", v.claim());
        if (v.status() != null) c.set("#Status.TextSpans", v.status());
        if (v.cost() != null) c.set("#CostText.TextSpans", v.cost());
        if (v.blocked()) c.set("#Claim.Disabled", true);
    }

    private static Message text(String key) { return ClaimMapScreen.text(key); }
}
