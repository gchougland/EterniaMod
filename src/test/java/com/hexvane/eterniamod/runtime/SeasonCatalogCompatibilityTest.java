package com.hexvane.eterniamod.runtime;

import com.google.gson.Gson;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.persistence.LocalFileStore;
import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SeasonCatalogCompatibilityTest {
    private static final String SEASON="eternia:foundations";
    @TempDir Path data;

    @Test void recoveredReleasedCatalogRestartsWithoutChangingProgressOrRepeatingClaims() throws Exception {
        SeasonService.Definition released;
        try(var input=getClass().getResourceAsStream("/seasons/released-foundations-2026-10.json")) {
            released=new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(input),StandardCharsets.UTF_8),SeasonService.Definition.class);
        }
        assertEquals(30,released.quests().stream().filter(q->q.cadence()==SeasonService.Cadence.DAILY).count());
        assertEquals(15,released.quests().stream().filter(q->q.cadence()==SeasonService.Cadence.WEEKLY).count());
        assertEquals("eternia:quest/daily_undead",released.quests().getFirst().id());
        assertEquals(1500,released.quests().getFirst().bonusXp());
        assertEquals(80,released.quests().getFirst().coins());
        var clock=Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"),ZoneOffset.UTC);
        UUID actor=UUID.randomUUID();Path file=data.resolve("authority.bin");
        List<SeasonService.Progress> progress;List<SeasonService.QuestProgress> quests;
        TransactionalStore.Row definition;long balance;
        var reward=released.rewards().stream().filter(r->r.track()==SeasonService.Track.FREE&&r.level()==1).findFirst().orElseThrow();
        try(var store=new LocalFileStore(file)) {
            var services=new EterniaServices(store,clock,UUID::randomUUID);
            services.seasons().register(released);services.accounts().recordAuthenticatedLogin(actor,"SeasonTest");
            services.seasons().select(actor,SEASON);
            services.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),actor,
                SeasonService.ActivityKind.KILL,"Skeleton",1,2000,"test-kill",true));
            services.seasons().claimReward(actor,SEASON,reward.track(),reward.level(),reward.index());
            progress=services.seasons().progress(actor);quests=services.seasons().quests(actor,SEASON);
            definition=season(store);balance=services.economy().balance(Owner.player(actor)).available();
        }
        try(var store=new LocalFileStore(file)) {
            var services=new EterniaServices(store,clock,UUID::randomUUID);
            GameplayContent.load(services,data);GameplayContent.load(services,data);
            assertEquals(definition,season(store),"Startup must preserve the released season row and revision");
            assertEquals(progress,services.seasons().progress(actor));assertEquals(quests,services.seasons().quests(actor,SEASON));
            assertEquals(balance,services.economy().balance(Owner.player(actor)).available());
            services.seasons().claimReward(actor,SEASON,reward.track(),reward.level(),reward.index());
            assertEquals(reward.quantity(),services.ownership().available(Owner.player(actor),reward.contentId()));
        }
    }

    @Test void bundledConflictReportsOriginAndStoredAndIncomingFieldsWithoutOverwriting() {
        var store=new InMemoryStore();var services=new EterniaServices(store);GameplayContent.load(services,data);
        store.transaction(tx->{
            var row=tx.find("season_definition",SEASON).orElseThrow();var fields=new TreeMap<>(row.fields());
            fields.put("q0.bonus","500");tx.save(row.namespace(),row.key(),row.revision(),fields);return null;
        });
        var before=season(store);
        var error=assertThrows(DomainException.class,()->GameplayContent.load(services,data));
        assertEquals(DomainException.Code.CONFLICT,error.code());
        assertTrue(error.getMessage().contains("bundled Server/EterniaMod/Seasons/foundations.json"));
        assertTrue(error.getMessage().contains(SEASON));
        assertTrue(error.getMessage().contains("q0.bonus [500 -> 1500]"));
        assertEquals(before,season(store));
    }

    @Test void conflictingCustomFileReportsItsPathWithoutReplacingBundledSeason() throws Exception {
        var store=new InMemoryStore();var services=new EterniaServices(store);GameplayContent.load(services,data);
        var released=services.seasons().definitions().getFirst();var before=season(store);
        var custom=new SeasonService.Definition(released.id(),"Custom season",released.thresholds(),released.rewards(),released.quests());
        Path file=data.resolve("Seasons/custom.json");Files.writeString(file,new Gson().toJson(custom));
        var error=assertThrows(DomainException.class,()->GameplayContent.load(services,data));
        assertTrue(error.getMessage().contains(file.toAbsolutePath().toString()));
        assertTrue(error.getMessage().contains("name [Foundations -> Custom season]"));
        assertEquals(before,season(store));
    }

    private static TransactionalStore.Row season(TransactionalStore store) {
        return store.transaction(tx->tx.find("season_definition",SEASON).orElseThrow());
    }
}
