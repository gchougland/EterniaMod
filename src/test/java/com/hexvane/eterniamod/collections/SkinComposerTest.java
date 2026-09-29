package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.domain.CollectionService;
import com.hypixel.hytale.protocol.PlayerSkin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SkinComposerTest {
    @TempDir Path data;
    @Test void wearableChangesOnlyItsSlotAndNeverMutatesOriginal() {
        var original=new PlayerSkin();original.face="OriginalFace";original.haircut="OriginalHair.Blue";original.overtop="OriginalJacket.Red";
        var wearable=new CollectionService.Definition("eternia:wearable/test","Test",CollectionService.Kind.WEARABLE,"overtop","PuffyJacket.Turquoise");
        var changed=SkinComposer.compose(original,Map.of(),Map.of("overtop",wearable));
        assertEquals("PuffyJacket.Turquoise",changed.overtop);assertEquals("OriginalJacket.Red",original.overtop);
        assertEquals(original.face,changed.face);assertEquals(original.haircut,changed.haircut);
        assertEquals(original,SkinComposer.compose(original,Map.of(),Map.of()));
    }
    @Test void shippedOutfitPreservesIdentityAndRejectsUnsafeAssetReferences() {
        var original=new PlayerSkin();original.face="OriginalFace";original.eyes="OriginalEyes.Green";original.cape="OriginalCape.Red";
        var changed=SkinComposer.compose(original,SkinComposer.outfit(data,"citadel_traveler"),Map.of());
        assertEquals(original.face,changed.face);assertEquals(original.eyes,changed.eyes);assertNull(changed.cape);assertNotNull(original.cape);
        assertThrows(IllegalArgumentException.class,()->SkinComposer.outfit(data,"../escape"));
        assertThrows(IllegalArgumentException.class,()->SkinComposer.compose(original,Map.of("unknown","Some.Part"),Map.of()));
    }
}
