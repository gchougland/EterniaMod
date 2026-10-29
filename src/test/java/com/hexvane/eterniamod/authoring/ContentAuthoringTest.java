package com.hexvane.eterniamod.authoring;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.nio.file.Files;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentAuthoringTest {
    private static AuthoringDrafts.Content lamp(){var d=new AuthoringDrafts.Content();d.id="garden_lamp";d.displayName="Garden Lamp";d.description="A warm light.";d.sold=true;d.crowns=120;return d;}
    private static AuthoringDrafts.Season pass(){var s=new AuthoringDrafts.Season();s.id="eternia:harvest";s.name="Harvest";s.thresholds=new ArrayList<>(ContentAuthoring.thresholds(10,1000));return s;}

    @Test void validationExplainsWhatToFix(){
        ContentAuthoring.validate(lamp());
        var bad=lamp();bad.id="Garden Lamp";assertThrows(IllegalArgumentException.class,()->ContentAuthoring.validate(bad));
        var free=lamp();free.crowns=0;assertThrows(IllegalArgumentException.class,()->ContentAuthoring.validate(free));
        var quiet=lamp();quiet.description="";assertThrows(IllegalArgumentException.class,()->ContentAuthoring.validate(quiet));
        quiet.sold=false;ContentAuthoring.validate(quiet);
        var season=pass();season.endsOn="2026-01-01";season.startsOn="2026-02-01";assertThrows(IllegalArgumentException.class,()->ContentAuthoring.validate(season));
        season.startsOn="Nov 1";assertThrows(IllegalArgumentException.class,()->ContentAuthoring.validate(season));
    }
    @Test void storeRevisionsOnlyAdvanceWhenTheListingChanges(){
        var first=ContentAuthoring.storeItem(lamp(),null).orElseThrow();
        assertEquals("eternia:store/garden_lamp",first.id());assertEquals(1,first.revision());assertEquals(120,first.price());
        assertEquals("eternia:prop/garden_lamp",first.benefits().getFirst().contentId());assertEquals(OwnershipService.Kind.QUANTITY,first.benefits().getFirst().kind());
        assertTrue(ContentAuthoring.storeItem(lamp(),first).isEmpty());
        var dearer=lamp();dearer.crowns=150;assertEquals(2,ContentAuthoring.storeItem(dearer,first).orElseThrow().revision());
        var house=lamp();house.kind="house";assertEquals(OwnershipService.Kind.UNLOCK,ContentAuthoring.storeItem(house,null).orElseThrow().benefits().getFirst().kind());
        var services=new EterniaServices(new InMemoryStore());services.premium().register(first);services.premium().register(ContentAuthoring.storeItem(dearer,first).orElseThrow());
        assertEquals(150,services.premium().items().getFirst().price());
    }
    @Test void placingARewardMovesItAndFindsAFreeSlot(){
        var season=pass();var lamp=lamp();lamp.passId=season.id;lamp.level=3;lamp.track="PAID";
        ContentAuthoring.placeReward(season,lamp);
        var shelf=lamp();shelf.id="shelf";shelf.passId=season.id;shelf.level=3;shelf.track="PAID";ContentAuthoring.placeReward(season,shelf);
        assertEquals(List.of(0,1),season.rewards.stream().map(r->r.index).toList());
        lamp.level=5;ContentAuthoring.placeReward(season,lamp);
        assertEquals(2,season.rewards.size());assertEquals(5,season.rewards.stream().filter(r->r.contentId.equals("eternia:prop/garden_lamp")).findFirst().orElseThrow().level);
        lamp.level=11;assertThrows(IllegalArgumentException.class,()->ContentAuthoring.placeReward(season,lamp));
    }
    @Test void goingLiveRegistersAPassThatSurvivesReloadAndStaysFixed(){
        var season=pass();var lamp=lamp();lamp.passId=season.id;lamp.level=2;ContentAuthoring.placeReward(season,lamp);
        var services=new EterniaServices(new InMemoryStore());var definition=ContentAuthoring.definition(season,List.of());
        services.seasons().register(definition);
        var reloaded=new Gson().fromJson(new Gson().toJson(definition),SeasonService.Definition.class);
        services.seasons().register(reloaded);
        assertTrue(ContentAuthoring.liveHasReward(definition,lamp));
        season.rewards.clear();assertThrows(DomainException.class,()->services.seasons().register(ContentAuthoring.definition(season,List.of())));
        var draft=ContentAuthoring.draftFrom(definition);assertTrue(draft.live);assertEquals(10,draft.thresholds.size());assertEquals(1,draft.rewards.size());
    }
    @Test void uploadsCarryTheAdminAndTheEntryDetails(){
        var lamp=lamp();lamp.passId="eternia:harvest";lamp.level=4;lamp.track="PAID";UUID admin=UUID.randomUUID();
        var body=ContentAuthoring.contentUpload(lamp,JsonParser.parseString("{\"blocks\":[]}"),new JsonObject(),admin,"Hexvane");
        assertEquals("garden_lamp",body.get("id").getAsString());assertEquals(120,body.getAsJsonObject("store").get("crowns").getAsInt());
        assertEquals(4,body.getAsJsonObject("season").get("level").getAsInt());assertEquals(admin.toString(),body.getAsJsonObject("author").get("uuid").getAsString());
        var season=pass();season.live=true;season.startsOn="2026-11-01";
        var upload=ContentAuthoring.seasonUpload(season,admin,"Hexvane");assertEquals("live",upload.get("status").getAsString());assertEquals(10,upload.getAsJsonArray("thresholds").size());assertFalse(upload.has("endsOn"));
    }
    @Test void draftsRoundTripThroughTheDataFolder()throws Exception{
        var folder=Files.createTempDirectory("eternia-authoring");var drafts=AuthoringDrafts.of(folder);
        var lamp=lamp();lamp.prefabPath="Aqua Lamp.prefab.json";drafts.save(lamp);drafts.save(pass());
        assertEquals("Aqua Lamp.prefab.json",drafts.content("prop","garden_lamp").orElseThrow().prefabPath);
        assertEquals("Harvest",drafts.season("eternia:harvest").orElseThrow().name);assertEquals(1,drafts.seasons().size());
        assertTrue(drafts.content("house","garden_lamp").isEmpty());
    }
}
