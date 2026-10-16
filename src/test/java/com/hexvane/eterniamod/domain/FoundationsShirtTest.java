package com.hexvane.eterniamod.domain;

import com.google.gson.Gson;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FoundationsShirtTest {
    @Test void existingPaidClaimsSurviveAddingShirtAndBothClaimsRemainIdempotent() {
        var s=new EterniaServices(new InMemoryStore());var actor=UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(actor,"ShirtTester");
        var next=new Gson().fromJson(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/Server/EterniaMod/Seasons/foundations.json")),StandardCharsets.UTF_8),SeasonService.Definition.class);
        var old=new SeasonService.Definition(next.id(),next.name(),next.thresholds(),next.rewards().stream().filter(r->!r.contentId().equals("eternia:wearable/prowl_shirt")).toList(),next.quests());
        s.seasons().register(old);s.seasons().select(actor,old.id());
        s.seasons().recordActivity(new SeasonService.Activity(UUID.randomUUID(),actor,SeasonService.ActivityKind.INTEGRATION,"test",1,next.thresholds().getFirst(),"shirt-test",true));
        assertThrows(DomainException.class,()->s.seasons().claimReward(actor,next.id(),SeasonService.Track.PAID,1,0));
        s.ownership().grant(new OwnershipService.GrantRequest("paid-test",Owner.player(actor),SeasonService.paidEntitlementId(next.id()),OwnershipService.Kind.UNLOCK,1,null));
        var original=s.seasons().claimReward(actor,next.id(),SeasonService.Track.PAID,1,0);
        s.seasons().register(next);s.seasons().register(next);
        assertEquals(original,s.seasons().claimReward(actor,next.id(),SeasonService.Track.PAID,1,0));
        var shirt=s.seasons().claimReward(actor,next.id(),SeasonService.Track.PAID,1,1);
        assertEquals("eternia:wearable/prowl_shirt",shirt.contentId());
        assertEquals(shirt,s.seasons().claimReward(actor,next.id(),SeasonService.Track.PAID,1,1));
        var changed=new ArrayList<>(next.rewards());changed.set(0,new SeasonService.Reward(SeasonService.Track.FREE,1,0,"eternia:prop/other",OwnershipService.Kind.UNLOCK,1));
        assertThrows(DomainException.class,()->s.seasons().register(new SeasonService.Definition(next.id(),next.name(),next.thresholds(),changed,next.quests())));
    }
}
