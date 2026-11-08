package com.hexvane.eterniamod.housing.relocation;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bson.BsonArray;
import org.bson.BsonDocument;

/**
 * Pure rules and geometry for moving a packed hex plot (no game types, no I/O, any thread).
 *
 * <ul>
 *   <li>A packed hex plot is set down only on the same shape in the same turn ({@link #move}): its cells moved by a
 *       whole number of cells, which is an exact whole block move, so the box snapshot and its cell mask move intact.</li>
 *   <li>{@link #check} is the destination rule (shape, free cells, then road frontage or guild land), shared by the
 *       destination list ({@link #destinations}) and the commit in {@code NativeRelocationCoordinator.restoreCells}.</li>
 *   <li>The BSON helpers keep every hex snapshot inside its cells.</li>
 * </ul>
 */
public final class HexRelocation {
    private HexRelocation() {}

    /** How far a packed plot moves: in cells and in blocks (the box and every block move by {@code dx, dz}). */
    public record Move(HexCoord cellOffset, int dx, int dz) {}

    /** The whole block move that carries {@code from} onto {@code to}. Refuses a different grid, shape or turn. */
    public static Move move(PlotCells from, PlotCells to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (!from.lattice().equals(to.lattice())) throw new IllegalStateException("The packed plot was cut from a different hexagon grid");
        if (!HexClaimRules.sameShape(from.cells(), to.cells())) throw new IllegalStateException("A packed plot can only be set down on the same shape, turned the same way");
        HexCoord offset = to.cells().getFirst().minus(from.cells().getFirst());
        if (!from.translate(offset).equals(to)) throw new IllegalStateException("A packed plot can only be set down on the same shape, turned the same way");
        var lattice = from.lattice();
        int dx = Math.subtractExact(lattice.minX(to.cells().getFirst()), lattice.minX(from.cells().getFirst()));
        int dz = Math.subtractExact(lattice.minZ(to.cells().getFirst()), lattice.minZ(from.cells().getFirst()));
        var a = from.bounds();
        var b = to.bounds();
        if (b.x() - a.x() != dx || b.z() - a.z() != dz || a.width() != b.width() || a.depth() != b.depth())
            throw new IllegalStateException("Hexagon move is not a whole block translate");
        return new Move(offset, dx, dz);
    }

    /**
     * Can a packed plot with cells {@code packed} be set down on {@code destination}? Same shape and turn, every cell
     * free, then the claim rule for its kind: public plots and estates need a hexagon beside a street, guild member
     * plots must touch {@code anchors} (the guild's joined land). The packed plot itself is not in {@code land}.
     */
    public static HexClaimRules.Result check(HexClaimRules.Land land, HexClaimRules.Kind kind, PlotCells packed, Collection<HexCoord> destination, Set<HexCoord> anchors) {
        Set<HexCoord> set = Set.copyOf(destination);
        if (!packed.lattice().equals(land.lattice()) || !HexClaimRules.sameShape(packed.cells(), set))
            return HexClaimRules.Result.of(HexClaimRules.Reason.RESTORE_SHAPE);
        for (HexCoord c : set) {
            if (land.street().contains(c)) return HexClaimRules.Result.of(HexClaimRules.Reason.STREET);
            if (land.owners().containsKey(c)) return HexClaimRules.Result.of(HexClaimRules.Reason.TAKEN);
            if (land.reserved().contains(c)) return HexClaimRules.Result.of(HexClaimRules.Reason.RESERVED);
            if (land.blocked().contains(c)) return HexClaimRules.Result.of(HexClaimRules.Reason.BLOCKED);
        }
        if (kind == HexClaimRules.Kind.GUILD_MEMBER) {
            boolean joined = set.stream().anyMatch(c -> c.neighbors().stream().anyMatch(anchors::contains));
            return HexClaimRules.Result.of(joined ? HexClaimRules.Reason.ALLOWED : HexClaimRules.Reason.ANCHOR);
        }
        return HexClaimRules.Result.of(set.stream().anyMatch(land::frontage) ? HexClaimRules.Reason.ALLOWED : HexClaimRules.Reason.ROAD);
    }

    /**
     * Every place near {@code near} where the packed plot can be set down right now: the packed cells moved so that
     * some cell lies within {@code radius} of {@code near}, valid by {@link #check}, nearest first (then reading
     * order), at most {@code limit}. What a destination picker or the claim map lists.
     */
    public static List<PlotCells> destinations(HexClaimRules.Land land, HexClaimRules.Kind kind, PlotCells packed, Set<HexCoord> anchors, HexCoord near, int radius, int limit) {
        if (radius < 0 || limit <= 0 || !packed.lattice().equals(land.lattice())) return List.of();
        Set<PlotCells> seen = new LinkedHashSet<>();
        for (HexCoord target : HexCoord.disk(near, radius)) {
            for (HexCoord member : packed.cells()) {
                // Put each packed cell on the target in turn, so placements that only clip the area count too.
                HexCoord offset = target.minus(member);
                seen.add(packed.translate(offset));
            }
        }
        List<PlotCells> valid = new ArrayList<>();
        for (PlotCells candidate : seen) if (check(land, kind, packed, candidate.cells(), anchors).valid()) valid.add(candidate);
        valid.sort(Comparator.<PlotCells>comparingInt(c -> distance(c, near)).thenComparing(c -> c.cells().getFirst(), HexCoord.READING_ORDER));
        return List.copyOf(valid.subList(0, Math.min(limit, valid.size())));
    }

