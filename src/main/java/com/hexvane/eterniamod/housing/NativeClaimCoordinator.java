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
        return validate(world,rect,ClaimEligibility.load(plugin.getServices(),actor,scope,paid));
    }
    /** Preview overload uses account data loaded off the world thread. Commit always loads fresh data. */
    HousingRules.Result validate(World world,PlotRect rect,ClaimEligibility eligibility) {
        UUID actor=eligibility.actor();var scope=eligibility.scope();boolean paid=eligibility.paid();UUID guild=eligibility.guild();
        var infra=plugin.getInfrastructure().world(world.getName()).orElse(null);
        if(infra==null||!infra.supportsHousing())return new HousingRules.Result(false,"world","Use your plot deed in a world with housing enabled.");
        if(!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())return new HousingRules.Result(false,"splineRoad","An interrupted public road needs recovery before new claims can depend on this world's roads. Ask a builder to use the Road Designer's recovery action.");
        if(com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world.getName()).stream().anyMatch(rect::overlaps))return new HousingRules.Result(false,"paving","This ground has an unfinished public-road edit. Ask a builder to recover it before claiming here.");
        if(com.hexvane.eterniamod.guildroads.GuildRoads.roadRects(world.getName()).stream().anyMatch(road->road.overlaps(rect)))return new HousingRules.Result(false,"guildRoad","Ask a guild architect to remove the intersecting community road segment before claiming here. It can be rebuilt after the claim.");
        var denied=eligibility.check(rect);if(denied!=null)return denied;
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        // Legacy owned plots also occupy a player's slot until migrated; no duplicate free claims.
        if(scope!=HousingRules.Scope.GUILD_ROOT&&!manager.listPlotsForOwner(actor).isEmpty())return new HousingRules.Result(false,"legacy","Your existing house must be migrated before claiming another plot.");
        int members=eligibility.members();
        var anchors=manager.listPlots().stream().map(p->new HousingRules.Anchor(p.getPlotId(),NativeHousingChecks.rect(p.getFootprint()),
            p.getGuildOwnerUuid()!=null?HousingRules.Scope.GUILD_ROOT:p.getAttachedGuildUuid()!=null?HousingRules.Scope.GUILD_MEMBER:HousingRules.Scope.PUBLIC,
            p.getGuildOwnerUuid()!=null?p.getGuildOwnerUuid():p.getAttachedGuildUuid(),p.hasBuilding())).toList();
        return HousingRules.claim(new HousingRules.Candidate(rect,scope,scope==HousingRules.Scope.PUBLIC?null:guild,paid,members),anchors,infra.roads(),infra.portals(),com.hexvane.eterniamod.pathtool.SplineRoadTool.roadNetwork(world.getName(),infra),plugin.getRuntimeConfig().local());
    }
    public synchronized UUID claim(World world,UUID actor,PlotRect rect,int groundY,HousingRules.Scope scope,boolean paid,UUID operation) {
        var eligibility=ClaimEligibility.load(plugin.getServices(),actor,scope,paid);var result=validate(world,rect,eligibility);
        if(!result.valid())throw new IllegalStateException(result.message());
        var services=plugin.getServices();UUID guild=eligibility.guild();
        Owner owner=eligibility.owner();
        var existing=eligibility.slot();
        if(existing!=null&&existing.state()==HousingService.State.PACKED){plugin.getRelocation().restore(world,actor,owner,existing.operationId(),rect,groundY,scope,paid);return existing.propertyId();}
        UUID property=UUID.randomUUID();UUID attachment=scope==HousingRules.Scope.GUILD_MEMBER?guild:null;
        var location=new HousingService.ClaimLocation(world.getName(),rect.x(),rect.z(),rect.width(),rect.depth(),guild==null?HousingService.ClaimScope.PUBLIC:HousingService.ClaimScope.GUILD,guild,false);
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var projection=new HubPlotRecord(property,world.getName(),HubPlotFootprint.forCreate(rect.x(),rect.endX()-1,rect.z(),rect.endZ()-1,groundY),scope==HousingRules.Scope.GUILD_ROOT?null:actor);
        projection.setGuildOwnerUuid(scope==HousingRules.Scope.GUILD_ROOT?guild:null);projection.setAttachedGuildUuid(attachment);
        com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator.captureBaseline(plugin,world,projection);
        services.housing().beginClaim(owner,property,operation,attachment,location);
        manager.addPlot(projection);manager.saveIfDirty();
        services.batch(()->{var journal=services.journal().find(operation).orElseThrow();
            services.journal().advance(operation,journal.revision(),JournalService.State.WORLD_APPLIED);
            services.housing().activate(owner,operation);return null;});
        if(scope==HousingRules.Scope.GUILD_ROOT)plugin.getRuntime().welcomeGuild(guild);
        return property;
    }
    /** A crash after saving the native claim projection is safe to acknowledge on world load. */
    public void recoverClaims(World world) {
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var services=plugin.getServices();
        for(var entry:services.housing().properties()) {
            var slot=entry.slot();var location=entry.location();
            if(slot.owner().kind()==Owner.Kind.GUILD&&location!=null&&location.worldId().equals(world.getName()))plugin.getRuntime().welcomeGuild(slot.owner().id());
            if(slot.state()!=HousingService.State.CLAIMING)continue;
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
