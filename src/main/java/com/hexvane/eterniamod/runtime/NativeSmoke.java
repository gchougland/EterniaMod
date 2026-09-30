package com.hexvane.eterniamod.runtime;

import com.google.gson.Gson;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.*;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3i;

/** Opt-in native acceptance fixture, only in an empty local authority. Never registered by production. */
public final class NativeSmoke {
    private NativeSmoke() {}
    public static void register(EterniaModPlugin plugin) {
        if (!"1".equals(System.getenv("ETERNIA_NATIVE_SMOKE"))) return;
        com.hexvane.eterniamod.inventory.NativeInventorySmoke.register(plugin);
        if (!plugin.getRuntimeConfig().local() || plugin.getRuntimeConfig().postgres() || !plugin.getInfrastructure().worlds().isEmpty() || !plugin.getServices().housing().allSlots().isEmpty())
            throw new IllegalStateException("Native smoke requires a fresh isolated local file store without PostgreSQL");
        plugin.getEventRegistry().register(BootEvent.class, event -> {
            CompletableFuture.runAsync(() -> run(plugin)).orTimeout(360, TimeUnit.SECONDS).whenComplete((unused, failure) -> {
                if (failure == null) { plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_PASS: housing policy, claim, native house/palette/path/props, pack, elevation restore, guild hall and mixed-owner custody, policy restoration"); HytaleServer.get().shutdownServer(); }
                else { plugin.getLogger().atSevere().withCause(failure).log("ETERNIA_NATIVE_SMOKE_FAIL"); HytaleServer.get().shutdownServer(ShutdownReason.CRASH.withMessage(Message.raw("Eternia native smoke failed"))); }
            });
        });
    }
    private static void run(EterniaModPlugin plugin) {
        long started=System.nanoTime();
        try {
            com.hexvane.eterniamod.ui.NativeUiSmoke.validate();
            NativeRetainedPropSmoke.run(plugin);
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_UI_PASS: Citadel native UI commands, pagination and full action-label widths validated");
            for(String suffix:List.of("","_Soft","_Faint")){
                String id="Eternia_Plot_Border_Motes"+suffix;
                if(com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem.getAssetMap().getAsset(id)==null||com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner.getAssetMap().getAsset(id+"_Spawner")==null)throw new IllegalStateException("Mote boundary assets failed native loading: "+id);
            }
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_BORDER_ASSETS_PASS: all mote systems and spawners loaded");
            World world=Universe.get().addWorld("eternia_smoke", "Flat", null).join();
            com.hexvane.eterniamod.inventory.NativeInventorySmoke.run(world);
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_INVENTORY_SAVE_PASS: live components retained without cloning; inventory and custody proof saved together and reloaded");
            String previousGameplay=world.getWorldConfig().getGameplayConfig();var previousTickers=Set.copyOf(world.getWorldConfig().getDisabledFluidTickers());
            var config = new HousingInfrastructure.FileData(1, Map.of(world.getName(), new HousingInfrastructure.WorldPlan("housing",
                List.of(new PlotRect(0,0,6,96),new PlotRect(96,0,6,128)), List.of(new PlotRect(5,8,2,2),new PlotRect(5,40,2,2),new PlotRect(98,48,2,2)), new HousingInfrastructure.Point(6,1,6))));
            Files.writeString(plugin.getDataDirectory().resolve("housing-infrastructure.json"),new Gson().toJson(config)); plugin.getInfrastructure().load();
            com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().join();
            var loads=new ArrayList<CompletableFuture<?>>();
            for(int x=-1;x<=8;x++)for(int z=-1;z<=5;z++)loads.add(world.getChunkAsync(ChunkUtil.indexChunk(x,z)));
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
            loads.clear();for(int sy=0;sy<ChunkUtil.HEIGHT/ChunkUtil.SIZE;sy++)loads.add(world.getChunkStore().getChunkSectionReferenceAsync(-1,sy,-1));
            CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
            var result=new CompletableFuture<Void>();
            world.execute(()-> {try {
                com.hexvane.eterniamod.boundary.NativeBoundarySmoke.validate(world);
                plugin.getLogger().atInfo().log("ETERNIA_NATIVE_BOUNDARY_SIDES_PASS: all four sides and protected public paving support the visual border");
                long personalStarted=System.nanoTime();exercise(plugin,world);
                plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: personal custody complete; durationMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-personalStarted));
                com.hexvane.eterniamod.setup.paving.RoadPaving.nativeSmoke(plugin,world);
                com.hexvane.eterniamod.pathtool.SplineRoadTool.nativeSmoke(plugin,world);
                com.hexvane.eterniamod.setup.ManagedHubServices.nativeSmoke(plugin,world);
                NativeGuildSmoke.exercise(plugin,world);result.complete(null);
            } catch(Throwable failure) { result.completeExceptionally(failure); }});
            result.join();
            Files.writeString(plugin.getDataDirectory().resolve("housing-infrastructure.json"),new Gson().toJson(new HousingInfrastructure.FileData(1,Map.of())));plugin.getInfrastructure().load();
            com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().join();
            if(!previousGameplay.equals(world.getWorldConfig().getGameplayConfig())||!previousTickers.equals(world.getWorldConfig().getDisabledFluidTickers()))throw new IllegalStateException("Removing the housing role did not restore prior native world policy");
            com.hexvane.eterniamod.localplayground.LocalPlayground.nativeSmoke(plugin);
            plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: policy restoration complete; totalDurationMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            // Check the rig last so an independent artwork mismatch does not prevent housing checks.
            // A mismatch still fails the complete smoke.
            NativeProwlSmoke.validate(plugin);
        } catch(Exception failure) { throw new CompletionException(failure); }
    }
    private static void exercise(EterniaModPlugin plugin,World world) throws Exception {
        world.getWorldConfig().setSpawningNPC(false); world.getWorldConfig().setBlockTicking(false);
        if(world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed()||world.getGameplayConfig().getWorldConfig().isBlockGatheringAllowed()||!world.getWorldConfig().getDisabledFluidTickers().contains("Fluid"))throw new IllegalStateException("Housing world policy did not block native placement/gathering and fluid simulation");
        UUID actor=UUID.fromString("ffffffff-ffff-4fff-8fff-fffffffffff1"); Owner owner=Owner.player(actor);
        plugin.getRuntime().welcome(actor,"NativeSmokeFixture");
        UUID property=plugin.getClaims().claim(world,actor,new PlotRect(8,8,24,24),0,HousingRules.Scope.PUBLIC,false,UUID.randomUUID());
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: claim committed");
        var manager=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin); var plot=manager.getPlot(property);
        var prefab=PrefabResolveUtil.resolvePrefabBuffer("House.prefab.json");
        var definition=plugin.getBuildingCatalog().get("hub_house");
        var preview=new com.hexvane.eterniamod.placement.BuildingPlacementSession(world,property,new Vector3i(9,3,9),0,"hub_house");
        if(!com.hexvane.eterniamod.placement.BuildingPlacementValidator.findValidPosition(world,plot,actor,preview,definition,plugin)||com.hexvane.eterniamod.placement.BuildingPlacementValidator.validate(world,manager,plot,actor,preview.getAnchor(),preview.getPrefabYaw(),definition,plugin)!=null)throw new IllegalStateException("A free plot must offer a valid house preview with the catalog anchor offset");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_HOUSE_PREVIEW_PASS: native player-facing validator accepts the automatically selected position");
        if(prefab==null)throw new IllegalStateException("House prefab did not resolve");
        var placed=NativePlacementTransactions.place(plugin,world,plot,actor,"hub_house",new Vector3i(20,1,20),Rotation.None,prefab,true);
        plot.setBuilding(new HubPlotBuilding("hub_house",20,1,20,Rotation.None,List.of()));manager.updatePlot(plot);manager.saveIfDirty();
        plugin.getServices().housing().updateBuildingPresent(owner,property,true);NativePlacementTransactions.complete(plugin,placed.operation());
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: house committed");
        var customization=com.hexvane.eterniamod.customization.CustomizationBootstrap.service();
        var palette=customization.palette(world,property,actor,customization.catalog().palettes().getFirst());
        customization.confirm(world,palette);
        var customized=plugin.getServices().provenance().find(placed.instanceId()).orElseThrow();
        if(!"cut_stone".equals(customized.nativeData().get("palette"))||!customized.owner().equals(owner)||!customized.sourceReference().equals("unlock:eternia:house/hub_house"))throw new IllegalStateException("Palette did not retain original house custody and source");
        var path=customization.path(world,property,actor,customization.catalog().paths().getFirst(),10,1,20);
        customization.confirm(world,path);
        boolean duplicateRejected=false;try{customization.confirm(world,path);}catch(IllegalStateException expected){duplicateRejected=true;}
        if(!duplicateRejected)throw new IllegalStateException("Reusing a path preview created a second path");
        customization.confirm(world,customization.removePath(world,property,actor));
        for(int width:new int[]{2,3}){
            var wider=customization.path(world,property,actor,customization.catalog().paths().getFirst(),10,1,20,width);customization.confirm(world,wider);
            var paved=plugin.getServices().provenance().instances(property).stream().filter(i->i.state().equals("PLACED")&&"PATH".equals(i.nativeData().get("kind"))).findFirst().orElseThrow();
            if(!Integer.toString(width).equals(paved.nativeData().get("width")))throw new IllegalStateException("Paving lost selected width");
            customization.confirm(world,customization.removePath(world,property,actor));
        }
        customization.confirm(world,customization.path(world,property,actor,customization.catalog().paths().getFirst(),10,1,20));
        if(plugin.getServices().provenance().instances(property).stream().filter(i->"PATH".equals(i.nativeData().get("kind"))).count()!=1)throw new IllegalStateException("Path removal and replacement duplicated provenance");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: palette and path confirmed, removed and reused under native placement restriction");
        plugin.getServices().ownership().grant(new OwnershipService.GrantRequest("native-smoke-prop",owner,"eternia:prop/potion_shelf",OwnershipService.Kind.QUANTITY,1,null));
        var prop=plugin.getPropCatalog().get("potion_shelf");
        var shelf=NativePlacementTransactions.place(plugin,world,plot,actor,"potion_shelf",new Vector3i(20,2,20),Rotation.None,PrefabResolveUtil.resolvePrefabBuffer(prop.getPrefabPath()),false);
        plot.addProp(new HubPlotProp(shelf.instanceId(),"potion_shelf",20,2,20,Rotation.None));manager.updatePlot(plot);manager.saveIfDirty();NativePlacementTransactions.complete(plugin,shelf.operation());
        if(NativeSnapshotStore.entities(world,NativeRelocationCoordinator.bounds(plot)).size()!=3)throw new IllegalStateException("Potion shelf decorative entities did not spawn");
        Set<UUID> decorationIds=entityIds(world,plot);
        UUID pickup=NativePlacementTransactions.pickup(plugin,world,plot,shelf.instanceId());plot.removeProp(shelf.instanceId());manager.updatePlot(plot);manager.saveIfDirty();NativePlacementTransactions.complete(plugin,pickup);
        if(!entityIds(world,plot).isEmpty())throw new IllegalStateException("Packaged shelf left native entities behind");
        var replaced=NativePlacementTransactions.place(plugin,world,plot,actor,"potion_shelf",new Vector3i(20,2,20),Rotation.None,PrefabResolveUtil.resolvePrefabBuffer(prop.getPrefabPath()),false);
        plot.addProp(new HubPlotProp(replaced.instanceId(),"potion_shelf",20,2,20,Rotation.None));manager.updatePlot(plot);manager.saveIfDirty();NativePlacementTransactions.complete(plugin,replaced.operation());
        if(!entityIds(world,plot).equals(decorationIds))throw new IllegalStateException("Replaced shelf lost stable native entity identities");
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: decorative entity pickup and replacement committed");
        NativePropRecoverySmoke.exercise(plugin,world,plot,actor);
        UUID operation=UUID.randomUUID();plugin.getRelocation().pack(world,owner,operation,false);
        plugin.getLogger().atInfo().log("ETERNIA_NATIVE_SMOKE_STAGE: whole property packed");
        if(plugin.getServices().housing().find(owner).orElseThrow().state()!=HousingService.State.PACKED || manager.getPlot(property)!=null)throw new IllegalStateException("Pack did not retain exclusive custody");
        plugin.getServices().ownership().grant(new OwnershipService.GrantRequest("native-smoke-size",owner,"eternia:plot/personal_32",OwnershipService.Kind.CAPABILITY,1,null));
        plugin.getRelocation().restore(world,actor,owner,operation,new PlotRect(8,40,32,32),2,HousingRules.Scope.PUBLIC,true);
        var restored=manager.getPlot(property);
        if(restored==null || !restored.hasBuilding() || restored.getBuilding().getAnchorX()!=24 || restored.getBuilding().getAnchorY()!=3 || restored.getBuilding().getAnchorZ()!=56)throw new IllegalStateException("Restore did not translate the native house correctly");
        if(NativeSnapshotStore.entities(world,NativeRelocationCoordinator.bounds(restored)).size()!=3)throw new IllegalStateException("Restoration duplicated or lost decorative entities");
        if(!entityIds(world,restored).equals(decorationIds))throw new IllegalStateException("Whole-plot restore changed native entity identities");
        var restoredPath=plugin.getServices().provenance().instances(property).stream().filter(i->i.state().equals("PLACED")&&"PATH".equals(i.nativeData().get("kind"))).findFirst().orElseThrow();
        var pathBounds=NativePlacementTransactions.snapshots(plugin).load(new SnapshotFiles.Saved(restoredPath.nativeData().get("after"),restoredPath.nativeData().get("afterHash"))).bounds();
        if(pathBounds.minY()!=2||pathBounds.minX()!=12||pathBounds.minZ()!=56)throw new IllegalStateException("Sparse path did not relocate with house elevation and size change");
        if(plugin.getServices().housing().find(owner).orElseThrow().state()!=HousingService.State.ACTIVE || plugin.getServices().ownership().available(owner,HousingService.MOVE_CREDIT)!=0)throw new IllegalStateException("Restore did not commit its slot and single move credit");
        if(!plugin.getServices().journal().unfinished().isEmpty())throw new IllegalStateException("Native smoke left unfinished mutations");
    }
    private static Set<UUID> entityIds(World world,HubPlotRecord plot){
        var ids=new HashSet<UUID>();
        for(var ref:NativeSnapshotStore.entities(world,NativeRelocationCoordinator.bounds(plot)))ids.add(ref.getStore().getComponent(ref,com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType()).getUuid());
        return ids;
    }
}
