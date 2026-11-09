package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.hub.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hexvane.eterniamod.hexplots.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Called on the owning world thread. Domain transactions additionally serialize reservations across worlds. */
public final class NativeClaimCoordinator {
    private final EterniaModPlugin plugin;
    private final com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer terraformer;
    public NativeClaimCoordinator(EterniaModPlugin plugin){this.plugin=plugin;terraformer=new com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer(plugin);}
    public com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer terraformer(){return terraformer;}
    public HousingRules.Result validate(World world,UUID actor,PlotRect rect,HousingRules.Scope scope,boolean paid) {
        if(hexWorld(world))return asRectResult(HexClaimCheck.validate(plugin,world,actor,cellsForRect(world,rect,scope,paid).cells(),scope,paid));
        return validate(world,rect,ClaimEligibility.load(plugin.getServices(),actor,scope,paid));
    }
    /** Preview overload uses account data loaded off the world thread. Commit always loads fresh data. */
    HousingRules.Result validate(World world,PlotRect rect,ClaimEligibility eligibility) {
        UUID actor=eligibility.actor();var scope=eligibility.scope();boolean paid=eligibility.paid();UUID guild=eligibility.guild();
        if(hexWorld(world))return asRectResult(HexClaimCheck.validate(plugin,world,cellsForRect(world,rect,scope,paid).cells(),eligibility));
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
        if(hexWorld(world)) {
            // Hex worlds: the rectangle only says where. The plot is the start shape on the hexagon under its centre.
            var done=claimCellsNow(world,actor,cellsForRect(world,rect,scope,paid),HexClaimCheck.kind(scope),groundY,operation);
            if(!done.ok())throw new IllegalStateException(done.playerMessage());
            return UUID.fromString(done.plotId());
        }
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
        recoverExpansions(world);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hex plots (worlds where plan.hexPlots()). The claim map and My Plots call claimCells / expandPlot; the rectangle
    // entry points above route here in hex worlds so smokes, the playground and old callers keep working.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Claims {@code cells} for {@code player}: loads the ground, validates again with fresh account data, captures the
     * terrain baseline, reserves durably ({@link HexClaimCheck#reserve}), adds the plot to the world, finishes the
     * journal and tells the player. Completes on the world thread; never completes exceptionally (failures come back
     * as a result with a friendly message). Safe to call from any thread.
     */
    public CompletableFuture<HexCommitResult> claimCells(PlayerRef player,World world,PlotCells cells,HexClaimRules.Kind kind) {
        Objects.requireNonNull(player);Objects.requireNonNull(world);Objects.requireNonNull(cells);Objects.requireNonNull(kind);
        UUID operation=UUID.randomUUID();UUID actor=player.getUuid();
        // The land is shaped before the claim commits, so the terrain baseline the claim saves is the shaped ground.
        var request=new com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer.Request(world.getName(),cells.lattice(),cells.asSet(),()->claimGroup(actor,kind),null);
        return onWorld(world,()->loadGround(world,cells.bounds())).thenCompose(f->f)
            .thenCompose(v->terraformer.shapeThen(world,request,()->HexClaimCheck.validate(plugin,world,actor,cells.cells(),scope(kind),paidFor(kind,cells.size())).valid(),outcome->{
                HexCommitResult done;
                try{done=claimCellsNow(world,actor,cells,kind,groundY(world,player,cells),operation);if(done.ok())recordLevel(world,done.plotId(),outcome,true);}
                catch(RuntimeException failure){
                    plugin.getLogger().atWarning().withCause(failure).log("Hex claim did not finish");
                    done=HexCommitResult.failed(HexPlotText.plots("claim.failed","Your claim could not finish. Open My Plots to check it before you try again."),null);
                }
                tell(player,done);return done;
            }))
            .exceptionallyAsync(error->{
                plugin.getLogger().atWarning().withCause(error).log("Hex claim could not load its ground");
                var done=HexCommitResult.failed(HexPlotText.plots("claim.retry","The land here is still loading. Try again in a moment."),null);
                tell(player,done);return done;
            },world);
    }

    /**
     * Adds {@code cell} to the plot {@code plotId} (the player's own plot, or their guild estate when their guild role
     * may resize it), charging the price from {@code HexPlots.json} to the player. Validates again at commit; completes
     * on the world thread with a friendly message. Safe to call from any thread.
     */
    public CompletableFuture<HexCommitResult> expandPlot(PlayerRef player,World world,String plotId,HexCoord cell) {
        Objects.requireNonNull(player);Objects.requireNonNull(world);Objects.requireNonNull(plotId);Objects.requireNonNull(cell);
        UUID operation=UUID.randomUUID();UUID plot=parse(plotId);
        var lattice=plugin.getInfrastructure().world(world.getName()).flatMap(HousingInfrastructure.WorldPlan::hexLattice).orElse(null);
        if(plot==null||lattice==null)return onWorld(world,()->{var done=expandPlotNow(world,player.getUuid(),plot,cell,operation);tell(player,done);return done;});
        // The new hexagon is shaped to the plot's level while it is still free land, then the plot grows onto it.
        var request=new com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer.Request(world.getName(),lattice,Set.of(cell),()->null,plot);
        return terraformer.shapeThen(world,request,()->HexLand.view(plugin,world).map(v->v.isFree(cell)&&v.plots().containsKey(plot)).orElse(false),outcome->{
                var done=expandPlotNow(world,player.getUuid(),plot,cell,operation);
                if(done.ok())recordLevel(world,plotId,outcome,false);
                tell(player,done);return done;
            })
            .exceptionallyAsync(error->{
                plugin.getLogger().atWarning().withCause(error).log("Hex expansion did not finish");
                var done=HexCommitResult.failed(HexPlotText.plots("expand.retry","Your plot could not grow just now. Open My Plots to check it, then try again."),plotId);
                tell(player,done);return done;
            },world);
    }

    /** World thread. The synchronous claim: validate, baseline, reserve, native tail. Chunks under the cells must be loaded. */
    public synchronized HexCommitResult claimCellsNow(World world,UUID actor,PlotCells cells,HexClaimRules.Kind kind,int groundY,UUID operation) {
        var plan=plugin.getInfrastructure().world(world.getName()).orElse(null);
        if(plan==null||!plan.supportsHousing())return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.WORLD));
        if(!plan.hexPlots()||plan.hexLattice().isEmpty())return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_LATTICE));
        if(!cells.lattice().equals(plan.hexLattice().get()))return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.CHANGED));
        var scope=scope(kind);boolean paid=paidFor(kind,cells.size());
        var services=plugin.getServices();var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        // Replay: the same operation already reserved (a retry after a lost reply) only needs its native tail.
        var replay=services.housing().properties().stream().filter(p->p.slot().operationId().equals(operation)&&p.location()!=null&&p.location().isHex()).findFirst();
        UUID property=replay.map(p->p.slot().propertyId()).orElseGet(UUID::randomUUID);
        if(replay.isEmpty()) {
            var verdict=HexClaimCheck.validate(plugin,world,actor,cells.cells(),scope,paid);
            if(!verdict.valid())return HexCommitResult.refused(verdict);
            // A packed home is set down whole on a same shape placement through the relocation journal.
            var eligibility=ClaimEligibility.load(services,actor,scope,paid);var packed=eligibility.slot();
            if(packed!=null&&packed.state()==HousingService.State.PACKED) {
                try{plugin.getRelocation().restoreCells(world,actor,eligibility.owner(),packed.operationId(),cells,groundY,scope);}
                catch(RuntimeException e){return HexCommitResult.failed(HexPlotText.plots("claim.restoreFailed","Your home could not be set down here: {reason}","reason",String.valueOf(e.getMessage())),packed.propertyId().toString());}
                return HexCommitResult.success(HexPlotText.plots("claim.restored","Your home is back! It stands on its new hexagons."),packed.propertyId().toString(),verdict);
            }
            // Baseline before the durable reservation: a failure here leaves nothing to recover.
            var probe=new HubPlotRecord(property,world.getName(),HubPlotFootprint.forCells(cells,groundY),null);
            com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator.captureBaseline(plugin,world,probe);
        }
        var out=HexClaimCheck.reserve(plugin,world,actor,cells.cells(),scope,paid,property,operation);
        if(!out.ok())return HexCommitResult.refused(out.result());
        var slot=out.slot();property=slot.propertyId();
        UUID estateGuild=slot.owner().kind()==Owner.Kind.GUILD?slot.owner().id():null;
        if(manager.getPlot(property)==null) {
            var plot=new HubPlotRecord(property,world.getName(),HubPlotFootprint.forCells(out.cells(),groundY),estateGuild==null?actor:null);
            plot.setGuildOwnerUuid(estateGuild);plot.setAttachedGuildUuid(slot.attachedGuild());
            manager.addPlot(plot);
        }
        manager.saveIfDirty();
        finishClaimJournal(slot.owner(),slot.operationId());
        if(estateGuild!=null)plugin.getRuntime().welcomeGuild(estateGuild);
        String shape=shapeName(out.cells());
        var line=out.charged()>0
            ?HexPlotText.plots("claim.paid","Your {shape} is ready! {coins} coins paid. Open your housing menu to place a house.","shape",shape,"coins",out.charged())
            :HexPlotText.plots("claim.done","Your {shape} is ready! Open your housing menu to place a house.","shape",shape);
        return HexCommitResult.success(line,property.toString(),out.result());
    }

    /** World thread. The synchronous expansion: checks who may grow the plot, commits durably, then applies it. */
    public synchronized HexCommitResult expandPlotNow(World world,UUID actor,UUID plotId,HexCoord cell,UUID operation) {
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var plot=plotId==null?null:manager.getPlot(plotId);
        if(plot==null||!plot.isHex())return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
        var services=plugin.getServices();var owner=HousingAccess.owner(plot);
        if(owner==null)return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
        HexClaimRules.Kind kind;
        if(owner.kind()==Owner.Kind.GUILD) {
            if(!services.guilds().can(actor,owner.id(),"housing.resize"))
                return HexCommitResult.failed(HexPlotText.plots("expand.guildRole","Only your guild leader can grow the guild estate."),plotId.toString());
            kind=HexClaimRules.Kind.GUILD_ESTATE;
        } else {
            if(!actor.equals(plot.getOwnerUuid()))return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.OUTSIDE));
            kind=plot.getAttachedGuildUuid()!=null?HexClaimRules.Kind.GUILD_MEMBER:HexClaimRules.Kind.PUBLIC;
        }
        var slot=services.housing().find(owner).orElse(null);
        if(slot==null||!slot.propertyId().equals(plotId))return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
        // A paid hexagon the world has not shown yet is finished first, so the player never pays twice.
        for(var pending:services.housing().pendingExpansions(world.getName()))if(pending.propertyId().equals(plotId))applyExpansion(world,pending);
        slot=services.housing().find(owner).orElse(slot);
        if(slot.state()!=HousingService.State.ACTIVE)return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.RECOVERY));
        var context=HexLand.context(plugin,world).orElse(null);
        if(context==null)return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_LATTICE));
        if(!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty())return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.ROAD_WORK));
        var out=services.housing().expandPlot(owner,plotId,cell,slot.revision(),operation,kind,context,Owner.player(actor));
        if(!out.ok())return HexCommitResult.refused(out.result());
        applyCells(manager,plot,out.cells());
        services.housing().completeExpansion(owner,operation);
        var line=out.charged()>0
            ?HexPlotText.plots("expand.paid","Your plot grew to {cells} hexagons. {coins} coins paid.","cells",out.cells().size(),"coins",out.charged())
            :HexPlotText.plots("expand.done","Your plot grew to {cells} hexagons.","cells",out.cells().size());
        return HexCommitResult.success(line,plotId.toString(),out.result());
    }

    /**
     * Sells {@code cell} of the plot {@code plotId} back: loads its ground, packs the decorations standing on it, puts
     * its ground back as it was before it was claimed, refunds what the newest hexagon cost and shrinks the plot.
     * Completes on the world thread with a friendly message; never completes exceptionally. Safe from any thread.
     */
    public CompletableFuture<HexCommitResult> sellCell(PlayerRef player,World world,String plotId,HexCoord cell) {
        Objects.requireNonNull(player);Objects.requireNonNull(world);Objects.requireNonNull(plotId);Objects.requireNonNull(cell);
        UUID operation=UUID.randomUUID();UUID plot=parse(plotId);
        var lattice=plugin.getInfrastructure().world(world.getName()).flatMap(HousingInfrastructure.WorldPlan::hexLattice).orElse(null);
        if(plot==null||lattice==null)return onWorld(world,()->{var done=HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));tell(player,done);return done;});
        return onWorld(world,()->loadGround(world,lattice.bounds(cell))).thenCompose(f->f)
            .thenApplyAsync(v->{
                HexCommitResult done;
                try{done=sellCellNow(world,player.getUuid(),plot,cell,operation);}
                catch(RuntimeException failure){
                    plugin.getLogger().atWarning().withCause(failure).log("Selling hexagon %s of plot %s did not finish",cell,plotId);
                    done=HexCommitResult.failed(HexPlotText.plots("sell.failed","Your hexagon could not be sold just now. Nothing was charged. Try again in a moment."),plotId);
                }
                tell(player,done);return done;
            },world)
            .exceptionallyAsync(error->{
                plugin.getLogger().atWarning().withCause(error).log("Selling a hexagon could not load its ground");
                var done=HexCommitResult.failed(HexPlotText.plots("claim.retry","The land here is still loading. Try again in a moment."),plotId);
                tell(player,done);return done;
            },world);
    }

    /** World thread. The synchronous sale: who may shrink the plot, pack what stands on the hexagon, restore its ground, commit. */
    public synchronized HexCommitResult sellCellNow(World world,UUID actor,UUID plotId,HexCoord cell,UUID operation) {
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        var plot=plotId==null?null:manager.getPlot(plotId);
        if(plot==null||!plot.isHex())return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
        var services=plugin.getServices();var owner=HousingAccess.owner(plot);
        if(owner==null)return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
        HexClaimRules.Kind kind;
        if(owner.kind()==Owner.Kind.GUILD) {
            if(!services.guilds().can(actor,owner.id(),"housing.resize"))
                return HexCommitResult.failed(HexPlotText.plots("sell.guildRole","Only your guild leader can sell hexagons of the guild estate."),plotId.toString());
            kind=HexClaimRules.Kind.GUILD_ESTATE;
        } else {
            if(!actor.equals(plot.getOwnerUuid()))return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.OUTSIDE));
            kind=plot.getAttachedGuildUuid()!=null?HexClaimRules.Kind.GUILD_MEMBER:HexClaimRules.Kind.PUBLIC;
        }
        var slot=services.housing().find(owner).orElse(null);
        if(slot==null||!slot.propertyId().equals(plotId))return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
        for(var pending:services.housing().pendingExpansions(world.getName()))if(pending.propertyId().equals(plotId))applyExpansion(world,pending);
        slot=services.housing().find(owner).orElse(slot);
        if(slot.state()!=HousingService.State.ACTIVE)return HexCommitResult.refused(HexClaimRules.Result.of(HexClaimRules.Reason.RECOVERY));
        var settings=HexPlotSettings.current();var lattice=plot.getCells().lattice();
        // What stands where: the house and a garden path block the sale; decorations on the hexagon are packed first.
        Set<HexCoord> houseCells=new HashSet<>(),pathCells=new HashSet<>();var onCell=new ArrayList<ProvenanceService.Instance>();
        var snapshots=com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions.snapshots(plugin);
        for(var item:services.provenance().instances(plotId)) {
            if(!item.state().equals("PLACED"))continue;
            com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore.Bounds b;
            try{b=snapshots.load(new com.hexvane.eterniamod.housing.relocation.SnapshotFiles.Saved(item.nativeData().get("before"),item.nativeData().get("beforeHash"))).bounds();}
            catch(java.io.IOException|RuntimeException unreadable){plugin.getLogger().atWarning().withCause(unreadable).log("Could not read where %s stands",item.id());continue;}
            var touched=lattice.cellsTouching(new PlotRect(b.minX(),b.minZ(),b.maxX()-b.minX(),b.maxZ()-b.minZ()));
            String what=item.nativeData().getOrDefault("kind","PROP");
            if(what.equals("HOUSE"))houseCells.addAll(touched);
            else if(what.equals("PATH"))pathCells.addAll(touched);
            else if(touched.contains(cell))onCell.add(item);
        }
        var verdict=HexClaimRules.sell(plot.getCells().asSet(),cell,kind,settings,houseCells,pathCells);
        if(!verdict.valid())return HexCommitResult.refused(verdict);
        onCell.sort(Comparator.comparingLong((ProvenanceService.Instance i)->Long.parseLong(i.nativeData().getOrDefault("placedAt","0"))).reversed());
        int packed=0;
        for(var item:onCell) {
            UUID op;
            try{op=com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions.pickup(plugin,world,plot,actor,item.id());}
            catch(Exception failure){
                plugin.getLogger().atWarning().withCause(failure).log("Selling %s of plot %s: could not pack %s (%s)",cell,plotId,item.id(),item.contentId());
                return HexCommitResult.failed(HexPlotText.plots("sell.packFailed","{name} on this hexagon could not be packed: {reason}","name",com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions.name(plugin,item),"reason",String.valueOf(failure.getMessage())),plotId.toString());
            }
            try{plot.removeProp(item.id());manager.updatePlot(plot);manager.saveIfDirty();com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions.complete(plugin,op);packed++;}
            catch(RuntimeException failure){com.hexvane.eterniamod.housing.relocation.NativePlacementTransactions.lock(plugin,op);throw failure;}
        }
        com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator.releaseCell(plugin,world,plot,cell);
        var out=services.housing().sellCell(owner,plotId,cell,slot.revision(),operation,kind,settings,houseCells,pathCells,Owner.player(actor));
        if(!out.ok())return HexCommitResult.refused(out.result());
        applyCells(manager,plot,out.cells());
        String packedNote=packed==0?"":packed==1?" 1 decoration from it went back to your inventory.":" "+packed+" decorations from it went back to your inventory.";
        var line=out.charged()>0
            ?HexPlotText.plots("sell.paid","You sold a hexagon for {coins} coins. Your plot now has {cells} hexagons.{packed}","coins",out.charged(),"cells",out.cells().size(),"packed",packedNote)
            :HexPlotText.plots("sell.done","You gave back a hexagon. Your plot now has {cells} hexagons.{packed}","cells",out.cells().size(),"packed",packedNote);
        return HexCommitResult.success(line,plotId.toString(),out.result());
    }

    /** Coins back for selling one hexagon of a plot with {@code cellsOwned} cells. */
    public static long sellRefund(int cellsOwned){return HexPlotSettings.current().sellRefund(cellsOwned);}

    /** What the next hexagon costs for a plot of {@code cellsOwned} cells (pages show it before the player confirms). */
    public static long expansionPrice(int cellsOwned){return HexPlotSettings.current().expansionPrice(cellsOwned);}

    /**
     * Paid hexagons whose world change was lost (a crash between payment and the plot growing) are applied on world
     * load, so a paid hexagon always arrives. Idempotent.
     */
    void recoverExpansions(World world) {
        for(var pending:plugin.getServices().housing().pendingExpansions(world.getName())) {
            try{applyExpansion(world,pending);}
            catch(RuntimeException failure){plugin.getLogger().atWarning().withCause(failure).log("Paid hexagon %s for plot %s is still waiting",pending.cell(),pending.propertyId());}
        }
    }

    private void applyExpansion(World world,HousingService.PendingExpansion pending) {
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);
        if(applyExpansion(plugin.getServices().housing(),pending,manager::getPlot,manager::updatePlot))manager.saveIfDirty();
        // Record the new hexagons' ground so a grown plot can still be packed (idempotent).
        var plot=manager.getPlot(pending.propertyId());
        if(plot!=null&&plot.getCells()!=null)com.hexvane.eterniamod.housing.relocation.NativeRelocationCoordinator.extendBaseline(plugin,world,plot);
    }

    /**
     * Shows a paid hexagon on the plot's world record ({@code cells} from the durable row, the truth) and acknowledges
     * it. A plot that is not in this world (packed, moving) is left for later: its row already holds the cells.
     * Returns true when the world record changed. Idempotent.
     */
    static boolean applyExpansion(HousingService housing,HousingService.PendingExpansion pending,java.util.function.Function<UUID,HubPlotRecord> plots,java.util.function.Consumer<HubPlotRecord> updated) {
        var plot=plots.apply(pending.propertyId());
        if(plot==null||pending.location()==null||!pending.location().isHex())return false;
        var cells=pending.location().cells();boolean changed=!cells.equals(plot.getCells());
        if(changed){plot.setFootprint(plot.getFootprint().withCells(cells));updated.accept(plot);}
        housing.completeExpansion(pending.owner(),pending.operationId());
        return changed;
    }

    private static void applyCells(HubPlotManager manager,HubPlotRecord plot,PlotCells cells) {
        if(!cells.equals(plot.getCells())){plot.setFootprint(plot.getFootprint().withCells(cells));manager.updatePlot(plot);}
        manager.saveIfDirty();
    }

    private void finishClaimJournal(Owner owner,UUID operation) {
        var services=plugin.getServices();
        services.batch(()->{
            var op=services.journal().find(operation).orElseThrow();
            if(op.state()==JournalService.State.PREPARED)op=services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);
            if(op.state()==JournalService.State.WORLD_APPLIED)services.housing().activate(owner,operation);
            return null;
        });
    }

    private boolean hexWorld(World world){return plugin.getInfrastructure().world(world.getName()).map(HousingInfrastructure.WorldPlan::hexPlots).orElse(false);}

    static HousingRules.Scope scope(HexClaimRules.Kind kind) {
        return switch(kind){case PUBLIC->HousingRules.Scope.PUBLIC;case GUILD_ESTATE->HousingRules.Scope.GUILD_ROOT;case GUILD_MEMBER->HousingRules.Scope.GUILD_MEMBER;};
    }

    /** A claim of the larger start shape needs the plot upgrade; when both shapes match, the free one is meant. */
    static boolean paidFor(HexClaimRules.Kind kind,int cells) {
        var s=HexPlotSettings.current();
        return s.startCells(kind,false)!=s.startCells(kind,true)&&cells==s.startCells(kind,true);
    }

    /** Player name of a plot's shape ("Cottage plot"), or "plot" for a grown shape with no name. English. */
    static String shapeName(PlotCells cells) {
        for(var shape:HexShape.values())if(HexClaimRules.hasShape(cells.cells(),shape))
            return new HexPlotText.Line("eternia_hexplots.shape."+shape.name().toLowerCase(Locale.ROOT),Map.of(),shape.displayName()+" plot").english().toLowerCase(Locale.ROOT);
        return "plot";
    }

    /**
     * Cells for a rectangle request in a hex world: the start shape placed over the hexagon under the rectangle's
     * centre, preferring a free placement with road frontage, else the first.
     */
    PlotCells cellsForRect(World world,PlotRect rect,HousingRules.Scope scope,boolean paid) {
        var lattice=plugin.getInfrastructure().world(world.getName()).flatMap(HousingInfrastructure.WorldPlan::hexLattice).orElseThrow();
        HexCoord centre=lattice.cellAt(rect.x()+rect.width()/2,rect.z()+rect.depth()/2);
        var shape=HexClaimCheck.startShape(scope,paid);
        var view=HexLand.view(plugin,world).orElse(null);
        List<Set<HexCoord>> placements=shape.placementsCovering(centre);
        Set<HexCoord> best=placements.getFirst();
        if(view!=null)for(var p:placements)if(p.stream().allMatch(view::isFree)&&p.stream().anyMatch(view::isFrontage)){best=p;break;}
        return PlotCells.of(lattice,best);
    }

    private static HousingRules.Result asRectResult(HexClaimRules.Result r) {
        String code=r.reason()==HexClaimRules.Reason.ROAD_WORK?"splineRoad":r.code();
        return new HousingRules.Result(r.valid(),code,HexPlotText.reason(r).english());
    }

    private static UUID parse(String id){try{return UUID.fromString(id);}catch(IllegalArgumentException bad){return null;}}

    private static void tell(PlayerRef player,HexCommitResult done){if(player.isValid())player.sendMessage(done.message());}

    /** Ground under the middle of the cells (the plot's glow height), else where the player stands. World thread. */
    private static int groundY(World world,PlayerRef player,PlotCells cells) {
        var box=cells.bounds();int x=box.x()+box.width()/2,z=box.z()+box.depth()/2;
        int y=ChunkSectionBlockUtil.columnHeight(world,x,z);
        if(y>=ChunkUtil.MIN_Y)return y+1;
        var t=player.getTransform();
        return t==null?64:(int)Math.floor(t.getPosition().y);
    }

    /** Every chunk column and section under {@code box}, so the terrain baseline can be captured. Call on the world thread. */
    private static CompletableFuture<Void> loadGround(World world,PlotRect box) {
        int minX=Math.floorDiv(box.x(),32),maxX=Math.floorDiv(box.endX()-1,32),minZ=Math.floorDiv(box.z(),32),maxZ=Math.floorDiv(box.endZ()-1,32);
        int minY=Math.floorDiv(ChunkUtil.MIN_Y,32),maxY=Math.floorDiv(com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore.MAX_Y-1,32);
        var columns=new ArrayList<CompletableFuture<?>>();
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)columns.add(world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(x,z),GetChunkFlags.SET_TICKING));
        return CompletableFuture.allOf(columns.toArray(CompletableFuture[]::new)).thenCompose(v->{
            var sections=new ArrayList<CompletableFuture<?>>();
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++)sections.add(world.getChunkStore().getChunkSectionReferenceAsync(x,y,z,GetChunkFlags.SET_TICKING));
            return CompletableFuture.allOf(sections.toArray(CompletableFuture[]::new));
        });
    }

    /** Owner group of a fresh claim for terraforming: the player, or their guild for an estate or member plot. Off the world thread. */
    private String claimGroup(UUID actor,HexClaimRules.Kind kind) {
        if(kind==HexClaimRules.Kind.PUBLIC)return com.hexvane.eterniamod.hexplots.terraform.TerraformLevel.playerGroup(actor);
        return plugin.getServices().guilds().membership(actor).map(m->com.hexvane.eterniamod.hexplots.terraform.TerraformLevel.guildGroup(m.guildId()))
            .orElse(com.hexvane.eterniamod.hexplots.terraform.TerraformLevel.playerGroup(actor));
    }

    /** Saves the shaped ground height on the plot (a fresh claim always; a grown plot only when it had none). World thread. */
    private void recordLevel(World world,String plotId,com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer.Outcome outcome,boolean fresh) {
        if(!outcome.shaped()||plotId==null)return;
        var id=parse(plotId);if(id==null)return;
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin);var plot=manager.getPlot(id);
        if(plot==null||!fresh&&plot.getTerrainLevel()!=null)return;
        plot.setTerrainLevel(outcome.level());manager.updatePlot(plot);manager.saveIfDirty();
    }

    private static <T> CompletableFuture<T> onWorld(World world,java.util.function.Supplier<T> work) {
        return world.isInThread()?CompletableFuture.completedFuture(work.get()):CompletableFuture.supplyAsync(work,world);
    }
}
