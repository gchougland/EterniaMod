package com.hexvane.eterniamod.domain;

import com.google.gson.Gson;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.io.InputStreamReader;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecurringQuestsTest {
    static final class Time extends Clock {
        Instant now=Instant.parse("2026-09-27T23:59:59Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    static SeasonService.Definition bundled(){
        return new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(RecurringQuestsTest.class.getResourceAsStream("/Server/EterniaMod/Seasons/foundations.json"))),SeasonService.Definition.class);
    }
    static SeasonService.Quest goal(String id,SeasonService.Cadence cadence){return new SeasonService.Quest("eternia:"+id,SeasonService.ActivityKind.KILL,SeasonService.ObjectiveKind.COUNT,Set.of("group:undead"),1,100,80L,cadence,"Keep the peace","Defeat an undead enemy.");}
    @Test void dailyAndWeeklyResetAtUtcBoundaryAndPayOnceInEachWindow(){
        var time=new Time();var s=new EterniaServices(new InMemoryStore(),time,UUID::randomUUID);UUID actor=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(actor,"Tester");
        var pass=new SeasonService.Definition("eternia:test","Test",List.of(1000L),List.of(),List.of(goal("daily",SeasonService.Cadence.DAILY),goal("weekly",SeasonService.Cadence.WEEKLY)));
        s.seasons().register(pass);s.seasons().select(actor,pass.id());
        var first=new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.KILL,"Skeleton_Fighter_Patrol",1,10,"npc1",true,time.now);
        assertTrue(s.seasons().recordActivity(first));assertEquals(160,s.economy().balance(Owner.player(actor)).available());
        time.now=Instant.parse("2026-09-28T00:00:00Z");assertTrue(s.seasons().quests(actor,pass.id()).stream().noneMatch(SeasonService.QuestProgress::completed));
        assertFalse(s.seasons().recordActivity(first));assertEquals(160,s.economy().balance(Owner.player(actor)).available());
        s.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.KILL,"Ghoul",1,10,"npc2",true,time.now));
        assertEquals(320,s.economy().balance(Owner.player(actor)).available());
        time.now=time.now.plusSeconds(86400);
        var goals=s.seasons().quests(actor,pass.id());assertFalse(goals.get(0).completed());assertTrue(goals.get(1).completed());
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"),SeasonService.resetsAt(SeasonService.Cadence.WEEKLY,time.now));
    }
    @Test void outboxKeepsTheTimeOfGameplayAcrossMidnight(){
        var time=new Time();var s=new EterniaServices(new InMemoryStore(),time,UUID::randomUUID);UUID actor=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(actor,"Tester");
        var pass=new SeasonService.Definition("eternia:test","Test",List.of(1000L),List.of(),List.of(goal("daily",SeasonService.Cadence.DAILY)));
        s.seasons().register(pass);s.seasons().select(actor,pass.id());s.activitySources().verifiedDeath(actor,UUID.randomUUID(),UUID.randomUUID(),"Skeleton_Archer",10);
        time.now=time.now.plusSeconds(2);s.activitySources().drain(100);
        assertEquals(80,s.economy().balance(Owner.player(actor)).available());assertFalse(s.seasons().quests(actor,pass.id()).getFirst().completed());
        assertEquals(0,s.activitySources().drain(100));assertEquals(80,s.economy().balance(Owner.player(actor)).available());
    }
    @Test void catalogHasVarietyAndRotatesWithoutChangingWithinEachWindow(){
        var pass=bundled();assertEquals(30,pass.quests().stream().filter(q->q.cadence()==SeasonService.Cadence.DAILY).count());assertEquals(15,pass.quests().stream().filter(q->q.cadence()==SeasonService.Cadence.WEEKLY).count());
        var day=Instant.parse("2026-09-28T00:00:00Z");var daily=new HashSet<String>();var weekly=new HashSet<String>();
        for(int i=0;i<70;i++){
            Instant at=day.plusSeconds(i*86400L);var chosen=SeasonService.availableQuests(pass,at);assertEquals(6,chosen.size());
            assertEquals(chosen,SeasonService.availableQuests(pass,at.plusSeconds(86399)));
            chosen.forEach(q->{assertFalse(q.name().isBlank());assertFalse(q.goal().isBlank());(q.cadence()==SeasonService.Cadence.DAILY?daily:weekly).add(q.id());});
        }
        assertEquals(30,daily.size());assertEquals(15,weekly.size());
    }
    @Test void nativeTargetsIncludeVariantsAndExcludeAnimals(){
        for(String role:List.of("Skeleton","Skeleton_Archer_Wander","Skeleton_Frost_Fighter","Ghoul","Zombie_Sand"))assertTrue(QuestTargets.matches("group:undead",role),role);
        assertFalse(QuestTargets.matches("group:enemies","Cow"));assertFalse(QuestTargets.matches("group:undead","Trork_Warrior"));
        assertTrue(QuestTargets.matches("ore:iron","Ore_Iron"));assertTrue(QuestTargets.matches("ore:iron","Ore_Iron_Stone"));assertFalse(QuestTargets.matches("ore:iron","Ore_Copper_Stone"));
        assertTrue(QuestTargets.matches("crop:wheat","*Plant_Crop_Wheat_Block_State_StageFinal"));assertFalse(QuestTargets.matches("crop:wheat","Plant_Crop_Corn_Block"));
        assertEquals(QuestTargets.distinctTarget(SeasonService.ActivityKind.HARVEST,"*Plant_Crop_Wheat_Block_State_StageFinal"),QuestTargets.distinctTarget(SeasonService.ActivityKind.HARVEST,"Eternia_Trial_Wheat_State_StageFinal"));
    }
    @Test void cropCyclesSurviveGenerationResetAndRejectDuplicateCallbacks(){
        var s=new EterniaServices(new InMemoryStore());UUID actor=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(actor,"Tester");var block=new ActivitySourceService.Block(UUID.randomUUID(),10,1,20);
        assertTrue(s.activitySources().verifiedHarvestCycle(actor,block,"Plant_Crop_Wheat_Block","1:2026-09-27T12:00:00Z",30));
        assertFalse(s.activitySources().verifiedHarvestCycle(actor,block,"Plant_Crop_Wheat_Block","1:2026-09-27T12:00:00Z",30));
        assertTrue(s.activitySources().verifiedHarvestCycle(actor,block,"Plant_Crop_Wheat_Block","1:2026-09-28T12:00:00Z",30));
        assertEquals(2,s.activitySources().drain(100));assertEquals(0,s.activitySources().drain(100));
    }
    @Test void shippedPrototypeUpgradesWithoutErasingXpAndCannotChangeRewards(){
        var s=new EterniaServices(new InMemoryStore());var next=bundled();UUID actor=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(actor,"Tester");
        var old=new SeasonService.Definition(next.id(),next.name(),next.thresholds(),next.rewards(),List.of(
            new SeasonService.Quest("eternia:quest/hunt",SeasonService.ActivityKind.KILL,SeasonService.ObjectiveKind.COUNT,Set.of(),50,8000),
            new SeasonService.Quest("eternia:quest/miner",SeasonService.ActivityKind.MINE,SeasonService.ObjectiveKind.DISTINCT,Set.of(),12,8000),
            new SeasonService.Quest("eternia:quest/harvest",SeasonService.ActivityKind.HARVEST,SeasonService.ObjectiveKind.COUNT,Set.of(),100,8000)));
        s.seasons().register(old);s.seasons().select(actor,old.id());s.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.KILL,"Skeleton",1,42,"one",true));
        s.seasons().register(next);s.seasons().register(next);assertEquals(42,s.seasons().progress(actor).getFirst().xp());assertEquals(6,s.seasons().quests(actor,next.id()).size());
        assertThrows(DomainException.class,()->s.seasons().register(new SeasonService.Definition(next.id(),next.name(),List.of(1L),List.of(),next.quests())));
    }
    @Test void questWordingCanChangeWithoutResettingCompletionOrPayingAgain(){
        var time=new Time();var s=new EterniaServices(new InMemoryStore(),time,UUID::randomUUID);UUID actor=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(actor,"Tester");
        var quest=goal("daily",SeasonService.Cadence.DAILY);
        var pass=new SeasonService.Definition("eternia:test","Test",List.of(1000L),List.of(),List.of(quest));
        s.seasons().register(pass);s.seasons().select(actor,pass.id());
        s.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.KILL,"Skeleton",1,10,"npc1",true,time.now));
        var revised=new SeasonService.Quest(quest.id(),quest.activity(),quest.kind(),quest.targets(),quest.required(),quest.bonusXp(),quest.coins(),quest.cadence(),"Rest easy","Defeat 1 undead.");
        var next=new SeasonService.Definition(pass.id(),pass.name(),pass.thresholds(),pass.rewards(),List.of(revised));
        s.seasons().register(next);s.seasons().register(next);
        var progress=s.seasons().quests(actor,pass.id()).getFirst();assertEquals(revised,progress.quest());assertTrue(progress.completed());assertEquals(1,progress.count());
        assertEquals(110,s.seasons().progress(actor).getFirst().xp());assertEquals(80,s.economy().balance(Owner.player(actor)).available());
        var changedReward=new SeasonService.Quest(revised.id(),revised.activity(),revised.kind(),revised.targets(),revised.required(),revised.bonusXp(),81L,revised.cadence(),revised.name(),revised.goal());
        assertThrows(DomainException.class,()->s.seasons().register(new SeasonService.Definition(pass.id(),pass.name(),pass.thresholds(),pass.rewards(),List.of(changedReward))));
    }
}
