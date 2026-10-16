package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.premium.PremiumShopSnapshot;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BatchedStoreTest {
    @Test void crownCatalogUsesOneTransactionAndOneOwnershipScanRegardlessOfItemCount() {
        var counted=new CountingStore();var services=new EterniaServices(counted);var actor=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(actor,"Collector");
        for(int i=0;i<40;i++)services.premium().register(new PremiumService.Item("eternia:store/title_"+i,1,
            "Title "+i,"A title.","Titles",100,List.of(new CommerceService.Benefit("eternia:title/"+i,OwnershipService.Kind.UNLOCK,1,false))));
        services.ownership().grant(new OwnershipService.GrantRequest("first",Owner.player(actor),"eternia:title/0",OwnershipService.Kind.UNLOCK,1,null));
        counted.transactions=counted.scans=counted.finds=0;
        var snapshot=PremiumShopSnapshot.load(services,actor);
        assertEquals(40,snapshot.items().size());assertEquals(Set.of("eternia:store/title_0"),snapshot.owned());
        assertEquals(1,counted.transactions);
        assertEquals(2,counted.scans,"Catalog and grants each load once, not once per item");
        assertEquals(1,counted.finds,"One Crown balance lookup");
        services.ownership().grant(new OwnershipService.GrantRequest("second",Owner.player(actor),"eternia:title/1",OwnershipService.Kind.UNLOCK,1,null));
        assertEquals(2,PremiumShopSnapshot.load(services,actor).owned().size(),"A new page must see new grants");
    }

    @Test void cachedReadsSeeWritesAndDoNotEscapeTheBatch() {
        var counted=new CountingStore();var store=new BatchedStore(counted);
        var escaped=new AtomicReference<TransactionalStore.Transaction>();
        store.batch(()->{
            assertTrue(store.transaction(tx->tx.find("test","a")).isEmpty());
            assertTrue(store.transaction(tx->tx.find("test","a")).isEmpty());
            assertEquals(1,counted.finds);
            store.transaction(tx->tx.save("test","a",0,Map.of("value","old")));
            assertEquals("old",store.transaction(tx->tx.find("test","a").orElseThrow()).value("value"));
            assertEquals(1,store.transaction(tx->tx.scan("test")).size());
            store.transaction(tx->tx.save("test","a",1,Map.of("value","new")));
            store.transaction(tx->tx.save("test","b",0,Map.of("value","second")));
            var rows=store.transaction(tx->tx.scan("test"));
            assertEquals(List.of("new","second"),rows.stream().map(r->r.value("value")).toList());
            assertEquals(1,counted.scans);
            store.transaction(tx->{escaped.set(tx);return null;});
            assertThrows(IllegalStateException.class,()->escaped.get().scan("test"));
            return null;
        });
        assertEquals(1,counted.transactions);
        assertEquals("new",store.transaction(tx->tx.find("test","a").orElseThrow()).value("value"));
        assertThrows(IllegalStateException.class,()->escaped.get().scan("test"));
    }

    @Test void caughtServiceFailureStillRollsBackEverythingAndNextRequestRecovers() {
        var store=new BatchedStore(new InMemoryStore());
        assertThrows(IllegalStateException.class,()->store.batch(()->{
            store.transaction(tx->tx.save("test","first",0,Map.of()));
            assertThrows(IllegalArgumentException.class,()->store.transaction(tx->{
                tx.save("test","second",0,Map.of());throw new IllegalArgumentException("Fail");
            }));
            return null;
        }));
        assertTrue(store.transaction(tx->tx.scan("test")).isEmpty());
        store.batch(()->store.transaction(tx->tx.save("test","success",0,Map.of())));
        assertEquals(1,store.transaction(tx->tx.scan("test")).size());
    }

    @Test void nestedTransactionCallbacksRemainForbidden() {
        var store=new BatchedStore(new InMemoryStore());
        assertThrows(IllegalStateException.class,()->store.batch(()->store.transaction(tx->store.transaction(other->null))));
        assertThrows(IllegalStateException.class,()->store.batch(()->store.transaction(tx->store.batch(()->null))));
        assertEquals("ok",store.batch(()->store.batch(()->"ok")));
    }

    @Test void bulkReadsCacheMissesAndFollowLaterWrites() {
        var counted=new CountingStore();var store=new BatchedStore(counted);
        store.transaction(tx->tx.save("test","a",0,Map.of("value","first")));
        store.batch(()->{
            assertEquals(Set.of("a"),store.transaction(tx->tx.findAll("test",List.of("a","b","a"))).keySet());
            int reads=counted.finds;
            assertTrue(store.transaction(tx->tx.find("test","b")).isEmpty());
            store.transaction(tx->tx.findAll("test",List.of("b","a")));
            assertEquals(reads,counted.finds);
            store.transaction(tx->tx.save("test","b",0,Map.of("value","second")));
            assertEquals(2,store.transaction(tx->tx.findAll("test",List.of("a","b"))).size());
            assertEquals(reads,counted.finds);
            return null;
        });
    }

    @Test void bulkRewardClaimsMatchIndividualClaimsAndAreScopedToPlayer() {
        var services=new EterniaServices(new InMemoryStore());var actor=UUID.randomUUID();var other=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(actor,"Collector");
        services.accounts().recordAuthenticatedLogin(other,"Other");
        var reward=new SeasonService.Reward(SeasonService.Track.FREE,1,0,"eternia:prop/test",OwnershipService.Kind.QUANTITY,1);
        var season=new SeasonService.Definition("eternia:test","Test",List.of(1L),List.of(reward),List.of());
        services.seasons().register(season);services.seasons().select(actor,season.id());
        services.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.KILL,"Skeleton",1,1,"test-source",true));
        assertTrue(services.seasons().claimedRewards(actor,season).isEmpty());
        services.seasons().claimReward(actor,season.id(),reward.track(),reward.level(),reward.index());
        assertEquals(Set.of(reward),services.batch(()->services.seasons().claimedRewards(actor,season)));
        assertTrue(services.seasons().isClaimed(actor,season.id(),reward.track(),reward.level(),reward.index()));
        assertTrue(services.seasons().claimedRewards(other,season).isEmpty());
    }

    private static final class CountingStore implements TransactionalStore {
        final InMemoryStore delegate=new InMemoryStore();int transactions,scans,finds;
        public <T>T transaction(Function<Transaction,T> work) {
            transactions++;
            return delegate.transaction(tx->work.apply(new Transaction(){
                public Optional<Row> find(String ns,String key){finds++;return tx.find(ns,key);}
                public List<Row> scan(String ns){scans++;return tx.scan(ns);}
                public Row save(String ns,String key,long revision,Map<String,String> fields){return tx.save(ns,key,revision,fields);}
            }));
        }
    }
}
