package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IngressInvariantTest {
    private final InMemoryStore store=new InMemoryStore();
    private final java.time.Instant now=java.time.Instant.parse("2026-09-12T12:00:00Z");
    private final EterniaServices services=new EterniaServices(store,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC),UUID::randomUUID);
    private UUID account(String name){UUID id=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(id,name);return id;}
    private void product(){services.commerce().register(new CommerceService.Product("123",1,"Move",List.of(new CommerceService.Benefit("eternia:move",OwnershipService.Kind.QUANTITY,1,false)),false));}
    private CommerceService.VerifiedPurchase payment(UUID recipient,String transaction){return new CommerceService.VerifiedPurchase("tebex",transaction,"123","123",1,Owner.player(recipient),2,"",null,null);}
    @Test void eitherProviderArrivalOrderRequiresBothHalvesAndRetryNeverDuplicates(){
        UUID player=account("Mira");product();services.commerceIngress().stageVerifiedLine(payment(player,"first"));assertEquals(0,services.commerceIngress().reconcile(10));
        services.commerceIngress().expectConsole("first","123",player,2,1);assertEquals(1,services.commerceIngress().reconcile(10));assertEquals(0,services.commerceIngress().reconcile(10));
        services.commerceIngress().expectConsole("second","123",player,2,1);assertEquals(0,services.commerceIngress().reconcile(10));services.commerceIngress().stageVerifiedLine(payment(player,"second"));services.commerceIngress().reconcile(10);
        assertEquals(4,services.ownership().available(Owner.player(player),"eternia:move"));
    }
    @Test void consoleCannotChangeVerifiedRecipientOrQuantity(){
        UUID player=account("Mira"),other=account("Other");product();services.commerceIngress().stageVerifiedLine(payment(player,"first"));services.commerceIngress().expectConsole("first","123",other,2,1);
        services.commerceIngress().reconcile(10);assertEquals("CONSOLE_MISMATCH",services.commerceIngress().reviews().getFirst().reason());assertEquals(0,services.ownership().available(Owner.player(other),"eternia:move"));
    }
    @Test void reversalBeforeCompletionAndRepeatedDisputeNeverGrants(){
        UUID player=account("Mira");product();services.commerceIngress().reverseTransaction("first","payment.refunded");services.commerceIngress().stageVerifiedLine(payment(player,"first"));services.commerceIngress().expectConsole("first","123",player,2,1);services.commerceIngress().reconcile(10);
        assertEquals(0,services.ownership().available(Owner.player(player),"eternia:move"));
        services.commerceIngress().stageVerifiedLine(payment(player,"second"));services.commerceIngress().expectConsole("second","123",player,2,1);services.commerceIngress().reconcile(10);
        services.commerceIngress().reverseTransaction("second","payment.dispute.opened");services.commerceIngress().reverseTransaction("second","payment.dispute.lost");assertEquals(0,services.ownership().available(Owner.player(player),"eternia:move"));
    }
    @Test void concurrentReconcilersGrantOnePurchase()throws Exception{
        UUID player=account("Mira");product();services.commerceIngress().stageVerifiedLine(payment(player,"first"));services.commerceIngress().expectConsole("first","123",player,2,1);
        try(var pool=Executors.newFixedThreadPool(4)){var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->services.commerceIngress().reconcile(10)));for(var task:tasks)task.get();}
        assertEquals(2,services.ownership().available(Owner.player(player),"eternia:move"));
    }
    @Test void placedBlocksAndSourceReplayCannotFarmXpAfterServiceRestart(){
        UUID player=account("Mira"),other=account("Other"),world=UUID.randomUUID();var position=new ActivitySourceService.Block(world,1,2,3);services.activitySources().markPlayerPlacement(position);
        var restarted=new EterniaServices(store);assertFalse(restarted.activitySources().verifiedNaturalBreak(player,position,"Ore_Iron",5));
        var natural=new ActivitySourceService.Block(world,2,2,3);assertTrue(restarted.activitySources().verifiedNaturalBreak(player,natural,"Ore_Iron",5));assertFalse(restarted.activitySources().verifiedNaturalBreak(other,natural,"Ore_Iron",5));
        UUID mob=UUID.randomUUID();assertTrue(restarted.activitySources().verifiedDeath(player,world,mob,"Skeleton",10));assertFalse(restarted.activitySources().verifiedDeath(other,world,mob,"Skeleton",10));
        assertEquals(2,restarted.activitySources().drain(100));assertEquals(0,restarted.activitySources().drain(100));var stats=restarted.accounts().stats(player).orElseThrow();assertEquals(1L,stats.mobsDefeated());assertEquals(1L,stats.resourcesGathered());
    }
    @Test void aCropGenerationCanBeClaimedOnlyOnceEvenByAnotherPlayer(){
        UUID first=account("Mira"),second=account("Other");var block=new ActivitySourceService.Block(UUID.randomUUID(),10,50,10);
        assertTrue(services.activitySources().verifiedHarvest(first,block,"Plant_Crop",4,5));assertFalse(services.activitySources().verifiedHarvest(second,block,"Plant_Crop",4,5));assertTrue(services.activitySources().verifiedHarvest(first,block,"Plant_Crop",5,5));
        services.activitySources().drain(100);assertEquals(2L,services.accounts().stats(first).orElseThrow().resourcesGathered());
    }
    @Test void delayedSubscriptionPurchaseKeepsGiftButCannotReopenEndedAccess(){
        UUID player=account("Mira");services.commerce().register(new CommerceService.Product("456",1,"Supporter",List.of(new CommerceService.Benefit("eternia:rank",OwnershipService.Kind.CAPABILITY,1,true),new CommerceService.Benefit("eternia:gift",OwnershipService.Kind.QUANTITY,1,false)),true));
        var start=now.minusSeconds(1000);var end=now.minusSeconds(500);
        services.commerceIngress().stageSubscriptionControl("sub","ENDED",end,"event-end");
        services.commerceIngress().stageVerifiedLine(new CommerceService.VerifiedPurchase("tebex","late","456","456",1,Owner.player(player),1,"sub",start,end.plusSeconds(10000)));services.commerceIngress().expectConsole("late","456",player,1,1);services.commerceIngress().reconcile(10);
        assertFalse(services.ownership().owns(Owner.player(player),"eternia:rank"));assertEquals(1,services.ownership().available(Owner.player(player),"eternia:gift"));assertEquals("ENDED",services.commerce().subscription("tebex","sub").orElseThrow().state());
    }
    @Test void reversalBetweenJoinDecisionAndGrantSurvivesCrashBeforeQueueAcknowledgement(){
        var interleaving=new InterleavingStore();var game=new EterniaServices(interleaving);UUID player=UUID.randomUUID();game.accounts().recordAuthenticatedLogin(player,"Mira");
        game.commerce().register(new CommerceService.Product("123",1,"Chair",List.of(new CommerceService.Benefit("eternia:chair",OwnershipService.Kind.QUANTITY,1,false)),false));
        game.commerceIngress().expectConsole("race","123",player,2,1);game.commerceIngress().stageVerifiedLine(payment(player,"race"));
        interleaving.afterReady=()->game.commerceIngress().reverseTransaction("race","payment.refunded");interleaving.crashAfterPurchase=true;
        assertThrows(IllegalStateException.class,()->game.commerceIngress().reconcile(10));assertEquals(0,game.ownership().available(Owner.player(player),"eternia:chair"));
        new EterniaServices(interleaving).commerceIngress().reconcile(10);assertEquals(0,game.ownership().available(Owner.player(player),"eternia:chair"));
    }
    @Test void oldBillingPeriodCannotClearNewCancellationAndEachPackageGetsItsBenefits(){
        UUID player=account("Mira");for(String pkg:List.of("456","789"))services.commerce().register(new CommerceService.Product(pkg,1,pkg,List.of(new CommerceService.Benefit("eternia:gift/"+pkg,OwnershipService.Kind.QUANTITY,1,false)),true));
        for(String pkg:List.of("456","789"))services.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","current",pkg,pkg,1,Owner.player(player),1,"sub",now.minusSeconds(100),now.plusSeconds(1000)));
        services.commerce().applySubscriptionControl("tebex","sub","CANCEL_REQUESTED",now,"cancel");
        services.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","old","456","456",1,Owner.player(player),1,"sub",now.minusSeconds(200),now.minusSeconds(100)));
        assertTrue(services.commerce().subscription("tebex","sub").orElseThrow().cancellationRequested());assertEquals(1,services.ownership().available(Owner.player(player),"eternia:gift/789"));assertEquals(2,services.ownership().available(Owner.player(player),"eternia:gift/456"));
    }
    private static final class InterleavingStore implements com.hexvane.eterniamod.persistence.TransactionalStore {
        private final InMemoryStore delegate=new InMemoryStore();Runnable afterReady;boolean crashAfterPurchase;
        public <T>T transaction(java.util.function.Function<Transaction,T> work){T result=delegate.transaction(work);
            if("READY".equals(result)&&afterReady!=null){var hook=afterReady;afterReady=null;hook.run();}
            if(result instanceof CommerceService.Purchase&&crashAfterPurchase){crashAfterPurchase=false;throw new IllegalStateException("Simulated crash after purchase commit");}return result;}
    }
}
