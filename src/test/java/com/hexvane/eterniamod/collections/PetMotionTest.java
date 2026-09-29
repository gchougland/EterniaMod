package com.hexvane.eterniamod.collections;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PetMotionTest {
    @Test void propertyInsetContainsPetEnvelopeEvenAtNegativeWorldCoordinates() {
        var bounds=new PetMotion.Bounds(-24,-32,0,0);
        for(double value:new double[]{-1000,-32,-24,-1,0,1000}) {
            assertTrue(bounds.contains(bounds.x(value),bounds.z(value)));
            assertTrue(bounds.x(value)-.35>=bounds.minX());
            assertTrue(bounds.z(value)+.35<bounds.maxZ());
        }
    }
    @Test void largeTickNeverOvershootsAndInvalidTicksDoNotMovePet() {
        assertEquals(3,PetMotion.blend(0,10,10),1e-9);
        assertEquals(5,PetMotion.blend(5,10,Double.NaN));
        assertEquals(5,PetMotion.blend(5,10,-1));
        assertThrows(IllegalArgumentException.class,()->new PetMotion.Bounds(0,0,1,1));
    }
}
