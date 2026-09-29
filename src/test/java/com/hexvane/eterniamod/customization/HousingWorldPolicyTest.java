package com.hexvane.eterniamod.customization;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class HousingWorldPolicyTest {
    @Test void restoresTrackedFieldsWithoutDiscardingOperatorChanges() {
        var before=new HousingWorldPolicy.Previous("houses","Default",Set.of("Fluid=Lava"));
        var restored=HousingWorldPolicy.restore(before,HousingWorldPolicy.GAMEPLAY,Set.of("Fluid","Fluid=Lava"));
        assertEquals("Default",restored.gameplay());assertEquals(Set.of("Fluid=Lava"),restored.disabled());
        var modified=HousingWorldPolicy.restore(before,"CustomGameplay",Set.of("Fluid","Fluid=Lava","CustomTag"));
        assertEquals("CustomGameplay",modified.gameplay());assertEquals(Set.of("Fluid","Fluid=Lava","CustomTag"),modified.disabled());
    }
    @Test void preservesAnExistingFluidFreezeAndDoesNotInventUnknownPreviousConfig() {
        var before=new HousingWorldPolicy.Previous("houses",HousingWorldPolicy.GAMEPLAY,Set.of("Fluid"));
        var restored=HousingWorldPolicy.restore(before,HousingWorldPolicy.GAMEPLAY,Set.of("Fluid"));
        assertEquals(HousingWorldPolicy.GAMEPLAY,restored.gameplay());assertEquals(Set.of("Fluid"),restored.disabled());
    }
    @Test void shippedPolicyDisablesBothNativeFluidEntryPointsThroughPlacementFlag()throws Exception {
        var policy=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/Server/GameplayConfigs/Eternia_Housing.json"))).getAsJsonObject();
        assertFalse(policy.getAsJsonObject("World").get("AllowBlockPlacement").getAsBoolean());
        assertFalse(policy.getAsJsonObject("World").get("AllowBlockGathering").getAsBoolean());
    }
}
