package com.hexvane.eterniamod.collections;

import java.util.List;

/**
 * Pure mood machine for pets whose model can walk. Picks the looping movement clip from the pet's speed,
 * plays a one shot emote now and then while idle, naps when the owner stands still, and wakes when they leave.
 * Clip names are the pet model's AnimationSets ids; the caller sends them and holds the pet still while {@link Step#hold()}.
 */
public final class PetBehavior {
    public static final String IDLE="Idle",WALK="Walk",RUN="Run",SLEEP="Sleep";
    public enum Emote {
        HAPPY("Happy",1.7),LOOK_AROUND("LookAround",2.5),WIGGLE("Wiggle",2.0),SURPRISED("Surprised",1.2),WAKE("Wake",1.0);
        public final String clip;public final double seconds;
        Emote(String clip,double seconds){this.clip=clip;this.seconds=seconds;}
    }
    static final List<Emote> FIDGETS=List.of(Emote.HAPPY,Emote.LOOK_AROUND,Emote.WIGGLE);
    static final double WALK_SPEED=.25,RUN_SPEED=3.2,NAP_AFTER=40,FIDGET_MIN=6,FIDGET_SPREAD=8,GREET_AFTER=3;

    /** movement: the looping clip to show; emote: a clip to start this tick, else null; hold: keep the pet in place. */
    public record Step(String movement,Emote emote,boolean hold) {}

    private String movement=IDLE;
    private boolean asleep;
    private double still,idle,travelled,emoteLeft,nextFidget=FIDGET_MIN;

    /**
     * @param speed      blocks per second the pet moved last tick
     * @param recovered  the pet was just moved back beside an owner it lost
     * @param ownerMoves the owner moved since the last tick
     * @param random     a uniform value in [0,1) for this tick's choices
     */
    public Step update(double dt,double speed,boolean recovered,boolean ownerMoves,double random) {
        if(!Double.isFinite(dt)||dt<=0)return new Step(movement,null,emoteLeft>0||asleep);
        emoteLeft=Math.max(0,emoteLeft-dt);
        if(recovered){asleep=false;return start(Emote.SURPRISED,IDLE,random);}
        if(asleep) {
            if(!ownerMoves)return new Step(movement=SLEEP,null,true);
            asleep=false;still=0;return start(Emote.WAKE,IDLE,random);
        }
        still=ownerMoves?0:still+dt;
        if(emoteLeft>0)return new Step(movement,null,true);
        boolean moving=Double.isFinite(speed)&&speed>WALK_SPEED;
        if(moving) {
            travelled+=dt;idle=0;
            return new Step(movement=speed>RUN_SPEED?RUN:WALK,null,false);
        }
        idle+=dt;
        if(travelled>=GREET_AFTER&&random<.5){travelled=0;return start(Emote.HAPPY,IDLE,random);}
        travelled=0;
        if(still>=NAP_AFTER){asleep=true;return new Step(movement=SLEEP,null,true);}
        if(idle>=nextFidget){idle=0;return start(FIDGETS.get(Math.min(FIDGETS.size()-1,(int)(random*FIDGETS.size()))),IDLE,random);}
        return new Step(movement=IDLE,null,false);
    }
    private Step start(Emote emote,String rest,double random) {
        emoteLeft=emote.seconds;idle=0;nextFidget=FIDGET_MIN+random*FIDGET_SPREAD;
        return new Step(movement=rest,emote,true);
    }
    /** The owner got too far away: drop any nap or emote so the pet can follow at once. */
    public void interrupt(){emoteLeft=0;asleep=false;still=0;}
    public boolean asleep(){return asleep;}
}
