package com.hexvane.eterniamod.housing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;

/** Administrators author world roles and protected road/portal columns; players cannot alter this registry. */
public final class HousingInfrastructure {
    public record Point(double x, double y, double z) {
        public Point { if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Arrival coordinates must be finite"); }
    }
    public enum AreaKind { ROAD, PORTAL }
    public record Area(AreaKind kind, PlotRect rect) {
        public Area { Objects.requireNonNull(kind); Objects.requireNonNull(rect); }
    }
    public record WorldPlan(String role, List<PlotRect> roads, List<PlotRect> portals, Point arrival, boolean housingEnabled, Map<String,Area> areas) {
        public WorldPlan {
            if (!Set.of("hub", "housing", "adventure").contains(role)) throw new IllegalArgumentException("Unknown world role: " + role);
            if (housingEnabled && role.equals("adventure")) throw new IllegalArgumentException("Housing cannot be enabled in an adventure world");
            roads = List.copyOf(Objects.requireNonNull(roads)); portals = List.copyOf(Objects.requireNonNull(portals));
            if(roads.size()+portals.size()>2048)throw new IllegalArgumentException("A world supports at most 2048 infrastructure areas");
            if(areas==null) {
                var migrated=new TreeMap<String,Area>();
                for(var kind:AreaKind.values())for(var rect:kind==AreaKind.ROAD?roads:portals) {
                    String base="legacy-"+kind.name().toLowerCase(Locale.ROOT)+"-"+UUID.nameUUIDFromBytes((kind+":"+rect).getBytes(StandardCharsets.UTF_8)).toString().substring(0,8);
                    String id=base;for(int suffix=2;migrated.containsKey(id);suffix++)id=base+"-"+suffix;
                    migrated.put(id,new Area(kind,rect));
                }
                areas=Map.copyOf(migrated);
            }else {
                areas=Map.copyOf(areas);
                areas.forEach((id,area)->{requireAreaId(id);Objects.requireNonNull(area);});
                if(!sameRectangles(roads,areas,AreaKind.ROAD)||!sameRectangles(portals,areas,AreaKind.PORTAL))throw new IllegalArgumentException("Named infrastructure areas do not match their road/portal rectangles");
            }
        }
        /** Version-one files without the optional flag retain their original world behavior. */
        public WorldPlan(String role, List<PlotRect> roads, List<PlotRect> portals, Point arrival) {
            this(role, roads, portals, arrival, false, null);
        }
        public WorldPlan(String role, List<PlotRect> roads, List<PlotRect> portals, Point arrival, boolean housingEnabled) { this(role,roads,portals,arrival,housingEnabled,null); }
        public WorldPlan withRole(String role,boolean housing) { return new WorldPlan(role,roads,portals,arrival,housing,areas); }
        public WorldPlan withArrival(Point point) { return new WorldPlan(role,roads,portals,point,housingEnabled,areas); }
        public WorldPlan withAreas(Map<String,Area> values) {
            var sorted=new TreeMap<>(values);
            return new WorldPlan(role,sorted.values().stream().filter(a->a.kind==AreaKind.ROAD).map(Area::rect).toList(),sorted.values().stream().filter(a->a.kind==AreaKind.PORTAL).map(Area::rect).toList(),arrival,housingEnabled,sorted);
        }
        public boolean supportsHousing() { return role.equals("housing") || housingEnabled; }
        /** A residential hub keeps public travel at its portals; distant homes still need their convenience unlock. */
        public boolean allowsPublicTravelFrom(PlotRect point) {
            return role.equals("hub") && !supportsHousing() || portals.stream().anyMatch(portal -> portal.gap(point) <= 5);
        }
    }
    public record FileData(int version, Map<String, WorldPlan> worlds, long revision) {
        public FileData(int version,Map<String,WorldPlan> worlds){this(version,worlds,0);}
    }
    public record Snapshot(long revision,Map<String,WorldPlan> worlds) {}
    private final Path file;
    private final Function<String,List<PlotRect>> guildRoads;
    private final ThreadLocal<RoadWrite> roadWrite = new ThreadLocal<>();
    private record RoadWrite(String world, PlotRect exactRect) {}
    private volatile Map<String, WorldPlan> worlds = Map.of();
    private long revision;
    private byte[] loadedHash;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public HousingInfrastructure(Path file) { this(file, com.hexvane.eterniamod.guildroads.GuildRoads::roadRects); }
    HousingInfrastructure(Path file, Function<String,List<PlotRect>> guildRoads) { this.file = file; this.guildRoads = Objects.requireNonNull(guildRoads); }
    public synchronized void load() throws IOException {
        if (!Files.exists(file)) { Files.createDirectories(file.getParent()); Files.writeString(file, JSON.toJson(new FileData(1, Map.of())), StandardOpenOption.CREATE_NEW); }
        byte[] bytes=readFile();FileData data=parse(bytes);
        worlds = Map.copyOf(data.worlds);revision=data.revision;loadedHash=hash(bytes);
    }
    public synchronized Snapshot snapshot(){return new Snapshot(revision,worlds);}
    /** Compare both the in-memory revision and disk bytes; external edits never get silently overwritten. */
    public synchronized void saveWorld(long expectedRevision,String name,WorldPlan plan) throws IOException {
        if(revision!=expectedRevision||loadedHash==null||!MessageDigest.isEqual(loadedHash,hash(readFile())))throw new IOException("Infrastructure changed since preview. Reload and review the change again.");
        var next=new TreeMap<>(worlds);next.put(name,Objects.requireNonNull(plan));
        byte[] bytes=JSON.toJson(new FileData(1,next,Math.addExact(revision,1))).getBytes(StandardCharsets.UTF_8);
        FileData validated=parse(bytes);Path pending=file.resolveSibling(file.getFileName()+".pending-"+UUID.randomUUID());
        try {
            try(var channel=FileChannel.open(pending,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(pending,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(pending);}
        worlds=Map.copyOf(validated.worlds);revision=validated.revision;loadedHash=hash(bytes);
    }
    private byte[] readFile() throws IOException {
        if(Files.isSymbolicLink(file)||Files.size(file)>8_388_608)throw new IOException("Unsafe infrastructure file");
        return Files.readAllBytes(file);
    }
    private static FileData parse(byte[] bytes) throws IOException {
        try {
            var data=JSON.fromJson(new String(bytes,StandardCharsets.UTF_8),FileData.class);
            if(data==null||data.version!=1||data.revision<0||data.worlds==null||data.worlds.size()>1024)throw new IllegalArgumentException("Invalid infrastructure schema");
            data.worlds.forEach((name,plan)->{if(name==null||name.isBlank()||name.length()>128||plan==null)throw new IllegalArgumentException("Invalid world infrastructure entry");});
            return data;
        }catch(RuntimeException failure){throw new IOException("Invalid housing infrastructure: "+failure.getMessage(),failure);}
    }
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    public static void requireAreaId(String id){if(id==null||!id.matches("[a-z][a-z0-9_-]{0,47}"))throw new IllegalArgumentException("Area names use 1–48 lowercase letters, numbers, underscores or hyphens, starting with a letter");}
    private static boolean sameRectangles(List<PlotRect> rectangles,Map<String,Area> areas,AreaKind kind) {
        var remaining=new ArrayList<>(rectangles);for(var area:areas.values())if(area.kind==kind&&!remaining.remove(area.rect))return false;return remaining.isEmpty();
    }
    public Optional<WorldPlan> world(String name) { return Optional.ofNullable(worlds.get(name)); }
    public Map<String, WorldPlan> worlds() { return worlds; }
    public boolean isHousing(String world) { return world(world).map(WorldPlan::supportsHousing).orElse(false); }
    /** Claim anchors deliberately use WorldPlan.roads(), which contains only administrator-authored roads. */
    public List<PlotRect> structureRoads(String world) {
        var roads = new ArrayList<PlotRect>(world(world).map(WorldPlan::roads).orElse(List.of()));
        roads.addAll(guildRoads.apply(world));
        return List.copyOf(roads);
    }
    public boolean publicProtectedColumn(String world, int x, int z) {
        return world(world).map(p -> p.roads.stream().anyMatch(r -> r.contains(x,z)) || p.portals.stream().anyMatch(r -> r.contains(x,z))).orElse(false);
    }
    public boolean publicPortalColumn(String world, int x, int z) {
        return world(world).map(p -> p.portals.stream().anyMatch(r -> r.contains(x,z))).orElse(false);
    }
    public boolean protectedColumn(String world, int x, int z) {
        if (com.hexvane.eterniamod.setup.paving.RoadPaving.protectedColumn(world,x,z)) return true;
        if (com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingColumn(world,x,z)) return true;
        // A journalled spline write may restore only its own named road areas and exact cells.
        if (world(world).map(p -> p.areas.entrySet().stream().anyMatch(e -> e.getValue().rect.contains(x,z)
            && (e.getValue().kind == AreaKind.PORTAL || !com.hexvane.eterniamod.pathtool.SplineRoadTool.mayWriteArea(world,e.getKey(),x,z)))).orElse(false)) return true;
        RoadWrite scope = roadWrite.get();
        if (scope != null && scope.world.equals(world) && scope.exactRect.contains(x,z)) return false;
        return guildRoads.apply(world).stream().anyMatch(r -> r.contains(x,z));
    }
    public boolean intersectsProtected(String world, PlotRect rect) {
        if (com.hexvane.eterniamod.setup.paving.RoadPaving.protectedRects(world).stream().anyMatch(rect::overlaps)) return true;
        if (com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world).stream().anyMatch(rect::overlaps)) return true;
        if (world(world).map(p -> p.roads.stream().anyMatch(r -> r.overlaps(rect)) || p.portals.stream().anyMatch(r -> r.overlaps(rect))).orElse(false)) return true;
        RoadWrite scope = roadWrite.get();
        for (PlotRect road : guildRoads.apply(world)) {
            if (!road.overlaps(rect)) continue;
            if (scope == null || !scope.world.equals(world)) return true;
            var intersection = new PlotRect(Math.max(road.x(),rect.x()), Math.max(road.z(),rect.z()),
                Math.min(road.endX(),rect.endX())-Math.max(road.x(),rect.x()), Math.min(road.endZ(),rect.endZ())-Math.max(road.z(),rect.z()));
            if (!scope.exactRect.contains(intersection)) return true;
        }
        return false;
    }
    /**
     * Trusted guild-road journal adapter only, on the world thread after identity and public-road validation.
     * The exact segment may edit its own dynamic protection; public roads/portals remain protected.
     * The scope never changes the road list used for structure setbacks or public claim anchors.
     */
    public <T> T withGuildRoadWrite(String world, PlotRect exactRect, Callable<T> operation) throws Exception {
        Objects.requireNonNull(world); Objects.requireNonNull(exactRect); Objects.requireNonNull(operation);
        if ((exactRect.width()!=1 && exactRect.depth()!=1) || exactRect.area()>128) throw new IllegalArgumentException("Guild road write must be one straight segment of at most 128 cells");
        RoadWrite previous = roadWrite.get();
        roadWrite.set(new RoadWrite(world,exactRect));
        try { return operation.call(); }
        finally { if (previous == null) roadWrite.remove(); else roadWrite.set(previous); }
    }
}
