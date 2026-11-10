package com.hexvane.eterniamod.hexplots.terraform;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TerraformBlocksTest {
    @Test
    void naturalLandMayBeShaped() {
        for (String id : new String[] {"Soil_Grass", "Soil_Dirt", "Rock_Stone", "Sand_White", "Snow_Block", "Ore_Iron_Stone", "Plant_Grass_Lush", "Plant_Leaves_Oak", "Wood_Oak_Trunk", "Rubble_Stone"})
            assertTrue(TerraformBlocks.isNatural(id), id);
    }

    @Test
    void buildsAndCropsAreNeverShaped() {
        for (String id : new String[] {"Wood_Oak_Planks", "Rock_Stone_Brick", "Rock_Stone_Cobble", "Wood_Oak_Trunk_Stairs", "Furniture_Table", "Rock_Stone_Brick_Smooth", "Soil_Grass_Stairs", "", "Planter_Box"})
            assertFalse(TerraformBlocks.isNatural(id), id);
    }

    @Test
    void growthIsClearedButGrassStays() {
        for (String id : new String[] {"Plant_Leaves_Oak", "Plant_Flower_Common_Red", "Plant_Bush_Green", "Wood_Oak_Trunk", "Wood_Oak_Roots", "Rubble_Stone", "Plant_Crop_Wheat_Stage_4", "Plant_Crop_Berry_Block", "Plant_Bush_Berry", "Plant_Mushroom_Spotted"})
            assertTrue(TerraformBlocks.isGrowth(id), id);
        for (String id : new String[] {"Soil_Gravel", "Rock_Stone", "Planter_Box", "Wood_Oak_Planks", "Furniture_Flower_Pot", "Plant_Flower_Potted"})
            assertFalse(TerraformBlocks.isGrowth(id), id);
        assertTrue(TerraformBlocks.isKeptGrass("Plant_Grass_Lush"));
        assertFalse(TerraformBlocks.isKeptGrass("Plant_Flower_Common_Red"));
    }

    @Test
    void cropsTreesAndTilledSoil() {
        assertTrue(TerraformBlocks.isCrop("Plant_Crop_Wheat_Stage_2"));
        assertFalse(TerraformBlocks.isCrop("Plant_Bush_Berry"));
        assertTrue(TerraformBlocks.isTilled("Soil_Dirt_Tilled"));
        assertFalse(TerraformBlocks.isTilled("Soil_Dirt"));
        for (String id : new String[] {"Wood_Oak_Trunk", "Wood_Oak_Trunk_Full", "Wood_Oak_Branch_Long", "Wood_Oak_Branch_Corner", "Wood_Oak_Roots"})
            assertTrue(TerraformBlocks.isTreeWood(id), id);
        for (String id : new String[] {"Wood_Oak_Planks", "Wood_Oak_Trunk_Half", "Wood_Oak_Trunk_Stairs", "Plant_Leaves_Oak"})
            assertFalse(TerraformBlocks.isTreeWood(id), id);
        assertTrue(TerraformBlocks.isLeaves("Plant_Leaves_Oak"));
    }

    @Test
    void groundAndTopsoil() {
        assertTrue(TerraformBlocks.isGround("Rock_Stone"));
        assertFalse(TerraformBlocks.isGround("Plant_Grass_Lush"));
        assertTrue(TerraformBlocks.isTopsoil("Soil_Grass"));
        assertFalse(TerraformBlocks.isTopsoil("Rock_Stone"));
        assertEquals("Soil_Dirt", TerraformBlocks.subsoilFor("Soil_Grass"));
        assertEquals("Sand_White", TerraformBlocks.subsoilFor("Sand_White"));
    }
}
