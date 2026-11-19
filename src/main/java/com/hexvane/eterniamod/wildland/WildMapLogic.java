package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.claimmap.ClaimMapGeometry;
import com.hexvane.eterniamod.claimmap.ClaimMapScreen;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;
import com.hypixel.hytale.server.core.Message;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the land map shows in an adventure world, free of the world and the page: the colour of every hexagon and the
 * side panel for the hexagon picked. One map serves your own land and, on the guild tab, your guild's.
 */
public final class WildMapLogic {
    private WildMapLogic() {}

    /** What the panel does when its main button is pressed. */
    public enum Action { NONE, ADD, RELEASE }

    /**
     * Everything the map needs. {@code land} is every claimed hexagon here; {@code balance} the Coins of whoever pays
     * (you, or your guild); {@code blocked} land kept free for everyone.
     */
    public record View(UUID me, UUID guild, String guildName, boolean guildTab, boolean mayManageGuild,
                       Map<HexCoord, WildLandIndex.Land> land, WildLandSettings settings, long balance,
                       Predicate<HexCoord> blocked, HexCoord selected, boolean confirming, boolean working,
                       Message result, boolean resultOk) {
        /** Whose land the page is about. */
        public Owner owner() { return guildTab && guild != null ? Owner.guild(guild) : Owner.player(me); }

        public WildLandIndex.Land mine() {
            Owner owner = owner();
            for (var l : land.values()) if (l.owner().equals(owner)) return l;
            return null;
        }

        Map<HexCoord, String> owners() {
            Map<HexCoord, String> out = new HashMap<>();
            land.forEach((cell, l) -> out.put(cell, l.owner().key()));
            return out;
        }

        /** Owners allowed right beside this land: yourself and your own guild, both ways. */
        Predicate<String> friendly() {
            String meKey = Owner.player(me).key(), guildKey = guild == null ? null : Owner.guild(guild).key();
            return o -> o.equals(meKey) || o.equals(guildKey);
        }
    }

    /** Colour of every visible hexagon. */
    public static Map<HexCoord, CellState> states(View v, ClaimMapGeometry g) {
        Map<HexCoord, CellState> out = new HashMap<>();
        var owners = v.owners();
        for (HexCoord cell : g.visibleCells()) {
            WildLandIndex.Land l = v.land().get(cell);
            if (l != null) out.put(cell, state(v, l));
            else if (v.blocked().test(cell) || WildRules.blockedBy(owners, cell, v.settings().bufferRings(), v.friendly()) != null) out.put(cell, CellState.RESERVED);
            else out.put(cell, CellState.FREE);
        }
        return out;
    }

    /** The claim id of every visible claimed hexagon, so borders are drawn around each patch. */
    public static Map<HexCoord, UUID> plots(View v, ClaimMapGeometry g) {
        Map<HexCoord, UUID> out = new HashMap<>();
        for (HexCoord cell : g.visibleCells()) { var l = v.land().get(cell); if (l != null) out.put(cell, l.id()); }
        return out;
    }

    private static CellState state(View v, WildLandIndex.Land l) {
        if (l.owner().kind() == Owner.Kind.PLAYER) return l.owner().id().equals(v.me()) ? CellState.MINE : CellState.OTHER_PLAYER;
        return l.owner().id().equals(v.guild()) ? CellState.MY_GUILD : CellState.OTHER_GUILD;
    }

    /** What pressing the main button would do now. */
    public static Action action(View v) {
        if (v.selected() == null) return Action.NONE;
        WildLandIndex.Land l = v.land().get(v.selected());
        WildLandIndex.Land mine = v.mine();
        if (l != null) return mine != null && l.id().equals(mine.id()) ? Action.RELEASE : Action.NONE;
        return Action.ADD;
    }

    /** Why adding the picked hexagon would be refused, or null. */
    public static WildRules.Reason addReason(View v) {
        if (v.guildTab() && v.guild() == null) return WildRules.Reason.NO_GUILD;
        if (v.guildTab() && !v.mayManageGuild()) return WildRules.Reason.FORBIDDEN;
        WildLandIndex.Land mine = v.mine();
        Set<HexCoord> cells = mine == null ? Set.of() : mine.cells();
        var tier = v.settings().tier(v.guildTab());
        WildRules.Reason r = WildRules.add(v.owners(), cells, v.selected(), v.owner().key(), v.settings().bufferRings(), v.friendly(), tier.maxCells(), v.blocked());
        if (r != null) return r;
        if (price(v) > v.balance()) return v.guildTab() ? WildRules.Reason.GUILD_COINS : WildRules.Reason.COINS;
        return null;
    }

    /** What the picked hexagon costs to add (or, giving it up, gives back). */
    public static long price(View v) {
        WildLandIndex.Land mine = v.mine();
        return v.settings().tier(v.guildTab()).price(mine == null ? 0 : mine.cells().size());
    }

