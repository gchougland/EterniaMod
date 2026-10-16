package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.domain.CollectionService;
import com.hypixel.hytale.protocol.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomWearablesTest {
    @Test void shirtChangesOnlyTheDisplayedChestAndDoesNotMutateArmorSource(){
        var equipment=new EquipmentUpdate();String[] original={"Helmet","Chest","Hands","Legs"};
        equipment.armorIds=original;equipment.rightHandItemId="Pickaxe";equipment.leftHandItemId="Shield";
        CustomWearables.project(equipment);
        assertNotSame(original,equipment.armorIds);
        for(var slot:ItemArmorSlot.values())assertEquals(slot==ItemArmorSlot.Chest?CustomWearables.ITEM:original[slot.ordinal()],equipment.armorIds[slot.ordinal()]);
        assertEquals("Chest",original[ItemArmorSlot.Chest.ordinal()]);
        assertEquals("Pickaxe",equipment.rightHandItemId);assertEquals("Shield",equipment.leftHandItemId);
        CustomWearables.project(equipment);assertEquals(CustomWearables.ITEM,equipment.armorIds[ItemArmorSlot.Chest.ordinal()]);
    }
    @Test void customShirtKeepsAuthenticatedClothesAvailableForRestoration(){
        var skin=new PlayerSkin();skin.overtop="Jacket.Red";skin.undertop="Shirt.Blue";skin.face="Face";
        var shirt=new CollectionService.Definition("eternia:wearable/prowl_shirt","Prowl Shirt",CollectionService.Kind.WEARABLE,"overtop",CustomWearables.ITEM);
        assertEquals(skin,SkinComposer.compose(skin,Map.of(),Map.of("overtop",shirt)));
    }
}
