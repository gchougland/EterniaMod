package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DomainInvariantTest {
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-09-12T12:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
        void advance(Duration d){now=now.plus(d);}
    }
    final MutableClock clock=new MutableClock();
    final EterniaServices services=new EterniaServices(new InMemoryStore(),clock,UUID::randomUUID);
    UUID account(String name){UUID id=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(id,name);return id;}
    void coins(UUID player,long amount){services.economy().credit(Owner.player(player),amount,"mint:"+player);}
    OwnershipService.Grant quantity(UUID player,String content,long amount,String receipt){return services.ownership().grant(new OwnershipService.GrantRequest(receipt,Owner.player(player),content,OwnershipService.Kind.QUANTITY,amount,null));}
    String item(UUID player,long count,boolean transferable){var r=services.escrow().prepareDeposit(Owner.player(player),new EscrowService.Item("Ore_Iron",count,Base64.getEncoder().encodeToString(new byte[]{1,2,3,4}),transferable),"deposit:"+UUID.randomUUID());services.escrow().acknowledgeNativeRemoval(r.id(),"native:"+r.id());return r.id();}
    HousingService.Slot home(UUID player,int x){UUID property=UUID.randomUUID(),op=UUID.randomUUID();var slot=services.housing().beginClaim(Owner.player(player),property,op,null,new HousingService.ClaimLocation("housing",x,0,24,24,HousingService.ClaimScope.PUBLIC,null,true));var journal=services.journal().find(op).orElseThrow();services.journal().advance(op,journal.revision(),JournalService.State.WORLD_APPLIED);return services.housing().activate(Owner.player(player),op);}

    @Test void duplicateGrantAndRevocationPreserveOtherSources(){
        UUID player=account("Mira");var original=quantity(player,"eternia:chair",3,"drop:one");assertEquals(original,quantity(player,"eternia:chair",3,"drop:one"));
        assertThrows(DomainException.class,()->quantity(player,"eternia:chair",4,"drop:one"));quantity(player,"eternia:chair",2,"pass:one");
        services.ownership().revokeSource("drop:one");assertEquals(2,services.ownership().available(Owner.player(player),"eternia:chair"));
    }
    @Test void concurrentQuantityReservationsHaveOneWinner()throws Exception{
        UUID player=account("Mira");quantity(player,"eternia:plot_move_credit",1,"starter");AtomicInteger wins=new AtomicInteger();CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(8)){var tasks=new ArrayList<Future<?>>();for(int i=0;i<8;i++){int n=i;tasks.add(pool.submit(()->{try{start.await();services.ownership().reserve(Owner.player(player),"eternia:plot_move_credit",1,"move:"+n);wins.incrementAndGet();}catch(DomainException e){assertEquals(DomainException.Code.INSUFFICIENT_BALANCE,e.code());}catch(InterruptedException e){throw new RuntimeException(e);}}));}start.countDown();for(var task:tasks)task.get();}
        assertEquals(1,wins.get());assertEquals(0,services.ownership().available(Owner.player(player),"eternia:plot_move_credit"));
    }
    @Test void concurrentLastItemCheckoutTransfersExactlyOnce()throws Exception{
        UUID seller=account("Seller"),a=account("Alice"),b=account("Bob");home(seller,0);coins(a,20);coins(b,20);var listing=services.market().list(seller,item(seller,1,true),10,"listing");
        AtomicInteger wins=new AtomicInteger();try(var pool=Executors.newFixedThreadPool(2)){var tasks=List.of(a,b).stream().map(buyer->pool.submit(()->{try{services.market().buy(buyer,listing.id(),1,listing.revision(),"buy:"+buyer);wins.incrementAndGet();}catch(DomainException e){assertTrue(Set.of(DomainException.Code.CONFLICT,DomainException.Code.INVALID_STATE).contains(e.code()));}})).toList();for(var task:tasks)task.get();}
        assertEquals(1,wins.get());assertEquals(10,services.economy().balance(Owner.player(seller)).available());
        assertEquals(30,services.economy().balance(Owner.player(a)).available()+services.economy().balance(Owner.player(b)).available());
        assertEquals(1,services.escrow().deliveries(Owner.player(a)).size()+services.escrow().deliveries(Owner.player(b)).size());
    }
    @Test void fullMailboxDoesNotTakeTheSendersAttachment(){
        UUID sender=account("Sender"),recipient=account("Recipient");for(int i=0;i<20;i++)services.mail().send(sender,recipient,"Hello","Letter",List.of(),"mail:"+i);
        String item=item(sender,5,true);var error=assertThrows(DomainException.class,()->services.mail().send(sender,recipient,"Parcel","Items",List.of(item),"overflow"));assertEquals(DomainException.Code.CAPACITY,error.code());assertEquals("AVAILABLE",services.escrow().find(item).orElseThrow().state());
        var first=services.mail().inbox(recipient).getFirst();services.mail().archive(recipient,first.id());var mail=services.mail().send(sender,recipient,"Parcel","Items",List.of(item),"overflow");
        services.mail().claim(recipient,mail.id());services.mail().claim(recipient,mail.id());assertEquals(1,services.escrow().deliveries(Owner.player(recipient)).size());assertEquals(5,services.escrow().deliveries(Owner.player(recipient)).getFirst().item().quantity());
    }
    @Test void uncertainNativeHandoffRemainsReserved(){
        UUID player=account("Mira");var delivery=services.escrow().withdraw(Owner.player(player),item(player,3,true),"withdraw");UUID attempt=UUID.randomUUID();
        services.escrow().beginNativeDelivery(Owner.player(player),delivery.id(),attempt);services.escrow().resolveNativeDelivery(delivery.id(),attempt,EscrowService.NativeOutcome.UNKNOWN);
        assertThrows(DomainException.class,()->services.escrow().beginNativeDelivery(Owner.player(player),delivery.id(),UUID.randomUUID()));
        services.escrow().resolveNativeDelivery(delivery.id(),attempt,EscrowService.NativeOutcome.INSERTED);
        assertEquals("DELIVERED",services.escrow().resolveNativeDelivery(delivery.id(),attempt,EscrowService.NativeOutcome.INSERTED).state());
    }
    @Test void editedTradeInvalidatesBothConfirmationsAndCancellationRestoresCustody(){
        UUID a=account("Alice"),b=account("Bob");coins(a,50);String item=item(b,2,true);var trade=services.trades().open(a,b);
        trade=services.trades().offer(a,trade.id(),trade.offerRevision(),new TradeService.Offer(List.of(),20));trade=services.trades().offer(b,trade.id(),trade.offerRevision(),new TradeService.Offer(List.of(item),0));
        trade=services.trades().confirm(a,trade.id(),trade.offerRevision());assertTrue(trade.firstConfirmed());long prior=trade.offerRevision();
        trade=services.trades().offer(b,trade.id(),trade.offerRevision(),new TradeService.Offer(List.of(item),0));assertFalse(trade.firstConfirmed());assertFalse(trade.secondConfirmed());UUID id=trade.id();
        assertThrows(DomainException.class,()->services.trades().confirm(a,id,prior));services.trades().cancel(b,id);
        assertEquals(50,services.economy().balance(Owner.player(a)).available());assertEquals(0,services.economy().balance(Owner.player(a)).reserved());assertEquals("AVAILABLE",services.escrow().find(item).orElseThrow().state());
    }
    @Test void settledTradeIsAtomicAndMetadataIsPreserved(){
        UUID a=account("Alice"),b=account("Bob");coins(a,50);String item=item(b,2,true);var trade=services.trades().open(a,b);
        trade=services.trades().offer(a,trade.id(),0,new TradeService.Offer(List.of(),20));trade=services.trades().offer(b,trade.id(),trade.offerRevision(),new TradeService.Offer(List.of(item),0));
        services.trades().confirm(a,trade.id(),trade.offerRevision());trade=services.trades().confirm(b,trade.id(),trade.offerRevision());assertEquals("COMPLETED",trade.state());
        assertEquals(30,services.economy().balance(Owner.player(a)).available());assertEquals(20,services.economy().balance(Owner.player(b)).available());
        var delivery=services.escrow().deliveries(Owner.player(a)).getFirst();assertArrayEquals(new byte[]{1,2,3,4},Base64.getDecoder().decode(delivery.item().metadataBase64()));
    }
    @Test void oldSeasonsAndLatePaidClaimsSurviveSwitchesAndEventReplays(){
        UUID player=account("Mira");String season="eternia:founders";
        services.seasons().register(new SeasonService.Definition(season,"Founders",List.of(100L),List.of(new SeasonService.Reward(SeasonService.Track.FREE,1,0,"eternia:chair",OwnershipService.Kind.QUANTITY,1),new SeasonService.Reward(SeasonService.Track.PAID,1,0,"eternia:title",OwnershipService.Kind.UNLOCK,1)),List.of(new SeasonService.Quest("eternia:variety",SeasonService.ActivityKind.MINE,SeasonService.ObjectiveKind.DISTINCT,Set.of(),2,50))));
        services.seasons().select(player,season);
        var e=new SeasonService.Activity(UUID.randomUUID(),player,SeasonService.ActivityKind.MINE,"Iron",10,25,"natural:1",true);
        assertTrue(services.seasons().recordActivity(e));assertFalse(services.seasons().recordActivity(e));assertFalse(services.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),player,e.kind(),e.targetId(),e.quantity(),e.xp(),e.sourceInstance(),true)));
        services.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),player,e.kind(),"Iron",1,25,"natural:2",true));services.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),player,e.kind(),"Copper",1,25,"natural:3",true));
        assertEquals(125,services.seasons().progress(player).getFirst().xp());services.seasons().claimReward(player,season,SeasonService.Track.FREE,1,0);services.seasons().claimReward(player,season,SeasonService.Track.FREE,1,0);assertEquals(1,services.ownership().available(Owner.player(player),"eternia:chair"));
        assertThrows(DomainException.class,()->services.seasons().claimReward(player,season,SeasonService.Track.PAID,1,0));
        services.ownership().grant(new OwnershipService.GrantRequest("paid",Owner.player(player),SeasonService.paidEntitlementId(season),OwnershipService.Kind.CAPABILITY,1,null));services.seasons().claimReward(player,season,SeasonService.Track.PAID,1,0);
        clock.advance(Duration.ofDays(1000));assertEquals(125,services.seasons().progress(player).getFirst().xp());assertTrue(services.ownership().owns(Owner.player(player),"eternia:title"));assertEquals(12,services.accounts().stats(player).orElseThrow().resourcesGathered());
    }
    @Test void claimReplayCannotMoveItsOriginalRectangle(){
        UUID player=account("Mira"),property=UUID.randomUUID(),operation=UUID.randomUUID();Owner owner=Owner.player(player);
        var location=new HousingService.ClaimLocation("housing",0,0,24,24,HousingService.ClaimScope.PUBLIC,null,false);services.housing().beginClaim(owner,property,operation,null,location);
        assertThrows(DomainException.class,()->services.housing().beginClaim(owner,property,operation,null,new HousingService.ClaimLocation("housing",1,0,24,24,HousingService.ClaimScope.PUBLIC,null,false)));
        UUID other=account("Other");assertThrows(DomainException.class,()->services.housing().beginClaim(Owner.player(other),UUID.randomUUID(),UUID.randomUUID(),null,location));assertTrue(services.housing().find(Owner.player(other)).isEmpty());
    }
    @Test void packedSlotCannotMintAnotherHomeOrMoveCredit(){
        UUID player=account("Mira");Owner owner=Owner.player(player);home(player,0);quantity(player,HousingService.MOVE_CREDIT,1,"starter-move");UUID operation=UUID.randomUUID();services.housing().beginRelocation(owner,operation,false);
        var op=services.journal().find(operation).orElseThrow();op=services.journal().attachVerifiedSnapshot(operation,op.revision(),"snapshots/one","a".repeat(64));services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);services.housing().acknowledgePacked(owner,operation);
        assertThrows(DomainException.class,()->services.housing().beginClaim(owner,UUID.randomUUID(),UUID.randomUUID(),null));assertEquals(0,services.ownership().available(owner,HousingService.MOVE_CREDIT));
        services.housing().beginRestore(owner,operation);services.housing().updateRestoreLocation(owner,operation,new HousingService.ClaimLocation("housing",100,0,24,24,HousingService.ClaimScope.PUBLIC,null,true));
        op=services.journal().find(operation).orElseThrow();services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);services.housing().activate(owner,operation);assertEquals(0,services.ownership().available(owner,HousingService.MOVE_CREDIT));
    }
    @Test void loginAfterCrashDoesNotAccrueOfflineHours(){
        UUID player=account("Mira");clock.advance(Duration.ofSeconds(30));services.accounts().heartbeat(player);clock.advance(Duration.ofHours(12));services.accounts().recordAuthenticatedLogin(player,"Mira");
        clock.advance(Duration.ofSeconds(30));services.accounts().heartbeat(player);assertEquals(60,services.accounts().stats(player).orElseThrow().playtimeSeconds());
    }
    @Test void exactDepartureDeadlineCannotBeCancelledByLateRejoin(){
        UUID leader=account("Leader"),member=account("Member");UUID guild=services.guilds().create(leader,"Guild","create").id();services.guilds().acceptInvite(member,services.guilds().invite(leader,member));
        UUID op=UUID.randomUUID();services.housing().beginClaim(Owner.player(member),UUID.randomUUID(),op,guild);var j=services.journal().find(op).orElseThrow();services.journal().advance(op,j.revision(),JournalService.State.WORLD_APPLIED);services.housing().activate(Owner.player(member),op);
        services.guilds().leave(member);assertFalse(services.guilds().can(member,guild,"guild.view"));clock.advance(Duration.ofHours(48));services.guilds().acceptInvite(member,services.guilds().invite(leader,member));
        assertEquals(1,services.guilds().leaseDueDepartures("worker",10).size());
    }
    @Test void rejoinBeforeDepartureDeadlineCancelsPacking(){
        UUID leader=account("Leader"),member=account("Member");UUID guild=services.guilds().create(leader,"Guild","create").id();services.guilds().acceptInvite(member,services.guilds().invite(leader,member));
        services.housing().beginClaim(Owner.player(member),UUID.randomUUID(),UUID.randomUUID(),guild);services.guilds().leave(member);clock.advance(Duration.ofHours(47));services.guilds().acceptInvite(member,services.guilds().invite(leader,member));clock.advance(Duration.ofHours(2));assertTrue(services.guilds().leaseDueDepartures("worker",10).isEmpty());
        assertThrows(DomainException.class,()->services.guilds().defineRole(leader,new GuildService.Role("bad","Bad",20,Set.of("guild.view\nrole.edit"))));
    }
    @Test void commerceReplayAndExpiredSupporterPreservePermanentGifts(){
        UUID player=account("Mira");services.commerce().register(new CommerceService.Product("123",1,"Supporter",List.of(new CommerceService.Benefit("eternia:supporter",OwnershipService.Kind.CAPABILITY,1,true),new CommerceService.Benefit("eternia:gift",OwnershipService.Kind.QUANTITY,1,false)),true));
        var request=new CommerceService.VerifiedPurchase("tebex","tx","line","123",1,Owner.player(player),1,"sub",clock.instant(),clock.instant().plus(Duration.ofDays(30)));var purchase=services.commerce().fulfill(request);assertEquals("DELIVERED",purchase.state());services.commerce().fulfill(request);
        assertEquals(1,services.ownership().available(Owner.player(player),"eternia:gift"));services.commerce().requestCancellation("tebex","sub","cancel");assertTrue(services.ownership().owns(Owner.player(player),"eternia:supporter"));
        clock.advance(Duration.ofDays(31));services.commerce().expireSubscriptions();assertFalse(services.ownership().owns(Owner.player(player),"eternia:supporter"));assertEquals(1,services.ownership().available(Owner.player(player),"eternia:gift"));
        services.commerce().reverse(purchase.id(),"refund","Requested refund");assertEquals(0,services.ownership().available(Owner.player(player),"eternia:gift"));
    }
    @Test void petHasOneAssignmentAndProvenanceKeepsGuildOwner(){
        UUID player=account("Mira");var home=home(player,0);services.collection().register(new CollectionService.Definition("eternia:pet","Prowl",CollectionService.Kind.PET,"","Prowl"));var grant=quantity(player,"eternia:pet",2,"pets");var one=services.collection().materializePet(Owner.player(player),grant.id(),0);var two=services.collection().materializePet(Owner.player(player),grant.id(),1);
        services.collection().follow(player,one.id());services.collection().follow(player,two.id());assertEquals(1,services.collection().pets(Owner.player(player)).stream().filter(p->p.assignment().equals("FOLLOWER")).count());services.collection().assignProperty(player,two.id(),home.propertyId());assertEquals(0,services.collection().pets(Owner.player(player)).stream().filter(p->p.assignment().equals("FOLLOWER")).count());
        Owner guild=Owner.guild(UUID.randomUUID());var instance=services.provenance().recordVerifiedPlacement(UUID.randomUUID(),guild,player,home.propertyId(),"eternia:chair","migration:proof",Map.of("position","1,2,3"),"instance");var packed=services.provenance().acknowledgePacked(instance.id(),instance.revision(),"snapshot");var restored=services.provenance().acknowledgeRestored(packed.id(),packed.revision(),UUID.randomUUID());assertEquals(guild,restored.owner());
    }
    @Test void departingMemberPetCannotRemainAssignedToGuildProperty(){
        UUID player=account("Mira"),nextLeader=account("Other");var guild=services.guilds().create(player,"Citadel","guild");services.guilds().acceptInvite(nextLeader,services.guilds().invite(player,nextLeader));
        Owner guildOwner=Owner.guild(guild.id());UUID property=UUID.randomUUID(),operation=UUID.randomUUID();services.housing().beginClaim(guildOwner,property,operation,null,new HousingService.ClaimLocation("housing",0,0,48,48,HousingService.ClaimScope.GUILD,guild.id(),true));var op=services.journal().find(operation).orElseThrow();services.journal().advance(operation,op.revision(),JournalService.State.WORLD_APPLIED);services.housing().activate(guildOwner,operation);
        services.collection().register(new CollectionService.Definition("eternia:pet","Prowl",CollectionService.Kind.PET,"","Prowl"));var grant=quantity(player,"eternia:pet",1,"pets");var pet=services.collection().materializePet(Owner.player(player),grant.id(),0);services.collection().assignProperty(player,pet.id(),property);
        services.guilds().transferLeadership(player,nextLeader);services.guilds().leave(player);assertEquals(1,services.collection().reconcileEntitlements());assertEquals("UNASSIGNED",services.collection().pets(Owner.player(player)).getFirst().assignment());
    }
    @Test void customizationChangesDescriptorsButCannotChangeCustodyOrAcceptStaleRevision(){
        UUID player=account("Mira"),property=UUID.randomUUID();Owner owner=Owner.guild(UUID.randomUUID());var placed=services.provenance().recordVerifiedPlacement(UUID.randomUUID(),owner,player,property,"eternia:house/test","source-proof",Map.of("before","original","after","first"),"placement");
        var customized=services.provenance().acknowledgeCustomization(placed.id(),placed.revision(),Map.of("before","original","after","new-palette"));assertEquals(owner,customized.owner());assertEquals(property,customized.propertyId());assertEquals("source-proof",customized.sourceReference());assertEquals("eternia:house/test",customized.contentId());assertEquals("new-palette",customized.nativeData().get("after"));
        assertThrows(DomainException.class,()->services.provenance().acknowledgeCustomization(placed.id(),placed.revision(),Map.of("after","stale")));
    }
    @Test void packingHouseStopsCheckoutWithoutTakingBuyerCoinsOrStock(){
        UUID seller=account("Seller"),buyer=account("Buyer");var property=home(seller,0);coins(buyer,100);String escrow=item(seller,3,true);var listing=services.market().list(seller,escrow,10,"listing");
        services.housing().updateBuildingPresent(Owner.player(seller),property.propertyId(),false);assertThrows(DomainException.class,()->services.market().buy(buyer,listing.id(),1,listing.revision(),"purchase"));
        assertEquals(100,services.economy().balance(Owner.player(buyer)).available());assertEquals(3,services.escrow().find(escrow).orElseThrow().remaining());assertTrue(services.escrow().deliveries(Owner.player(buyer)).isEmpty());
    }
}
