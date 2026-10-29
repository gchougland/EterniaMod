package com.hexvane.eterniamod.town;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.NativeSnapshotStore;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import com.hexvane.eterniamod.pathtool.TownRoadAuthor;
import com.hexvane.eterniamod.prefab.PrefabResolveUtil;
import com.hexvane.eterniamod.setup.HubNpcIdentity;
import com.hexvane.eterniamod.setup.ManagedHubServices;
import com.hexvane.eterniamod.setup.SetupAccess;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import com.hypixel.hytale.server.core.util.PrefabUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.joml.Vector3i;

/**
 * Builds the Eternia town on the terrain where an admin stands, and takes it away again (docs/implementation/town-placement.md).
 * A placement runs as a job on its own thread: chunk loads are awaited there, and every world change runs on the world thread
 * one tile or one prefab at a time. Before anything changes, every touched column is backed up; the manifest records what was
 * added (props, managed NPCs, the plaza, roads) so `town undo` can reverse it in order.
 */
public final class TownBuilder {
    private TownBuilder(){}
    static final int TILE=32,ABOVE=50,BELOW=3;
    private static final int PASTE_SETTINGS=SetBlockSettings.NO_UPDATE_STATE|SetBlockSettings.NO_SEND_PARTICLES;
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final ExecutorService JOBS=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"Eternia town builder");t.setDaemon(true);return t;});
    private static volatile boolean busy;
    /** A planned placement awaiting the admin's confirmation. */
    public record Preview(UUID actor,String world,int x,int z,TownPlan plan,TownTerrainPlanner.Result result,String summary,Instant expires){}

    // ---- the manifest ---------------------------------------------------------------------------------------------------
    static final class Manifest {
        String id,world,state;int[] anchor;long created;
        List<Backup> backups=new ArrayList<>();List<String> entities=new ArrayList<>(),roads=new ArrayList<>(),services=new ArrayList<>();
        boolean registered,worldCreated,previousHousing;String previousRole;double[] previousArrival;String plazaArea;
    }
    static final class Backup {String name,sha;int[] min,max;}
    static Path folder(EterniaModPlugin plugin){return plugin.getDataDirectory().resolve("town");}
    static Path manifestPath(EterniaModPlugin plugin,String world){return folder(plugin).resolve(world.replaceAll("[^A-Za-z0-9_.-]","_")+"-manifest.json");}
    static void save(EterniaModPlugin plugin,Manifest m)throws IOException{Files.createDirectories(folder(plugin));var path=manifestPath(plugin,m.world);var tmp=path.resolveSibling(path.getFileName()+".pending");Files.writeString(tmp,JSON.toJson(m));Files.move(tmp,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
    static Optional<Manifest> load(EterniaModPlugin plugin,String world)throws IOException{var path=manifestPath(plugin,world);return Files.exists(path)?Optional.of(JSON.fromJson(Files.readString(path),Manifest.class)):Optional.empty();}
    static NativeSnapshotStore store(EterniaModPlugin plugin,String id){return new NativeSnapshotStore(folder(plugin).resolve("backups").resolve(id));}

    // ---- preview ------------------------------------------------------------------------------------------------------
    /** Surveys the land round (x, z) and plans the town on it; completes with a preview to confirm. */
    public static CompletableFuture<Preview> preview(EterniaModPlugin plugin,World world,UUID actor,int x,int z,PlayerRef player){
        if(busy)return CompletableFuture.failedFuture(new IllegalStateException("A town is already being placed or removed"));
        return CompletableFuture.supplyAsync(()->{
            try{
                SetupAccess.require(actor);
                var existing=load(plugin,world.getName());if(existing.isPresent()&&!"undone".equals(existing.get().state))throw new IllegalStateException("This world already has a town. Use /e admin town undo first");
                var plan=TownPlan.load();var areas=TownTerrainPlanner.survey(plan);
                tell(player,"Surveying the land...");
                var heights=new HashMap<Long,Integer>();
                for(var area:areas){loadArea(world,x+area.minX(),z+area.minZ(),x+area.maxX(),z+area.maxZ());
                    for(int tx=Math.floorDiv(area.minX(),TILE);tx<=Math.floorDiv(area.maxX(),TILE);tx++)for(int tz=Math.floorDiv(area.minZ(),TILE);tz<=Math.floorDiv(area.maxZ(),TILE);tz++){
                        int x0=Math.max(area.minX(),tx*TILE),x1=Math.min(area.maxX(),tx*TILE+TILE-1),z0=Math.max(area.minZ(),tz*TILE),z1=Math.min(area.maxZ(),tz*TILE+TILE-1);
                        on(world,()->{for(int cx=x0;cx<=x1;cx++)for(int cz=z0;cz<=z1;cz++){long k=key(cx,cz);if(!heights.containsKey(k))heights.put(k,natural(world,x+cx,z+cz));}
                            for(int cx=x0;cx<=x1;cx++)for(int cz=z0;cz<=z1;cz++)if(isProtected(plugin,world,plan,x+cx,z+cz,cx,cz))throw new IllegalStateException("The town would cover registered roads, plots or plazas near "+(x+cx)+", "+(z+cz)+". Choose another place or remove them first");}).join();}}
                var result=TownTerrainPlanner.plan(plan,(cx,cz)->heights.getOrDefault(key(cx,cz),TownTerrainPlanner.UNKNOWN));
                int cut=0,filled=0,changed=0;for(int i=0;i<result.target.length;i++){int t=result.target[i],n=result.natural[i];if(t==TownTerrainPlanner.UNKNOWN||n==TownTerrainPlanner.UNKNOWN)continue;if(t!=n)changed++;cut=Math.max(cut,n-t);filled=Math.max(filled,t-n);}
                String summary="The town square will sit at height "+result.anchorY()+". The ground changes in "+changed+" columns: up to "+cut+" blocks cut away and up to "+filled+" filled. "
                    +plan.lots.size()+" houses, "+plan.walls.towers.size()+" towers, "+plan.walls.gates.size()+" gates and "+plan.roads.size()+" roads will be built. Everything is backed up first; /e admin town undo puts the land back.";
                return new Preview(actor,world.getName(),x,z,plan,result,summary,Instant.now().plusSeconds(300));
            }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}
        },JOBS);
    }
    static boolean isProtected(EterniaModPlugin plugin,World world,TownPlan plan,int wx,int wz,int x,int z){
        return TownTerrainPlanner.cheb(x,z)<=TownTerrainPlanner.REACH&&plugin.getInfrastructure().protectedColumn(world.getName(),wx,wz);
    }

    // ---- placing -------------------------------------------------------------------------------------------------------
    /** Builds the previewed town. Progress and the outcome go to the player. */
    public static void place(EterniaModPlugin plugin,World world,Preview preview,PlayerRef player){
        if(Instant.now().isAfter(preview.expires()))throw new IllegalStateException("The preview expired; run /e admin town place again");
        if(busy)throw new IllegalStateException("A town is already being placed or removed");busy=true;
        JOBS.execute(()->{
            var m=new Manifest();m.id=UUID.randomUUID().toString();m.world=world.getName();m.state="placing";m.created=Instant.now().toEpochMilli();
            var r=preview.result();var plan=preview.plan();int ax=preview.x(),az=preview.z(),ay=r.anchorY();m.anchor=new int[]{ax,ay,az};
            try{
                save(plugin,m);var store=store(plugin,m.id);var tiles=tiles(r);
                tell(player,"Backing up "+tiles.size()+" areas...");
                for(var tile:tiles){var b=tile.bounds(r,world,ax,az);if(b==null)continue;loadArea(world,b.minX(),b.minZ(),b.maxX()-1,b.maxZ()-1);
                    var doc=captureWhenIdle(world,store,b);
                    on(world,()->{var saved=store.save("tile-"+b.minX()+"_"+b.minZ()+".json",b,doc);
                        var backup=new Backup();backup.name=saved.file().reference();backup.sha=saved.file().sha256();backup.min=new int[]{b.minX(),b.minY(),b.minZ()};backup.max=new int[]{b.maxX(),b.maxY(),b.maxZ()};m.backups.add(backup);}).join();
                    save(plugin,m);}
                tell(player,"Shaping the ground...");
                var streets=new HashMap<Long,Integer>();for(var s:plan.streets)streets.put(key(s[0],s[1]),s[2]);
                for(var tile:tiles){loadTile(world,tile,ax,az);on(world,()->terraform(world,r,tile,ax,az,streets)).join();}
                on(world,()->trees(world,plan,r,ax,az)).join();
                tell(player,"Raising the walls...");
                var walls=TownWalls.generate(plan,r);var byTile=new TreeMap<Long,List<TownWalls.Edit>>();
                for(var e:walls.values())byTile.computeIfAbsent(key(Math.floorDiv(e.x(),TILE),Math.floorDiv(e.z(),TILE)),k->new ArrayList<>()).add(e);
                for(var edits:byTile.values()){var first=edits.get(0);loadArea(world,ax+first.x()-TILE,az+first.z()-TILE,ax+first.x()+TILE,az+first.z()+TILE);
                    on(world,()->write(world,edits,ax,az)).join();}
                tell(player,"Building the town...");
                var pieces=new ArrayList<Object[]>();
                for(var p:plan.pad.pieces)pieces.add(new Object[]{p.prefab,ax+p.at[0],ay,az+p.at[1]});
                for(int i=0;i<plan.lots.size();i++){var p=plan.lots.get(i);pieces.add(new Object[]{p.prefab,ax+p.at[0],r.lotBase[i]+1,az+p.at[1]});}
                for(int i=0;i<plan.features.size();i++){var p=plan.features.get(i);pieces.add(new Object[]{p.prefab,ax+p.at[0],r.featureBase[i]+1,az+p.at[1]});}
                for(int i=0;i<plan.walls.towers.size();i++){var t=plan.walls.towers.get(i);pieces.add(new Object[]{t.prefab,ax+t.x,r.towerBase[i]+1,az+t.z});}
                for(int i=0;i<plan.walls.gates.size();i++){var g=plan.walls.gates.get(i);pieces.add(new Object[]{g.prefab,ax+g.origin[0],r.gateBase[i]+1,az+g.origin[1]});}
                int done=0;
                for(var piece:pieces){paste(world,(String)piece[0],new Vector3i((int)piece[1],(int)piece[2],(int)piece[3]),m);if(++done%20==0){save(plugin,m);tell(player,"Built "+done+" of "+pieces.size()+" pieces...");}}
                save(plugin,m);
                tell(player,"Registering the town and its services...");
                on(world,()->register(plugin,world,plan,m)).join();save(plugin,m);
                loadArea(world,ax-60,az-60,ax+60,az+60);
                on(world,()->services(plugin,world,preview.actor(),plan,m)).join();save(plugin,m);
                tell(player,"Laying the roads...");
                var failed=new ArrayList<String>();
                for(var road:r.roads){int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;for(var n:road.nodes()){minX=Math.min(minX,n[0]);maxX=Math.max(maxX,n[0]);minZ=Math.min(minZ,n[2]);maxZ=Math.max(maxZ,n[2]);}
                    loadArea(world,ax+minX-8,az+minZ-8,ax+maxX+8,az+maxZ+8);
                    try{on(world,()->{var nodes=road.nodes().stream().map(n->new double[]{ax+n[0]+.5,n[1],az+n[2]+.5}).toList();m.roads.add(TownRoadAuthor.author(world,preview.actor(),nodes,road.road().width).toString());}).join();save(plugin,m);}
                    catch(Exception e){failed.add(road.road().id+" ("+cause(e)+")");}}
                m.state="placed";save(plugin,m);
                tell(player,"The town is built. The arrival point is on the podium at "+ax+", "+(ay+plan.arrival[1])+", "+az+"."+(failed.isEmpty()?"":" Some roads could not be laid: "+String.join("; ",failed)+". Lay them with the Road Designer."));
            }catch(Exception e){
                m.state="failed";try{save(plugin,m);}catch(IOException ignored){}
                tell(player,"Building the town stopped: "+cause(e)+". Use /e admin town undo to put the land back.");
                plugin.getLogger().atWarning().withCause(e).log("Town placement failed");
            }finally{busy=false;}
        });
    }

    /** The 32 x 32 tiles of columns the town changes, relative to the anchor. */
    record Tile(int tx,int tz){
        int minX(){return tx*TILE;}int minZ(){return tz*TILE;}
        /** The tile's backup box in world coordinates: from just below the lowest change to well above the highest. */
        NativeSnapshotStore.Bounds bounds(TownTerrainPlanner.Result r,World world,int ax,int az){
            int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
            for(int x=minX();x<minX()+TILE;x++)for(int z=minZ();z<minZ()+TILE;z++){int t=r.target(x,z),n=r.natural(x,z);if(t==TownTerrainPlanner.UNKNOWN)continue;
                low=Math.min(low,Math.min(t,n==TownTerrainPlanner.UNKNOWN?t:n));high=Math.max(high,Math.max(t+ABOVE,n==TownTerrainPlanner.UNKNOWN?t:n+30));}
            if(low==Integer.MAX_VALUE)return null;
            return new NativeSnapshotStore.Bounds(ax+minX(),Math.max(1,low-BELOW),az+minZ(),ax+minX()+TILE,Math.min(NativeSnapshotStore.MAX_Y,high+1),az+minZ()+TILE);
        }
    }
    static List<Tile> tiles(TownTerrainPlanner.Result r){
        var set=new TreeSet<Long>();var g=r.grid;
        for(int i=0;i<g.size();i++)if(r.target[i]!=TownTerrainPlanner.UNKNOWN)set.add(key(Math.floorDiv(g.x(i),TILE),Math.floorDiv(g.z(i),TILE)));
        return set.stream().map(k->new Tile((int)(k>>32),(int)(long)k)).toList();
    }
    static void loadTile(World world,Tile t,int ax,int az){loadArea(world,ax+t.minX(),az+t.minZ(),ax+t.minX()+TILE-1,az+t.minZ()+TILE-1);}

    // ---- ground ------------------------------------------------------------------------------------------------------
    /** The natural ground at a column: the top solid soil, rock or sand, under any plants, trees or snow cover. */
    static int natural(World world,int x,int z){
        int top=ChunkSectionBlockUtil.columnHeight(world,x,z);if(top<ChunkUtil.MIN_Y)return TownTerrainPlanner.UNKNOWN;
        for(int y=Math.min(top,NativeSnapshotStore.MAX_Y-1);y>Math.max(ChunkUtil.MIN_Y,top-64);y--){var type=ChunkSectionBlockUtil.blockType(world,x,y,z);if(type!=null&&isGround(type.getId()))return y;}
        return TownTerrainPlanner.UNKNOWN;
    }
    static boolean isGround(String id){return id!=null&&(id.startsWith("Soil_")||id.startsWith("Rock_")||id.startsWith("Ore_")||id.startsWith("Sand"));}

    /** Cuts and fills a tile's columns to their planned heights, and paves or grasses their tops. */
    static void terraform(World world,TownTerrainPlanner.Result r,Tile tile,int ax,int az,Map<Long,Integer> streets){
        var sel=selection();int inner=r.plan.walls.inner+r.plan.walls.thick;
        for(int x=tile.minX();x<tile.minX()+TILE;x++)for(int z=tile.minZ();z<tile.minZ()+TILE;z++){
            int t=r.target(x,z),n=r.natural(x,z),k=r.kind(x,z);if(t==TownTerrainPlanner.UNKNOWN||n==TownTerrainPlanner.UNKNOWN)continue;
            boolean town=TownTerrainPlanner.cheb(x,z)<inner;
            if(!town&&t==n&&k!=TownTerrainPlanner.ROAD&&k!=TownTerrainPlanner.SHOULDER)continue;
            int wx=ax+x,wz=az+z,top=Math.max(n,ChunkSectionBlockUtil.columnHeight(world,wx,wz));
            String surface=surface(k,streets.get(key(x,z)),wx,wz);
            for(int y=t+1;y<=top+1;y++)if(ChunkSectionBlockUtil.blockId(world,wx,y,wz)!=0)sel.addEmptyAtWorldPos(wx,y,wz);
            for(int y=n+1;y<t;y++)add(sel,wx,y,wz,surface.equals("Soil_Grass")&&y>=t-2?"Soil_Dirt":"Rock_Stone");
            add(sel,wx,t,wz,surface);
        }
        sel.placeNoReturn(world,new Vector3i(0,0,0),world.getEntityStore().getStore());
    }
    static String surface(int kind,Integer street,int x,int z){
        if(kind==TownTerrainPlanner.STREET)return street!=null&&street==1?"Rock_Stone_Brick":switch(TownWalls.hash(x,0,z)%7){case 0,1,2,3->"Rock_Stone_Brick";case 4,5->"Rock_Stone_Brick_Smooth";default->"Rock_Stone";};
        if(kind==TownTerrainPlanner.PAD||kind==TownTerrainPlanner.GARDEN||kind==TownTerrainPlanner.OUTSIDE||kind==TownTerrainPlanner.SHOULDER)return "Soil_Grass";
        return "Rock_Stone";
    }
    static void trees(World world,TownPlan plan,TownTerrainPlanner.Result r,int ax,int az){
        var sel=selection();
        for(int i=0;i<plan.trees.size();i++){int[] t=plan.trees.get(i);int b=r.treeBase[i];if(b==TownTerrainPlanner.UNKNOWN)continue;int x=ax+t[0],z=az+t[1];
            for(int dx=-3;dx<=3;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-3;dz<=3;dz++)if(Math.pow(dx/3.5,2)+Math.pow(dy/2.5,2)+Math.pow(dz/3.5,2)<=1)add(sel,x+dx,b+8+dy,z+dz,"Plant_Leaves_Oak");
            for(int y=b+1;y<=b+7;y++)add(sel,x,y,z,"Wood_Oak_Trunk");}
        sel.placeNoReturn(world,new Vector3i(0,0,0),world.getEntityStore().getStore());
    }
    static void write(World world,List<TownWalls.Edit> edits,int ax,int az){
        var sel=selection();
        for(var e:edits){if(e.block().equals("Empty"))sel.addEmptyAtWorldPos(ax+e.x(),e.y(),az+e.z());else add(sel,ax+e.x(),e.y(),az+e.z(),e.block(),e.rotation());}
        sel.placeNoReturn(world,new Vector3i(0,0,0),world.getEntityStore().getStore());
    }
    static BlockSelection selection(){var s=new BlockSelection();s.setPosition(0,0,0);s.setAnchor(0,0,0);return s;}
    static void add(BlockSelection sel,int x,int y,int z,String id){add(sel,x,y,z,id,0);}
    static void add(BlockSelection sel,int x,int y,int z,String id,int rotation){
        int index=BlockType.getAssetMap().getIndex(id);if(index<0)throw new IllegalStateException("Unknown block "+id);
        sel.addBlockAtWorldPos(x,y,z,index,rotation,FillerBlockUtil.NO_FILLER,0);
    }

    // ---- prefabs, registration, services -------------------------------------------------------------------------------
    static void paste(World world,String prefab,Vector3i at,Manifest m){
        var buffer=PrefabResolveUtil.resolvePrefabBuffer(prefab);if(buffer==null)throw new IllegalStateException("The prefab "+prefab+" is missing");
        var region=PrefabUtil.loadPasteRegionAsync(buffer,world,at,PrefabRotation.fromRotation(Rotation.None),GetChunkFlags.SET_TICKING).join();
        on(world,()->PrefabUtil.paste(buffer,world,at,Rotation.None,new Random(at.x*31L+at.z),PrefabUtil.Flags.FORCE,PASTE_SETTINGS,region,holder->{},holder->{
            // Prefabs store one ID per decoration; a fresh one per paste keeps reused buildings from sharing IDs.
            var id=new UUIDComponent(UUID.randomUUID());holder.putComponent(UUIDComponent.getComponentType(),id);m.entities.add(id.getUuid().toString());},world.getEntityStore().getStore())).join();
    }
    /** The walled town as a public plaza, and the arrival point on the podium. */
    static void register(EterniaModPlugin plugin,World world,TownPlan plan,Manifest m)throws IOException{
        var infra=plugin.getInfrastructure();var snapshot=infra.snapshot();var before=snapshot.worlds().get(world.getName());int ax=m.anchor[0],ay=m.anchor[1],az=m.anchor[2];
        var arrival=new HousingInfrastructure.Point(ax+plan.arrival[0]+.5,ay+plan.arrival[1],az+plan.arrival[2]+.5);
        int x0=ax+plan.plaza.min[0],z0=az+plan.plaza.min[1];var rect=new PlotRect(x0,z0,plan.plaza.max[0]-plan.plaza.min[0]+1,plan.plaza.max[1]-plan.plaza.min[1]+1);
        m.plazaArea="eternia-town";
        HousingInfrastructure.WorldPlan next;
        if(before==null){m.worldCreated=true;boolean hasHub=snapshot.worlds().values().stream().anyMatch(p->"hub".equals(p.role()));
            next=new HousingInfrastructure.WorldPlan(hasHub?"housing":"hub",List.of(),List.of(),arrival,true).withAreas(Map.of(m.plazaArea,new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,rect)));}
        else{if(before.areas().containsKey(m.plazaArea))throw new IllegalStateException("This world already has a town plaza registered");
            m.previousRole=before.role();m.previousHousing=before.housingEnabled();var a=before.arrival();m.previousArrival=a==null?null:new double[]{a.x(),a.y(),a.z()};
            var areas=new TreeMap<>(before.areas());areas.put(m.plazaArea,new HousingInfrastructure.Area(HousingInfrastructure.AreaKind.PORTAL,rect));
            next=before.withRole(before.role(),true).withAreas(areas).withArrival(arrival);}
        infra.saveWorld(snapshot.revision(),world.getName(),next);m.registered=true;
    }
    /** The Waygate's world portal and the six Hub NPCs at their desk spots, unless the world has them already. */
    static void services(EterniaModPlugin plugin,World world,UUID actor,TownPlan plan,Manifest m)throws Exception{
        int ax=m.anchor[0],ay=m.anchor[1],az=m.anchor[2];
        if(ManagedHubServices.live(world,null).isEmpty()){var p=plan.portal.at;m.services.add(ManagedHubServices.ensurePortal(plugin,world,actor,ax+p[0],ay+p[1],az+p[2],TownPlan.yaw(plan.portal.facing)).toString());}
        for(var npc:plan.npcs){var identity=Arrays.stream(HubNpcIdentity.values()).filter(i->i.name().equals(npc.role)).findFirst().orElseThrow();
            if(ManagedHubServices.live(world,identity.role()).isPresent())continue;
            m.services.add(ManagedHubServices.ensureNpc(plugin,world,actor,identity,ax+npc.at[0],ay+npc.at[1],az+npc.at[2],TownPlan.yaw(npc.facing)).toString());}
    }

    // ---- undo --------------------------------------------------------------------------------------------------------
    /** Removes the town placed in this world: its roads, services, plaza and props, then puts every backed-up area back. */
    public static void undo(EterniaModPlugin plugin,World world,UUID actor,PlayerRef player){
        if(busy)throw new IllegalStateException("A town is already being placed or removed");busy=true;
        JOBS.execute(()->{
            try{
                SetupAccess.require(actor);
                var m=load(plugin,world.getName()).orElseThrow(()->new IllegalStateException("No town has been placed in this world"));
                if("undone".equals(m.state))throw new IllegalStateException("The town here has already been removed");
                int ax=m.anchor[0],az=m.anchor[2];
                tell(player,"Removing the town's roads and services...");
                for(var id:List.copyOf(m.roads)){loadArea(world,ax-REACH_ROADS,az-REACH_ROADS,ax+REACH_ROADS,az+REACH_ROADS);
                    try{on(world,()->TownRoadAuthor.remove(world,actor,UUID.fromString(id))).join();m.roads.remove(id);save(plugin,m);}catch(Exception e){tell(player,"A road could not be removed ("+cause(e)+"); remove it with the Road Designer, then run undo again");throw e;}}
                loadArea(world,ax-60,az-60,ax+60,az+60);
                for(var id:List.copyOf(m.services)){on(world,()->ManagedHubServices.removeById(plugin,world,actor,UUID.fromString(id))).join();m.services.remove(id);save(plugin,m);}
                if(m.registered){on(world,()->unregister(plugin,world,m)).join();m.registered=false;save(plugin,m);}
                tell(player,"Removing the town's props...");
                loadArea(world,ax-140,az-140,ax+140,az+140);
                on(world,()->{var store=world.getEntityStore().getStore();for(var id:m.entities){var ref=world.getEntityStore().getRefFromUUID(UUID.fromString(id));if(ref!=null&&ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);}}).join();
                m.entities.clear();save(plugin,m);
                tell(player,"Putting the land back...");
                var store=store(plugin,m.id);
                for(var b:m.backups.reversed()){var bounds=new NativeSnapshotStore.Bounds(b.min[0],b.min[1],b.min[2],b.max[0],b.max[1],b.max[2]);loadArea(world,bounds.minX(),bounds.minZ(),bounds.maxX()-1,bounds.maxZ()-1);
                    var snapshot=store.load(new SnapshotFiles.Saved(b.name,b.sha));on(world,()->store.apply(world,snapshot,bounds.origin())).join();}
                m.state="undone";save(plugin,m);
                tell(player,"The town is gone and the land is as it was.");
            }catch(Exception e){tell(player,"Undo stopped: "+cause(e));plugin.getLogger().atWarning().withCause(e).log("Town undo failed");}
            finally{busy=false;}
        });
    }
    static final int REACH_ROADS=380;
    static void unregister(EterniaModPlugin plugin,World world,Manifest m)throws IOException{
        var infra=plugin.getInfrastructure();var snapshot=infra.snapshot();var current=snapshot.worlds().get(world.getName());if(current==null)return;
        var areas=new TreeMap<>(current.areas());areas.remove(m.plazaArea);
        var arrival=m.previousArrival==null?current.arrival():new HousingInfrastructure.Point(m.previousArrival[0],m.previousArrival[1],m.previousArrival[2]);
        var next=current.withAreas(areas).withArrival(arrival);if(!m.worldCreated)next=next.withRole(m.previousRole,m.previousHousing);
        infra.saveWorld(snapshot.revision(),world.getName(),next);
    }

    // ---- threads and chunks --------------------------------------------------------------------------------------------
    @FunctionalInterface interface Work {void run()throws Exception;}
    /** Runs work on the world thread and completes when it has. */
    static CompletableFuture<Void> on(World world,Work work){var done=new CompletableFuture<Void>();world.execute(()->{try{work.run();done.complete(null);}catch(Throwable e){done.completeExceptionally(e);}});return done;}
    /** Loads (and keeps ticking) every chunk and section under a box of columns, in world coordinates. Call off the world thread. */
    static void loadArea(World world,int minX,int minZ,int maxX,int maxZ){
        var loads=new ArrayList<CompletableFuture<?>>();int cx0=Math.floorDiv(minX,ChunkUtil.SIZE),cx1=Math.floorDiv(maxX,ChunkUtil.SIZE),cz0=Math.floorDiv(minZ,ChunkUtil.SIZE),cz1=Math.floorDiv(maxZ,ChunkUtil.SIZE);
        for(int x=cx0;x<=cx1;x++)for(int z=cz0;z<=cz1;z++)loads.add(world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(x,z),GetChunkFlags.SET_TICKING));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();loads.clear();
        for(int x=cx0;x<=cx1;x++)for(int z=cz0;z<=cz1;z++)for(int y=0;y<NativeSnapshotStore.MAX_Y/ChunkUtil.SIZE;y++)loads.add(world.getChunkStore().getChunkSectionReferenceAsync(x,y,z,GetChunkFlags.SET_TICKING));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
    }
    /** Captures a backup, waiting while the world is still writing any of its chunks to disk. Call off the world thread. */
    static org.bson.BsonDocument captureWhenIdle(World world,NativeSnapshotStore store,NativeSnapshotStore.Bounds b)throws InterruptedException{
        for(int attempt=1;;attempt++){
            var doc=new org.bson.BsonDocument[1];
            try{on(world,()->doc[0]=store.capture(world,b,Set.of(),false,false)).join();return doc[0];}
            catch(java.util.concurrent.CompletionException e){if(!(e.getCause() instanceof NativeSnapshotStore.StorageBusy)||attempt>=BUSY_RETRIES)throw e;}
            Thread.sleep(BUSY_WAIT_MS);
        }
    }
    static final int BUSY_RETRIES=240,BUSY_WAIT_MS=250;
    static long key(int x,int z){return ((long)x<<32)|(z&0xFFFFFFFFL);}
    static void tell(PlayerRef player,String text){if(player!=null)player.sendMessage(Message.raw(text));}
    static String cause(Throwable e){while(e.getCause()!=null&&(e instanceof java.util.concurrent.CompletionException||e.getMessage()==null))e=e.getCause();return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
}
