package com.hexvane.eterniamod.collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PetTurnTest {
    private static final float SPEED = (float) Math.toRadians(40);

    @Test
    void turnsOnlyAFewDegreesPerTick() {
        float yaw = CollectionRuntime.turn(0f, (float) Math.PI / 2, SPEED, 0.05f);
        assertEquals(Math.toRadians(2), yaw, 1e-4);
    }

    @Test
    void stopsOnTheTarget() {
        assertEquals(0.5f, CollectionRuntime.turn(0.49f, 0.5f, SPEED, 0.05f), 1e-6);
    }

    @Test
    void goesTheShortWayRound() {
        float from = (float) Math.toRadians(170), to = (float) Math.toRadians(-170);
        float yaw = CollectionRuntime.turn(from, to, SPEED, 0.05f);
        assertEquals(Math.toRadians(172), yaw, 1e-4);
    }

    @Test
    void floatingTurnStartsGentlyThenSettlesOnTheTarget() {
        float yaw = 0f, speed = 0f, target = (float) Math.PI / 2;
        float[] first = CollectionRuntime.swing(yaw, speed, target, 0.05f);
        org.junit.jupiter.api.Assertions.assertTrue(first[0] > 0 && first[0] < Math.toRadians(3), "eases in");
        for (int i = 0; i < 80; i++) { float[] s = CollectionRuntime.swing(yaw, speed, target, 0.05f); yaw = s[0]; speed = s[1]; }
        assertEquals(target, yaw, 0.01);
    }
}
