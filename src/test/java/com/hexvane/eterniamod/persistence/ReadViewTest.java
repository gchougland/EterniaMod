package com.hexvane.eterniamod.persistence;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadViewTest {
    private final BatchedStore store=new BatchedStore(new InMemoryStore());
    private final AtomicInteger reads=new AtomicInteger();
    private List<TransactionalStore.Row> read(){reads.incrementAndGet();return store.transaction(tx->tx.scan("test"));}
    private List<TransactionalStore.Row> view(Object key){return store.readView(key,Duration.ofSeconds(30),this::read);}
    @Test void emptyResultsAreCachedAndRelevantCommittedWritesInvalidate(){
        for(int i=0;i<100;i++)assertTrue(view("page").isEmpty());
        assertEquals(1,reads.get());
        store.transaction(tx->tx.save("other","a",0,Map.of()));view("page");assertEquals(1,reads.get());
        store.transaction(tx->tx.save("test","a",0,Map.of()));assertEquals(1,view("page").size());assertEquals(2,reads.get());
    }
    @Test void failedMutationsAndReadFailuresDoNotPoisonCache(){
        view("page");
        assertThrows(IllegalStateException.class,()->store.transaction(tx->{tx.save("test","a",0,Map.of());throw new IllegalStateException();}));
        assertTrue(view("page").isEmpty());assertEquals(1,reads.get());
        assertThrows(IllegalStateException.class,()->store.readView("failure",Duration.ofSeconds(1),()->{throw new IllegalStateException();}));
        assertTrue(view("failure").isEmpty());assertEquals(2,reads.get());
    }
    @Test void cachedViewsCannotWriteAndActionsBypassCachedResults(){
        view("page");
        assertThrows(IllegalStateException.class,()->store.readView("write",Duration.ofSeconds(1),()->store.transaction(tx->tx.save("test","a",0,Map.of()))));
        store.batch(()->{assertTrue(view("page").isEmpty());store.transaction(tx->tx.save("test","a",0,Map.of()));assertEquals(1,view("page").size());return null;});
        assertEquals(3,reads.get());assertEquals(1,view("page").size());
    }
    @Test void keysSeparatePlayersAndExpiryRefreshesExternalChanges() throws Exception {
        var delegate=new InMemoryStore();var cached=new BatchedStore(delegate);
        var first=UUID.randomUUID();var second=UUID.randomUUID();
        assertEquals(first,cached.readView(first,Duration.ofSeconds(1),()->first));
        assertEquals(second,cached.readView(second,Duration.ofSeconds(1),()->second));
        cached.readView("ttl",Duration.ofMillis(1),()->cached.transaction(tx->tx.scan("test")));
        delegate.transaction(tx->tx.save("test","external",0,Map.of()));
        Thread.sleep(5);
        assertEquals(1,cached.readView("ttl",Duration.ofMillis(1),()->cached.transaction(tx->tx.scan("test"))).size());
    }
    @Test void concurrentReadersShareLoad() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var first=executor.submit(()->store.readView("same",Duration.ofSeconds(30),()->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return read();}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second=executor.submit(()->view("same"));release.countDown();
            assertEquals(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));assertEquals(1,reads.get());
        }
    }
    @Test void only512KeysAreRetained(){
        for(int i=0;i<513;i++)view(i);
        view(512);assertEquals(513,reads.get());view(0);assertEquals(514,reads.get());
    }
}
