package com.hexvane.eterniamod.town;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The Eternia town as /e admin town place builds it (docs/implementation/town-placement.md), read from
 * Server/EterniaMod/Town/Eternia_Town.json. tools/prefabs/Eternia_Old_Town.build.mjs --plan writes that file from the same
 * layout the prefab builds use. Coordinates are blocks from the town's anchor: x east, z south; y from the anchor's height.
 */
public final class TownPlan {
    public static final String RESOURCE="Server/EterniaMod/Town/Eternia_Town.json";
    public int version;
    public Pad pad;
    public List<Piece> lots,features;
    public List<int[]> trees;
    /** Street cells as [x, z, edge]: edge 1 is a kerb row. */
    public List<int[]> streets;
    public Walls walls;
    public List<Road> roads;
    public Rect plaza;
    public int[] arrival;
    public Spot portal;
    public List<Npc> npcs;

    public static final class Pad {public int radius;public List<Piece> pieces;}
    /** A prefab pasted unturned at its anchor; min and max bound its footprint, front is the street cell before its door. */
    public static final class Piece {public String kind,prefab;public int[] at,min,max,front;}
    public static final class Walls {public int inner,thick,walk,laneAt;public Map<String,Side> sides;public List<Tower> towers;public List<Gate> gates;public List<Stair> stairs;}
    /** A wall side's frame: town x = ax * a + qx * q, z = az * a + qz * q (a along the side, q out from the middle). */
    public static final class Side {public int ax,qx,az,qz,turns;public String out,along;}
    public static final class Tower {public int x,z,r,top;public int[] door;public String prefab;}
    public static final class Gate {public String side,prefab;public int at,half;public int[] origin;}
    public static final class Stair {public String side;public int landing;}
    public static final class Road {public String id,name;public int width;public List<int[]> nodes;}
    public static final class Rect {public int[] min,max;}
    public static final class Spot {public int[] at;public String facing;}
    public static final class Npc {public String role,facing;public int[] at;}

    public static TownPlan load()throws IOException{
        try(var stream=TownPlan.class.getClassLoader().getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IOException("The town plan "+RESOURCE+" is missing from the mod");
            var plan=new Gson().fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),TownPlan.class);
            if(plan==null||plan.version!=1)throw new IOException("Unsupported town plan version");
            for(var road:plan.roads)if(road.width<5)throw new IOException("Road "+road.id+" is narrower than 5 blocks");
            Objects.requireNonNull(plan.walls,"walls");Objects.requireNonNull(plan.pad,"pad");
            plan.roads=gateRoads(plan);
            return plan;
        }
    }
    /** How far each entrance road reaches out of its gate, in blocks. */
    public static final int GATE_ROAD=28,GATE_GAP=6;
    /**
     * One short road straight out of each gate, lined up with it: where the planned road through that gate starts, as
     * wide as it, heading the way the gate faces. The town road network grows the rest (/e admin town roads grow).
     */
    static List<Road> gateRoads(TownPlan plan){
        var out=new java.util.ArrayList<Road>();
        if(plan.walls.gates==null||plan.roads==null)return out;
        for(var gate:plan.walls.gates){
            var side=plan.walls.sides==null?null:plan.walls.sides.get(gate.side);if(side==null)continue;
            Road nearest=null;double best=Double.MAX_VALUE;
            for(var road:plan.roads){var first=road.nodes.get(0);double d=Math.hypot(first[0]-gate.origin[0],first[1]-gate.origin[1]);if(d<best){best=d;nearest=road;}}
            if(nearest==null||best>40)continue;
            var start=nearest.nodes.get(0);var stub=new Road();
            stub.id=gate.side+"-gate-road";stub.name=nearest.name;stub.width=nearest.width;
            // It starts just past the paving in front of the gate: the Road Designer only builds on plain ground.
            int x0=start[0]+side.qx*GATE_GAP,z0=start[1]+side.qz*GATE_GAP;
            stub.nodes=List.of(new int[]{x0,z0},new int[]{x0+side.qx*GATE_ROAD,z0+side.qz*GATE_ROAD});
            out.add(stub);
        }
        return List.copyOf(out);
    }
    /** Entity yaw for a compass direction (an entity looks north at yaw 0). */
    public static float yaw(String facing){
        return switch(facing==null?"south":facing){case "north"->0f;case "west"->(float)(Math.PI/2);case "east"->(float)(-Math.PI/2);default->(float)Math.PI;};
    }
}
