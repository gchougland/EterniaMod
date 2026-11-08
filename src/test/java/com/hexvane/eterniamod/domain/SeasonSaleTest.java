package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.domain.SeasonService.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.premium.PremiumShopSnapshot;
import com.hexvane.eterniamod.seasonsales.SeasonSaleText;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Season passes and season store items can only be bought while their season runs; ownership is forever. */
class SeasonSaleTest {
    static final class Time extends Clock {
        Instant now;Time(String at){now=Instant.parse(at);}
        void set(String at){now=Instant.parse(at);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    static final String SEASON="eternia:winter";
    static final SaleWindow WINTER=new SaleWindow(LocalDate.parse("2026-12-01"),LocalDate.parse("2027-01-30"));
    final Time time=new Time("2026-11-07T12:00:00Z");
    final EterniaServices s=new EterniaServices(new InMemoryStore(),time,UUID::randomUUID);
    final UUID player=UUID.randomUUID(),other=UUID.randomUUID();
    final PremiumService.Item premium=new PremiumService.Item("eternia:store/winter",1,"Winter Premium","The paid track of Winter.","Season passes",700,
        List.of(new CommerceService.Benefit(SeasonService.paidEntitlementId(SEASON),OwnershipService.Kind.CAPABILITY,1,false)));
    final PremiumService.Item sledge=new PremiumService.Item("eternia:store/sledge",1,"Snow Sledge","A sledge for the winter.","Furnishings",200,
        List.of(new CommerceService.Benefit("eternia:prop/sledge",OwnershipService.Kind.QUANTITY,1,false)),SEASON);
    final PremiumService.Item chair=new PremiumService.Item("eternia:store/chair",1,"Chair","A chair.","Furnishings",100,
        List.of(new CommerceService.Benefit("eternia:prop/chair",OwnershipService.Kind.QUANTITY,1,false)));

    SeasonSaleTest(){
        for(var id:List.of(player,other)){s.accounts().recordAuthenticatedLogin(id,"Buyer"+id.toString().substring(0,4));s.premium().creditLocalExample(id,5000,"local-playground:"+id);}
        s.seasons().register(new Definition(SEASON,"Winter",List.of(100L,200L),List.of(new Reward(Track.PAID,1,0,"eternia:title/frost",OwnershipService.Kind.UNLOCK,1)),List.of()));
        s.seasons().setSaleWindow(SEASON,WINTER);
        for(var item:List.of(premium,sledge,chair))s.premium().register(item);
    }

    @Test void windowStatesUseWholeUtcDaysWithInclusiveEnds(){
        assertEquals(SaleState.UPCOMING,WINTER.state(Instant.parse("2026-11-30T23:59:59.999Z")));
        assertEquals(SaleState.ON_SALE,WINTER.state(Instant.parse("2026-12-01T00:00:00Z")),"Opens at midnight UTC on the first day");
        assertEquals(SaleState.ON_SALE,WINTER.state(Instant.parse("2027-01-30T23:59:59.999Z")),"The last day is included");
        assertEquals(SaleState.ENDED,WINTER.state(Instant.parse("2027-01-31T00:00:00Z")));
        // 30 Jan 18:00 in Los Angeles is already 31 Jan in UTC: the server clock, not the player's, decides.
        assertEquals(SaleState.ENDED,WINTER.state(ZonedDateTime.parse("2027-01-30T18:00:00-08:00[America/Los_Angeles]").toInstant()));
        assertEquals(SaleState.ON_SALE,WINTER.state(ZonedDateTime.parse("2027-01-31T08:00:00+09:00[Asia/Tokyo]").toInstant()));
        assertEquals(SaleState.UNSCHEDULED,SaleWindow.NONE.state(Instant.EPOCH));
        assertTrue(SaleState.UNSCHEDULED.purchasable());assertFalse(SaleState.UPCOMING.purchasable());assertFalse(SaleState.ENDED.purchasable());
        var openEnded=SaleWindow.parse("2026-12-01","");assertEquals(SaleState.ON_SALE,openEnded.state(Instant.parse("2099-01-01T00:00:00Z")));
        var untilOnly=SaleWindow.parse(null," 2027-01-30 ");assertEquals(SaleState.ON_SALE,untilOnly.state(Instant.EPOCH));assertEquals(SaleState.ENDED,untilOnly.state(Instant.parse("2027-01-31T00:00:00Z")));
        var oneDay=SaleWindow.parse("2027-01-30","2027-01-30");assertEquals(SaleState.ON_SALE,oneDay.state(Instant.parse("2027-01-30T12:00:00Z")));
        assertThrows(DomainException.class,()->SaleWindow.parse("2027-02-01","2027-01-30"));
        assertThrows(DomainException.class,()->SaleWindow.parse("30/01/2027",""));
    }

    @Test void statusIsExposedForOtherViewsAndWindowsCanChange(){
        assertEquals(new SaleStatus(SEASON,"Winter",SaleState.UPCOMING,WINTER),s.seasons().saleStatus(SEASON));
        assertFalse(s.seasons().purchasable(SEASON,time.instant()));assertTrue(s.seasons().purchasable(SEASON,Instant.parse("2026-12-15T00:00:00Z")));
        assertEquals(SaleState.UNSCHEDULED,s.seasons().saleStatus("eternia:unknown").state(),"A season without dates is always on sale");
        assertEquals(Set.of(SEASON),s.seasons().saleStatuses(time.instant()).keySet());
        s.seasons().setSaleWindow(SEASON,SaleWindow.NONE);assertEquals(SaleState.UNSCHEDULED,s.seasons().saleStatus(SEASON).state());
        s.seasons().setSaleWindow(SEASON,WINTER);assertEquals(WINTER,s.seasons().saleWindow(SEASON));
        assertEquals(Optional.of(SEASON),SeasonService.seasonOfPaidEntitlement(SeasonService.paidEntitlementId(SEASON)));
        assertEquals(Optional.of("eternia:foundations"),SeasonService.seasonOfPaidEntitlement("eternia:season_paid/eternia/foundations"));
        assertEquals(Optional.empty(),SeasonService.seasonOfPaidEntitlement("eternia:prop/chair"));
        assertEquals(Optional.of(SEASON),PremiumService.seasonOf(premium));assertEquals(Optional.of(SEASON),PremiumService.seasonOf(sledge));assertEquals(Optional.empty(),PremiumService.seasonOf(chair));
    }

    @Test void premiumTrackSellsOnlyDuringItsSeasonAndStaysOwnedAfterwards(){
        var early=assertThrows(DomainException.class,()->s.premium().purchase(player,premium.id(),1,700,"early"));
        assertTrue(early.getMessage().contains("Winter begins on 1 December 2026"),early.getMessage());
        assertEquals(5000,s.premium().balance(player).available(),"A rejected purchase never charges");

        time.set("2027-01-30T23:59:00Z");
        var order=s.premium().purchase(player,premium.id(),1,700,"last-minute");assertEquals("DELIVERED",order.state());

        time.set("2027-01-31T00:00:00Z");
        assertEquals(order,s.premium().purchase(player,premium.id(),1,700,"last-minute"),"A retry of an earlier order returns it after the season closes");
        var late=assertThrows(DomainException.class,()->s.premium().purchase(other,premium.id(),1,700,"late"));
        assertTrue(late.getMessage().contains("Winter has ended"),late.getMessage());
        assertEquals(5000,s.premium().balance(other).available());

        // Owned forever: still owned, still claimable, still shown as owned.
        time.set("2030-06-01T00:00:00Z");
        assertTrue(s.premium().alreadyOwned(player,premium));
        assertTrue(s.seasons().progress(player).stream().filter(p->p.id().equals(SEASON)).findFirst().orElseThrow().paid());
        s.seasons().select(player,SEASON);s.seasons().recordActivity(new Activity(UUID.randomUUID(),player,ActivityKind.KILL,"npc:skeleton",1,150,"skeleton-1",true));
        s.seasons().claimReward(player,SEASON,Track.PAID,1,0);assertTrue(s.ownership().owns(Owner.player(player),"eternia:title/frost"));
        var snapshot=PremiumShopSnapshot.load(s,player);assertTrue(snapshot.owned().contains(premium.id()));assertEquals(SaleState.ENDED,snapshot.sale(premium).state());
    }

    @Test void seasonStoreItemsAreGatedButOrdinaryItemsAreNot(){
        assertThrows(DomainException.class,()->s.premium().purchase(player,sledge.id(),1,200,"sledge-early"));
        s.premium().purchase(player,chair.id(),1,100,"chair-early");
        time.set("2026-12-24T10:00:00Z");s.premium().purchase(player,sledge.id(),1,200,"sledge");
        time.set("2027-02-14T10:00:00Z");
        assertThrows(DomainException.class,()->s.premium().purchase(player,sledge.id(),1,200,"sledge-again"));
        s.premium().purchase(player,chair.id(),1,100,"chair-late");
        assertEquals(1,s.ownership().available(Owner.player(player),"eternia:prop/sledge"),"The sledge bought in season is kept");
        assertEquals(2,s.ownership().available(Owner.player(player),"eternia:prop/chair"));
    }

    @Test void lateTebexCompletionOfASeasonPassIsStillDelivered(){
        s.commerce().register(new CommerceService.Product("9001",1,"Winter Premium",List.of(new CommerceService.Benefit(SeasonService.paidEntitlementId(SEASON),OwnershipService.Kind.CAPABILITY,1,false)),false));
        time.set("2027-02-02T09:00:00Z");// checkout began on 30 Jan; the provider confirms days later
        var purchase=s.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex","tbx-late","line","9001",1,Owner.player(other),1,"",null,null));
        assertEquals("DELIVERED",purchase.state());assertTrue(s.ownership().owns(Owner.player(other),SeasonService.paidEntitlementId(SEASON)));
    }

    @Test void itemsWithoutASeasonKeepTheirStoredFormAndSeasonItemsRoundTrip(){
        s.premium().register(chair);// unchanged revisions re-register cleanly
        s.premium().register(sledge);
        var stored=s.premium().items().stream().filter(i->i.id().equals(sledge.id())).findFirst().orElseThrow();assertEquals(SEASON,stored.season());
        assertEquals("",s.premium().items().stream().filter(i->i.id().equals(chair.id())).findFirst().orElseThrow().season());
        assertThrows(DomainException.class,()->s.premium().register(new PremiumService.Item(chair.id(),1,chair.name(),chair.description(),chair.category(),chair.price(),chair.benefits(),SEASON)),"A revision cannot gain a season later");
    }

    @Test void storeSnapshotListsEndedItemsLastAndExplainsEachState(){
        time.set("2027-03-01T00:00:00Z");
        var rug=new PremiumService.Item("eternia:store/zz_rug",1,"Rug","A rug.","Furnishings",50,List.of(new CommerceService.Benefit("eternia:prop/rug",OwnershipService.Kind.QUANTITY,1,false)));s.premium().register(rug);
        var snapshot=PremiumShopSnapshot.load(s,player);
        assertEquals(List.of(chair.id(),rug.id(),sledge.id(),premium.id()),snapshot.items().stream().map(PremiumService.Item::id).toList(),"Items that can no longer be bought go last");
        assertFalse(snapshot.onSale(sledge));assertTrue(snapshot.onSale(chair));
        Instant now=Instant.parse("2026-11-07T00:00:00Z");
        assertEquals("Opens 1 Dec",SeasonSaleText.badge(new SaleStatus(SEASON,"Winter",SaleState.UPCOMING,WINTER),now));
        assertEquals("Until 30 Jan 2027",SeasonSaleText.badge(new SaleStatus(SEASON,"Winter",SaleState.ON_SALE,WINTER),now));
        assertEquals("Until 30 Jan",SeasonSaleText.badge(new SaleStatus(SEASON,"Winter",SaleState.ON_SALE,WINTER),Instant.parse("2027-01-02T00:00:00Z")));
        assertEquals("Season ended",SeasonSaleText.badge(new SaleStatus(SEASON,"Winter",SaleState.ENDED,WINTER),now));
        assertEquals("",SeasonSaleText.badge(new SaleStatus(SEASON,"Winter",SaleState.UNSCHEDULED,SaleWindow.NONE),now));
        assertEquals("Coming soon",SeasonSaleText.closedButton(SaleState.UPCOMING));
        assertEquals("On sale now",SeasonSaleText.designer("2026-11-01","2026-11-30",now).headline());
        assertEquals("Check the dates",SeasonSaleText.designer("2026-11-30","2026-11-01",now).headline());
        assertEquals("Always on sale",SeasonSaleText.designer("","",now).headline());
        for(var state:SaleState.values()){var text=SeasonSaleText.note(new SaleStatus(SEASON,"Winter",state,WINTER))+SeasonSaleText.rejection(new SaleStatus(SEASON,"Winter",state,WINTER));
            assertFalse(text.contains(" - ")||text.contains(" — ")||text.contains("eternia:"),"Player text stays plain: "+text);}
    }
}
