package com.hexvane.eterniamod.domain;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PremiumServiceTest {
    final EterniaServices s=new EterniaServices(new InMemoryStore());final UUID player=UUID.randomUUID();
    PremiumServiceTest(){s.accounts().recordAuthenticatedLogin(player,"CrownTester");s.commerce().register(new CommerceService.Product("topup",1,"500 Crowns",List.of(new CommerceService.Benefit(PremiumService.CROWNS,OwnershipService.Kind.QUANTITY,500,false)),false));}
    CommerceService.Purchase topup(String tx){return s.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex",tx,"topup","topup",1,Owner.player(player),1,"",null,null));}
    PremiumService.Item item(int revision,long price,OwnershipService.Kind kind){var i=new PremiumService.Item("eternia:store/chair",revision,"Reading Chair","A cozy seat.","Furnishings",price,List.of(new CommerceService.Benefit("eternia:prop/chair",kind,1,false)));s.premium().register(i);return i;}
    @Test void verifiedTopupAndPurchaseAreAtomicAndRetrySafe(){
        topup("one");topup("one");assertEquals(500,s.premium().balance(player).available());assertEquals(0,s.economy().balance(Owner.player(player)).available());item(1,300,OwnershipService.Kind.QUANTITY);
        var order=s.premium().purchase(player,"eternia:store/chair",1,300,"click");assertEquals(order,s.premium().purchase(player,"eternia:store/chair",1,300,"click"));
        assertEquals(200,s.premium().balance(player).available());assertEquals(1,s.ownership().available(Owner.player(player),"eternia:prop/chair"));
        assertThrows(DomainException.class,()->s.premium().purchase(player,"eternia:store/chair",1,300,"another"));assertEquals(1,s.premium().orders(player).size());
    }
    @Test void concurrentOrdersCannotOverspend()throws Exception{
        topup("one");item(1,400,OwnershipService.Kind.QUANTITY);AtomicInteger wins=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)){var jobs=new ArrayList<Future<?>>();for(int n=0;n<8;n++){String id="click"+n;jobs.add(pool.submit(()->{try{s.premium().purchase(player,"eternia:store/chair",1,400,id);wins.incrementAndGet();}catch(DomainException e){assertEquals(DomainException.Code.INSUFFICIENT_BALANCE,e.code());}}));}for(var j:jobs)j.get();}
        assertEquals(1,wins.get());assertEquals(100,s.premium().balance(player).available());
    }
    @Test void refundsCannotMintCreditAndSpentRefundCreatesRecoverableDebt(){
        var purchase=topup("one");item(1,400,OwnershipService.Kind.QUANTITY);s.premium().purchase(player,"eternia:store/chair",1,400,"buy");
        s.commerce().reverse(purchase.id(),"refund1","Refund");s.commerce().reverse(purchase.id(),"refund2","Refund");topup("one");
        assertEquals(new PremiumService.Balance(0,400),s.premium().balance(player));topup("two");assertEquals(new PremiumService.Balance(100,0),s.premium().balance(player));
    }
    @Test void reversalBeforeDeliveryBlocksDelayedCredit(){s.commerce().reverseTebexTransaction("late","Refund");assertEquals("REVERSED",topup("late").state());assertEquals(0,s.premium().balance(player).available());}
    @Test void outdatedPriceAndDuplicateUnlockDoNotCharge(){
        topup("one");item(1,100,OwnershipService.Kind.UNLOCK);item(2,150,OwnershipService.Kind.UNLOCK);
        assertThrows(DomainException.class,()->s.premium().purchase(player,"eternia:store/chair",1,100,"old"));
        assertThrows(DomainException.class,()->s.premium().purchase(player,"eternia:store/chair",2,1,"tamper"));
        s.premium().purchase(player,"eternia:store/chair",2,150,"buy");assertThrows(DomainException.class,()->s.premium().purchase(player,"eternia:store/chair",2,150,"twice"));assertEquals(350,s.premium().balance(player).available());
    }
    @Test void purchasedGuildCharterCanBeRedeemedButAnOperatorGrantCannot(){
        var guild=s.guilds().create(player,"Test Guild","guild");topup("one");
        var charter=new PremiumService.Item("eternia:store/charter",1,"Guild Waygate","A guild travel unlock.","Guild charters",300,List.of(new CommerceService.Benefit("eternia:guild_voucher/teleporter",OwnershipService.Kind.QUANTITY,1,false)));
        s.premium().register(charter);s.premium().purchase(player,charter.id(),1,300,"charter");
        s.ownership().grant(new OwnershipService.GrantRequest("operator",Owner.player(player),"eternia:guild_voucher/teleporter",OwnershipService.Kind.QUANTITY,1,null));
        var vouchers=s.guildBenefits().available(player);assertEquals(1,vouchers.size());
        s.guildBenefits().redeem(player,guild.id(),vouchers.getFirst().grantId(),"donate");assertTrue(s.ownership().owns(Owner.guild(guild.id()),"eternia:convenience/teleporter"));
    }
}