    public static ClaimMapScreen.Panel panel(View v, ClaimMapGeometry shown, String picture, boolean loading) {
        WildLandIndex.Land mine = v.mine();
        var tier = v.settings().tier(v.guildTab());
        int have = mine == null ? 0 : mine.cells().size();
        Message kicker = raw(v.guildTab() ? "Guild land" : "Your land");
        Message ownLabel = raw(v.guildTab() ? (v.guildName() == null ? "Your guild" : v.guildName()) : "You have");
        Message own = raw(have + " of " + tier.maxCells() + " hexagons · " + coins(v.balance()));
        Message title, body = null, cost = null, status = null, claim = raw("Pick a hexagon");
        CellState swatch = null;
        boolean enabled = false;
        Message back = mine != null && !v.confirming() ? raw("Share with friends") : null;
        HexCoord cell = v.selected();
        if (v.guildTab() && v.guild() == null) {
            title = raw("Guild land");
            body = raw("Join a guild to claim land together.");
            back = null;
        } else if (cell == null) {
            title = raw(mine == null ? "Claim some land" : "Grow your land");
            body = raw(mine == null
                ? "Pick an open hexagon to make it yours. Only you and the friends you choose can build, open chests or use things there."
                : "Pick an open hexagon beside your land to add it, or one of yours to give it up. Each new hexagon costs a little more.");
        } else {
            WildLandIndex.Land owner = v.land().get(cell);
            switch (action(v)) {
                case RELEASE -> {
                    swatch = state(v, owner);
                    title = raw(v.guildTab() ? "Guild land" : "Your land");
                    long refund = v.settings().refund(owner.paid().getOrDefault(cell, 0L));
                    WildRules.Reason splits = WildRules.release(mine.cells(), cell);
                    body = raw(mine.cells().size() == 1 ? "This is your last hexagon here. Giving it up ends your claim." : "You can give this hexagon up. Someone else may claim it after.");
                    cost = raw(refund == 0 ? "Gives nothing back" : "Gives back " + coins(refund));
                    if (v.guildTab() && !v.mayManageGuild()) status = raw(WildRules.Reason.FORBIDDEN.text(0, null));
                    else if (splits != null) status = raw(splits.text(0, null));
                    else {
                        enabled = !v.working();
                        claim = raw(v.confirming() ? "Yes, give it up" : "Give up this hexagon");
                    }
                }
                case ADD -> {
                    long price = price(v);
                    WildRules.Reason why = addReason(v);
                    swatch = why == null ? CellState.FREE : CellState.RESERVED;
                    title = raw(mine == null ? "Open land" : "Open land beside yours");
                    body = raw(mine == null ? "Claim this hexagon to start your land here." : "Add this hexagon to your land.");
                    cost = raw(price == 0 ? "Free" : coins(price));
                    if (why != null) status = raw(why.text(why == WildRules.Reason.MAX_CELLS ? tier.maxCells() : price, nearName(v, cell)));
                    else {
                        enabled = !v.working();
                        claim = raw(v.confirming() ? "Pay " + coins(price) : mine == null ? "Claim this hexagon" : "Add this hexagon");
                    }
                }
                default -> {
                    swatch = state(v, owner);
                    title = raw(owner.ownerName() + "'s land");
                    body = raw("Only " + owner.ownerName() + " and their friends can build here.");
                }
            }
        }
        if (v.result() != null && !v.confirming()) status = v.result();
        if (v.working()) status = raw("Working on it...");
        List<ClaimMapScreen.Mark> picks = cell == null || shown == null ? List.of() : ClaimMapScreen.marks(shown, List.of(cell));
        ClaimMapScreen.Mark selected = cell == null || shown == null || !shown.shows(cell) ? null : new ClaimMapScreen.Mark(shown.centre(cell).x(), shown.centre(cell).y());
        boolean valid = action(v) != Action.ADD || addReason(v) == null;
        return new ClaimMapScreen.Panel(kicker, v.guild() != null, v.guildTab(), raw("Mine"), raw("Guild"),
            raw(v.confirming() ? "Are you sure?" : "Hexagon"), swatch, title, body, null, null, false, false,
            cost, ownLabel, own, status, claim, enabled, v.confirming() ? raw("Back") : back,
            picture, loading, selected, action(v) == Action.ADD ? picks : List.of(), valid, null);
    }

    private static String nearName(View v, HexCoord cell) {
        String key = WildRules.blockedBy(v.owners(), cell, v.settings().bufferRings(), v.friendly());
        if (key == null) return null;
        for (var l : v.land().values()) if (l.owner().key().equals(key)) return l.ownerName();
        return null;
    }

    static String coins(long amount) { return String.format(Locale.ROOT, "%,d", amount) + (amount == 1 ? " Coin" : " Coins"); }

    private static Message raw(String text) { return Message.raw(text); }
}
