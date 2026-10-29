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
        service.confirm(world,review);
        return review.pending().id();
    }
    /** Removes a road the town laid, restoring the ground it was built on. */
    public static void remove(World world,UUID actor,UUID id)throws Exception{
        SetupAccess.require(actor);var service=SplineRoadTool.service();service.confirm(world,service.reviewRemoval(world,actor,id));
    }
}
