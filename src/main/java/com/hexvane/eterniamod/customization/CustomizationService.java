package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.housing.*;
import com.hexvane.eterniamod.housing.relocation.*;
import com.hexvane.eterniamod.hub.*;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bson.*;

/** World-thread customization with exact preview masks, durable snapshots and custody-preserving updates. */
public final class CustomizationService implements AutoCloseable {
    public record Preview(UUID operation,UUID actor,UUID property,String world,String content,String capability,String kind,String description,
        ProvenanceService.Instance instance,NativeSnapshotStore.Snapshot before,NativeSnapshotStore.Snapshot after,NativeSnapshotStore.Snapshot instanceAfter,Map<String,String> descriptor,Instant expires){}
    private final EterniaModPlugin plugin;private final CustomizationCatalog catalog;private volatile boolean closed;
    public CustomizationService(EterniaModPlugin plugin){this.plugin=plugin;catalog=new CustomizationCatalog(plugin.getDataDirectory());}
    public CustomizationCatalog catalog(){return catalog;}
    private NativeSnapshotStore snapshots(){return NativePlacementTransactions.snapshots(plugin);}
    private HubPlotRecord require(World world,UUID property,UUID actor,String content,String capability){
        if(closed)throw new IllegalStateException("Customization is unavailable");
        var plot=EterniaWorldRegistries.getOrCreateHubPlotManager(world,plugin).getPlot(property);
        if(plot==null||!HousingAccess.can(plugin,plot,actor,capability))throw new IllegalStateException("Housing permission required");
        NativePlacementTransactions.requireActive(plugin,plot);
        if(!plugin.getServices().ownership().owns(HousingAccess.owner(plot),content))throw new IllegalStateException("This customization is not owned");
        return plot;
    }
    public Preview palette(World world,UUID property,UUID actor,CustomizationCatalog.Palette palette)throws IOException {
        String content="eternia:palette/"+palette.id();var plot=require(world,property,actor,content,"housing.palette");
        if(!plot.hasBuilding()||!plot.getBuilding().getBuildingId().equals(palette.houseId()))throw new IllegalStateException("This palette requires its matching house style");
        var instance=plugin.getServices().provenance().instances(property).stream().filter(i->i.state().equals("PLACED")&&"HOUSE".equals(i.nativeData().get("kind"))).findFirst().orElseThrow(()->new IllegalStateException("House provenance is missing"));
        if(!instance.owner().equals(HousingAccess.owner(plot)))throw new IllegalStateException("House custody does not match plot");
        var files=snapshots();var expected=files.load(pointer(instance.nativeData(),"after"));
        var base=files.load(instance.nativeData().containsKey("paletteBase")?pointer(instance.nativeData(),"paletteBase"):pointer(instance.nativeData(),"after"));
        var expectedCells=CellEdits.index(expected.document(),"blocks");var mask=new BsonArray();
        var bounds=expected.bounds();var footprint=rect(plot);var roads=plugin.getInfrastructure().structureRoads(world.getName());
        for(var entry:CellEdits.index(base.document(),"blocks").entrySet()) {
            String name=entry.getValue().getString("name").getValue();String replacement=palette.blocks().get(name);if(replacement==null)continue;
            var current=expectedCells.get(entry.getKey());if(current==null||current.containsKey("components")||current.getInt32("filler",new BsonInt32(0)).getValue()!=0)throw new IllegalStateException("Palette surfaces must be simple blocks");
            simpleBlock(name);simpleBlock(replacement);int x=bounds.minX()+current.getInt32("x").getValue(),z=bounds.minZ()+current.getInt32("z").getValue();
            if(!HousingRules.structure(footprint,new PlotRect(x,z,1,1),roads).valid()||plugin.getInfrastructure().protectedColumn(world.getName(),x,z))throw new IllegalStateException("A palette surface crosses protected setbacks");
            var changed=current.clone();changed.put("name",new BsonString(replacement));if(!CellEdits.sameCell(current,changed))mask.add(changed);
        }
        if(mask.isEmpty())throw new IllegalStateException("This palette is already applied or has no matching authored surfaces");
        BsonDocument changes=expected.document().clone();changes.put("blocks",mask);changes.put("fluids",new BsonArray());changes.remove("entities");
        var current=files.capture(world,bounds,Set.of(),false,false);var keys=CellEdits.index(changes,"blocks").keySet();CellEdits.requireSame(current,CellEdits.select(expected.document(),keys));
        var afterDocument=expected.document().clone();CellEdits.overlay(afterDocument,changes);
        UUID operation=UUID.randomUUID();var before=files.save(operation+"-before.json",bounds,CellEdits.select(current,keys));var after=files.save(operation+"-after.json",bounds,changes);
        var instanceAfter=files.save(operation+"-house-after.json",bounds,afterDocument);Map<String,String> data=new HashMap<>(instance.nativeData());
        if(!data.containsKey("paletteBase")){data.put("paletteBase",expected.file().reference());data.put("paletteBaseHash",expected.file().sha256());}
        data.put("after",instanceAfter.file().reference());data.put("afterHash",instanceAfter.file().sha256());data.put("palette",palette.id());
        return new Preview(operation,actor,property,world.getName(),content,"housing.palette","PALETTE",palette.name()+": "+mask.size()+" authored masonry blocks",instance,before,after,instanceAfter,Map.copyOf(data),Instant.now().plusSeconds(90));
    }
    public Preview path(World world,UUID property,UUID actor,CustomizationCatalog.PathStyle style,int startX,int startY,int startZ)throws IOException {
        return path(world,property,actor,style,startX,startY,startZ,1);
    }
    public Preview path(World world,UUID property,UUID actor,CustomizationCatalog.PathStyle style,int startX,int startY,int startZ,int width)throws IOException {
        String content="eternia:path/"+style.id();var plot=require(world,property,actor,content,"housing.road.manage");simpleBlock(style.blockId());
        if(!plot.hasBuilding())throw new IllegalStateException("Place your house before building its path");
        var instances=plugin.getServices().provenance().instances(property);if(instances.stream().anyMatch(i->i.state().equals("PLACED")&&"PATH".equals(i.nativeData().get("kind"))))throw new IllegalStateException("Remove the current path before changing its route");
        var house=instances.stream().filter(i->i.state().equals("PLACED")&&"HOUSE".equals(i.nativeData().get("kind"))).findFirst().orElseThrow();
        var occupied=new ArrayList<NativeSnapshotStore.Bounds>();
        for(var placed:instances)if(placed.state().equals("PLACED"))occupied.add(snapshots().load(pointer(placed.nativeData(),"after")).bounds());
        int ground=surface(world,startX,startZ,startY);if(ground==Integer.MIN_VALUE)throw new IllegalStateException("Stand on level natural ground outside your door");
        var obstacles=occupied.stream().map(b->new PlotRect(b.minX(),b.minZ(),b.maxX()-b.minX(),b.maxZ()-b.minZ())).toList();
        var route=PathPlanner.plan(rect(plot),new PathPlanner.Cell(startX,startZ),plugin.getInfrastructure().structureRoads(world.getName()),width,cell->
            !plugin.getInfrastructure().protectedColumn(world.getName(),cell.x(),cell.z())&&surface(world,cell.x(),cell.z(),ground+1)==ground,obstacles).blocks();
        int minX=route.stream().mapToInt(PathPlanner.Cell::x).min().orElseThrow(),maxX=route.stream().mapToInt(PathPlanner.Cell::x).max().orElseThrow()+1;
        int minZ=route.stream().mapToInt(PathPlanner.Cell::z).min().orElseThrow(),maxZ=route.stream().mapToInt(PathPlanner.Cell::z).max().orElseThrow()+1;
        var bounds=new NativeSnapshotStore.Bounds(minX,ground,minZ,maxX,ground+1,maxZ);var files=snapshots();var capture=files.capture(world,bounds,Set.of(),false,false);
        Set<String> keys=new HashSet<>();for(var cell:route)keys.add((cell.x()-minX)+",0,"+(cell.z()-minZ));
        BsonDocument beforeDocument=CellEdits.select(capture,keys);var afterDocument=beforeDocument.clone();
        if(!beforeDocument.getArray("fluids",new BsonArray()).isEmpty())throw new IllegalStateException("Paths cannot replace fluid cells");
        for(var value:afterDocument.getArray("blocks")){var cell=value.asDocument();if(cell.containsKey("components")||cell.getInt32("filler",new BsonInt32(0)).getValue()!=0)throw new IllegalStateException("A path cannot replace a multiblock or functional block");cell.put("name",new BsonString(style.blockId()));}
        UUID operation=UUID.randomUUID();var before=files.save(operation+"-before.json",bounds,beforeDocument);var after=files.save(operation+"-after.json",bounds,afterDocument);
        var packed=plugin.getServices().provenance().ownedInstances(HousingAccess.owner(plot)).stream().filter(i->i.state().equals("PACKED")&&i.contentId().equals(content)&&"PATH".equals(i.nativeData().get("kind"))).findFirst().orElse(null);
        Map<String,String> descriptor=Map.of("before",before.file().reference(),"beforeHash",before.file().sha256(),"after",after.file().reference(),"afterHash",after.file().sha256(),"kind","PATH","catalog",style.id(),"width",Integer.toString(width),"yaw","None","placedAt",Long.toString(System.currentTimeMillis()));
        return new Preview(operation,actor,property,world.getName(),content,"housing.road.manage","PATH",style.name()+" · "+width+" block"+(width==1?"":"s")+" wide · "+route.size()+" paving blocks. Leaves room beside your home and decorations.",packed,before,after,after,descriptor,Instant.now().plusSeconds(90));
    }
    public Preview removePath(World world,UUID property,UUID actor)throws IOException {
        var item=plugin.getServices().provenance().instances(property).stream().filter(i->i.state().equals("PLACED")&&"PATH".equals(i.nativeData().get("kind"))).findFirst().orElseThrow(()->new IllegalStateException("There is no placed path"));
        var plot=require(world,property,actor,item.contentId(),"housing.road.manage");if(!item.owner().equals(HousingAccess.owner(plot)))throw new IllegalStateException("Path owner differs from plot owner");
        var files=snapshots();var placed=files.load(pointer(item.nativeData(),"after"));var original=files.load(pointer(item.nativeData(),"before"));
        var current=files.capture(world,placed.bounds(),Set.of(),false,false);CellEdits.requireSame(current,placed.document());UUID operation=UUID.randomUUID();
        var before=files.save(operation+"-before.json",placed.bounds(),CellEdits.select(current,CellEdits.index(placed.document(),"blocks").keySet()));
        return new Preview(operation,actor,property,world.getName(),item.contentId(),"housing.road.manage","REMOVE_PATH","Remove this path and restore its saved ground",item,before,original,placed,item.nativeData(),Instant.now().plusSeconds(90));
    }
    public void confirm(World world,Preview preview)throws IOException {
        if(!world.getName().equals(preview.world)||Instant.now().isAfter(preview.expires))throw new IllegalStateException("Preview expired. Open a fresh preview.");
        var plot=require(world,preview.property,preview.actor,preview.content,preview.capability);var services=plugin.getServices();var files=snapshots();
        if(preview.instance!=null){var current=services.provenance().find(preview.instance.id()).orElseThrow();if(current.revision()!=preview.instance.revision())throw new IllegalStateException("House or path changed after preview");}
        if(preview.kind.equals("PALETTE")) {
            for(var existing:services.provenance().instances(preview.property)) {
                if(!existing.state().equals("PLACED")||existing.id().equals(preview.instance.id()))continue;
                var bounds=files.load(pointer(existing.nativeData(),"after")).bounds();
                for(var value:preview.after.document().getArray("blocks")) {
                    var cell=value.asDocument();int x=preview.after.bounds().minX()+cell.getInt32("x").getValue(),y=preview.after.bounds().minY()+cell.getInt32("y").getValue(),z=preview.after.bounds().minZ()+cell.getInt32("z").getValue();
                    if(bounds.contains(x,y,z))throw new IllegalStateException("A catalog decoration overlaps a palette surface; pack it before applying this palette");
                }
            }
        }
        if(preview.kind.equals("PATH")) {
            var placed=services.provenance().instances(preview.property).stream().filter(i->i.state().equals("PLACED")).toList();
            if(placed.stream().anyMatch(i->"PATH".equals(i.nativeData().get("kind"))))throw new IllegalStateException("A path was placed after this preview");
            for(var existing:placed) {
                var bounds=files.load(pointer(existing.nativeData(),"after")).bounds();
                for(var value:preview.after.document().getArray("blocks")){var cell=value.asDocument();int x=preview.after.bounds().minX()+cell.getInt32("x").getValue(),z=preview.after.bounds().minZ()+cell.getInt32("z").getValue();if(x>=bounds.minX()-1&&x<bounds.maxX()+1&&z>=bounds.minZ()-1&&z<bounds.maxZ()+1)throw new IllegalStateException("A house or decoration is too close to this path. Create a new preview.");}
            }
        }
        var currentRoads=plugin.getInfrastructure().structureRoads(world.getName());
        for(var cell:preview.after.document().getArray("blocks")){
            var c=cell.asDocument();int x=preview.after.bounds().minX()+c.getInt32("x").getValue(),z=preview.after.bounds().minZ()+c.getInt32("z").getValue();
            if(!rect(plot).contains(x,z)||plugin.getInfrastructure().protectedColumn(world.getName(),x,z))throw new IllegalStateException("Protected columns changed after preview");
            if(preview.kind.equals("PALETTE")&&!HousingRules.structure(rect(plot),new PlotRect(x,z,1,1),currentRoads).valid())throw new IllegalStateException("Road or border setbacks changed after preview");
        }
        CellEdits.requireSame(files.capture(world,preview.before.bounds(),Set.of(),false,false),preview.before.document());
        var journal=services.journal();var op=journal.prepare(preview.operation,"CUSTOMIZE_"+preview.kind,HousingAccess.owner(plot),preview.property.toString());
        UUID instance=preview.instance==null?UUID.randomUUID():preview.instance.id();var manifest=new BsonDocument("before",new BsonString(preview.before.file().reference())).append("beforeHash",new BsonString(preview.before.file().sha256())).append("after",new BsonString(preview.after.file().reference())).append("afterHash",new BsonString(preview.after.file().sha256())).append("instance",new BsonString(instance.toString())).append("kind",new BsonString(preview.kind));
        manifest.put("world",new BsonString(world.getName()));
        if(preview.instance!=null){BsonDocument previous=new BsonDocument();preview.instance.nativeData().forEach((key,value)->previous.put(key,new BsonString(value)));manifest.put("previousDescriptor",previous);manifest.put("previousState",new BsonString(preview.instance.state()));manifest.put("previousSnapshot",new BsonString(preview.instance.snapshotRef()));manifest.put("previousProperty",new BsonString(preview.instance.propertyId().toString()));}
        try{var saved=files.files().write(preview.operation+"-customization.json",manifest.toJson().getBytes(StandardCharsets.UTF_8));op=journal.attachVerifiedSnapshot(op.id(),op.revision(),saved.reference(),saved.sha256());}
        catch(IOException|RuntimeException failure){journal.advance(op.id(),op.revision(),JournalService.State.CANCELLED);throw failure;}
        try {
            files.apply(world,preview.after,preview.after.bounds().origin());files.verify(world,preview.after,preview.after.bounds().origin(),Set.of(),false);NativePlacementTransactions.flush(world,preview.after.bounds());
            if(preview.kind.equals("PALETTE"))services.provenance().acknowledgeCustomization(instance,preview.instance.revision(),preview.descriptor);
            else if(preview.kind.equals("REMOVE_PATH"))services.provenance().acknowledgePacked(instance,preview.instance.revision(),NativeRelocationCoordinator.reference(preview.instanceAfter.file()));
            else if(preview.instance!=null)services.provenance().acknowledgeRestored(instance,preview.instance.revision(),preview.property,preview.descriptor);
            else services.provenance().recordVerifiedPlacement(instance,HousingAccess.owner(plot),preview.actor,preview.property,preview.content,"unlock:"+preview.content,preview.descriptor,"customization:"+preview.operation);
            op=journal.advance(op.id(),op.revision(),JournalService.State.WORLD_APPLIED);journal.advance(op.id(),op.revision(),JournalService.State.COMPLETED);
        }catch(Throwable failure){NativePlacementTransactions.lock(plugin,op.id());throw new IllegalStateException("Customization is locked for recovery; before and after snapshots are retained",failure);}
    }
    /** Operator recovery hook. Never restores over a third value or silently discards component/fluid edits. */
    public void rollback(World world,UUID operation)throws IOException {
        var services=plugin.getServices();var journal=services.journal();var op=journal.find(operation).orElseThrow();
        if(!op.kind().startsWith("CUSTOMIZE_")||Set.of(JournalService.State.COMPLETED,JournalService.State.CANCELLED).contains(op.state()))throw new IllegalStateException("This is not an unfinished customization");
        var files=snapshots();var manifest=BsonDocument.parse(new String(files.files().read(new SnapshotFiles.Saved(op.snapshotRef(),op.snapshotHash())),StandardCharsets.UTF_8));
        if(!world.getName().equals(manifest.getString("world").getValue()))throw new IllegalStateException("Recovery belongs to another world");
        var before=files.load(new SnapshotFiles.Saved(manifest.getString("before").getValue(),manifest.getString("beforeHash").getValue()));
        var after=files.load(new SnapshotFiles.Saved(manifest.getString("after").getValue(),manifest.getString("afterHash").getValue()));
        var actual=files.capture(world,before.bounds(),Set.of(),false,false);var currentCells=CellEdits.index(actual,"blocks");var beforeCells=CellEdits.index(before.document(),"blocks");var afterCells=CellEdits.index(after.document(),"blocks");
        var currentFluids=CellEdits.index(actual,"fluids");var beforeFluids=CellEdits.index(before.document(),"fluids");var afterFluids=CellEdits.index(after.document(),"fluids");
        for(var entry:beforeCells.entrySet()) {
            String key=entry.getKey();if(!CellEdits.sameCell(currentCells.get(key),entry.getValue())&&!CellEdits.sameCell(currentCells.get(key),afterCells.get(key)))throw new IllegalStateException("Recovery cells contain unrecognized edits; manual review required");
            if(!Objects.equals(currentFluids.get(key),beforeFluids.get(key))&&!Objects.equals(currentFluids.get(key),afterFluids.get(key)))throw new IllegalStateException("Recovery fluids contain unrecognized edits");
        }
        UUID instance=UUID.fromString(manifest.getString("instance").getValue());var record=services.provenance().find(instance).orElse(null);
        if(record!=null&&(!record.owner().equals(op.owner())||!record.propertyId().toString().equals(op.detail())&&!record.propertyId().toString().equals(manifest.getString("previousProperty",new BsonString(op.detail())).getValue())))throw new IllegalStateException("Customization provenance changed ownership");
        if(op.state()!=JournalService.State.RECOVERY_REQUIRED)op=journal.advance(operation,op.revision(),JournalService.State.RECOVERY_REQUIRED);
        files.apply(world,before,before.bounds().origin());files.verify(world,before,before.bounds().origin(),Set.of(),false);NativePlacementTransactions.flush(world,before.bounds());
        if(record!=null) {
            Map<String,String> previous=new HashMap<>();manifest.getDocument("previousDescriptor",new BsonDocument()).forEach((key,value)->previous.put(key,value.asString().getValue()));
            String previousState=manifest.getString("previousState",new BsonString("ABSENT")).getValue();
            if(previousState.equals("PLACED")) {
                if(record.state().equals("PACKED"))record=services.provenance().acknowledgeRestored(instance,record.revision(),record.propertyId(),previous);
                else services.provenance().acknowledgeCustomization(instance,record.revision(),previous);
            }else if(record.state().equals("PLACED")) {
                if(!previous.isEmpty())record=services.provenance().acknowledgeCustomization(instance,record.revision(),previous);
                String snapshot=manifest.getString("previousSnapshot",new BsonString("")).getValue();if(snapshot.isBlank())snapshot=NativeRelocationCoordinator.reference(after.file());
                services.provenance().acknowledgePacked(instance,record.revision(),snapshot);
            }
        }
        op=journal.find(operation).orElseThrow();journal.advance(operation,op.revision(),JournalService.State.CANCELLED);
    }
    private static SnapshotFiles.Saved pointer(Map<String,String> data,String key){return new SnapshotFiles.Saved(data.get(key),data.get(key+"Hash"));}
    private static PlotRect rect(HubPlotRecord plot){var p=plot.getFootprint();return new PlotRect(p.getMinX(),p.getMinZ(),p.getMaxX()-p.getMinX()+1,p.getMaxZ()-p.getMinZ()+1);}
    private static void simpleBlock(String id){var block=BlockType.getAssetMap().getAsset(id);if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null)throw new IllegalStateException("Customization requires a valid plain cube block: "+id);}
    private int surface(World world,int x,int z,int baseline){
        if(!ChunkSectionBlockUtil.isChunkInMemory(world,x,z))return Integer.MIN_VALUE;
        for(int y=baseline+1;y>=baseline-3;y--){if(y<0||y>316)continue;var block=ChunkSectionBlockUtil.blockType(world,x,y,z);if(block==null||!block.isCubeDrawType()||block.getBlockEntity()!=null)continue;
            String id=block.getId();if(!(id.startsWith("Soil_")||id.startsWith("Grass_")||id.startsWith("Sand_")||id.equals("Rock_Stone_Cobble")||id.equals("Rock_Stone")))continue;
            if(empty(world,x,y+1,z)&&empty(world,x,y+2,z))return y;
        }return Integer.MIN_VALUE;
    }
    private boolean empty(World world,int x,int y,int z){if(ChunkSectionBlockUtil.sectionRefAt(world,x,y,z)==null)return false;var block=ChunkSectionBlockUtil.blockType(world,x,y,z);return block==null||block==BlockType.EMPTY||block.getMaterial()==com.hypixel.hytale.protocol.BlockMaterial.Empty;}
    @Override public void close(){closed=true;CustomizationBootstrap.stop();}
}
