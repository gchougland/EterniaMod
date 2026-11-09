package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BoundaryGroundRuleTest {
    private static final double NONE = Double.NaN;

    @Test
    void flatGroundGlowsOnTop() {
        assertEquals(65.0, BoundaryGroundSampler.combine(new double[] {65, 65, 65, 65}));
    }

    @Test
    void oneBlockStepGlowsAlongItsTopEdge() {
        assertEquals(66.0, BoundaryGroundSampler.combine(new double[] {65, 66, 65, 66}));
    }

    @Test
    void cliffIsLeftOut() {
        assertTrue(Double.isNaN(BoundaryGroundSampler.combine(new double[] {65, 67, 65, 67})));
    }

    @Test
    void fenceOnOneSideDoesNotHideTheLine() {
        assertEquals(65.0, BoundaryGroundSampler.combine(new double[] {65, NONE, 65, NONE}));
    }

    @Test
    void nothingToStandOnShowsNothing() {
        assertTrue(Double.isNaN(BoundaryGroundSampler.combine(new double[] {NONE, NONE, NONE, NONE})));
    }

    @Test
    void pavedPlotEdgesCountAsGround() {
        for (String id : new String[] {"Rock_Stone_Cobble", "Wood_Oak_Planks", "Rock_Gravel", "Soil_Grass", "Rock_Stone_Brick"})
            assertTrue(BoundarySurfacePolicy.isBorderGround(id), id);
        for (String id : new String[] {"Wood_Roof_Shingle", "Plant_Leaves_Oak", null})
            assertFalse(BoundarySurfacePolicy.isBorderGround(id), String.valueOf(id));
    }
}
