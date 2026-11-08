package com.hexvane.eterniamod.seasonsales;

import com.hexvane.eterniamod.authoring.*;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SeasonSaleSyncTest {
    @TempDir Path data;

    @Test void startupReadsSeasonFilesThenDesignerDraftsAndSkipsBadDates()throws Exception{
        var services=new EterniaServices(new InMemoryStore());
        Files.createDirectories(data.resolve("Seasons"));
        Files.writeString(data.resolve("Seasons/harvest.json"),"{\"id\":\"eternia:harvest\",\"name\":\"Harvest\",\"startsOn\":\"2026-09-01\",\"endsOn\":\"2026-10-31\"}");
        Files.writeString(data.resolve("Seasons/spring.json"),"{\"id\":\"eternia:spring\",\"startsOn\":\"2027-04-01\",\"endsOn\":\"2027-03-01\"}");
        Files.writeString(data.resolve("Seasons/plain.json"),"{\"id\":\"eternia:plain\",\"name\":\"No dates\"}");
        var draft=new AuthoringDrafts.Season();draft.id="eternia:harvest";draft.name="Harvest";draft.startsOn="2026-09-15";draft.endsOn="";draft.live=true;
        AuthoringDrafts.of(data).save(draft);
        var warnings=new ArrayList<String>();
        assertEquals(1,SeasonSaleSync.sync(services,data,warnings::add));
        assertEquals(new SeasonService.SaleWindow(LocalDate.parse("2026-09-15"),null),services.seasons().saleWindow("eternia:harvest"),"The designer draft wins over the file");
        assertEquals(SeasonService.SaleWindow.NONE,services.seasons().saleWindow("eternia:spring"));
        assertEquals(SeasonService.SaleWindow.NONE,services.seasons().saleWindow("eternia:plain"));
        assertEquals(1,warnings.size());assertTrue(warnings.getFirst().contains("eternia:spring"));
    }

    @Test void soldEntriesOnAPassBecomeSeasonItems(){
        var entry=new AuthoringDrafts.Content();entry.id="lantern";entry.displayName="Lantern";entry.description="A warm light.";entry.sold=true;entry.crowns=120;
        var plain=ContentAuthoring.storeItem(entry,null).orElseThrow();assertEquals("",plain.season());
        entry.passId="eternia:winter";
        var seasonal=ContentAuthoring.storeItem(entry,plain).orElseThrow();
        assertEquals("eternia:winter",seasonal.season());assertEquals(2,seasonal.revision(),"Linking to a pass publishes a new revision");
        assertTrue(ContentAuthoring.storeItem(entry,seasonal).isEmpty(),"An unchanged listing keeps its revision");
    }
}
