package com.hexvane.eterniamod.boundary;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundaryParticleAssetTest {
    @Test void motesMoveContinuouslyStayLowAndHaveBoundedEmission() throws Exception {
        for(String suffix:List.of("","_Soft","_Faint")) {
            String id="Eternia_Plot_Border_Motes"+suffix;
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
    private JsonObject resource(String name) throws Exception {
        try(var stream=getClass().getClassLoader().getResourceAsStream("Server/Particles/Eternia/"+name)) {
            assertNotNull(stream,name);return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
