package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActivityCoinsTest {
    @TempDir Path directory;
    private static final String MOB="Skeleton",ORE="Ore_Iron",CROP="Plant_Crop";
    private UUID account(EterniaServices services){UUID player=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(player,"Explorer");return player;}
    private void rewards(EterniaServices services,long kill,long mine,long harvest){services.activitySources().configureCoinRewards(Map.of(SeasonService.ActivityKind.KILL,Map.of(MOB,kill),SeasonService.ActivityKind.MINE,Map.of(ORE,mine),SeasonService.ActivityKind.HARVEST,Map.of(CROP,harvest)));}
    @Test void duplicatedSourcesAndConcurrentDrainsPayEachQualifiedActivityOnce()throws Exception{
        var services=new EterniaServices(new InMemoryStore());UUID player=account(services),world=UUID.randomUUID(),mob=UUID.randomUUID();rewards(services,2,3,4);
        var mined=new ActivitySourceService.Block(world,1,80,1);var harvested=new ActivitySourceService.Block(world,2,80,1);
        assertTrue(services.activitySources().verifiedDeath(player,world,mob,MOB,10));assertFalse(services.activitySources().verifiedDeath(player,world,mob,MOB,10));
        assertTrue(services.activitySources().verifiedNaturalBreak(player,mined,ORE,5));assertFalse(services.activitySources().verifiedNaturalBreak(player,mined,ORE,5));
        assertTrue(services.activitySources().verifiedHarvest(player,harvested,CROP,7,5));assertFalse(services.activitySources().verifiedHarvest(player,harvested,CROP,7,5));
        try(var pool=Executors.newFixedThreadPool(4)){var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->services.activitySources().drain(100)));for(var task:tasks)task.get();}
        assertEquals(9,services.economy().balance(Owner.player(player)).available());assertEquals(0,services.activitySources().drain(100));
        var stats=services.accounts().stats(player).orElseThrow();assertEquals(1L,stats.mobsDefeated());assertEquals(2L,stats.resourcesGathered());
    }
    @Test void queuedRewardSurvivesRestartAndConfigurationChanges(){
        Path file=directory.resolve("rewards.data");UUID player,world=UUID.randomUUID(),mob=UUID.randomUUID();
        try(var store=new LocalFileStore(file)){var services=new EterniaServices(store);player=account(services);rewards(services,3,0,0);services.activitySources().verifiedDeath(player,world,mob,MOB,10);}
        try(var store=new LocalFileStore(file)){var services=new EterniaServices(store);rewards(services,99,0,0);assertEquals(1,services.activitySources().drain(1));assertEquals(3,services.economy().balance(Owner.player(player)).available());
            assertFalse(services.activitySources().verifiedDeath(player,world,mob,MOB,10));services.activitySources().verifiedDeath(player,world,UUID.randomUUID(),MOB,10);services.activitySources().drain(1);assertEquals(102,services.economy().balance(Owner.player(player)).available());}
    }
    @Test void crashAfterXpOrAfterCoinCommitRecoversWithoutLostOrDuplicatedCoins(){
        for(String cut:List.of("activity_event","ledger")){
            Path file=directory.resolve(cut+".data");UUID player,world=UUID.randomUUID();
            try(var durable=new LocalFileStore(file)){
                var interrupted=new CrashAfterCommit(durable,cut);var services=new EterniaServices(interrupted);player=account(services);rewards(services,7,0,0);services.activitySources().verifiedDeath(player,world,UUID.randomUUID(),MOB,10);interrupted.armed=true;
                assertThrows(IllegalStateException.class,()->services.activitySources().drain(1));assertEquals(cut.equals("ledger")?7:0,services.economy().balance(Owner.player(player)).available());
                assertEquals("READY",durable.transaction(tx->tx.scan("activity_outbox").getFirst().value("state")));
            }
            try(var durable=new LocalFileStore(file)){
                var restarted=new EterniaServices(durable);rewards(restarted,999,0,0);assertEquals(1,restarted.activitySources().drain(1));assertEquals(7,restarted.economy().balance(Owner.player(player)).available());assertEquals(0,restarted.activitySources().drain(1));assertEquals(1L,restarted.accounts().stats(player).orElseThrow().mobsDefeated());
            }
        }
    }
    @Test void placedAndAlreadyMinedPositionsCannotMintAfterRestart(){
        Path file=directory.resolve("terrain.data");UUID player,world=UUID.randomUUID();var placed=new ActivitySourceService.Block(world,1,80,1);var natural=new ActivitySourceService.Block(world,2,80,1);
        try(var store=new LocalFileStore(file)){var services=new EterniaServices(store);player=account(services);rewards(services,0,5,0);services.activitySources().markPlayerPlacement(placed);assertFalse(services.activitySources().verifiedNaturalBreak(player,placed,ORE,5));assertTrue(services.activitySources().verifiedNaturalBreak(player,natural,ORE,5));services.activitySources().drain(100);}
        try(var store=new LocalFileStore(file)){var services=new EterniaServices(store);rewards(services,0,500,0);assertFalse(services.activitySources().verifiedNaturalBreak(player,placed,ORE,5));assertFalse(services.activitySources().verifiedNaturalBreak(player,natural,ORE,5));assertEquals(0,services.activitySources().drain(100));assertEquals(5,services.economy().balance(Owner.player(player)).available());}
    }
    @Test void legacyOutboxWithoutCoinAmountDoesNotGainNewConfiguredRewards(){
        var store=new InMemoryStore();var services=new EterniaServices(store);UUID player=account(services);services.activitySources().verifiedDeath(player,UUID.randomUUID(),UUID.randomUUID(),MOB,5);
        store.transaction(tx->{var row=tx.scan("activity_outbox").getFirst();var data=new HashMap<>(row.fields());data.remove("coins");tx.save(row.namespace(),row.key(),row.revision(),data);return null;});
        rewards(services,100,0,0);services.activitySources().drain(100);assertEquals(0,services.economy().balance(Owner.player(player)).available());assertEquals(1L,services.accounts().stats(player).orElseThrow().mobsDefeated());
    }
    private static final class CrashAfterCommit implements TransactionalStore {
        final TransactionalStore delegate;final String namespace;boolean armed;
        CrashAfterCommit(TransactionalStore delegate,String namespace){this.delegate=delegate;this.namespace=namespace;}
        public <T>T transaction(Function<Transaction,T> work){boolean[] hit={false};T result=delegate.transaction(tx->work.apply(new Transaction(){
            public Optional<Row> find(String ns,String key){return tx.find(ns,key);}public List<Row> scan(String ns){return tx.scan(ns);}
            public Row save(String ns,String key,long revision,Map<String,String> fields){if(ns.equals(namespace))hit[0]=true;return tx.save(ns,key,revision,fields);}
        }));if(armed&&hit[0]){armed=false;throw new IllegalStateException("Injected crash after "+namespace+" committed");}return result;}
    }
}
