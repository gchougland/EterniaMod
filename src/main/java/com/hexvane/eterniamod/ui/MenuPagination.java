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
}
