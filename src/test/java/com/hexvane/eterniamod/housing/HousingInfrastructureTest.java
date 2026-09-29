package com.hexvane.eterniamod.housing;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HousingInfrastructureTest {
    @TempDir Path temp;
    @Test void namedAreaEditsPersistAtomicallyAndStaleReviewsCannotOverwriteThem() throws Exception {
        var infrastructure=infrastructure();var before=infrastructure.snapshot();var plan=before.worlds().get("housing");
        var legacyNames=plan.areas().keySet();assertEquals(2,legacyNames.size());
        var areas=new java.util.TreeMap<>(plan.areas());areas.put("village-square",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,new PlotRect(40,40,10,10)));
        infrastructure.saveWorld(before.revision(),"housing",plan.withAreas(areas));
        assertEquals(before.revision()+1,infrastructure.snapshot().revision());
        assertThrows(IOException.class,()->infrastructure.saveWorld(before.revision(),"housing",plan));
        infrastructure.load();var saved=infrastructure.world("housing").orElseThrow();assertTrue(saved.areas().keySet().containsAll(legacyNames));
        assertEquals(new PlotRect(40,40,10,10),saved.areas().get("village-square").rect());
        assertTrue(infrastructure.publicPortalColumn("housing",45,45));
        try(var files=Files.list(temp)){assertEquals(0,files.filter(path->path.getFileName().toString().contains(".pending-")).count());}
    }
    @Test void externalFileChangesRequireReloadInsteadOfBeingOverwritten() throws Exception {
        var infrastructure=infrastructure();var before=infrastructure.snapshot();Path file=temp.resolve("infrastructure.json");
        Files.writeString(file,Files.readString(file)+"\n");
        assertThrows(IOException.class,()->infrastructure.saveWorld(before.revision(),"housing",before.worlds().get("housing")));
        assertEquals(before,infrastructure.snapshot());
        infrastructure.load();infrastructure.saveWorld(before.revision(),"housing",before.worlds().get("housing"));
    }
    @Test void malformedNamedAreaReloadRetainsLastWorkingRegistry() throws Exception {
        var infrastructure=infrastructure();var before=infrastructure.snapshot();
        Files.writeString(temp.resolve("infrastructure.json"),"""
            {"version":1,"worlds":{"default":{"role":"hub","roads":[],"portals":[],
              "areas":{"plaza":{"kind":"PORTAL","rect":{"x":1,"z":1,"width":2,"depth":2}}}}}}
            """);
        assertThrows(IOException.class,infrastructure::load);assertEquals(before,infrastructure.snapshot());
        assertThrows(IllegalArgumentException.class,()->HousingInfrastructure.requireAreaId("../world"));
    }
    @Test void aHubCanHostHousingWithoutLosingItsHubIdentityOrInfrastructure() throws Exception {
        Path file=temp.resolve("combined.json");
        Files.writeString(file,"""
            {"version":1,"worlds":{"default":{"role":"hub","housingEnabled":true,
              "roads":[{"x":0,"z":0,"width":4,"depth":100}],
              "portals":[{"x":4,"z":0,"width":4,"depth":4}],
              "arrival":{"x":5.5,"y":70,"z":1.5}}}}
            """);
        var infrastructure=new HousingInfrastructure(file,w->List.of());infrastructure.load();
        var plan=infrastructure.world("default").orElseThrow();
        assertEquals("hub",plan.role());
        assertTrue(infrastructure.isHousing("default"));
        assertEquals(new HousingInfrastructure.Point(5.5,70,1.5),plan.arrival());
        assertTrue(infrastructure.publicProtectedColumn("default",0,50));
        assertTrue(infrastructure.publicPortalColumn("default",5,1));
        var candidate=new HousingRules.Candidate(new PlotRect(7,8,24,24),HousingRules.Scope.PUBLIC,null,false,0);
        assertTrue(HousingRules.claim(candidate,List.of(),plan.roads(),plan.portals()).valid());
        assertTrue(plan.allowsPublicTravelFrom(new PlotRect(5,1,1,1)));
        assertFalse(plan.allowsPublicTravelFrom(new PlotRect(24,24,1,1)),"A distant house must not inherit the old whole-hub free teleport permission");
    }
    @Test void legacyWorldRolesKeepTheirBehaviorWhenTheOptionalHousingFlagIsMissing() throws Exception {
        Path file=temp.resolve("legacy.json");
        Files.writeString(file,"""
            {"version":1,"worlds":{
              "Hub":{"role":"hub","roads":[],"portals":[]},
              "Housing":{"role":"housing","roads":[],"portals":[]},
              "Wilds":{"role":"adventure","roads":[],"portals":[]}}}
            """);
        var infrastructure=new HousingInfrastructure(file,w->List.of());infrastructure.load();
        assertFalse(infrastructure.isHousing("Hub"));
        assertTrue(infrastructure.isHousing("Housing"));
        assertFalse(infrastructure.isHousing("Wilds"));
        assertTrue(infrastructure.world("Hub").orElseThrow().allowsPublicTravelFrom(new PlotRect(1000,1000,1,1)));
        assertFalse(infrastructure.world("Housing").orElseThrow().allowsPublicTravelFrom(new PlotRect(1000,1000,1,1)));
        assertThrows(IllegalArgumentException.class,()->new HousingInfrastructure.WorldPlan("adventure",List.of(),List.of(),null,true));
    }
    private HousingInfrastructure infrastructure() throws IOException {
        Path file=temp.resolve("infrastructure.json");
        Files.writeString(file,"""
            {"version":1,"worlds":{"housing":{"role":"housing","roads":[{"x":0,"z":0,"width":2,"depth":1}],
            "portals":[{"x":4,"z":0,"width":1,"depth":1}],"arrival":{"x":4,"y":1,"z":0}}}}
            """);
        var result=new HousingInfrastructure(file,w->w.equals("housing")?List.of(new PlotRect(0,0,10,1),new PlotRect(20,0,5,1)):List.of());
        result.load();return result;
    }
    @Test void dynamicRoadsProtectColumnsAndStructureSetbacksWithoutCreatingPublicClaimAnchors() throws Exception {
        var infrastructure=infrastructure();
        assertTrue(infrastructure.protectedColumn("housing",8,0));
        assertTrue(infrastructure.intersectsProtected("housing",new PlotRect(22,-2,1,4)));
        assertFalse(infrastructure.protectedColumn("adventure",8,0));
        assertEquals(List.of(new PlotRect(0,0,2,1)),infrastructure.world("housing").orElseThrow().roads());
        assertEquals(3,infrastructure.structureRoads("housing").size());
        assertFalse(infrastructure.publicProtectedColumn("housing",8,0));
        assertTrue(infrastructure.publicPortalColumn("housing",4,0));
        var plot=new PlotRect(0,0,32,32);var house=new PlotRect(5,5,14,14);
        assertTrue(HousingRules.structure(plot,house,infrastructure.world("housing").orElseThrow().roads()).valid());
        assertEquals("roadSetback",HousingRules.structure(plot,house,infrastructure.structureRoads("housing")).code());
    }
    @Test void aRoadJournalCanEditOnlyItsExactDynamicColumnsAndCannotBypassPublicInfrastructure() throws Exception {
        var infrastructure=infrastructure();
        infrastructure.withGuildRoadWrite("housing",new PlotRect(0,0,10,1),()->{
            assertTrue(infrastructure.protectedColumn("housing",0,0));
            assertTrue(infrastructure.protectedColumn("housing",4,0));
            assertFalse(infrastructure.protectedColumn("housing",8,0));
            assertTrue(infrastructure.protectedColumn("housing",20,0));
            assertFalse(infrastructure.intersectsProtected("housing",new PlotRect(8,0,2,1)));
            assertTrue(infrastructure.intersectsProtected("housing",new PlotRect(8,0,13,1)));
            assertTrue(infrastructure.intersectsProtected("housing",new PlotRect(3,0,2,1)));
            assertEquals(3,infrastructure.structureRoads("housing").size());
            assertTrue(CompletableFuture.supplyAsync(()->infrastructure.protectedColumn("housing",8,0)).get());
            return null;
        });
        assertTrue(infrastructure.protectedColumn("housing",8,0));
        assertThrows(IllegalArgumentException.class,()->infrastructure.withGuildRoadWrite("housing",new PlotRect(0,0,2,2),()->null));
    }
    @Test void nestedRoadScopesAndExceptionalReturnsRestoreTheirPreviousProtection() throws Exception {
        var infrastructure=infrastructure();
        assertThrows(IOException.class,()->infrastructure.withGuildRoadWrite("housing",new PlotRect(8,0,1,1),()->{
            assertFalse(infrastructure.protectedColumn("housing",8,0));
            infrastructure.withGuildRoadWrite("housing",new PlotRect(20,0,1,1),()->{
                assertTrue(infrastructure.protectedColumn("housing",8,0));
                assertFalse(infrastructure.protectedColumn("housing",20,0));
                return null;
            });
            assertFalse(infrastructure.protectedColumn("housing",8,0));
            assertTrue(infrastructure.protectedColumn("housing",20,0));
            throw new IOException("Interrupted road mutation");
        }));
        assertTrue(infrastructure.protectedColumn("housing",8,0));
        assertTrue(infrastructure.protectedColumn("housing",20,0));
    }
}
