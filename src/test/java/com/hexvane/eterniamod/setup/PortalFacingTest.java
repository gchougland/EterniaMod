package com.hexvane.eterniamod.setup;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortalFacingTest {
    @Test void facesViewerFromAllFourSidesIncludingAcrossNegativeCoordinates() {
        assertFacing(10.5,5,Rotation.None);
        assertFacing(5,10.5,Rotation.Ninety);
        assertFacing(10.5,15,Rotation.OneEighty);
        assertFacing(15,10.5,Rotation.TwoSeventy);
        assertEquals(PortalFacing.rotation((float)(-Math.PI/2)),PortalFacing.rotation((float)(3*Math.PI/2)));
        assertEquals(PortalFacing.rotation(0),PortalFacing.rotation(PortalFacing.toward(-12,-10,-11.5,-15,2)));
    }
    private static void assertFacing(double x,double z,Rotation expected) {
        assertEquals(RotationTuple.of(expected,Rotation.None,Rotation.None).index(),PortalFacing.rotation(PortalFacing.toward(10,10,x,z,0)));
    }
    @Test void snapsNearCardinalAndUsesFallbackOnlyWhenDirectlyAboveCenter() {
        assertEquals(PortalFacing.rotation(0),PortalFacing.rotation(.2f));
        assertEquals(1.2f,PortalFacing.toward(10,10,10.5,10.5,1.2f));
    }
    @Test void legacyTagsStayUnrotatedAndNewTagsRetainRotationWhenCloned() {
        UUID id=UUID.randomUUID();var legacy=new ManagedPortalTag(id);
        assertEquals(0,legacy.rotation());
        var current=new ManagedPortalTag(id,PortalFacing.rotation((float)(Math.PI/2)));
        var copied=(ManagedPortalTag)current.clone();assertTrue(copied.matches(id));assertEquals(current.rotation(),copied.rotation());
    }
}