    /** Distance in cells from {@code near} to the nearest cell of {@code cells}. */
    public static int distance(PlotCells cells, HexCoord near) {
        int best = Integer.MAX_VALUE;
        for (HexCoord c : cells.cells()) best = Math.min(best, c.distance(near));
        return best;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Snapshot documents. Block, fluid and entity positions are local to the snapshot box; (originX, originZ) is the
    // world position of the box's north west corner.
    // ---------------------------------------------------------------------------------------------------------------

    /** Throws when any block, fluid or entity of {@code doc} lies in a column outside {@code mask}. */
    public static void requireInside(BsonDocument doc, ColumnMask mask, int originX, int originZ) {
        if (mask.all()) return;
        for (String type : List.of("blocks", "fluids")) for (var value : doc.getArray(type, new BsonArray())) {
            var cell = value.asDocument();
            if (!mask.contains(originX + cell.getInt32("x").getValue(), originZ + cell.getInt32("z").getValue()))
                throw new IllegalStateException("Plot content lies outside its hexagons");
        }
        for (var value : doc.getArray("entities", new BsonArray())) {
            var p = position(value.asDocument());
            if (!mask.contains(originX + (int) Math.floor(p.getNumber("X").doubleValue()), originZ + (int) Math.floor(p.getNumber("Z").doubleValue())))
                throw new IllegalStateException("Plot decoration lies outside its hexagons");
        }
    }

    /** A copy of {@code doc} without anything standing in columns outside {@code mask}. */
    public static BsonDocument keepInside(BsonDocument doc, ColumnMask mask, int originX, int originZ) {
        var result = doc.clone();
        if (mask.all()) return result;
        for (String type : List.of("blocks", "fluids")) {
            if (!result.containsKey(type)) continue;
            var kept = new BsonArray();
            for (var value : result.getArray(type)) {
                var cell = value.asDocument();
                if (mask.contains(originX + cell.getInt32("x").getValue(), originZ + cell.getInt32("z").getValue())) kept.add(value);
            }
            result.put(type, kept);
        }
        if (result.containsKey("entities")) {
            var kept = new BsonArray();
            for (var value : result.getArray("entities")) {
                var p = position(value.asDocument());
                if (mask.contains(originX + (int) Math.floor(p.getNumber("X").doubleValue()), originZ + (int) Math.floor(p.getNumber("Z").doubleValue()))) kept.add(value);
            }
            if (kept.isEmpty()) result.remove("entities");
            else result.put("entities", kept);
        }
        return result;
    }

    /**
     * Baseline of a grown plot: the old baseline (box {@code oldBounds}) and a capture of the added cells (box
     * {@code addedBounds}) moved into the grown box {@code grown}. Both inputs are masked captures of different cells,
     * so a block position present in both means the inputs are wrong and is refused.
     */
    public static BsonDocument mergeBaseline(BsonDocument old, NativeSnapshotStore.Bounds oldBounds, BsonDocument added, NativeSnapshotStore.Bounds addedBounds, NativeSnapshotStore.Bounds grown) {
        if (oldBounds.minY() != grown.minY() || addedBounds.minY() != grown.minY() || oldBounds.maxY() != grown.maxY() || addedBounds.maxY() != grown.maxY())
            throw new IllegalStateException("Grown baseline must keep the full plot height");
        int width = grown.maxX() - grown.minX(), height = grown.maxY() - grown.minY(), depth = grown.maxZ() - grown.minZ();
        var a = PlotSnapshotTransform.translate(old, oldBounds.minX() - grown.minX(), 0, oldBounds.minZ() - grown.minZ(), 0, 0, 0, width, height, depth);
        var b = PlotSnapshotTransform.translate(added, addedBounds.minX() - grown.minX(), 0, addedBounds.minZ() - grown.minZ(), 0, 0, 0, width, height, depth);
        var result = a.clone();
        Set<String> keys = new HashSet<>();
        for (var value : a.getArray("blocks", new BsonArray())) keys.add(key(value.asDocument()));
        var blocks = result.getArray("blocks", new BsonArray());
        for (var value : b.getArray("blocks", new BsonArray())) {
            if (!keys.add(key(value.asDocument()))) throw new IllegalStateException("Added hexagon overlaps the existing baseline");
            blocks.add(value);
        }
        result.put("blocks", blocks);
        var fluids = result.getArray("fluids", new BsonArray());
        fluids.addAll(b.getArray("fluids", new BsonArray()));
        result.put("fluids", fluids);
        if (b.containsKey("entities")) {
            var entities = result.getArray("entities", new BsonArray());
            entities.addAll(b.getArray("entities"));
            result.put("entities", entities);
        }
        return result;
    }

    private static BsonDocument position(BsonDocument entity) {
        return entity.getDocument("Components").getDocument("Transform").getDocument("Position");
    }

    private static String key(BsonDocument d) {
        return d.getInt32("x").getValue() + "," + d.getInt32("y").getValue() + "," + d.getInt32("z").getValue();
    }
}
