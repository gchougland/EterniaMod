package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.setup.SetupAccess;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.UUID;

/**
 * Lays and registers public roads for /e admin town place, through the same review and confirm the Road Designer uses:
 * the same checks, the same paving, the same snapshots, and the same removal. Runs on the world thread; the actor needs
 * builder permission.
 */
public final class TownRoadAuthor {
    private TownRoadAuthor(){}
    /** Builds a road through the nodes (x, scan height, z) and returns its id; the ground must already be graded. */
    public static UUID author(World world,UUID actor,List<double[]> nodes,int width)throws Exception{
        SetupAccess.require(actor);var service=SplineRoadTool.service();
        var styles=service.styles();var style=styles.stream().filter(s->s.id().contains("cobble")).findFirst().orElse(styles.get(0));
        var review=service.review(world,actor,nodes.stream().map(n->new SplineGeometry.Node(n[0],n[1],n[2])).toList(),width,style.id(),null);
        try{service.confirm(world,review);}
        catch(Exception failure){
            // An interrupted build keeps its cells locked; put the ground back so nothing half built is left joined to the town.
            try{if(service.find(review.pending().id())!=null&&service.find(review.pending().id()).state()==SplineRoadStore.State.PENDING)service.recover(world,actor,review.pending().id());}
            catch(Exception ignored){failure.addSuppressed(ignored);}
            throw failure;
        }
        return review.pending().id();
    }
    /** A road that joins onto another road's surface: its id, whether it was left half built, and a spot on it. */
    public record Joiner(UUID id,boolean pending,int x,int z){}

    /** Roads whose junctions rest on road {@code id}, so it cannot be removed before them. */
    public static List<Joiner> joiners(World world,UUID id){
        var service=SplineRoadTool.service();var road=service.find(id);
        if(road==null||road.current()==null)return List.of();
        var cells=new java.util.HashSet<SplineGeometry.Column>();road.current().cells().forEach(c->cells.add(c.column()));
        var out=new java.util.ArrayList<Joiner>();
        for(var other:service.roads()){
            if(other.id().equals(id)||!other.world().equals(world.getName())||other.state()==SplineRoadStore.State.REMOVED)continue;
            var hit=RoadConnectionRules.connections(other).stream().filter(c->cells.contains(c.column())).findFirst();
            hit.ifPresent(c->out.add(new Joiner(other.id(),other.state()==SplineRoadStore.State.PENDING,c.x(),c.z())));
        }
        return List.copyOf(out);
    }

    /** A road left unfinished in a world: its id and the box of columns it locks (minX, minZ, maxX, maxZ). */
    public record Unfinished(UUID id,int[] box){}

    /** Every road build left unfinished in this world. Each one keeps its cells locked and stops new claims. */
    public static List<Unfinished> unfinished(World world){
        var out=new java.util.ArrayList<Unfinished>();
        for(var road:SplineRoadTool.service().roads()){
            if(!road.world().equals(world.getName())||road.state()!=SplineRoadStore.State.PENDING||road.change()==null)continue;
            int x0=Integer.MAX_VALUE,z0=Integer.MAX_VALUE,x1=Integer.MIN_VALUE,z1=Integer.MIN_VALUE;
            for(var c:road.change().cells()){x0=Math.min(x0,c.x());z0=Math.min(z0,c.z());x1=Math.max(x1,c.x());z1=Math.max(z1,c.z());}
            out.add(new Unfinished(road.id(),new int[]{x0,z0,x1,z1}));
        }
        return List.copyOf(out);
    }

    /** Puts back the ground of a road whose build was interrupted. */
    public static void recover(World world,UUID actor,UUID id)throws Exception{SetupAccess.require(actor);SplineRoadTool.service().recover(world,actor,id);}

    /** Lifts the lock of an unfinished road that can no longer be put back, leaving its ground as it is. */
    public static void release(World world,UUID actor,UUID id)throws Exception{SetupAccess.require(actor);SplineRoadTool.service().release(world,actor,id);}

    /** Takes the town's remaining roads away together, newest first, when they cannot go one at a time. Returns how many roads' ground could not be written back. */
    public static int forceRemove(World world,UUID actor,List<UUID> newestFirst)throws Exception{SetupAccess.require(actor);return SplineRoadTool.service().forceRemove(world,actor,newestFirst);}

    /** Removes a road the town laid, restoring the ground it was built on. */
    public static void remove(World world,UUID actor,UUID id)throws Exception{
        SetupAccess.require(actor);var service=SplineRoadTool.service();service.confirm(world,service.reviewRemoval(world,actor,id));
    }
}
