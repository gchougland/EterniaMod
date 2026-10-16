package com.hexvane.eterniamod.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectionCategoriesTest {
    @Test void serviceEntitlementsAreHiddenAndHousingTypesStaySeparate(){
        for(String category:new String[]{"houses","decorations","materials","upgrades"})assertFalse(CollectionCategories.matches(category,"eternia:service/mailbox"));
        assertTrue(CollectionCategories.matches("houses","eternia:house/guild_hall"));
        assertFalse(CollectionCategories.matches("decorations","eternia:house/guild_hall"));
        assertTrue(CollectionCategories.matches("decorations","eternia:addition/porch"));
        assertTrue(CollectionCategories.matches("decorations","eternia:prop/aqua_lamp"));
        assertFalse(CollectionCategories.matches("houses","eternia:wearable/prowl_shirt"));
        assertTrue(CollectionCategories.matches("materials","eternia:palette/stone"));
        assertTrue(CollectionCategories.matches("upgrades","eternia:guild_voucher/large"));
    }
}
