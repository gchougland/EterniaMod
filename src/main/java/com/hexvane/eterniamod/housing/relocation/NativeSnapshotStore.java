package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaPlacedInstance;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.prefab.config.SelectionPrefabSerializer;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.bson.*;
import org.joml.Vector3i;

/** World-thread-only native adapter. Strict component round trips reject lossy snapshots before mutation. */
public final class NativeSnapshotStore {
    public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public Bounds { if(minX>=maxX||minY>=maxY||minZ>=maxZ||(long)(maxX-minX)*(maxY-minY)*(maxZ-minZ)>3_000_000L) throw new IllegalArgumentException("Invalid or oversized snapshot bounds"); }
        public boolean contains(double x,double y,double z){return x>=minX&&x<maxX&&y>=minY&&y<maxY&&z>=minZ&&z<maxZ;}
        public Vector3i origin(){return new Vector3i(minX,minY,minZ);}
    }
    public record Snapshot(SnapshotFiles.Saved file,Bounds bounds,BsonDocument document) {}
    private final SnapshotFiles files;
    public NativeSnapshotStore(Path directory){files=new SnapshotFiles(directory);}
    public SnapshotFiles files(){return files;}

    public BsonDocument capture(World world,Bounds bounds,Set<UUID> allowedInstances,boolean captureEntities,boolean rejectPlayers) {
        world.getEntityStore().getStore().assertThread();
        if(world.getChunkStore().supportsCubicSections()||bounds.minY()<ChunkUtil.MIN_Y||bounds.maxY()>ChunkUtil.HEIGHT) throw new IllegalStateException("Cubic or out-of-band sections need a dedicated snapshot adapter");
        BlockSelection selection=new BlockSelection();selection.setPosition(bounds.minX(),bounds.minY(),bounds.minZ());selection.setAnchor(0,0,0);
        selection.beginHolderInterning();
        for(int x=bounds.minX();x<bounds.maxX();x++)for(int z=bounds.minZ();z<bounds.maxZ();z++) {
            if(EterniaModPlugin.get().getInfrastructure().protectedColumn(world.getName(),x,z)) continue;
            var chunk=ChunkSectionBlockUtil.worldChunkIfInMemory(world,x,z);
            if(chunk==null) throw new IllegalStateException("Every affected chunk must be loaded");
            if(chunk.isSaving())throw new IllegalStateException("Affected chunk storage is in flight; retry after it completes");
            for(int y=bounds.minY();y<bounds.maxY();y++) {
                var section=ChunkSectionBlockUtil.sectionRefAt(world,x,y,z);
                if(section==null) throw new IllegalStateException("Every affected vertical section must be loaded");
                if(y==bounds.minY()||(y&31)==0)requireStorageIdle(world,section);
                var blocks=world.getChunkStore().getStore().getComponent(section,com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType());
                if(blocks==null)throw new IllegalStateException("Loaded section has no block data");
                var fluids=world.getChunkStore().getStore().getComponent(section,com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType());
                int blockId=blocks.get(x,y,z),fluidId=fluids==null?Fluid.EMPTY_ID:fluids.getFluidId(x,y,z);
                if(BlockType.getAssetMap().getAsset(blockId)==null||fluidId>0&&Fluid.getAssetMap().getAsset(fluidId)==null) throw new IllegalStateException("Unknown native block/fluid asset");
                Holder<ChunkStore> holder=ChunkSectionBlockUtil.blockEntityHolderAt(world,x,y,z);
                if(holder!=null) {
                    // Native BlockEntity.setBlockEntity always reconstructs this location/ref cache at the destination.
                    holder.tryRemoveComponent(BlockModule.BlockStateInfo.getComponentType());
                    assertLossless(holder,ChunkStore.REGISTRY);
                }
                var physics=world.getChunkStore().getStore().getComponent(section,BlockPhysics.getComponentType());
                selection.addBlockAtWorldPos(x,y,z,blockId,blocks.getRotationIndex(x,y,z),blocks.getFiller(x,y,z),physics==null?BlockPhysics.NULL_SUPPORT:physics.get(x,y,z),holder);
                if(fluidId>0)selection.addFluidAtWorldPos(x,y,z,fluidId,fluids.getFluidLevel(x,y,z));
            }
        }
        selection.endHolderInterning();
        if(captureEntities)for(var ref:entities(world,bounds)) {
            var store=world.getEntityStore().getStore();
            if(store.getComponent(ref,Player.getComponentType())!=null) {if(rejectPlayers)throw new IllegalStateException("Players must leave the plot before packing");continue;}
            var link=store.getComponent(ref,EterniaPlacedInstance.getComponentType());
            if(link==null||!allowedInstances.contains(link.getInstanceId()))throw new IllegalStateException("Unproven entity in snapshot; relocate it or record its provenance first");
            var dirty=store.getComponent(ref,com.hypixel.hytale.server.core.entity.Dirty.getComponentType());if(dirty!=null&&dirty.isSaving())throw new IllegalStateException("Decorative entity storage is in flight; retry after it completes");
            Holder<EntityStore> holder=NativeDecorativeEntities.capture(store.copyEntity(ref));selection.addEntityFromWorld(holder);
        }
        selection.sortEntitiesByPosition();
        BsonDocument doc=canonicalEmptyFluids(SelectionPrefabSerializer.serialize(selection));
        if(!doc.equals(canonicalEmptyFluids(SelectionPrefabSerializer.serialize(SelectionPrefabSerializer.deserialize(doc)))))throw new IllegalStateException("Native prefab round trip changed snapshot contents");
        return doc;
    }
    public Snapshot save(String name,Bounds bounds,BsonDocument document) throws IOException {
        document=canonicalEmptyFluids(SelectionPrefabSerializer.serialize(SelectionPrefabSerializer.deserialize(document)));
        var envelope=new BsonDocument("format",new BsonString("eternia-native-snapshot-1"));
        envelope.put("bounds",new BsonArray(List.of(new BsonInt32(bounds.minX()),new BsonInt32(bounds.minY()),new BsonInt32(bounds.minZ()),new BsonInt32(bounds.maxX()),new BsonInt32(bounds.maxY()),new BsonInt32(bounds.maxZ()))));
        envelope.put("clearFluidAtEveryBlock",BsonBoolean.TRUE);envelope.put("prefab",document);
        var saved=files.write(name,envelope.toJson().getBytes(StandardCharsets.UTF_8));return load(saved);
    }
    public Snapshot load(SnapshotFiles.Saved file) throws IOException {
        var envelope=BsonDocument.parse(new String(files.read(file),StandardCharsets.UTF_8));
        if(!envelope.getString("format").getValue().equals("eternia-native-snapshot-1")||!envelope.getBoolean("clearFluidAtEveryBlock").getValue())throw new IOException("Unsupported snapshot format");
        var b=envelope.getArray("bounds");var bounds=new Bounds(b.get(0).asInt32().getValue(),b.get(1).asInt32().getValue(),b.get(2).asInt32().getValue(),b.get(3).asInt32().getValue(),b.get(4).asInt32().getValue(),b.get(5).asInt32().getValue());
        var document=canonicalEmptyFluids(envelope.getDocument("prefab"));
        if(!document.equals(canonicalEmptyFluids(SelectionPrefabSerializer.serialize(SelectionPrefabSerializer.deserialize(document)))))throw new IOException("Snapshot asset/component round trip changed; migration required");
        return new Snapshot(file,bounds,document);
    }
    public void apply(World world,Snapshot snapshot,Vector3i destination) {
        NativeDecorativeEntities.preflightRestore(world,snapshot.document(),destination);
        var blockDoc=snapshot.document().clone();blockDoc.remove("entities");
        var selection=SelectionPrefabSerializer.deserialize(blockDoc);
        selection.forEachBlock((x,y,z,b)->{
            int wx=Math.addExact(destination.x,x),wy=Math.addExact(destination.y,y),wz=Math.addExact(destination.z,z);
            if(EterniaModPlugin.get().getInfrastructure().protectedColumn(world.getName(),wx,wz)||ChunkSectionBlockUtil.sectionRefAt(world,wx,wy,wz)==null)throw new IllegalStateException("Snapshot destination became protected or unavailable");
        });
        // Native serializers omit empty fluids; reconstruct explicit clearing for EVERY captured block.
        // BlockSelection iteration holds its read lock; writing its fluids inside that callback deadlocks.
        var clearFluids=new ArrayList<Vector3i>();selection.forEachBlock((x,y,z,b)->clearFluids.add(new Vector3i(x,y,z)));
        for(var cell:clearFluids)selection.addFluidAtLocalPos(cell.x,cell.y,cell.z,Fluid.EMPTY_ID,(byte)0);
        if(blockDoc.containsKey("fluids"))SelectionPrefabSerializer.deserialize(blockDoc).forEachFluid(selection::addFluidAtLocalPos);
        selection.place(null,world,destination,null);
        if(snapshot.document().containsKey("entities"))for(var value:snapshot.document().getArray("entities")) {
            Holder<EntityStore> holder=EntityStore.REGISTRY.deserialize(value.asDocument());
            holder.getComponent(TransformComponent.getComponentType()).getPosition().add(destination.x,destination.y,destination.z);
            var ref=world.getEntityStore().getStore().addEntity(holder,AddReason.LOAD);
            if(ref==null||!ref.isValid())throw new IllegalStateException("Native decorative entity spawn failed; recovery required");
            var dirty=world.getEntityStore().getStore().getComponent(ref,com.hypixel.hytale.server.core.entity.Dirty.getComponentType());if(dirty!=null)dirty.forceMarkDirty();
        }
    }
    private static void requireStorageIdle(World world,Ref<ChunkStore> ref){
        var store=world.getChunkStore().getStore();
        var chunk=store.getComponent(ref,com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection.getComponentType());
        var entities=store.getComponent(ref,com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection.getComponentType());
        var blocks=store.getComponent(ref,com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection.getComponentType());
        if(chunk!=null&&chunk.isSaving()||entities!=null&&entities.isSaving()||blocks!=null&&blocks.isSaving())throw new IllegalStateException("Affected section storage is in flight; retry after it completes");
        if(entities!=null&&!entities.getEntityHolders().isEmpty())throw new IllegalStateException("Affected section contains unloaded entities; fully load it before taking custody");
    }
    /** Empty fluids at captured block cells are represented by the envelope's explicit clear-every-block contract. */
    static BsonDocument canonicalEmptyFluids(BsonDocument source){
        var result=source.clone();var cells=new HashSet<String>();for(var block:result.getArray("blocks",new BsonArray()))cells.add(cellKey(block.asDocument()));
        var fluids=new BsonArray();for(var value:result.getArray("fluids",new BsonArray())){var fluid=value.asDocument();if("Empty".equals(fluid.getString("name").getValue())){if(fluid.getNumber("level").intValue()!=0||!cells.contains(cellKey(fluid)))throw new IllegalStateException("Empty fluid snapshot requires a captured block cell and zero level");}else fluids.add(value);}
        result.put("fluids",fluids);return result;
    }
    private static String cellKey(BsonDocument cell){return cell.getInt32("x").getValue()+","+cell.getInt32("y").getValue()+","+cell.getInt32("z").getValue();}
    public void verify(World world,Snapshot snapshot,Vector3i destination,Set<UUID> instances,boolean entities) {
        Bounds b=snapshot.bounds();Bounds moved=new Bounds(destination.x,destination.y,destination.z,destination.x+b.maxX()-b.minX(),destination.y+b.maxY()-b.minY(),destination.z+b.maxZ()-b.minZ());
        BsonDocument actual=capture(world,moved,instances,entities,false);
        BsonDocument expected=snapshot.document().clone();if(!entities)expected.remove("entities");
        actual=NativeRelocationCoordinator.onlyCapturedCells(actual,expected);
        actual=canonicalEmptyFluids(SelectionPrefabSerializer.serialize(SelectionPrefabSerializer.deserialize(actual)));
        if(!comparisonDocument(expected).equals(comparisonDocument(actual))){
            String diagnostic="unavailable";
            try{var saved=files.write("verify-"+UUID.randomUUID()+".json",new BsonDocument("expected",expected).append("actual",actual).toJson().getBytes(StandardCharsets.UTF_8));diagnostic=saved.reference();}catch(IOException ignored){}
            throw new IllegalStateException("Native world write did not match verified snapshot; recovery required; diagnostic="+diagnostic+"; "+differenceSummary(expected,actual));
        }
    }
    /** Hytale initializes this scheduler clock on FarmingBlock insertion. Keep it in saved
     * snapshots, but do not confuse clock initialization with a changed crop or inventory. */
    static BsonDocument comparisonDocument(BsonDocument source) {
        var copy=source.clone();
        for(var value:copy.getArray("blocks",new BsonArray())) {
            var block=value.asDocument();var holder=block.getDocument("components",new BsonDocument());
            var components=holder.getDocument("Components",new BsonDocument());
            var farming=components.getDocument("FarmingBlock",null);
            if(farming!=null){
                farming.remove("LastTickGameTime");
                // Native chunk reload omits a FarmingBlock with no persisted crop state.
                // Empty/timestamp-only defaults are equivalent; growth, inventory and other data are not.
                if(farming.isEmpty())components.remove("FarmingBlock");
            }
            if(components.isEmpty())holder.remove("Components");
            if(holder.isEmpty())block.remove("components");
        }
        return copy;
    }
    private static String differenceSummary(BsonDocument expected,BsonDocument actual){
        for(String field:List.of("version","blockIdVersion","anchorX","anchorY","anchorZ"))if(!Objects.equals(expected.get(field),actual.get(field)))return field+" differs";
        for(String field:List.of("blocks","fluids","entities")){
            var e=expected.getArray(field,new BsonArray());var a=actual.getArray(field,new BsonArray());if(e.size()!=a.size())return field+" count "+e.size()+" expected / "+a.size()+" actual";
            for(int i=0;i<e.size();i++)if(!e.get(i).equals(a.get(i))){var ed=e.get(i).asDocument();var ad=a.get(i).asDocument();var keys=new TreeSet<String>();keys.addAll(ed.keySet());keys.addAll(ad.keySet());keys.removeIf(k->Objects.equals(ed.get(k),ad.get(k)));return field+" entry "+i+" differs in "+keys;}
        }
        return "document fields differ";
    }
    public static List<Ref<EntityStore>> entities(World world,Bounds bounds) {
        List<Ref<EntityStore>> refs=new ArrayList<>();
        world.getEntityStore().getStore().forEachChunk(TransformComponent.getComponentType(),(chunk,commands)->{
            for(int i=0;i<chunk.size();i++){
                if(com.hexvane.eterniamod.collections.CosmeticPet.getComponentType()!=null&&chunk.getComponent(i,com.hexvane.eterniamod.collections.CosmeticPet.getComponentType())!=null)continue;
                var p=chunk.getComponent(i,TransformComponent.getComponentType()).getPosition();
                if(bounds.contains(p.x,p.y,p.z)&&!EterniaModPlugin.get().getInfrastructure().protectedColumn(world.getName(),(int)Math.floor(p.x),(int)Math.floor(p.z)))refs.add(chunk.getReferenceTo(i));
            }
        });return refs;
    }
    public static <T> void assertLossless(Holder<T> holder,ComponentRegistry<T> registry) {
        if(holder.getComponent(registry.getUnknownComponentType())!=null)throw new IllegalStateException("Unresolved component data requires migration before movement");
        var serializable=holder.cloneSerializable(registry.getData());
        if(!holder.getArchetype().equals(serializable.getArchetype()))throw new IllegalStateException("Snapshot contains unsupported transient components: "+holder.getArchetype());
        var doc=registry.serialize(holder);
        if(!doc.equals(registry.serialize(registry.deserialize(doc))))throw new IllegalStateException("Component codec cannot round trip its contents");
    }
}
