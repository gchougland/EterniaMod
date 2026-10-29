package com.hexvane.eterniamod.town;

import static com.hexvane.eterniamod.town.TownTerrainPlanner.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TownTerrainPlannerTest {
    static TownPlan plan;
    static Result hilly,flat;

    @BeforeAll static void plan()throws Exception{
        plan=TownPlan.load();
        // Rolling hills that rise to the east, a valley across the south, and a knoll in the north west.
        hilly=TownTerrainPlanner.plan(plan,(x,z)->64+(int)Math.round(x*.12+8*Math.sin(z/23.0)+10*Math.exp(-(Math.pow(x+60,2)+Math.pow(z+60,2))/900.0)));
        flat=TownTerrainPlanner.plan(plan,(x,z)->70);
    }

    @Test void squareIsLevel(){
        var g=hilly.grid;for(int i=0;i<g.size();i++)if(hilly.kind[i]==PAD)assertEquals(hilly.padSurface,hilly.target[i]);
        assertEquals(hilly.padSurface+1,hilly.anchorY());
    }
    @Test void streetsStepAtMostOneBlock(){
        var g=hilly.grid;int checked=0;
        for(int i=0;i<g.size();i++){if(!walkable(hilly.kind[i]))continue;int x=g.x(i),z=g.z(i);
            for(int[] d:new int[][]{{1,0},{0,1}}){int k=hilly.kind(x+d[0],z+d[1]);if(!walkable(k))continue;checked++;
                assertTrue(Math.abs(hilly.target(x+d[0],z+d[1])-hilly.target[i])<=1,"street step at "+x+","+z);}}
        assertTrue(checked>5000);
    }
    @Test void everyLotIsOneStepAboveItsStreet(){
        for(int n=0;n<plan.lots.size();n++){var lot=plan.lots.get(n);if(hilly.kind(lot.front[0],lot.front[1])!=STREET)continue;
            assertEquals(hilly.target(lot.front[0],lot.front[1]),hilly.lotBase[n],"lot "+lot.prefab+" at "+lot.at[0]+","+lot.at[1]);
            for(int x=lot.min[0];x<=lot.max[0];x++)for(int z=lot.min[1];z<=lot.max[1];z++)assertEquals(hilly.lotBase[n],hilly.target(x,z));}
    }
    @Test void wallStepsAtMostOneBlockAndTowersAndGatesSitLevel(){
        int n=TownTerrainPlanner.ringLength(plan),steps=0;
        for(int i=0;i<n;i++){int[] a=TownTerrainPlanner.ringCell(plan,i),b=TownTerrainPlanner.ringCell(plan,i+1);int d=Math.abs(hilly.wallBase(a[0],a[1])-hilly.wallBase(b[0],b[1]));assertTrue(d<=1);steps+=d;}
        assertTrue(steps>0,"the wall should follow the hills");
        for(int k=0;k<plan.walls.towers.size();k++){var t=plan.walls.towers.get(k);assertEquals(hilly.towerBase[k],hilly.wallBase(t.x,t.z));}
        for(int k=0;k<plan.walls.gates.size();k++){var g=plan.walls.gates.get(k);var s=plan.walls.sides.get(g.side);int q=plan.walls.inner+1;
            assertEquals(hilly.gateBase[k],hilly.wallBase(s.ax*g.at+s.qx*q,s.az*g.at+s.qz*q));}
    }
    @Test void roadsAreWideLevelAcrossAndGentle(){
        assertEquals(plan.roads.size(),hilly.roads.size());
        for(var profile:hilly.roads){var road=profile.road();assertTrue(road.width>=5,road.id);
            var line=TownTerrainPlanner.centre(road);int lo=-(road.width/2),hi=road.width-1-road.width/2,previous=Integer.MIN_VALUE;
            for(var c:line){int y=Integer.MIN_VALUE;
                for(int o=lo;o<=hi;o++){int x=c[2]==0?c[0]:c[0]+o,z=c[2]==0?c[1]+o:c[1];if(hilly.kind(x,z)!=ROAD)continue;int t=hilly.target(x,z);if(y==Integer.MIN_VALUE)y=t;else assertEquals(y,t,road.id+" is not level across");}
                if(y==Integer.MIN_VALUE)continue;if(previous!=Integer.MIN_VALUE)assertTrue(Math.abs(y-previous)<=1,road.id+" steps more than a block");previous=y;}
            for(var node:profile.nodes())assertTrue(node[1]>0);}
    }
    @Test void flatLandNeedsNoCuttingOrFilling(){
        var g=flat.grid;for(int i=0;i<g.size();i++)if(flat.target[i]!=UNKNOWN)assertEquals(70,flat.target[i],"at "+g.x(i)+","+g.z(i)+" kind "+flat.kind[i]);
    }
    @Test void wallsFollowTheHillsWithStairsOnTheWalk(){
        var edits=TownWalls.generate(plan,hilly);
        assertFalse(edits.isEmpty());
        long walkStairs=edits.values().stream().filter(e->e.block().equals("Rock_Stone_Brick_Stairs")&&e.rotation()<4).count();
        assertTrue(walkStairs>20);
        for(var e:edits.values())assertFalse(TownWalls.inTower(plan.walls,e.x(),e.z()),"walls write inside a tower at "+e.x()+","+e.z());
    }
    static boolean walkable(int kind){return kind==STREET||kind==PAD;}
}
