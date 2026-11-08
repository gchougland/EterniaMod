package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLandEvents;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.PlotCellIndex;
import com.hexvane.eterniamod.hexplots.PlotCells;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

public final class HubPlotManager {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final World world;
    private final Path saveFile;
    private final Map<UUID, HubPlotRecord> byPlotId = new LinkedHashMap<>();
    /** Hex plots only: cell to plot, kept in step with {@link #byPlotId} by every mutation below. */
    private final PlotCellIndex cellIndex = new PlotCellIndex();
    private boolean dirty;
    private long revision;

    public long getRevision() { return revision; }

    public HubPlotManager(@Nonnull World world, @Nonnull Path pluginDataDirectory) {
        this.world = world;
        this.saveFile =
            pluginDataDirectory.resolve("worlds").resolve(sanitizeWorldDirName(world.getName())).resolve("hub_plots.json");
    }

    @Nonnull
    private static String sanitizeWorldDirName(@Nonnull String worldName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < worldName.length(); i++) {
            char c = worldName.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.isEmpty() ? "world" : sb.toString();
    }

    public void loadFromDisk() {
        try {
            HubPlotWorldFile file = HubPlotWorldFile.readOrEmpty(saveFile);
            byPlotId.clear();
            cellIndex.clear();
            revision++;
            for (HubPlotRecord plot : file.getPlots()) {
                if (plot.getPlotId() != null) {
                    PlotCells cells = plot.getCells();
                    if (cells != null) cellIndex.put(plot.getPlotId(), cells);
                    byPlotId.put(plot.getPlotId(), plot);
                }
            }
            LOGGER.atInfo().log("EterniaMod loaded %s hub plots for world %s", byPlotId.size(), world.getName());
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to load hub plots for world %s", world.getName());
            throw new java.io.UncheckedIOException("Plot ownership could not be loaded; refusing to treat this world as empty", e);
        }
    }

    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        try {
            HubPlotWorldFile file = new HubPlotWorldFile();
            file.setPlots(new ArrayList<>(byPlotId.values()));
            Path temp = saveFile.resolveSibling(saveFile.getFileName() + ".tmp");
            file.write(temp);
            if (Files.isRegularFile(saveFile)) {
                Files.copy(saveFile, saveFile.resolveSibling("hub_plots.json.bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, saveFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to save hub plots for world %s", world.getName());
            throw new java.io.UncheckedIOException("Plot changes were not durably saved", e);
        }
    }

    @Nonnull
    public HubPlotRecord addPlot(@Nonnull HubPlotRecord plot) {
        if (byPlotId.containsKey(plot.getPlotId())) throw new IllegalStateException("Duplicate plot id");
        PlotCells cells = plot.getCells();
        if (cells != null) cellIndex.put(plot.getPlotId(), cells);
        byPlotId.put(plot.getPlotId(), plot);
        revision++;
        dirty = true;
        if (cells != null) HexLandEvents.fire(world.getName(), cells.asSet(), HexLandEvents.Cause.CLAIM);
        return plot;
    }

    public boolean removePlot(@Nonnull UUID plotId) {
        HubPlotRecord removed = byPlotId.remove(plotId);
        if (removed != null) {
            PlotCells cells = cellIndex.remove(plotId);
            revision++;
            dirty = true;
            if (cells != null) HexLandEvents.fire(world.getName(), cells.asSet(), HexLandEvents.Cause.UNCLAIM);
            return true;
        }
        return false;
    }

    public void updatePlot(@Nonnull HubPlotRecord plot) {
        PlotCells before = cellIndex.cellsOf(plot.getPlotId());
        PlotCells after = plot.getCells();
        if (after != null) cellIndex.put(plot.getPlotId(), after);
        else cellIndex.remove(plot.getPlotId());
        byPlotId.put(plot.getPlotId(), plot);
        revision++;
        dirty = true;
        if (!Objects.equals(before, after)) {
            Set<HexCoord> changed = new HashSet<>();
            if (before != null) changed.addAll(before.asSet());
            if (after != null) changed.addAll(after.asSet());
            if (before != null && after != null) {
                Set<HexCoord> same = new HashSet<>(before.asSet());
                same.retainAll(after.asSet());
                changed.removeAll(same);
            }
            HexLandEvents.fire(world.getName(), changed, HexLandEvents.Cause.EXPAND);
        }
    }

    /** Plot owning a hex cell, or null. O(1). World thread. */
    @Nullable
    public HubPlotRecord plotAtCell(@Nonnull HexCoord cell) {
        UUID id = cellIndex.plotAt(cell);
        return id == null ? null : byPlotId.get(id);
    }

    /**
     * Plot owning block column (x, z): the cell index for hex plots (O(1)), then a box scan of rectangle plots.
     * World thread.
     */
    @Nullable
    public HubPlotRecord plotAtColumn(int x, int z) {
        UUID id = cellIndex.plotAtColumn(x, z);
        if (id != null) return byPlotId.get(id);
        for (HubPlotRecord plot : byPlotId.values()) {
            if (!plot.isHex() && plot.getFootprint().containsHorizontal(x, z)) return plot;
        }
        return null;
    }

    /** Read-only cell to plot map of this world's hex plots. World thread. */
    @Nonnull
    public Map<HexCoord, UUID> cellOwners() {
        return cellIndex.owners();
    }

    /** Lattice of this world's hex plots, or null when it has none. */
    @Nullable
    public HexLattice indexedLattice() {
        return cellIndex.lattice();
    }

    @Nullable
    public HubPlotRecord getPlot(@Nonnull UUID plotId) {
        return byPlotId.get(plotId);
    }

    @Nonnull
    public List<HubPlotRecord> listPlots() {
        return new ArrayList<>(byPlotId.values());
    }

    @Nonnull
    public List<HubPlotRecord> listPlotsForOwner(@Nullable UUID ownerUuid) {
        List<HubPlotRecord> out = new ArrayList<>();
        for (HubPlotRecord plot : byPlotId.values()) {
            if (ownerUuid == null) {
                if (plot.getOwnerUuid() == null) {
                    out.add(plot);
                }
            } else if (plot.isOwnedBy(ownerUuid)) {
                out.add(plot);
            }
        }
        return out;
    }

    @Nullable
    public HubPlotRecord findPlotContaining(int x, int y, int z) {
        HubPlotRecord hex = plotAtCellColumn(x, z);
        if (hex != null) return hex.getFootprint().containsBlock(x, y, z) ? hex : null;
        for (HubPlotRecord plot : byPlotId.values()) {
            if (plot.getFootprint().containsBlock(x, y, z)) {
                return plot;
            }
        }
        return null;
    }

    @Nullable
    public HubPlotRecord findPlotContainingHorizontal(int x, int z) {
        return plotAtColumn(x, z);
    }

    @Nullable
    private HubPlotRecord plotAtCellColumn(int x, int z) {
        UUID id = cellIndex.plotAtColumn(x, z);
        return id == null ? null : byPlotId.get(id);
    }

    /** Resolves a plot from the player's feet, horizontal position, or look target. */
    @Nullable
    public HubPlotRecord findPlotAtPlayerOrTarget(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform != null) {
            Vector3d pos = transform.getPosition();
            int x = (int) Math.floor(pos.x);
            int y = (int) Math.floor(pos.y - 0.01);
            int z = (int) Math.floor(pos.z);
            HubPlotRecord plot = findPlotContainingHorizontal(x, z);
            if (plot != null) {
                return plot;
            }
            plot = findPlotContaining(x, y, z);
            if (plot != null) {
                return plot;
            }
        }
        Vector3i target = TargetUtil.getTargetBlock(ref, 64.0, store);
        if (target != null) {
            HubPlotRecord plot = findPlotContainingHorizontal(target.x, target.z);
            if (plot != null) {
                return plot;
            }
            return findPlotContaining(target.x, target.y, target.z);
        }
        return null;
    }

    @Nonnull
    public World getWorld() {
        return world;
    }
}
