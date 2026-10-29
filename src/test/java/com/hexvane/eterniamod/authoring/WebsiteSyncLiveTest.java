package com.hexvane.eterniamod.authoring;

import com.google.gson.*;
import com.hexvane.eterniamod.runtime.RuntimeConfig;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs only against a local fixture website started with the same token (see docs/implementation/content-authoring.md). */
class WebsiteSyncLiveTest {
    @Test void uploadsAPrefabEntryAndASeasonPass()throws Exception{
        String url=System.getenv("ETERNIA_TEST_WEBSITE_URL"),token=System.getenv("ETERNIA_TEST_BRIDGE_TOKEN");
        assumeTrue(url!=null&&token!=null,"Set ETERNIA_TEST_WEBSITE_URL and ETERNIA_TEST_BRIDGE_TOKEN to run");
        var sync=WebsiteSync.from(RuntimeConfig.from(Map.of("ETERNIA_MODE","local","ETERNIA_WEBSITE_URL",url,"ETERNIA_BRIDGE_TOKEN",token)));
        var lamp=new AuthoringDrafts.Content();lamp.id="aqua_lamp_sync_test";lamp.displayName="Aqua Lamp";lamp.description="A tall lamp of aqua brick.";lamp.sold=true;lamp.crowns=120;lamp.passId="eternia:sync_test";lamp.level=3;lamp.track="PAID";
        var prefab=JsonParser.parseString(Files.readString(Path.of("src/main/resources/Server/Prefabs/Aqua Lamp.prefab.json")));
        var nativeFields=new JsonObject();nativeFields.addProperty("rotationYaw","None");
        UUID admin=UUID.fromString("22222222-2222-4222-8222-222222222222");
        var first=sync.content(ContentAuthoring.contentUpload(lamp,prefab,nativeFields,admin,"Hexvane")).join();
        var again=sync.content(ContentAuthoring.contentUpload(lamp,prefab,nativeFields,admin,"Hexvane")).join();
        assertTrue(first.revision()>=1);assertTrue(again.unchanged());assertEquals(first.revision(),again.revision());
        var pass=new AuthoringDrafts.Season();pass.id="eternia:sync_test";pass.name="Sync Test";pass.startsOn="2026-11-01";pass.endsOn="2027-01-31";pass.thresholds=new ArrayList<>(ContentAuthoring.thresholds(10,1000));pass.live=true;
        ContentAuthoring.placeReward(pass,lamp);
        assertTrue(sync.season(ContentAuthoring.seasonUpload(pass,admin,"Hexvane")).join().revision()>=1);
        var wrong=WebsiteSync.from(RuntimeConfig.from(Map.of("ETERNIA_MODE","local","ETERNIA_WEBSITE_URL",url,"ETERNIA_BRIDGE_TOKEN","w".repeat(40))));
        var failure=assertThrows(java.util.concurrent.CompletionException.class,()->wrong.content(ContentAuthoring.contentUpload(lamp,prefab,nativeFields,admin,"Hexvane")).join());
        assertTrue(WebsiteSync.reason(failure).contains("key"));
    }
}
