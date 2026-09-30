package com.hexvane.eterniamod.domain;

import com.google.gson.Gson;
import com.hexvane.eterniamod.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SeasonCoinsTest {
    @TempDir Path directory;
    private static SeasonService.Definition pass(String id,SeasonService.ObjectiveKind kind,Long coins){
        return new SeasonService.Definition(id,"Test pass",List.of(100L),List.of(),List.of(new SeasonService.Quest("eternia:quest/test",SeasonService.ActivityKind.MINE,kind,Set.of(),2,50,coins)));
    }
    private static SeasonService.Activity event(UUID actor,String target,boolean qualified){
        return new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.MINE,target,1,10,UUID.randomUUID().toString(),qualified);
    }
    private static UUID player(EterniaServices s){UUID id=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(id,"QuestTester");return id;}
    @Test void qualifiedCountAndDistinctGoalsPayOnceAcrossRetriesAndRestart(){
        for(var kind:SeasonService.ObjectiveKind.values()){
            Path file=directory.resolve(kind+".data");UUID actor;SeasonService.Activity last;
            try(var store=new LocalFileStore(file)){
                var s=new EterniaServices(store);actor=player(s);s.seasons().register(pass("eternia:test",kind,75L));s.seasons().select(actor,"eternia:test");
                s.seasons().recordActivity(event(actor,"Iron",false));assertEquals(0,s.economy().balance(Owner.player(actor)).available());
                s.seasons().recordActivity(event(actor,"Iron",true));assertEquals(0,s.economy().balance(Owner.player(actor)).available());
                if(kind==SeasonService.ObjectiveKind.DISTINCT){s.seasons().recordActivity(event(actor,"Iron",true));assertEquals(0,s.economy().balance(Owner.player(actor)).available());}
                last=event(actor,"Copper",true);assertTrue(s.seasons().recordActivity(last));assertFalse(s.seasons().recordActivity(last));
                assertTrue(s.seasons().quests(actor,"eternia:test").getFirst().completed());assertEquals(75,s.economy().balance(Owner.player(actor)).available());
                assertEquals(0,s.premium().balance(actor).available());
                assertTrue(s.seasons().pendingQuestNotices(Set.of(),100).isEmpty());
                var notice=s.seasons().pendingQuestNotices(Set.of(actor),100);assertEquals(1,notice.size());
                assertTrue(notice.getFirst().completion());assertEquals(50,notice.getFirst().xp());assertEquals(75,notice.getFirst().coins());
            }
            try(var store=new LocalFileStore(file)){
                var s=new EterniaServices(store);assertFalse(s.seasons().recordActivity(last));s.seasons().recordActivity(event(actor,"Gold",true));s.seasons().quests(actor,"eternia:test");
                assertEquals(75,s.economy().balance(Owner.player(actor)).available());
                var notice=s.seasons().pendingQuestNotices(Set.of(actor),100);assertEquals(1,notice.size());
                assertThrows(DomainException.class,()->s.seasons().acknowledgeQuestNotice(UUID.randomUUID(),notice.getFirst().id()));
                s.seasons().acknowledgeQuestNotice(actor,notice.getFirst().id());s.seasons().acknowledgeQuestNotice(actor,notice.getFirst().id());
                s.seasons().quests(actor,"eternia:test");assertTrue(s.seasons().pendingQuestNotices(Set.of(actor),100).isEmpty());
            }
        }
    }
    @Test void existingCompletedQuestReceivesDefaultCoinsOnceWithoutResettingXp() throws Exception {
        var store=new InMemoryStore();var s=new EterniaServices(store);UUID actor=player(s);var definition=pass("eternia:old",SeasonService.ObjectiveKind.COUNT,null);
        s.seasons().register(definition);s.seasons().select(actor,definition.id());
        store.transaction(tx->{
            var saved=tx.find("season_definition",definition.id()).orElseThrow();var fields=new TreeMap<>(saved.fields());fields.remove("q0.coins");tx.save(saved.namespace(),saved.key(),saved.revision(),fields);
            tx.save("season_objective",DomainSupport.key(actor.toString(),definition.id(),definition.quests().getFirst().id()),0,Map.of("count","2","complete","true"));return null;
        });
        s.seasons().register(definition);assertEquals(0,s.economy().balance(Owner.player(actor)).available());
        try(var workers=Executors.newFixedThreadPool(4)){
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<8;i++)tasks.add(workers.submit(()->s.seasons().quests(actor,definition.id())));for(var task:tasks)task.get();
        }
        s.seasons().register(definition);assertEquals(100,s.economy().balance(Owner.player(actor)).available());assertEquals(0,s.seasons().progress(actor).getFirst().xp());
        var recovered=s.seasons().pendingQuestNotices(Set.of(actor),100);assertEquals(1,recovered.size());assertFalse(recovered.getFirst().completion());assertEquals(0,recovered.getFirst().xp());assertEquals(100,recovered.getFirst().coins());
        assertThrows(DomainException.class,()->s.seasons().register(pass(definition.id(),SeasonService.ObjectiveKind.COUNT,500L)));
    }
    @Test void failedCompletionRollsBackCoinsXpAndReceiptTogether(){
        var durable=new InMemoryStore();var failing=new FailAfterLedger(durable);var s=new EterniaServices(failing);UUID actor=player(s);
        s.seasons().register(pass("eternia:test",SeasonService.ObjectiveKind.COUNT,75L));s.seasons().select(actor,"eternia:test");s.seasons().recordActivity(event(actor,"Iron",true));
        var last=event(actor,"Copper",true);failing.armed=true;assertThrows(IllegalStateException.class,()->s.seasons().recordActivity(last));
        assertEquals(0,s.economy().balance(Owner.player(actor)).available());assertEquals(10,s.seasons().progress(actor).getFirst().xp());assertFalse(s.seasons().quests(actor,"eternia:test").getFirst().completed());assertTrue(s.seasons().pendingQuestNotices(Set.of(actor),100).isEmpty());
        failing.armed=false;assertTrue(s.seasons().recordActivity(last));assertEquals(75,s.economy().balance(Owner.player(actor)).available());assertEquals(70,s.seasons().progress(actor).getFirst().xp());
    }
    @Test void missingJsonCoinsDefaultTo100ButZeroAndNegativeAreExplicit(){
        String json="{\"id\":\"eternia:q\",\"activity\":\"MINE\",\"kind\":\"COUNT\",\"targets\":[],\"required\":1,\"bonusXp\":5}";
        assertEquals(100L,new Gson().fromJson(json,SeasonService.Quest.class).coins());
        assertEquals(0L,pass("eternia:zero",SeasonService.ObjectiveKind.COUNT,0L).quests().getFirst().coins());
        assertThrows(DomainException.class,()->pass("eternia:negative",SeasonService.ObjectiveKind.COUNT,-1L));
    }
    @Test void sameQuestInDifferentPassesAndDifferentPlayersHasSeparateReceipts(){
        var s=new EterniaServices(new InMemoryStore());UUID a=player(s),b=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(b,"SecondTester");
        for(String id:List.of("eternia:a","eternia:b")){
            s.seasons().register(pass(id,SeasonService.ObjectiveKind.COUNT,30L));
            for(UUID actor:List.of(a,b)){s.seasons().select(actor,id);s.seasons().recordActivity(event(actor,"Iron",true));s.seasons().recordActivity(event(actor,"Iron",true));}
        }
        for(int batch=0;batch<2;batch++){var notices=s.seasons().pendingQuestNotices(Set.of(a,b),100);assertEquals(2,notices.size());assertEquals(2,notices.stream().map(SeasonService.QuestNotice::actor).distinct().count());for(var notice:notices)s.seasons().acknowledgeQuestNotice(notice.actor(),notice.id());}
        assertTrue(s.seasons().pendingQuestNotices(Set.of(a,b),100).isEmpty());
        assertEquals(60,s.economy().balance(Owner.player(a)).available());assertEquals(60,s.economy().balance(Owner.player(b)).available());
    }
    private static final class FailAfterLedger implements TransactionalStore {
        final TransactionalStore delegate;boolean armed;
        FailAfterLedger(TransactionalStore delegate){this.delegate=delegate;}
        public <T>T transaction(Function<Transaction,T> work){return delegate.transaction(tx->work.apply(new Transaction(){
            public Optional<Row> find(String ns,String key){return tx.find(ns,key);}
            public List<Row> scan(String ns){return tx.scan(ns);}
            public Row save(String ns,String key,long revision,Map<String,String> fields){var row=tx.save(ns,key,revision,fields);if(armed&&ns.equals("ledger"))throw new IllegalStateException("Simulated interruption");return row;}
        }));}
    }
}
