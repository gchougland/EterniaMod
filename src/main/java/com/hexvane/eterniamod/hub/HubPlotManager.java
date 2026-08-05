package com.hexvane.eterniamod.hub;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class HubPlotManager {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final World world;
    private final Path saveFile;
    private final Map<UUID, HubPlotRecord> byPlotId = new LinkedHashMap<>();
    private boolean dirty;

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
            for (HubPlotRecord plot : file.getPlots()) {
                if (plot.getPlotId() != null) {
                    byPlotId.put(plot.getPlotId(), plot);
                }
            }
            LOGGER.atInfo().log("EterniaMod loaded %s hub plots for world %s", byPlotId.size(), world.getName());
        } catch (IOException e) {
            LOGGER.atWarning().withCause(e).log("Failed to load hub plots for world %s", world.getName());
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
        }
    }

    @Nonnull
    public HubPlotRecord addPlot(@Nonnull HubPlotRecord plot) {
        byPlotId.put(plot.getPlotId(), plot);
        dirty = true;
        return plot;
    }

    public boolean removePlot(@Nonnull UUID plotId) {
        HubPlotRecord removed = byPlotId.remove(plotId);
        if (removed != null) {
            dirty = true;
            return true;
        }
        return false;
    }

    public void updatePlot(@Nonnull HubPlotRecord plot) {
        byPlotId.put(plot.getPlotId(), plot);
        dirty = true;
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
        for (HubPlotRecord plot : byPlotId.values()) {
            if (plot.getFootprint().containsBlock(x, y, z)) {
                return plot;
            }
        }
        return null;
    }

    @Nonnull
    public World getWorld() {
        return world;
    }
}
