package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BoundaryEmissionScheduleTest {
    @Test void newEdgesAppearOnNextScanWithoutRestartingExistingEmitters() {
        var schedule = new BoundaryEmissionSchedule();
        assertTrue(schedule.advance(.03));
        assertTrue(schedule.ready("hub", 10, 20));
        schedule.emitted("hub", 10, 20);
        assertFalse(schedule.advance(.04));
        assertTrue(schedule.advance(.07));
        assertFalse(schedule.ready("hub", 10, 20));
        assertTrue(schedule.ready("hub", 11, 20), "Walking towards a new segment must not wait for the old emission interval");
        assertTrue(schedule.ready("adventure", 10, 20));
        for (int i = 0; i < 9; i++) schedule.advance(.1);
        assertFalse(schedule.ready("hub", 10, 20), "Leaving and returning must not duplicate an active emitter");
        schedule.advance(.3);
        assertTrue(schedule.ready("hub", 10, 20));
    }
    @Test void stalledTicksDoNotAccumulateCatchupBursts() {
        var schedule = new BoundaryEmissionSchedule();
        assertTrue(schedule.advance(30));
        schedule.emitted("hub", 1, 1);
        assertFalse(schedule.advance(.01));
        assertFalse(schedule.ready("hub", 1, 1));
    }
}
