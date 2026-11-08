package com.hexvane.eterniamod.placement;

import com.hypixel.hytale.server.core.Message;
import javax.annotation.Nullable;

/**
 * Player text for placement codes from {@link BuildingPlacementValidator} and {@link PropPlacementValidator}. Hex plot
 * codes ({@code hex...}, see {@link HexPlacement}) read from {@code eternia_hexplots.plots.place.*} and may carry a
 * number after a colon; every other code is an {@code eterniamod_common} line, as before.
 */
public final class PlacementText {
    private PlacementText() {}

    @Nullable
    public static Message message(@Nullable String code) {
        if (code == null) return null;
        int colon = code.indexOf(':');
        String key = colon < 0 ? code : code.substring(0, colon);
        String count = colon < 0 ? "" : code.substring(colon + 1);
        if (key.startsWith("hex")) return Message.translation("eternia_hexplots.plots.place." + key).param("count", count);
        return Message.translation("eterniamod_common.eterniamod.common." + key);
    }

    /** How many more decorations fit on the hexagon under a prop, for the placement page's status line. */
    public static Message room(int left) {
        if (left <= 0) return Message.translation("eternia_hexplots.plots.place.roomNone");
        if (left == 1) return Message.translation("eternia_hexplots.plots.place.roomOne");
        return Message.translation("eternia_hexplots.plots.place.room").param("count", String.valueOf(left));
    }
}
