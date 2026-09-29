package com.hexvane.eterniamod.premium;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremiumShopSnapshotTest {
    private static PremiumService.Item item(){return new PremiumService.Item("eternia:store/test_lamp",1,"Test Lamp","A lamp.","Furnishings",100,List.of(new CommerceService.Benefit("eternia:prop/test_lamp",OwnershipService.Kind.QUANTITY,1,false)));}
    @Test void retryAfterSuccessfulPurchaseButFailedSnapshotRefreshNeverDebitsTwice(){
        var store=new RefreshFailureStore();var services=new EterniaServices(store);UUID actor=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(actor,"Buyer");services.premium().register(item());services.premium().creditLocalExample(actor,500,"local-playground:snapshot");
        var before=PremiumShopSnapshot.load(services,actor);store.failAfterOrder=true;
        assertThrows(IllegalStateException.class,()->PremiumShopSnapshot.checkout(services,actor,item(),"same-review"));
        assertEquals(400,services.premium().balance(actor).available());
        var retry=PremiumShopSnapshot.checkout(services,actor,item(),"same-review");
        assertEquals(400,retry.snapshot().balance().available());assertEquals(1,services.premium().orders(actor).size());
        assertEquals(1,services.ownership().grants(Owner.player(actor)).stream().filter(grant->grant.contentId().equals("eternia:prop/test_lamp")).count());
        assertEquals(500,before.balance().available(),"A rendered snapshot must stay detached from later transactions");
        assertThrows(UnsupportedOperationException.class,()->retry.snapshot().items().clear());
    }
    @Test void snapshotMarksReusableUnlocksOwnedButLeavesQuantityProductsPurchasable(){
        var services=new EterniaServices(new InMemoryStore());UUID actor=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(actor,"Collector");
        var unlock=new PremiumService.Item("eternia:store/test_title",1,"Test title","A title.","Titles",100,List.of(new CommerceService.Benefit("eternia:title/test",OwnershipService.Kind.UNLOCK,1,false)));
        services.premium().register(item());services.premium().register(unlock);services.premium().creditLocalExample(actor,500,"local-playground:owned");
        PremiumShopSnapshot.checkout(services,actor,item(),"lamp");var snapshot=PremiumShopSnapshot.checkout(services,actor,unlock,"title").snapshot();
        assertTrue(snapshot.owned().contains(unlock.id()));assertFalse(snapshot.owned().contains(item().id()));
    }
    private static final class RefreshFailureStore implements TransactionalStore {
        private final InMemoryStore delegate=new InMemoryStore();private final AtomicBoolean failNextCatalogRead=new AtomicBoolean();boolean failAfterOrder;
        @Override public <T>T transaction(Function<Transaction,T> work){
            var wroteOrder=new AtomicBoolean();T value=delegate.transaction(tx->work.apply(new Transaction(){
                @Override public Optional<Row> find(String ns,String key){return tx.find(ns,key);}
                @Override public List<Row> scan(String ns){if(ns.equals("premium_item")&&failNextCatalogRead.getAndSet(false))throw new IllegalStateException("Simulated post-commit database outage");return tx.scan(ns);}
                @Override public Row save(String ns,String key,long revision,Map<String,String> fields){if(ns.equals("premium_order"))wroteOrder.set(true);return tx.save(ns,key,revision,fields);}
            }));
            if(failAfterOrder&&wroteOrder.get()){failAfterOrder=false;failNextCatalogRead.set(true);}return value;
        }
    }
}
