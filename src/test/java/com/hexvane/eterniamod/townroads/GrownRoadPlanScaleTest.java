package com.hexvane.eterniamod.townroads;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.hexplots.HexCoord;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrownRoadPlanScaleTest {
    @Test
    void aLargeTownPlansQuickly() {
        var land = new HashMap<HexCoord, GrownRoadPlan.Land>();
        for (HexCoord c : HexCoord.disk(HexCoord.ORIGIN, 25)) land.put(c, new GrownRoadPlan.Land(true, true, 0));
        long started = System.nanoTime();
        var plan = GrownRoadPlan.grow(HexCoord.ORIGIN, land, List.of(HexCoord.ORIGIN), List.of(), (a, m, b) -> m == null ? 0.0 : null,
            new GrownRoadPlan.Settings(2, 1, 2500, 0.03));
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("radius 25: " + plan.cells().size() + " road hexagons in " + ms + " ms");
        assertTrue(plan.cells().size() > 300, "a big town gets a big network");
        assertTrue(ms < 10_000, "planning stays quick");
    }
}
