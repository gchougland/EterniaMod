package com.hexvane.eterniamod.collections;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PetBehaviorTest {
    private static PetBehavior.Step run(PetBehavior pet,double seconds,double speed,boolean ownerMoves,double random) {
        PetBehavior.Step step=null;
        for(double t=0;t<seconds;t+=.05)step=pet.update(.05,speed,false,ownerMoves,random);
        return step;
    }
    @Test void speedPicksWalkOrRunAndStandingStillIsIdle() {
        var pet=new PetBehavior();
        assertEquals(PetBehavior.WALK,pet.update(.05,1.5,false,true,.9).movement());
        assertEquals(PetBehavior.RUN,pet.update(.05,5,false,true,.9).movement());
        var idle=pet.update(.05,0,false,true,.9);
        assertEquals(PetBehavior.IDLE,idle.movement());assertNull(idle.emote());assertFalse(idle.hold());
    }
    @Test void idlePetFidgetsWithinFourteenSecondsAndHoldsStillWhileItPlays() {
        var pet=new PetBehavior();PetBehavior.Step fidget=null;
        for(double t=0;t<14.1&&fidget==null;t+=.05){var step=pet.update(.05,0,false,true,.9);if(step.emote()!=null)fidget=step;}
        assertNotNull(fidget);assertTrue(PetBehavior.FIDGETS.contains(fidget.emote()));assertTrue(fidget.hold());
        assertTrue(pet.update(.05,0,false,true,.9).hold());
    }
    @Test void napsWhenOwnerStandsStillAndWakesWhenTheyMove() {
        var pet=new PetBehavior();
        var step=run(pet,PetBehavior.NAP_AFTER+3,0,false,.9);
        assertTrue(pet.asleep());assertEquals(PetBehavior.SLEEP,step.movement());assertTrue(step.hold());
        var wake=pet.update(.05,0,false,true,.9);
        assertEquals(PetBehavior.Emote.WAKE,wake.emote());assertFalse(pet.asleep());assertEquals(PetBehavior.IDLE,wake.movement());
    }
    @Test void catchingUpAfterAWalkCanGreetAndRecoveryIsASurprise() {
        var pet=new PetBehavior();
        run(pet,PetBehavior.GREET_AFTER+.5,1.5,true,.1);
        assertEquals(PetBehavior.Emote.HAPPY,pet.update(.05,0,false,true,.1).emote());
        assertEquals(PetBehavior.Emote.SURPRISED,pet.update(.05,0,true,true,.9).emote());
    }
    @Test void interruptReleasesHoldAndBadTicksChangeNothing() {
        var pet=new PetBehavior();
        assertTrue(pet.update(.05,0,true,true,.9).hold());
        pet.interrupt();
        assertFalse(pet.update(.05,2,false,true,.9).hold());
        assertEquals(PetBehavior.WALK,pet.update(Double.NaN,5,false,true,.9).movement());
    }
    @Test void walkingPaceRisesWithDistanceAndNeverOvershoots() {
        var near=PetMotion.walk(0,0,1,0,.05);var far=PetMotion.walk(0,0,10,0,.05);
        assertTrue(near.x()>0&&near.x()<far.x());
        assertEquals(.325,far.x(),1e-9);
        assertEquals(.5,PetMotion.walk(.49,0,.5,0,1).x(),1e-12);
        assertEquals(new PetMotion.Point(3,4),PetMotion.walk(3,4,3,4,.05));
    }
}
