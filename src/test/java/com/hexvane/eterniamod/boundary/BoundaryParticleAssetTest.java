package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundaryParticleAssetTest {
    /** Rectangle plot glow plus every hex relation at every strength, and the claimable outline. */
    static List<String> glowIds(boolean withMotes) {
        var ids=new java.util.ArrayList<String>();
        for(String suffix:List.of("","_Soft","_Faint")) {
            ids.add("Eternia_Plot_Border_Motes"+suffix);
            for(var r:HexBorderRelation.values()) if(r!=HexBorderRelation.CLAIMABLE) ids.add(HexBorderRelation.PARTICLE_PREFIX+r.assetName()+suffix);
        }
        if(!withMotes) ids.add(HexBorderRelation.CLAIMABLE.particle(1));
        return ids;
    }
    @Test void runeRibbonAppearsImmediatelyAndFitsOneBlockSegments() throws Exception {
        for(String id:glowIds(false)) {
            var spawner=resource(id+"_Rune.particlespawner");
            assertTrue(spawner.get("SpawnBurst").getAsBoolean());
            assertTrue(spawner.get("LifeSpan").getAsDouble()>=.1,"Emitter must survive the first client update");
            assertEquals(1,spawner.get("TotalParticles").getAsJsonObject().get("Max").getAsInt());
            assertEquals("None",spawner.get("ParticleRotationInfluence").getAsString());
            assertTrue(spawner.get("ParticleRotateWithSpawner").getAsBoolean());
            var particle=spawner.getAsJsonObject("Particle");var frame=particle.getAsJsonObject("InitialAnimationFrame");
            assertEquals(90,frame.getAsJsonObject("Rotation").getAsJsonObject("X").get("Max").getAsInt());
            // Native particles use 32 texture pixels per world unit at scale 1.
            double width=particle.getAsJsonObject("FrameSize").get("Width").getAsDouble()*frame.getAsJsonObject("Scale").getAsJsonObject("X").get("Max").getAsDouble()/32;
            assertEquals(1,width,.0001,"Adjacent segments must meet without extending beyond a corner");
            assertTrue(spawner.getAsJsonObject("ParticleLifeSpan").get("Min").getAsDouble()>BoundaryFogSystem.REFRESH_SECONDS);
            assertTrue(particle.getAsJsonObject("Animation").getAsJsonObject("0").get("Opacity").getAsDouble()>0);
        }
    }
    @Test void motesMoveContinuouslyStayLowAndHaveBoundedEmission() throws Exception {
        for(String id:glowIds(true)) {
            var system=resource(id+".particlesystem");var spawner=resource(id+"_Spawner.particlespawner");
            assertEquals(8,system.get("CullDistance").getAsInt());assertFalse(system.get("IsImportant").getAsBoolean());
            assertFalse(spawner.get("SpawnBurst").getAsBoolean());
            double life=spawner.getAsJsonObject("ParticleLifeSpan").get("Max").getAsDouble();
            assertTrue(spawner.getAsJsonObject("ParticleLifeSpan").get("Min").getAsDouble()>BoundaryFogSystem.REFRESH_SECONDS);
            assertTrue(spawner.get("LifeSpan").getAsDouble()+life<=BoundaryFogSystem.MAX_EFFECT_SECONDS);
            assertTrue(spawner.get("MaxConcurrentParticles").getAsInt()<=6);
            var speed=spawner.getAsJsonObject("InitialVelocity").getAsJsonObject("Speed");assertTrue(speed.get("Min").getAsDouble()>0);
            var particle=spawner.getAsJsonObject("Particle");
            double size=particle.getAsJsonObject("InitialAnimationFrame").getAsJsonObject("Scale").getAsJsonObject("X").get("Max").getAsDouble();
            assertTrue(size<=.12);assertEquals("Billboard",spawner.get("ParticleRotationInfluence").getAsString());
            double verticalOffset=spawner.getAsJsonObject("EmitOffset").getAsJsonObject("Y").get("Max").getAsDouble();
            assertTrue(BoundaryFogSystem.EMITTER_HEIGHT+verticalOffset+speed.get("Max").getAsDouble()*life+size/2<1);
            assertTrue(.36+size/2<.5,"Motes must stay short of each segment's corner");
        }
    }
    @Test void glowHasNoOpaqueTextureBorderAndOldCurtainIsRemoved() throws Exception {
        assertNull(getClass().getClassLoader().getResource("Common/Particles/Eternia/PlotAurora.png"));
        try(var input=getClass().getClassLoader().getResourceAsStream("Common/Particles/Eternia/PlotMote.png")) {
            assertNotNull(input);var image=javax.imageio.ImageIO.read(input);
            for(int i=0;i<64;i++){assertEquals(0,image.getRGB(i,0));assertEquals(0,image.getRGB(i,63));assertEquals(0,image.getRGB(0,i));assertEquals(0,image.getRGB(63,i));}
            assertTrue((image.getRGB(32,32)>>>24)>200);
        }
    }
    @Test void everyRelationHasItsOwnColouredRuneAndTheClaimableOutlineIsCheap() throws Exception {
        var claim=resource(HexBorderRelation.CLAIMABLE.particle(1)+".particlesystem");
        assertEquals(1,claim.getAsJsonArray("Spawners").size(),"no rising lights on claimable outlines");
        for(var r:HexBorderRelation.values()) {
            var rune=resource(HexBorderRelation.PARTICLE_PREFIX+r.assetName()+"_Rune.particlespawner");
            String texture=rune.getAsJsonObject("Particle").get("Texture").getAsString();
            assertEquals("Particles/Eternia/HexRune"+r.assetName()+".png",texture);
            try(var input=getClass().getClassLoader().getResourceAsStream("Common/"+texture)) {
                assertNotNull(input,texture);var image=javax.imageio.ImageIO.read(input);
                int rgb=image.getRGB(90,128)&0xFFFFFF;int want=r.color();
                for(int shift:new int[]{16,8,0})assertTrue(Math.abs(((rgb>>shift)&255)-((want>>shift)&255))<=40,r+" line colour "+Integer.toHexString(rgb));
            }
        }
    }
    private JsonObject resource(String name) throws Exception {
        try(var stream=getClass().getClassLoader().getResourceAsStream("Server/Particles/Eternia/"+name)) {
            assertNotNull(stream,name);return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
