package com.hexvane.eterniamod.localplayground;
import com.hexvane.eterniamod.domain.*;
import java.util.*;
/** A short manual-test chapter. Never loaded by production content bootstrap. */
final class PlaygroundContent {
    static final String PASS="eternia:playground_chapter";
    static void register(EterniaServices services){
        services.seasons().register(new SeasonService.Definition(PASS,"Village Field Notes · practice",List.of(500L,1000L,2000L),List.of(
            new SeasonService.Reward(SeasonService.Track.FREE,1,0,"eternia:prop/aqua_lamp",OwnershipService.Kind.QUANTITY,1),
            new SeasonService.Reward(SeasonService.Track.FREE,2,0,"eternia:title/wayfarer",OwnershipService.Kind.UNLOCK,1),
            new SeasonService.Reward(SeasonService.Track.PAID,1,0,"eternia:prop/cacti",OwnershipService.Kind.QUANTITY,1),
            new SeasonService.Reward(SeasonService.Track.PAID,3,0,"eternia:pet/rootling",OwnershipService.Kind.QUANTITY,1)),List.of(
            new SeasonService.Quest("eternia:quest/practice_hunt",SeasonService.ActivityKind.KILL,SeasonService.ObjectiveKind.COUNT,Set.of(),3,500),
            new SeasonService.Quest("eternia:quest/practice_miner",SeasonService.ActivityKind.MINE,SeasonService.ObjectiveKind.DISTINCT,Set.of(),4,500),
            new SeasonService.Quest("eternia:quest/practice_harvest",SeasonService.ActivityKind.HARVEST,SeasonService.ObjectiveKind.COUNT,Set.of(),3,500))));
        services.premium().register(new PremiumService.Item("eternia:store/practice_pass",1,"Field Notes Premium","A short local practice pass. Complete real activity goals and try claiming both tracks.","Season passes",100,List.of(new CommerceService.Benefit(SeasonService.paidEntitlementId(PASS),OwnershipService.Kind.CAPABILITY,1,false))));
    }
}
