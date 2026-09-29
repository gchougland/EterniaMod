package com.hexvane.eterniamod.guildroads;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;

/** Called only by NativeSmoke after its isolated local-fixture guard and guild/member claims. */
public final class NativeGuildRoadSmoke {
    private NativeGuildRoadSmoke(){}
    public record Proof(UUID road,NativeSnapshotStore.Snapshot terrain){}
    public static Proof prepareForMemberPack(EterniaModPlugin plugin,World world,UUID leader,UUID member)throws Exception{
        var service=GuildRoads.service();var files=NativePlacementTransactions.snapshots(plugin);
        var root=service.preview(world,leader,new GuildRoadPlanner.Cell(50,10),new GuildRoadPlanner.Cell(55,10),0,"cobblestone");
        service.confirm(world,root);check(GuildRoads.protectedColumn(world.getName(),52,10),"Built road must protect its full column");
        service.confirm(world,service.previewRemoval(world,leader,root.road().id()));
        var original=files.load(root.road().before());files.verify(world,original,original.bounds().origin(),Set.of(),false);
        check(!GuildRoads.protectedColumn(world.getName(),52,10),"Removing road must release its protection");
        var rebuilt=service.preview(world,leader,new GuildRoadPlanner.Cell(55,10),new GuildRoadPlanner.Cell(50,10),0,"cobblestone");
        service.confirm(world,rebuilt);service.confirm(world,service.previewRemoval(world,leader,rebuilt.road().id()));
        var plot=service.personalPlot(world,member,100,9);check(plot!=null,"Expected fixture member plot");
        expectFailure(()->service.preview(world,leader,new GuildRoadPlanner.Cell(102,9),new GuildRoadPlanner.Cell(105,9),0,"cobblestone"),"A leader cannot build on a member plot without an easement");
        service.setEasement(world,member,plot.getPlotId(),true);
        var stale=service.preview(world,leader,new GuildRoadPlanner.Cell(102,9),new GuildRoadPlanner.Cell(105,9),0,"cobblestone");
        service.setEasement(world,member,plot.getPlotId(),false);
        expectFailure(()->service.confirm(world,stale),"Revoked owner consent must invalidate a preview");
        service.setEasement(world,member,plot.getPlotId(),true);
        var attached=service.preview(world,leader,new GuildRoadPlanner.Cell(102,9),new GuildRoadPlanner.Cell(105,9),0,"cobblestone");
        service.confirm(world,attached);
        expectFailure(()->service.setEasement(world,member,plot.getPlotId(),false),"An easement cannot strand an existing road");
        check(GuildRoads.protectedColumn(world.getName(),103,9)&&GuildRoads.protectedColumn(world.getName(),105,9),"Road must protect the selected member plot columns");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_ROAD_SMOKE_STAGE: root build/remove/rebuild verified; owner consent and stale preview checked; attached road awaits forced pack");
        return new Proof(attached.road().id(),files.load(attached.road().before()));
    }
    public static void assertMemberPackRestoredTerrain(EterniaModPlugin plugin,World world,Proof proof){
        check(GuildRoads.service().roads().stream().noneMatch(r->r.id().equals(proof.road())),"Member packing must remove the complete crossing road segment");
        check(!GuildRoads.protectedColumn(world.getName(),103,9)&&!GuildRoads.protectedColumn(world.getName(),105,9),"Packed plot must release old road protection");
        NativePlacementTransactions.snapshots(plugin).verify(world,proof.terrain(),proof.terrain().bounds().origin(),Set.of(),false);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_ROAD_SMOKE_PASS: exact terrain restored before forced member packing");
    }
    private interface Action{void run()throws Exception;}
    private static void expectFailure(Action action,String message)throws Exception{try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new IllegalStateException(message);}
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
