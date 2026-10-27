package com.hexvane.eterniamod.customization;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class HousingWorldPolicyTest {
    @Test void restoresTrackedFieldsWithoutDiscardingOperatorChanges() {
        var before=new HousingWorldPolicy.Previous("houses","Default",Set.of("Fluid=Lava"));
        var restored=HousingWorldPolicy.restore(before,HousingWorldPolicy.GAMEPLAY,Set.of("Fluid","Fluid=Lava"));
        assertEquals("Default",restored.gameplay());assertEquals(Set.of("Fluid=Lava"),restored.disabled());
        var fromBuilding=HousingWorldPolicy.restore(before,HousingWorldPolicy.BUILD_GAMEPLAY,Set.of("Fluid","Fluid=Lava"));
        assertEquals("Default",fromBuilding.gameplay());assertEquals(Set.of("Fluid=Lava"),fromBuilding.disabled());
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
        assertFalse(policy.getAsJsonObject("World").get("AllowBlockBreaking").getAsBoolean());
        var build=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/Server/GameplayConfigs/Eternia_Housing_Build.json"))).getAsJsonObject().getAsJsonObject("World");
        assertTrue(build.get("AllowBlockBreaking").getAsBoolean());
        assertTrue(build.get("AllowBlockPlacement").getAsBoolean());
        assertFalse(build.get("AllowBlockGathering").getAsBoolean());
    }
    @Test void nativeRulesAreScopedToEachBuilderAndPreserveOtherGameplaySettings() {
        var builder=packet();var visitor=packet();
        HousingWorldPolicy.clientRules(builder,true);
        HousingWorldPolicy.clientRules(visitor,false);
        assertTrue(builder.worldConfig.allowBlockBreaking);assertTrue(builder.worldConfig.allowBlockPlacement);
        assertFalse(visitor.worldConfig.allowBlockBreaking);assertFalse(visitor.worldConfig.allowBlockPlacement);
        assertFalse(builder.worldConfig.allowBlockGathering);
        assertArrayEquals(new int[]{17},builder.worldConfig.blockedRootInteractionTagIndexes);
        assertEquals(com.hypixel.hytale.assetstore.AssetRegistry.TAG_NOT_FOUND,visitor.worldConfig.blockBreakingBypassTagIndex);
        HousingWorldPolicy.clientRules(builder,false);
        assertFalse(builder.worldConfig.allowBlockBreaking);assertFalse(builder.worldConfig.allowBlockPlacement);
        HousingWorldPolicy.clientRules(visitor,true);
        assertTrue(visitor.worldConfig.allowBlockBreaking);assertTrue(visitor.worldConfig.allowBlockPlacement);
    }
    private static com.hypixel.hytale.protocol.packets.world.WorldGameplayConfig packet() {
        var packet=new com.hypixel.hytale.protocol.packets.world.WorldGameplayConfig();
        packet.worldConfig=new com.hypixel.hytale.protocol.packets.world.WorldConfig();
        packet.worldConfig.blockedRootInteractionTagIndexes=new int[]{17};
        packet.worldConfig.blockBreakingBypassTagIndex=99;
        return packet;
    }
}
