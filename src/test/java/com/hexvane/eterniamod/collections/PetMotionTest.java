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
    @Test void followerTrailsItsCurrentPositionAndDoesNotOrbitOnCameraTurns() {
        var a=PetMotion.follow(0,0,8,0,0);var b=PetMotion.follow(0,0,8,0,Math.PI);
        assertEquals(a,b);assertEquals(6.2,a.x(),1e-9);assertEquals(0,a.z());
        var corner=PetMotion.follow(6,0,8,6,0);
        assertTrue(corner.x()>6&&corner.x()<8);assertTrue(corner.z()>0&&corner.z()<6);
        assertEquals(1.8,Math.hypot(8-corner.x(),6-corner.z()),1e-9);
        assertEquals(new PetMotion.Point(0,0),PetMotion.follow(0,0,0,1,Math.PI/2));
    }
    @Test void lostFollowerRecoversBehindOwner() {
        assertEquals(PetMotion.behind(100,100,Math.PI/2),PetMotion.follow(0,0,100,100,Math.PI/2));
    }
}
