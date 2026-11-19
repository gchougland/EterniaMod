package com.hexvane.eterniamod.housing.relocation;
import java.nio.file.*;
import org.bson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class NativeDecorativeEntitiesTest {
    private BsonDocument potion()throws Exception{return BsonDocument.parse(Files.readString(Path.of("src/main/resources/Server/Prefabs/Potion Shelf.prefab.json"))).getArray("entities").get(0).asDocument();}
    @Test void shippedPotionEntitiesMeetTheReviewedComponentContract()throws Exception{
        var prefab=BsonDocument.parse(Files.readString(Path.of("src/main/resources/Server/Prefabs/Potion Shelf.prefab.json")));
        assertEquals(3,prefab.getArray("entities").size());for(var entity:prefab.getArray("entities"))assertDoesNotThrow(()->NativeDecorativeEntities.validateDocument(entity.asDocument(),false));
    }
    @Test void unsupportedGameplayComponentsAndPickupItemsFailBeforeSpawning()throws Exception{
        var entity=potion();entity.getDocument("Components").put("Inventory",new BsonDocument());assertThrows(IllegalStateException.class,()->NativeDecorativeEntities.validateDocument(entity,false));
        var missing=potion();missing.getDocument("Components").remove("PreventPickup");assertThrows(IllegalStateException.class,()->NativeDecorativeEntities.validateDocument(missing,false));
        var stack=potion();stack.getDocument("Components").getDocument("Item").getDocument("Item").put("Quantity",new BsonInt32(64));assertThrows(IllegalStateException.class,()->NativeDecorativeEntities.validateDocument(stack,false));
        var linked=potion();linked.getDocument("Components").put("EterniaPlacedInstance",new BsonDocument());assertThrows(IllegalStateException.class,()->NativeDecorativeEntities.validateDocument(linked,false));
    }
    @Test void everyImportedPropKeepsItsItemDisplaysAndFrozenTag()throws Exception{
        int items=0,frozen=0;
        try(var files=Files.list(Path.of("src/main/resources/Server/Prefabs/EterniaMod/Props"))){
            for(var file:files.filter(f->f.getFileName().toString().startsWith("Pack_")&&f.toString().endsWith(".prefab.json")).toList()){
                var prefab=BsonDocument.parse(Files.readString(file));
                for(var entity:prefab.getArray("entities",new BsonArray())){
                    var components=entity.asDocument().getDocument("Components");
                    if(!components.containsKey("BlockEntity"))items++;
                    if(components.containsKey("Frozen"))frozen++;
                    assertDoesNotThrow(()->NativeDecorativeEntities.validateDocument(entity.asDocument(),false),file.getFileName().toString());
                }
            }
        }
        assertTrue(items>=17,"plain item displays (the axes, the backpack) are kept");
        assertTrue(frozen>=30,"Frozen decorations are kept");
    }
    @Test void aBlockDisplayStillHasToShowItsOwnItem()throws Exception{
        var entity=potion();entity.getDocument("Components").getDocument("Item").getDocument("Item").put("Id",new BsonString("Weapon_Sword_Iron"));
        assertThrows(IllegalStateException.class,()->NativeDecorativeEntities.validateDocument(entity,false));
        var frozen=potion();frozen.getDocument("Components").put("Frozen",new BsonDocument("Speed",new BsonInt32(1)));
        assertThrows(IllegalStateException.class,()->NativeDecorativeEntities.validateDocument(frozen,false),"Frozen carries no data");
    }
}
