package com.hexvane.eterniamod.wildland;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.claimmap.ClaimMapGeometry;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandView.CellState;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WildMapLogicTest {
    private final UUID me = UUID.randomUUID(), other = UUID.randomUUID();
    private final WildLandSettings settings = WildLandSettings.DEFAULTS;

    private WildMapLogic.View view(Map<HexCoord, WildLandIndex.Land> land, HexCoord selected, long balance) {
        return new WildMapLogic.View(me, null, null, false, false, land, settings, balance, c -> false, selected, false, false, null, false);
    }

    private static Map<HexCoord, WildLandIndex.Land> land(WildLandIndex.Land... claims) {
        Map<HexCoord, WildLandIndex.Land> out = new HashMap<>();
        for (var l : claims) for (var c : l.cells()) out.put(c, l);
        return out;
    }

    private WildLandIndex.Land claim(UUID owner, String name, HexCoord... cells) {
        return new WildLandIndex.Land(UUID.randomUUID(), "wilds", Owner.player(owner), name, Set.of(cells), Map.of(), Map.of());
    }

    @Test void colours() {
        var mine = claim(me, "Me", HexCoord.ORIGIN);
        var theirs = claim(other, "Tom", new HexCoord(4, 0));
        var v = view(land(mine, theirs), null, 0);
        var g = ClaimMapGeometry.centredOn(settings.lattice(), 0, 0);
        var states = WildMapLogic.states(v, g);
        assertEquals(CellState.MINE, states.get(HexCoord.ORIGIN));
        assertEquals(CellState.OTHER_PLAYER, states.get(new HexCoord(4, 0)));
        assertEquals(CellState.RESERVED, states.get(new HexCoord(3, 0)), "the gap around someone else's land");
        assertEquals(CellState.FREE, states.get(HexCoord.ORIGIN.neighbor(3)), "right beside your own land is open");
    }

    @Test void theButtonFollowsThePickedHexagon() {
        var mine = claim(me, "Me", HexCoord.ORIGIN, HexCoord.ORIGIN.neighbor(0));
        var all = land(mine, claim(other, "Tom", new HexCoord(6, 0)));
        assertEquals(WildMapLogic.Action.RELEASE, WildMapLogic.action(view(all, HexCoord.ORIGIN, 0)));
        assertEquals(WildMapLogic.Action.NONE, WildMapLogic.action(view(all, new HexCoord(6, 0), 0)));
        var add = view(all, HexCoord.ORIGIN.neighbor(3), 1000);
        assertEquals(WildMapLogic.Action.ADD, WildMapLogic.action(add));
        assertNull(WildMapLogic.addReason(add));
        assertEquals(350, WildMapLogic.price(add), "a third hexagon");
        assertEquals(WildRules.Reason.COINS, WildMapLogic.addReason(view(all, HexCoord.ORIGIN.neighbor(3), 10)));
        assertEquals(WildRules.Reason.NOT_TOUCHING, WildMapLogic.addReason(view(all, new HexCoord(-5, -5), 1000)));
        assertTrue(WildMapLogic.panel(add, null, null, false).claimEnabled());
    }

    @Test void landStaysInOnePiece() {
        assertTrue(WildRules.connected(Set.of(HexCoord.ORIGIN, HexCoord.ORIGIN.neighbor(0))));
        assertFalse(WildRules.connected(Set.of(HexCoord.ORIGIN, new HexCoord(3, 0))));
        assertNull(WildRules.release(Set.of(HexCoord.ORIGIN), HexCoord.ORIGIN), "the last hexagon may go");
    }
}
