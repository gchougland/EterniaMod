package com.hexvane.eterniamod.hexplots;

import com.google.gson.annotations.SerializedName;
import java.util.Locale;
import java.util.Optional;

/**
 * How a housing world cuts its land into plots. Saved per world as {@code plotShape} in
 * {@code housing-infrastructure.json}; a world without the field (every world saved before hex plots) is {@link #RECT}.
 */
public enum PlotShape {
    /** Legacy rectangles (24, 32, 48 ...) checked by {@code HousingRules}. */
    @SerializedName("rect") RECT,
    /** Hexagon cells on the world's {@link HexLattice}, checked by {@link HexClaimRules}. */
    @SerializedName("hex") HEX;

    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Optional<PlotShape> parse(String text) {
        if (text == null) return Optional.empty();
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "rect", "rectangle" -> Optional.of(RECT);
            case "hex", "hexagon" -> Optional.of(HEX);
            default -> Optional.empty();
        };
    }
}
