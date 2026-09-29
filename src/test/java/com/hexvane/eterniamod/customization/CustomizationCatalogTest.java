package com.hexvane.eterniamod.customization;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CustomizationCatalogTest {
    @TempDir Path temp;
    @Test void manifestsAndLocalOverrideValidateThroughRecordConstructors()throws Exception {
        var shipped=new CustomizationCatalog(temp);assertEquals("cut_stone",shipped.palettes().getFirst().id());assertEquals("cobblestone",shipped.paths().getFirst().id());
        Files.writeString(temp.resolve("Palettes/custom.json"),"{\"id\":\"cut_stone\",\"name\":\"Local\",\"houseId\":\"hub_house\",\"blocks\":{\"Rock_Stone_Cobble\":\"Rock_Stone_Brick\"}}");
        assertEquals("Local",new CustomizationCatalog(temp).palettes().getFirst().name());
        Files.copy(temp.resolve("Palettes/custom.json"),temp.resolve("Palettes/duplicate.json"));
        assertThrows(IllegalStateException.class,()->new CustomizationCatalog(temp));
    }
    @Test void rejectsUnsafeIdsAndEmptyMappings()throws Exception {
        assertThrows(IllegalArgumentException.class,()->new CustomizationCatalog.Palette("x","X","house",Map.of()));
        assertThrows(IllegalArgumentException.class,()->new CustomizationCatalog.PathStyle("../path","Path","Stone"));
        Files.createDirectories(temp.resolve("PathStyles"));Files.writeString(temp.resolve("PathStyles/bad.json"),"{\"id\":\"bad\",\"name\":\"Bad\",\"blockId\":\"../../unknown\"}");
        assertThrows(IllegalStateException.class,()->new CustomizationCatalog(temp));
    }
    @Test void freeToolsHaveNoResourceOutputsAndPrefabsHaveNoSeededInventory()throws Exception {
        var parser=new com.google.gson.Gson();
        for(String item:List.of("Eternia_Management_Block","Eternia_Plot_Deed","Eternia_Architect_Ledger")) {
            var json=parser.fromJson(Files.readString(Path.of("src/main/resources/Server/Item/Items/Eternia/"+item+".json")),com.google.gson.JsonObject.class);
            assertFalse(json.has("ResourceTypes"));assertFalse(json.has("Recipe"));
        }
        try(var paths=Files.list(Path.of("src/main/resources/Server/Prefabs"))){for(var file:paths.filter(p->p.toString().endsWith(".json")).toList()) {
            String json=Files.readString(file);assertFalse(json.contains("\"ItemContainer\""),file.toString());assertFalse(json.contains("\"ItemStack\""),file.toString());assertFalse(json.contains("\"Inventory\""),file.toString());
        }}
    }
}
