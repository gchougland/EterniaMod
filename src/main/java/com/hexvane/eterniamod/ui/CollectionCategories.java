package com.hexvane.eterniamod.ui;

/** Internal service grants never appear as collectible items. */
public final class CollectionCategories {
    private CollectionCategories() {}
    public static boolean matches(String category,String id) {
        if(id.startsWith("eternia:service/"))return false;
        return switch(category){
            case "", "houses" -> id.startsWith("eternia:house/");
            case "decorations" -> id.startsWith("eternia:prop/")||id.startsWith("eternia:addition/");
            case "materials" -> id.startsWith("eternia:palette/")||id.startsWith("eternia:path/");
            case "upgrades" -> id.startsWith("eternia:plot/")||id.startsWith("eternia:plot_move_credit")||id.startsWith("eternia:convenience/")||id.startsWith("eternia:guild_voucher/")||id.startsWith("eternia:season_paid/");
            default -> false;
        };
    }
}
