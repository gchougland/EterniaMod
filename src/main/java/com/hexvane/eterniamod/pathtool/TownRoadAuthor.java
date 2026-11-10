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

    /** Puts back the ground of a road whose build was interrupted. */
    public static void recover(World world,UUID actor,UUID id)throws Exception{SetupAccess.require(actor);SplineRoadTool.service().recover(world,actor,id);}

    /** Removes a road the town laid, restoring the ground it was built on. */
    public static void remove(World world,UUID actor,UUID id)throws Exception{
        SetupAccess.require(actor);var service=SplineRoadTool.service();service.confirm(world,service.reviewRemoval(world,actor,id));
    }
}
