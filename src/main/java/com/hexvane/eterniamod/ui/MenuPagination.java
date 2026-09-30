package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Pagination is a list control, not a route back to another menu. */
public final class MenuPagination {
    private MenuPagination() {}
    public static void show(UICommandBuilder commands, int entries, int pageSize) {
        boolean paged = entries > pageSize;
        commands.set("#Previous.Visible", paged);
        commands.set("#Next.Visible", paged);
        commands.set("#Pagination.Visible", paged);
    }
    /** Collapse the complete sidebar footer so navigation can use all remaining height. */
    public static void sidebar(UICommandBuilder commands, int entries, int pageSize) {
        show(commands, entries, pageSize);
        boolean paged = entries > pageSize;
        commands.set("#PageControls.Visible", paged);
        commands.setObject("#PageControls.Anchor", UiAnchors.height(paged ? 44 : 0));
        commands.setObject("#Pagination.Anchor", UiAnchors.heightWithBottom(paged ? 24 : 0, paged ? 6 : 0));
    }
}
