package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClaimEligibilityTest {
    @Test void movingPreviewReusesAnImmutableSnapshotWithoutMoreDatabaseWork(){
        var store=new CountingStore();var services=new EterniaServices(store);var actor=UUID.randomUUID();
        // Service startup seeds the committed protection index once; measure preview work separately.
        store.transactions=0;store.reads.clear();
        var snapshot=ClaimEligibility.load(services,actor,HousingRules.Scope.PUBLIC,false);
        assertEquals(1,store.transactions);assertEquals(List.of("housing_slot"),store.reads);
        for(int x=0;x<100;x++)assertNull(snapshot.check(new PlotRect(x,0,24,24)));
        assertEquals(1,store.transactions,"Moving the grid must not open another database transaction");
        assertTrue(snapshot.matches(HousingRules.Scope.PUBLIC,false));assertFalse(snapshot.matches(HousingRules.Scope.PUBLIC,true));assertFalse(snapshot.matches(HousingRules.Scope.GUILD_ROOT,false));
    }
    @Test void aFreshCheckSeesAClaimMadeSinceThePreviewLoaded(){
        var services=new EterniaServices(new InMemoryStore());var actor=UUID.randomUUID();var rect=new PlotRect(10,10,24,24);
        var preview=ClaimEligibility.load(services,actor,HousingRules.Scope.PUBLIC,false);assertNull(preview.check(rect));
        services.housing().beginClaim(Owner.player(actor),UUID.randomUUID(),UUID.randomUUID(),null,new HousingService.ClaimLocation("Hub",10,10,24,24,HousingService.ClaimScope.PUBLIC,null,false));
        assertNull(preview.check(rect),"A preview is immutable, not authority to commit");
        assertEquals("slot",ClaimEligibility.load(services,actor,HousingRules.Scope.PUBLIC,false).check(rect).code());
    }
    @Test void largerPlotRequiresTheOwnersUnlockAndRefreshSeesNewUnlock(){
        var services=new EterniaServices(new InMemoryStore());var actor=UUID.randomUUID();var rect=new PlotRect(0,0,32,32);
        var preview=ClaimEligibility.load(services,actor,HousingRules.Scope.PUBLIC,true);assertEquals("upgrade",preview.check(rect).code());
        services.ownership().grant(new OwnershipService.GrantRequest("upgrade",Owner.player(actor),"eternia:plot/personal_32",OwnershipService.Kind.UNLOCK,1,null));
        assertNull(ClaimEligibility.load(services,actor,HousingRules.Scope.PUBLIC,true).check(rect));assertEquals("upgrade",preview.check(rect).code());
    }
    @Test void guildChecksLoadTogetherAndKeepGuildAndPersonalOwnershipSeparate(){
        var store=new CountingStore();var services=new EterniaServices(store);var leader=UUID.randomUUID();var member=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(leader,"Leader");services.accounts().recordAuthenticatedLogin(member,"Member");var guild=services.guilds().create(leader,"Test Guild","guild");services.guilds().acceptInvite(member,services.guilds().invite(leader,member));store.transactions=0;
        var root=ClaimEligibility.load(services,leader,HousingRules.Scope.GUILD_ROOT,false);assertEquals(1,store.transactions);assertEquals(Owner.guild(guild.id()),root.owner());assertEquals(2,root.members());assertTrue(root.canClaim());
        var attached=ClaimEligibility.load(services,member,HousingRules.Scope.GUILD_MEMBER,false);assertEquals(guild.id(),attached.guild());assertEquals(Owner.player(member),attached.owner());
        assertEquals("permission",ClaimEligibility.load(services,member,HousingRules.Scope.GUILD_ROOT,false).check(new PlotRect(0,0,48,48)).code());
        var personal=ClaimEligibility.load(services,leader,HousingRules.Scope.PUBLIC,false);assertNull(personal.guild());assertEquals(Owner.player(leader),personal.owner());
    }
    @Test void packedPlotsStillRejectShrinkingAndIncompatibleShapes(){
        var actor=UUID.randomUUID();var owner=Owner.player(actor);var slot=new HousingService.Slot(owner,UUID.randomUUID(),HousingService.State.PACKED,UUID.randomUUID(),1,"snapshot",null);var location=new HousingService.ClaimLocation("Hub",0,0,32,32,HousingService.ClaimScope.PUBLIC,null,true);
        var snapshot=new ClaimEligibility(actor,HousingRules.Scope.PUBLIC,true,null,owner,slot,location,true,true,0);
        assertEquals("restoreSize",snapshot.check(new PlotRect(0,0,24,24)).code());assertEquals("restoreSize",snapshot.check(new PlotRect(0,0,33,32)).code());assertNull(snapshot.check(new PlotRect(0,0,32,32)));
    }
    private static final class CountingStore implements TransactionalStore {
        private final InMemoryStore delegate=new InMemoryStore();int transactions;final List<String> reads=new ArrayList<>();
        public <T>T transaction(Function<Transaction,T> work){transactions++;return delegate.transaction(tx->work.apply(new Transaction(){
            public Optional<Row> find(String ns,String key){reads.add(ns);return tx.find(ns,key);}
            public List<Row> scan(String ns){reads.add(ns);return tx.scan(ns);}
            public Row save(String ns,String key,long revision,Map<String,String> fields){return tx.save(ns,key,revision,fields);}
        }));}
    }
}
