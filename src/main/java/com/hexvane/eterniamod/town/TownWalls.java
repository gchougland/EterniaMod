package com.hexvane.eterniamod.town;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The town wall's body, walk, parapets and stairs, column by column at the heights TownTerrainPlanner chose: the same
 * wall as tools/prefabs/Eternia_Town_Walls.build.mjs, stepping with the ground. Pure; towers and gatehouses are kit
 * prefabs pasted whole, so this leaves their footprints alone. Only single-cell blocks.
 *
 * Heights follow the prefab convention: the ground block of a column is its base, a wall's y -1, so wall y k is at
 * base + 1 + k.
 */
public final class TownWalls {
    /** One block to write: the id (or "Empty") and its rotation index. */
    public record Edit(int x,int y,int z,String block,int rotation){}
    static final int SOUTH=0,EAST=1,NORTH=2,WEST=3;
    static int facing(String compass){return switch(compass){case "east"->EAST;case "north"->NORTH;case "west"->WEST;default->SOUTH;};}
    static int opposite(int f){return (f+2)%4;}
    static int upsideDown(int f){return 8+(f+2)%4;}
    static String brick(int x,int y,int z){int n=hash(x,y,z)%8;return n<5?"Rock_Stone_Brick":n<7?"Rock_Stone_Brick_Smooth":"Rock_Stone";}
    static String kerb(int x,int y,int z){return hash(x,y,z)%5<3?"Rock_Stone_Brick_Decorative":"Rock_Stone_Brick_Smooth";}
    static String footing(int x,int y,int z){return hash(x,y,z)%4==0?"Rock_Stone_Cobble_Mossy":"Rock_Stone_Cobble";}
    static int hash(int x,int y,int z){int h=x*73856093^y*19349663^z*83492791;h^=h>>>13;h*=0x5bd1e995;h^=h>>>15;return h&0x7fffffff;}

