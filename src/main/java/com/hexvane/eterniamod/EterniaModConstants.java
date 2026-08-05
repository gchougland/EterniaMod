package com.hexvane.eterniamod;

public final class EterniaModConstants {
    /** Logical plot anchor sits one block below the visible marker block used when placing buildings. */
    public static final int PLOT_SIGN_BLOCK_Y_ABOVE_LOGICAL_ANCHOR = 1;

    public static final String BUILDING_ITEM_ID = "Eternia_Building_Item";
    public static final String PROP_ITEM_ID = "Eternia_Prop_Item";
    public static final String PACKAGING_WAND_ID = "Eternia_Packaging_Wand";
    public static final String MANAGEMENT_BLOCK_TYPE_ID = "Eternia_Management_Block";
    public static final String PAGE_BUILDING_PLACEMENT = "EterniaBuildingPlacement";
    public static final String PAGE_BUILDING_PICKUP = "EterniaBuildingPickup";
    public static final String PAGE_PROP_PLACEMENT = "EterniaPropPlacement";

    /** Fixed vertical size of the plot outline preview (centered on creation Y). */
    public static final int HUB_PLOT_VISUAL_HEIGHT = 100;

    /** Extra space around prop highlight / packaging bounds so the cube does not clip the model. */
    public static final double PROP_BOUNDS_PADDING = 0.2;

    private EterniaModConstants() {}
}
