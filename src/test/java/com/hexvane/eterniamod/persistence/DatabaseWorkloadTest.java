package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseWorkloadTest {
    static class Counted implements TransactionalStore {
        final InMemoryStore inner=new InMemoryStore();int transactions;
        public <T>T transaction(Function<Transaction,T> work){transactions++;return inner.transaction(work);}
    }
    @Test void idleActivityPollsReuseEmptyQueueButNewEventsWakeItAndBatchPaysExactlyOnce(){
        var counted=new Counted();var services=new EterniaServices(counted);var actor=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(actor,"Miner");
        services.activitySources().configureCoinRewards(Map.of(SeasonService.ActivityKind.KILL,Map.of("Skeleton",2L)));
        java.util.function.Supplier<Boolean> pending=()->services.readView("outbox",Duration.ofSeconds(10),()->services.activitySources().hasPending());
        counted.transactions=0;for(int i=0;i<100;i++)assertFalse(pending.get());assertEquals(1,counted.transactions);
        var world=UUID.randomUUID();for(int i=0;i<20;i++)services.activitySources().verifiedDeath(actor,world,UUID.randomUUID(),"Skeleton",10);
        assertTrue(pending.get());counted.transactions=0;
        assertEquals(20,services.batch(()->services.activitySources().drain(25)));assertEquals(1,counted.transactions);
        assertFalse(pending.get());assertEquals(40,services.economy().balance(Owner.player(actor)).available());
        assertEquals(0,services.batch(()->services.activitySources().drain(25)));assertEquals(40,services.economy().balance(Owner.player(actor)).available());
    }
    @Test void cosmeticProjectionIsReadOnlyAndRevokedGrantsInvalidatePetsAndWardrobe(){
        var counted=new Counted();var services=new EterniaServices(counted);var actor=UUID.randomUUID();var owner=Owner.player(actor);
        services.accounts().recordAuthenticatedLogin(actor,"Collector");
        services.collection().register(new CollectionService.Definition("eternia:shirt/test","Shirt",CollectionService.Kind.WEARABLE,"top","Shirt"));
        services.collection().register(new CollectionService.Definition("eternia:pet/test","Pet",CollectionService.Kind.PET,"","Pet"));
        services.ownership().grant(new OwnershipService.GrantRequest("shirt",owner,"eternia:shirt/test",OwnershipService.Kind.UNLOCK,1,null));
        var grant=services.ownership().grant(new OwnershipService.GrantRequest("pet",owner,"eternia:pet/test",OwnershipService.Kind.QUANTITY,1,null));
        services.collection().equip(actor,"eternia:shirt/test");var pet=services.collection().materializePet(owner,grant.id(),0);services.collection().follow(actor,pet.id());
        java.util.function.Supplier<CollectionService.RenderState> render=()->services.readView("renderer",Duration.ofSeconds(10),()->services.collection().renderState(Set.of(actor)));
        counted.transactions=0;for(int i=0;i<100;i++){var state=render.get();assertEquals(1,state.pets().size());assertEquals("eternia:shirt/test",state.selections().get(actor).wearables().get("top"));}assertEquals(1,counted.transactions);
        services.ownership().revokeSource("shirt");services.ownership().revokeSource("pet");
        var revoked=render.get();assertTrue(revoked.pets().isEmpty());assertTrue(revoked.selections().get(actor).wearables().isEmpty());
        assertThrows(DomainException.class,()->services.collection().follow(actor,pet.id()));
    }
}
