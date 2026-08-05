package com.hexvane.eterniamod.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

public final class HubPlotWorldFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @SerializedName("plots")
    private List<HubPlotRecord> plots = new ArrayList<>();

    @Nonnull
    public List<HubPlotRecord> getPlots() {
        return plots != null ? plots : Collections.emptyList();
    }

    public void setPlots(@Nonnull List<HubPlotRecord> plots) {
        this.plots = new ArrayList<>(plots);
    }

    @Nonnull
    public static HubPlotWorldFile readOrEmpty(@Nonnull Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new HubPlotWorldFile();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            HubPlotWorldFile file = GSON.fromJson(reader, HubPlotWorldFile.class);
            return file != null ? file : new HubPlotWorldFile();
        }
    }

    public void write(@Nonnull Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
    }
}
