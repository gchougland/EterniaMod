package com.hexvane.eterniamod.ui;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentImagesTest {
    @Test void allBundledShopAndSeasonRewardsHavePackagedArtwork() throws Exception {
        var contentId=Pattern.compile("\"contentId\"\\s*:\\s*\"([^\"]+)\"");
        int checked=0;
        for(String directory:List.of("CrownShop","Seasons")) {
            try(var files=Files.walk(Path.of("src/main/resources/Server/EterniaMod",directory))) {
                for(var file:files.filter(p->p.toString().endsWith(".json")).toList()) {
                    var ids=contentId.matcher(Files.readString(file));
                    while(ids.find()) {
                        String id=ids.group(1),image=ContentImages.path(id,false);
                        assertNotEquals("EterniaMod/Icons/collection.png",image,id);
                        assertNotNull(getClass().getClassLoader().getResource("Common/UI/Custom/"+image),id);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked>=15,"Inspect the actual catalog and both season tracks");
    }

    @Test void nestedAndSingleSegmentEntitlementsResolveWithoutAllowingPaths() {
        assertEquals("EterniaMod/Icons/season.png",ContentImages.path("eternia:season_paid/eternia/foundations",false));
        assertEquals("EterniaMod/Icons/move.png",ContentImages.path("eternia:plot_move_credit",false));
        assertEquals("EterniaMod/Icons/wardrobe.png",ContentImages.path("eternia:wearable/future_jacket",true));
        for(String id:Arrays.asList(null,"","eternia:prop/../../secret","eternia:prop//cacti","eternia:prop\\cacti","https://example.com/image.png"))
            assertEquals("EterniaMod/Icons/collection.png",ContentImages.path(id,false));
    }

    @Test void actualCollectionPreviewsTakePrecedenceAndKeepTheirFrameRatio() throws Exception {
        for(String id:List.of("wearable/citadel_jacket","outfit/citadel_traveler","pet/rootling")) {
            for(boolean screenshot:List.of(false,true)) {
                String image=ContentImages.path("eternia:"+id,screenshot);
                assertEquals("EterniaMod/Catalog/"+id+"/"+(screenshot?"screenshot":"icon")+".png",image);
                try(var stream=getClass().getClassLoader().getResourceAsStream("Common/UI/Custom/"+image)) {
                    assertNotNull(stream);var decoded=ImageIO.read(stream);assertNotNull(decoded);
                    assertEquals(screenshot?1.6:1,(double)decoded.getWidth()/decoded.getHeight(),.001);
                }
            }
        }
    }
}