    public static Map<Long,Edit> generate(TownPlan plan,TownTerrainPlanner.Result r){
        var out=new LinkedHashMap<Long,Edit>();var w=plan.walls;int inner=w.inner,outer=w.inner+w.thick-1,walk=w.walk,tops=walk-1;
        for(var side:w.sides.values()){
            int along=facing(side.along),out_=facing(side.out),in=opposite(out_);
            for(int a=-outer;a<=outer;a++){
                if(inGate(w,side,a))continue;
                int b=base(r,side,a,outer);
                for(int q=inner;q<=outer;q++){int x=x(side,a,q),z=z(side,a,q);if(inTower(w,x,z))continue;
                    int bottom=Math.min(b,ground(r,x,z,b))-1;
                    for(int y=bottom;y<=b+1+tops;y++){int k=y-b-1;put(out,x,y,z,k<=0?footing(x,y,z):k==5&&q==outer?"Rock_Stone_Brick_Decorative":brick(x,y,z),0);}
                    // The walk steps up one block where the next column is higher: a stair, climbing towards it.
                    if(q>inner&&q<outer){int ahead=base(r,side,a+1,outer),behind=base(r,side,a-1,outer);
                        if(ahead==b+1&&!inGate(w,side,a+1))put(out,x,b+1+walk,z,"Rock_Stone_Brick_Stairs",opposite(along));
                        else if(behind==b+1&&!inGate(w,side,a-1))put(out,x,b+1+walk,z,"Rock_Stone_Brick_Stairs",along);}}
                int ix=x(side,a,inner),iz=z(side,a,inner),ox=x(side,a,outer),oz=z(side,a,outer);
                if(!inTower(w,ix,iz))put(out,ix,b+1+walk,iz,kerb(ix,a,iz),0);
                if(!inTower(w,ox,oz)){put(out,ox,b+1+walk,oz,kerb(ox,a,oz),0);if(Math.floorMod(a,2)==0)put(out,ox,b+2+walk,oz,"Rock_Stone_Brick_Decorative",0);}
                if(Math.floorMod(a,16)==8&&!inTower(w,ix,iz))put(out,ix,b+2+walk,iz,"Deco_Lantern",0);
                // Outside: a battered plinth, a buttress every 12. Inside: a pilaster every 12.
                int px=x(side,a,outer+1),pz=z(side,a,outer+1);
                if(!inTower(w,px,pz)){int bottom=Math.min(b,ground(r,px,pz,b))-1;for(int y=bottom;y<=b+1;y++)put(out,px,y,pz,footing(px,y,pz),0);
                    if(Math.floorMod(a,12)==6){for(int k=1;k<=7;k++)put(out,px,b+1+k,pz,brick(px,k,pz),0);put(out,px,b+9,pz,"Rock_Stone_Brick_Stairs",out_);}
                    else put(out,px,b+2,pz,"Rock_Stone_Brick_Stairs",out_);}
                int lx=x(side,a,inner-1),lz=z(side,a,inner-1);
                if(Math.floorMod(a,12)==0&&!inTower(w,lx,lz)&&Math.abs(a)<outer-8){int bottom=Math.min(b,ground(r,lx,lz,b));for(int y=bottom;y<=b+7;y++)put(out,lx,y,lz,y==bottom?"Rock_Stone_Cobble":brick(lx,y,lz),0);put(out,lx,b+8,lz,"Rock_Stone_Brick_Stairs",in);}
            }
        }
        stairs(plan,r,out);
        return out;
    }
    /** Stairs up the town side of the wall beside every tower and gate, from the Wall Lane's ground to the walk. */
    static void stairs(TownPlan plan,TownTerrainPlanner.Result r,Map<Long,Edit> out){
        var w=plan.walls;int inner=w.inner,outer=w.inner+w.thick-1;
        for(var spot:w.stairs){var side=w.sides.get(spot.side);int along=facing(side.along);
            int landing=spot.landing,b=base(r,side,landing,outer),walkSurface=b+w.walk;   // the walk's top block: wall y walk - 1
            for(int a:new int[]{landing,landing+1}){int x=x(side,a,inner-1),z=z(side,a,inner-1),g=ground(r,x,z,b);
                for(int y=Math.min(g,walkSurface);y<=walkSurface;y++)put(out,x,y,z,brick(x,y,z),0);for(int y=walkSurface+1;y<=walkSurface+3;y++)put(out,x,y,z,"Empty",0);
                int px=x(side,a,inner),pz=z(side,a,inner);int pb=base(r,side,a,outer);put(out,px,pb+1+w.walk,pz,"Empty",0);put(out,px,pb+2+w.walk,pz,"Empty",0);}
            for(int i=0;;i++){int a=landing+2+i,x=x(side,a,inner-1),z=z(side,a,inner-1),y=walkSurface-1-i,g=ground(r,x,z,b);
                if(y<=g||i>40||inTower(w,x,z)||inGate(w,side,a))break;
                for(int yy=g;yy<y;yy++)put(out,x,yy,z,brick(x,yy,z),0);put(out,x,y,z,"Rock_Stone_Brick_Stairs",along);
                for(int yy=y+1;yy<=y+3;yy++)put(out,x,yy,z,"Empty",0);}
        }
    }
    static int x(TownPlan.Side s,int a,int q){return s.ax*a+s.qx*q;}
    static int z(TownPlan.Side s,int a,int q){return s.az*a+s.qz*q;}
    static int base(TownTerrainPlanner.Result r,TownPlan.Side s,int a,int outer){return r.wallBase(x(s,a,outer-1),z(s,a,outer-1));}
    static int ground(TownTerrainPlanner.Result r,int x,int z,int fallback){int t=r.target(x,z);if(t!=TownTerrainPlanner.UNKNOWN)return t;int n=r.natural(x,z);return n==TownTerrainPlanner.UNKNOWN?fallback:n;}
    static boolean inGate(TownPlan.Walls w,TownPlan.Side s,int a){for(var g:w.gates)if(w.sides.get(g.side)==s&&Math.abs(a-g.at)<=g.half)return true;return false;}
    static boolean inTower(TownPlan.Walls w,int x,int z){for(var t:w.towers)if(Math.hypot(x-t.x,z-t.z)<=t.r+.5)return true;return false;}
    static long key(int x,int y,int z){return ((long)(x&0x3FFFFFF)<<38)|((long)(z&0x3FFFFFF)<<12)|(y&0xFFF);}
    static void put(Map<Long,Edit> out,int x,int y,int z,String block,int rotation){out.put(key(x,y,z),new Edit(x,y,z,block,rotation));}
}
