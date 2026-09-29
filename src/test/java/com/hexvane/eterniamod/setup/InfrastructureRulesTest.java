package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.housing.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InfrastructureRulesTest {
    private static final PlotRect ROAD=new PlotRect(0,0,4,160),PLAZA=new PlotRect(4,0,24,24);
    private static HousingInfrastructure.WorldPlan plan(){return new HousingInfrastructure.WorldPlan("hub",List.of(),List.of(),null,true).withAreas(Map.of("main-road",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,ROAD),"welcome-plaza",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,PLAZA)));}
    private static HousingRules.Anchor plot(int x,int z,boolean house){return new HousingRules.Anchor(UUID.randomUUID(),new PlotRect(x,z,24,24),HousingRules.Scope.PUBLIC,null,house);}
    @Test void oppositeCornersAreInclusiveAndLargeOrOverflowedSelectionsFail(){
        assertEquals(new PlotRect(-2,3,5,8),InfrastructureRules.selection(2,10,-2,3));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.selection(0,0,1023,1023));
        assertThrows(ArithmeticException.class,()->InfrastructureRules.selection(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0));
    }
    @Test void removalCannotDisconnectAnEmptyClaimOrAnEstablishedHouseChain(){
        var before=plan();var areas=new TreeMap<>(before.areas());areas.remove("welcome-plaza");var after=before.withAreas(areas);
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,after,List.of(plot(8,28,false)),List.of()));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,after,List.of(plot(8,28,true),plot(8,56,true)),List.of()));
    }
    @Test void replacingOnePortalWithAnotherThatSupportsTheSameClaimsIsAllowed(){
        var before=plan();var areas=new TreeMap<>(before.areas());areas.put("welcome-plaza",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,new PlotRect(4,1,24,24)));
        assertDoesNotThrow(()->InfrastructureRules.validate(before,before.withAreas(areas),List.of(plot(8,28,false)),List.of()));
    }
    @Test void removingTheOnlyRoadIsRejectedEvenWhenPortalSupportRemains(){
        var before=plan();var areas=new TreeMap<>(before.areas());areas.remove("main-road");
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withAreas(areas),List.of(plot(8,28,true)),List.of()));
    }
    @Test void existingRoadColumnsInsideAClaimCannotLoseTheirProtection(){
        var before=plan();var areas=new TreeMap<>(before.areas());areas.remove("main-road");
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withAreas(areas),List.of(plot(0,28,true)),List.of()));
    }
    @Test void registrationCannotOverwriteClaimsOrPendingConstruction(){
        var before=plan();var areas=new TreeMap<>(before.areas());areas.put("side-road",new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.ROAD,new PlotRect(8,30,24,1)));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withAreas(areas),List.of(plot(8,28,true)),List.of()));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withAreas(areas),List.of(),List.of(new PlotRect(8,30,24,1))));
    }
    @Test void aGuildPortalMustContinueSupportingTheEstateAndMemberChain(){
        var before=plan();UUID guild=UUID.randomUUID();var root=new HousingRules.Anchor(UUID.randomUUID(),new PlotRect(8,28,48,48),HousingRules.Scope.GUILD_ROOT,guild,true);
        var member=new HousingRules.Anchor(UUID.randomUUID(),new PlotRect(60,28,24,24),HousingRules.Scope.GUILD_MEMBER,guild,true);
        var areas=new TreeMap<>(before.areas());areas.remove("welcome-plaza");
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withAreas(areas),List.of(root,member),List.of()));
    }
    @Test void housingCannotBeDisabledOrPublicArrivalMovedInsideAnExistingClaim(){
        var before=plan();var claims=List.of(plot(8,28,true));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withRole("hub",false),claims,List.of()));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withArrival(new HousingInfrastructure.Point(10,70,30)),claims,List.of()));
        assertDoesNotThrow(()->InfrastructureRules.validate(before,before.withArrival(new HousingInfrastructure.Point(12,70,12)),claims,List.of()));
    }
    @Test void unfinishedPavingKeepsItsWorldRecoveryRoleAndCannotBecomeAnArrival(){
        var before=plan();var pending=List.of(new PlotRect(40,40,4,12));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withRole("adventure",false),List.of(),pending));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withRole("hub",false),List.of(),pending));
        assertThrows(IllegalArgumentException.class,()->InfrastructureRules.validate(before,before.withArrival(new HousingInfrastructure.Point(41,70,42)),List.of(),pending));
        assertDoesNotThrow(()->InfrastructureRules.validate(before,before.withArrival(new HousingInfrastructure.Point(12,70,12)),List.of(),pending));
    }
}
