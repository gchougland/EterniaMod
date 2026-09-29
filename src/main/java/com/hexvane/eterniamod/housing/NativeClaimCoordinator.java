package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;

/** Called on the owning world thread. Domain transactions additionally serialize reservations across worlds. */
public final class NativeClaimCoordinator {
    private final EterniaModPlugin plugin;
    public NativeClaimCoordinator(EterniaModPlugin plugin){this.plugin=plugin;}
    public HousingRules.Result validate(World world,UUID actor,PlotRect rect,HousingRules.Scope scope,boolean paid) {
        var infra=plugin.getInfrastructure().world(world.getName()).orElse(null);
        if(infra==null||!infra.supportsHousing())return new HousingRules.Result(false,"world","Use your plot deed in a world with housing enabled.");
        if(!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())return new HousingRules.Result(false,"splineRoad","An interrupted public road needs recovery before new claims can depend on this world's roads. Ask a builder to use the Road Designer's recovery action.");
        if(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world.getName()).stream().anyMatch(rect::overlaps))return new HousingRules.Result(false,"paving","This ground has an unfinished public-road edit. Ask a builder to recover it before claiming here.");
        if(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()).stream().anyMatch(road->road.overlaps(rect)))return new HousingRules.Result(false,"guildRoad","Ask a guild architect to remove the intersecting community road segment before claiming here. It can be rebuilt after the claim.");
        var services=plugin.getServices();UUID guild=services.guilds().membership(actor).map(GuildService.Membership::guildId).orElse(null);
        Owner owner=scope==HousingRules.Scope.GUILD_ROOT&&guild!=null?Owner.guild(guild):Owner.player(actor);
        var slot=services.housing().find(owner).orElse(null);
        if(slot!=null&&slot.state()!=HousingService.State.PACKED)return new HousingRules.Result(false,"slot",slot.state()==HousingService.State.ACTIVE?"This account already has a claimed plot. Open My plots below to locate it or review a move. Cancel only discards this preview; it does not unclaim an existing plot.":"A housing operation is being recovered. Open My plots below to see its state.");
        if(slot!=null){var old=services.housing().location(owner).orElse(null);if(old!=null&&(rect.width()<old.width()||rect.depth()<old.depth()||(rect.width()-old.width())%2!=0||(rect.depth()-old.depth())%2!=0))return new HousingRules.Result(false,"restoreSize","Use the packed plot's dimensions or a larger size that can contain it. Shrinking and incompatible shape changes cannot preserve its contents.");}
        if(scope==HousingRules.Scope.GUILD_ROOT&&(guild==null||!services.guilds().can(actor,guild,"housing.claim")))return new HousingRules.Result(false,"permission","Your guild role cannot claim a guild plot.");
        if(paid&&!services.ownership().owns(owner,scope==HousingRules.Scope.GUILD_ROOT?"eternia:plot/guild_64":"eternia:plot/personal_32"))return new HousingRules.Result(false,"upgrade","This size requires the larger plot entitlement.");
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        // Legacy owned plots also occupy a player's slot until migrated; no duplicate free claims.
        if(scope!=HousingRules.Scope.GUILD_ROOT&&!manager.listPlotsForOwner(actor).isEmpty())return new HousingRules.Result(false,"legacy","Your existing house must be migrated before claiming another plot.");
        int members=guild==null?0:services.guilds().roster(actor).size();
        var anchors=manager.listPlots().stream().map(p->new HousingRules.Anchor(p.getPlotId(),NativeHousingChecks.rect(p.getFootprint()),
            p.getGuildOwnerUuid()!=null?HousingRules.Scope.GUILD_ROOT:p.getAttachedGuildUuid()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,
            p.getGuildOwnerUuid()!=null?p.getGuildOwnerUuid():p.getAttachedGuildUuid(),p.hasBuilding())).toList();
        return HousingRules.claim(new HousingRules.Candidate(rect,scope,scope==HousingRules.Scope.PUBLIC?null:guild,paid,members),anchors,infra.roads(),infra.portals(),com.hexvane.eterniamod.pathtool.SplineRoadTool.roadNetwork(world.getName(),infra));
    }
    public synchronized UUID claim(World world,UUID actor,PlotRect rect,int groundY,HousingRules.Scope scope,boolean paid,UUID operation) {
        var result=validate(world,actor,rect,scope,paid);
        if(!result.valid())throw new IllegalStateException(result.message());
        var services=plugin.getServices();UUID guild=scope==HousingRules.Scope.PUBLIC?null:services.guilds().membership(actor).orElseThrow().guildId();
        Owner owner=scope==HousingRules.Scope.GUILD_ROOT?Owner.guild(guild):Owner.player(actor);
        var existing=services.housing().find(owner).orElse(null);
        if(existing!=null&&existing.state()==HousingService.State.PACKED){plugin.getRelocation().restore(world,actor,owner,existing.operationId(),rect,groundY,scope,paid);return existing.propertyId();}
        UUID property=UUID.randomUUID();UUID attachment=scope==HousingRules.Scope.GUILD_MEMBER?guild:null;
        var location=new HousingService.ClaimLocation(world.getName(),rect.x(),rect.z(),rect.width(),rect.depth(),guild==null?HousingService.ClaimScope.PUBLIC:HousingService.ClaimScope.GUILD,guild,false);
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var projection=new HubPlotRecord(property,world.getName(),HubPlotFootprint.forCreate(rect.x(),rect.endX()-1,rect.z(),rect.endZ()-1,groundY),scope==HousingRules.Scope.GUILD_ROOT?null:actor);
        projection.setGuildOwnerUuid(scope==HousingRules.Scope.GUILD_ROOT?guild:null);projection.setAttachedGuildUuid(attachment);
        com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator.captureBaseline(plugin,world,projection);
        services.housing().beginClaim(owner,property,operation,attachment,location);
        manager.addPlot(projection);manager.saveIfDirty();
        var journal=services.journal().find(operation).orElseThrow();
        services.journal().advance(operation,journal.revision(),JournalService.State.WORLD_APPLIED);
        services.housing().activate(owner,operation);
        if(scope==HousingRules.Scope.GUILD_ROOT)plugin.getRuntime().welcomeGuild(guild);
        return property;
    }
    /** A crash after saving the native claim projection is safe to acknowledge on world load. */
    public void recoverClaims(World world) {
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var services=plugin.getServices();
        for(var slot:services.housing().allSlots()) {
            if(slot.owner().kind()==Owner.Kind.GUILD&&services.housing().location(slot.owner()).map(l->l.worldId().equals(world.getName())).orElse(false))plugin.getRuntime().welcomeGuild(slot.owner().id());
            if(slot.state()!=HousingService.State.CLAIMING)continue;
            var location=services.housing().location(slot.owner()).orElse(null);
            if(location==null||!location.worldId().equals(world.getName()))continue;
            var plot=manager.getPlot(slot.propertyId());
            if(plot==null)continue; // Missing projection is retained for explicit recovery; never silently release a slot.
            if(!NativeHousingChecks.rect(plot.getFootprint()).equals(new PlotRect(location.minX(),location.minZ(),location.width(),location.depth())))throw new IllegalStateException("Claim projection does not match durable reservation: "+slot.propertyId());
            if(!HousingAccess.owner(plot).equals(slot.owner())||!Objects.equals(plot.getAttachedGuildUuid(),slot.attachedGuild()))throw new IllegalStateException("Claim projection ownership does not match reservation");
            var op=services.journal().find(slot.operationId()).orElseThrow();
            if(op.state()==JournalService.State.PREPARED)op=services.journal().advance(op.id(),op.revision(),JournalService.State.WORLD_APPLIED);
            if(op.state()==JournalService.State.WORLD_APPLIED)services.housing().activate(slot.owner(),op.id());
        }
    }
}
