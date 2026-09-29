package com.hexvane.eterniamod.housing;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.housing.HousingRules.*;

class HousingRulesTest {
    @Test void inclusiveLegacyConversionHasFiveWholeBlocksBetweenBorders() {
        PlotRect a = new PlotRect(0,0,24,24), b = new PlotRect(29,0,24,24);
        assertEquals(5,a.gap(b)); assertFalse(a.contains(24,0)); assertFalse(a.overlaps(b));
        assertEquals(Math.sqrt(50), a.gap(new PlotRect(29,29,24,24)));
        assertTrue(a.containsWithSetback(new PlotRect(5,5,14,14),5));
        assertFalse(a.containsWithSetback(new PlotRect(5,5,15,14),5));
    }
    @Test void guildMemberChainCannotBeUsedByPublicOrDifferentGuild() {
        UUID guild=UUID.randomUUID();
        var root=new Anchor(UUID.randomUUID(),new PlotRect(0,0,48,48),Scope.GUILD_ROOT,guild,true);
        var member=new Anchor(UUID.randomUUID(),new PlotRect(53,0,24,24),Scope.GUILD_MEMBER,guild,true);
        var rect=new PlotRect(82,0,24,24);
        var portals=List.of(new PlotRect(-10,0,1,1)); var roads=List.of(new PlotRect(82,-5,24,1));
        assertTrue(claim(new Candidate(rect,Scope.GUILD_MEMBER,guild,false,5),List.of(root,member),List.of(),portals).valid());
        assertFalse(claim(new Candidate(rect,Scope.PUBLIC,null,false,0),List.of(root,member),roads,portals).valid());
        assertFalse(claim(new Candidate(rect,Scope.GUILD_MEMBER,UUID.randomUUID(),false,5),List.of(root,member),roads,portals).valid());
        assertFalse(claim(new Candidate(rect,Scope.GUILD_MEMBER,guild,false,5),List.of(member),roads,portals).valid());
    }
    @Test void unbuiltAndFloatingPublicPlotsNeverAnchor() {
        var a=new Anchor(UUID.randomUUID(),new PlotRect(0,0,24,24),Scope.PUBLIC,null,false);
        var b=new Anchor(UUID.randomUUID(),new PlotRect(29,0,24,24),Scope.PUBLIC,null,true);
        assertEquals(Set.of(),reachable(List.of(a,b),List.of(new PlotRect(-5,0,1,1)),Scope.PUBLIC,null));
        assertEquals(Set.of(),reachable(List.of(new Anchor(a.id(),a.rect(),Scope.PUBLIC,null,true),b),List.of(),Scope.PUBLIC,null));
    }
    @Test void roadsMayCrossPlotsButNeverStructures() {
        var rect=new PlotRect(0,0,24,24);var road=new PlotRect(20,-20,2,100);
        assertTrue(claim(new Candidate(rect,Scope.PUBLIC,null,false,0),List.of(),List.of(road),List.of(new PlotRect(-5,0,1,1))).valid());
        assertFalse(structure(rect,new PlotRect(5,5,14,14),List.of(road)).valid());
        assertTrue(structure(rect,new PlotRect(5,5,10,14),List.of(road)).valid());
    }
    @Test void guildRectanglesHaveExactAreaAndRequireApprovalConnector() {
        assertTrue(allowedSize(new PlotRect(0,0,36,64),true,false));
        assertTrue(allowedSize(new PlotRect(0,0,128,32),true,true));
        assertFalse(allowedSize(new PlotRect(0,0,64,64),true,false));
        var candidate=new Candidate(new PlotRect(0,0,48,48),Scope.GUILD_ROOT,UUID.randomUUID(),false,5);
        assertEquals("connector",claim(candidate,List.of(),List.of(),List.of(new PlotRect(-100,0,1,1))).code());
    }
}
