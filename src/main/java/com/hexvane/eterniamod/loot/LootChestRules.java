package com.hexvane.eterniamod.loot;

/** Which chests become per player. Anything else stays an ordinary shared container. */
final class LootChestRules {
    private LootChestRules() {}
    /**
     * A chest qualifies only while the engine would roll it itself: an unrolled droplist on a block that opens as a
     * container, in a world that resolves spawners. Housing worlds are left alone so nothing built there changes. A
     * chest that already holds items keeps them, since nobody's copy could include them.
     */
    static boolean becomesPersonal(String droplist,boolean spawnersResolved,boolean housingWorld,boolean containerEmpty,boolean opensAsContainer) {
        return droplist!=null&&spawnersResolved&&!housingWorld&&containerEmpty&&opensAsContainer;
    }
    /** A marker copied into a housing world opens as the plain shared chest it is there. */
    static boolean opensPersonally(boolean marked,boolean housingWorld) {
        return marked&&!housingWorld;
    }
    /** Administrators in Creative can still remove a chest. Nothing else can break one: its loot is not all this player's. */
    static boolean breakDenied(boolean marked,boolean housingWorld,boolean creative) {
        return opensPersonally(marked,housingWorld)&&!creative;
    }
}
