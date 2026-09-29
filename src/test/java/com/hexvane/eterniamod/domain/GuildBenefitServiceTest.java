package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuildBenefitServiceTest {
    private static final String VOUCHER="eternia:guild_voucher/teleporter", BENEFIT="eternia:convenience/teleporter";
    private record Fixture(EterniaServices services,UUID leader,UUID member,UUID guild,String source,String purchase){}
    private Fixture fixture(){return fixture(1);}
    private Fixture fixture(int quantity){
        var services=new EterniaServices(new InMemoryStore());UUID leader=UUID.randomUUID(),member=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(leader,"Leader");services.accounts().recordAuthenticatedLogin(member,"Member");
        UUID guild=services.guilds().create(leader,"Test guild","create").id();services.guilds().acceptInvite(member,services.guilds().invite(leader,member));
        services.commerce().register(new CommerceService.Product("123",1,"Guild teleporter",List.of(new CommerceService.Benefit(VOUCHER,OwnershipService.Kind.QUANTITY,quantity,false)),false));
        var purchase=services.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","payment","line","123",1,Owner.player(leader),1,"",null,null));
        return new Fixture(services,leader,member,guild,services.guildBenefits().available(leader).getFirst().grantId(),purchase.id());
    }
    @Test void explicitGuildRedemptionIsIdempotentAndSourceRefundRevokesBenefit(){
        var f=fixture();var result=f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"redeem");
        assertEquals(result,f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"redeem"));
        assertTrue(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));assertEquals(0,f.services.ownership().available(Owner.player(f.leader),VOUCHER));
        f.services.commerce().reverse(f.purchase,"refund","Refund");
        assertFalse(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));assertTrue(f.services.guildBenefits().available(f.leader).isEmpty());
        assertFalse(f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"redeem").active()); // historical replay cannot present a reversed gift as active
        assertFalse(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));
    }
    @Test void changedMembershipOtherPurchaserAndForgedOperatorVoucherCannotDonate(){
        var f=fixture();
        assertThrows(DomainException.class,()->f.services.guildBenefits().redeem(f.member,f.guild,f.source,"wrong-player"));
        f.services.ownership().grant(new OwnershipService.GrantRequest("admin:voucher",Owner.player(f.leader),VOUCHER,OwnershipService.Kind.QUANTITY,1,null));
        String forged=f.services.ownership().grants(Owner.player(f.leader)).stream().filter(g->!g.id().equals(f.source)).findFirst().orElseThrow().id();
        assertThrows(DomainException.class,()->f.services.guildBenefits().redeem(f.leader,f.guild,forged,"forged"));
        f.services.guilds().transferLeadership(f.leader,f.member);
        assertThrows(DomainException.class,()->f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"stale-confirmation"));
        assertEquals(2,f.services.ownership().available(Owner.player(f.leader),VOUCHER));
    }
    @Test void concurrentClicksConsumeOneVoucherAndRefundBeforeRedemptionCannotGrant()throws Exception{
        var f=fixture();
        try(var executor=Executors.newFixedThreadPool(2)){
            var attempts=List.of(executor.submit(()->attempt(f,"one")),executor.submit(()->attempt(f,"two")));
            int successes=0;for(var attempt:attempts)if(attempt.get())successes++;assertEquals(1,successes);
        }
        var reversed=fixture();reversed.services.commerce().reverse(reversed.purchase,"refund-first","Refund");
        assertThrows(DomainException.class,()->reversed.services.guildBenefits().redeem(reversed.leader,reversed.guild,reversed.source,"late"));
        assertFalse(reversed.services.ownership().owns(Owner.guild(reversed.guild),BENEFIT));
    }
    @Test void refundRacingRedemptionAlwaysLeavesThePurchasedBenefitRevoked()throws Exception{
        var f=fixture();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){
            var redeem=executor.submit(()->{start.await();return attempt(f,"raced");});
            var refund=executor.submit(()->{start.await();return f.services.commerce().reverse(f.purchase,"racing-refund","Refund");});
            start.countDown();redeem.get();refund.get();
        }
        assertFalse(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));assertTrue(f.services.guildBenefits().available(f.leader).isEmpty());
        assertTrue(f.services.ownership().grants(Owner.guild(f.guild)).stream().allMatch(OwnershipService.Grant::revoked));
    }
    @Test void exactSourceCanFundTwoNamedGuildsAndRefundRevokesBothDerivedGrants(){
        var f=fixture(2);f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"first-guild");
        assertEquals(1,f.services.guildBenefits().available(f.leader).getFirst().available());
        f.services.guilds().transferLeadership(f.leader,f.member);f.services.guilds().leave(f.leader);
        UUID next=f.services.guilds().create(f.leader,"Next guild","next-guild").id();
        assertThrows(DomainException.class,()->f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"stale-guild"));
        f.services.guildBenefits().redeem(f.leader,next,f.source,"second-guild");
        assertTrue(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));assertTrue(f.services.ownership().owns(Owner.guild(next),BENEFIT));
        f.services.commerce().reverse(f.purchase,"refund-both","Refund");
        assertFalse(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));assertFalse(f.services.ownership().owns(Owner.guild(next),BENEFIT));
        assertEquals(0,f.services.ownership().available(Owner.player(f.leader),VOUCHER));
    }
    @Test void refundPreservesIndependentGuildAndPlayerSources(){
        var f=fixture();f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"first");
        f.services.ownership().grant(new OwnershipService.GrantRequest("independent-guild-gift",Owner.guild(f.guild),BENEFIT,OwnershipService.Kind.CAPABILITY,1,null));
        var other=f.services.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","other-payment","line","123",1,Owner.player(f.leader),1,"",null,null));
        String otherSource=f.services.guildBenefits().available(f.leader).getFirst().grantId();
        f.services.commerce().reverse(f.purchase,"refund-first","Refund");
        assertTrue(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));assertEquals(1,f.services.guildBenefits().available(f.leader).size());
        assertFalse(f.services.guildBenefits().redeem(f.leader,f.guild,f.source,"first").active());
        f.services.ownership().revokeSource("independent-guild-gift");f.services.guildBenefits().redeem(f.leader,f.guild,otherSource,"second");
        f.services.commerce().reverse(f.purchase,"refund-first-again","Refund");assertTrue(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));
        f.services.commerce().reverse(other.id(),"refund-second","Refund");assertFalse(f.services.ownership().owns(Owner.guild(f.guild),BENEFIT));
    }
    @Test void anotherGuildLeaderAndPermanentSubscriptionGiftsCannotUseTheVoucher(){
        var f=fixture();UUID other=UUID.randomUUID();f.services.accounts().recordAuthenticatedLogin(other,"Other leader");UUID guild=f.services.guilds().create(other,"Other guild","other-guild").id();
        assertThrows(DomainException.class,()->f.services.guildBenefits().redeem(other,guild,f.source,"foreign-source"));
        f.services.commerce().register(new CommerceService.Product("456",1,"Subscription gift",List.of(new CommerceService.Benefit(VOUCHER,OwnershipService.Kind.QUANTITY,1,false)),true));
        var start=java.time.Instant.parse("2026-01-01T00:00:00Z");f.services.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","subscription-payment","line","456",1,Owner.player(f.leader),1,"sub",start,start.plusSeconds(86400)));
        String gift=f.services.ownership().grants(Owner.player(f.leader)).stream().filter(g->!g.id().equals(f.source)).findFirst().orElseThrow().id();
        assertThrows(DomainException.class,()->f.services.guildBenefits().redeem(f.leader,f.guild,gift,"subscription-gift"));assertEquals(1,f.services.guildBenefits().available(f.leader).size());
    }
    private boolean attempt(Fixture f,String receipt){try{f.services.guildBenefits().redeem(f.leader,f.guild,f.source,receipt);return true;}catch(DomainException failure){return false;}}
}
