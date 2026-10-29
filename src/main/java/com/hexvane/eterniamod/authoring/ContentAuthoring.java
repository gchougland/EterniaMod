package com.hexvane.eterniamod.authoring;

import com.google.gson.*;
import com.hexvane.eterniamod.domain.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Checks admin input and turns drafts into game definitions and website uploads. No I/O. */
public final class ContentAuthoring {
    public static final List<String> KINDS=List.of("prop","addition","house");
    public static final List<String> STORE_CATEGORIES=List.of("Furnishings","Housing","Companions","Wardrobe","Titles","Season passes","Guild charters");
    private static final String SLUG="[a-z0-9][a-z0-9_-]{0,63}";
    private ContentAuthoring(){}

    public static String kindLabel(String kind){return switch(kind){case "house"->"House";case "addition"->"Addition";default->"Decoration";};}
    public static String contentId(AuthoringDrafts.Content d){return (d.kind.equals("house")?"eternia:house/":"eternia:prop/")+d.id;}
    public static OwnershipService.Kind rewardKind(AuthoringDrafts.Content d){return d.kind.equals("house")?OwnershipService.Kind.UNLOCK:OwnershipService.Kind.QUANTITY;}
    public static String storeItemId(AuthoringDrafts.Content d){return "eternia:store/"+d.id;}
    public static String seasonSlug(String passId){return passId.startsWith("eternia:")?passId.substring(8):passId;}

    /** Throws IllegalArgumentException with a message the admin can act on. */
    public static void validate(AuthoringDrafts.Content d){
        check(KINDS.contains(d.kind),"Choose a type.");
        check(d.id.matches(SLUG),"The ID needs lowercase letters, numbers, underscores or hyphens, up to 64 characters.");
        check(!d.displayName.isBlank()&&d.displayName.length()<=80,"Give it a name of up to 80 characters.");
        check(d.description.length()<=240,"Keep the details under 240 characters.");
        if(d.sold){
            check(d.crowns>=1&&d.crowns<=1_000_000,"Set a Crown price from 1 to 1000000.");
            check(!d.description.isBlank(),"Store items need details for the store page.");
            check(STORE_CATEGORIES.contains(d.category),"Choose a store category.");
        }
        if(!d.passId.isBlank()){check(d.track.equals("FREE")||d.track.equals("PAID"),"Choose the free or paid track.");check(d.level>=1&&d.level<=1000,"Choose a season level from 1 to 1000.");}
    }

    /** The next Crown store revision, or empty when the latest revision already matches. */
    public static Optional<PremiumService.Item> storeItem(AuthoringDrafts.Content d,PremiumService.Item latest){
        var benefit=new CommerceService.Benefit(contentId(d),rewardKind(d),1,false);
        var next=new PremiumService.Item(storeItemId(d),latest==null?1:latest.revision()+1,d.displayName.strip(),d.description.strip(),d.category,d.crowns,List.of(benefit));
        if(latest!=null&&latest.name().equals(next.name())&&latest.description().equals(next.description())&&latest.category().equals(next.category())&&latest.price()==next.price()&&latest.benefits().equals(next.benefits()))return Optional.empty();
        return Optional.of(next);
    }

    /** Puts this content on the draft pass at the chosen tier, replacing where it was before. */
    public static void placeReward(AuthoringDrafts.Season season,AuthoringDrafts.Content d){
        String id=contentId(d);
        check(d.level<=season.thresholds.size(),"That pass has "+season.thresholds.size()+" levels.");
        season.rewards.removeIf(r->r.contentId.equals(id));
        var reward=new AuthoringDrafts.Reward();reward.track=d.track;reward.level=d.level;reward.contentId=id;reward.kind=rewardKind(d).name();reward.quantity=1;reward.name=d.displayName.strip();
        reward.index=(int)season.rewards.stream().filter(r->r.track.equals(reward.track)&&r.level==reward.level).mapToInt(r->r.index+1).max().orElse(0);
        season.rewards.add(reward);
    }
    /** True when a live pass already holds this exact reward, so nothing would change. */
    public static boolean liveHasReward(SeasonService.Definition live,AuthoringDrafts.Content d){
        return live.rewards().stream().anyMatch(r->r.contentId().equals(contentId(d))&&r.track().name().equals(d.track)&&r.level()==d.level);
    }

    public static List<Long> thresholds(int levels,long xpPerLevel){
        check(levels>=1&&levels<=1000,"Choose from 1 to 1000 levels.");check(xpPerLevel>=1&&xpPerLevel<=1_000_000,"XP per level must be from 1 to 1000000.");
        var result=new ArrayList<Long>();for(int i=1;i<=levels;i++)result.add(xpPerLevel*i);return result;
    }
    public static void validate(AuthoringDrafts.Season s){
        check(s.id.matches("eternia:"+SLUG),"The pass ID needs lowercase letters, numbers, underscores or hyphens.");
        check(!s.name.isBlank()&&s.name.length()<=100,"Give the pass a name of up to 100 characters.");
        check(s.description.length()<=600,"Keep the description under 600 characters.");
        LocalDate start=date(s.startsOn,"start"),end=date(s.endsOn,"end");
        check(start==null||end==null||!end.isBefore(start),"The end date is before the start date.");
        check(!s.thresholds.isEmpty(),"A pass needs at least one level.");
        var tiers=new HashSet<String>();
        for(var r:s.rewards){check(r.level>=1&&r.level<=s.thresholds.size(),"A reward is above the last level. Remove it or add levels.");check(tiers.add(r.track+":"+r.level+":"+r.index),"Two rewards share one slot.");}
    }
    private static LocalDate date(String value,String which){
        if(value==null||value.isBlank())return null;
        try{return LocalDate.parse(value.strip());}catch(DateTimeParseException e){throw new IllegalArgumentException("Write the "+which+" date like 2026-11-01.");}
    }
    /** The game definition for going live. Requirements and rewards are fixed from then on. */
    public static SeasonService.Definition definition(AuthoringDrafts.Season s,List<SeasonService.Quest> quests){
        validate(s);
        var rewards=s.rewards.stream().map(r->new SeasonService.Reward(SeasonService.Track.valueOf(r.track),r.level,r.index,r.contentId,OwnershipService.Kind.valueOf(r.kind),r.quantity)).toList();
        return new SeasonService.Definition(s.id,s.name.strip(),s.thresholds,rewards,quests);
    }
    public static AuthoringDrafts.Season draftFrom(SeasonService.Definition live){
        var s=new AuthoringDrafts.Season();s.id=live.id();s.name=live.name();s.thresholds=new ArrayList<>(live.thresholds());s.live=true;
        for(var r:live.rewards()){var reward=new AuthoringDrafts.Reward();reward.track=r.track().name();reward.level=r.level();reward.index=r.index();reward.contentId=r.contentId();reward.kind=r.kind().name();reward.quantity=r.quantity();s.rewards.add(reward);}
        return s;
    }

    public static JsonObject contentUpload(AuthoringDrafts.Content d,JsonElement prefab,JsonObject nativeFields,UUID author,String authorName){
        var body=new JsonObject();body.addProperty("kind",d.kind);body.addProperty("id",d.id);body.addProperty("displayName",d.displayName.strip());body.addProperty("description",d.description.strip());
        body.add("native",nativeFields);
        var store=new JsonObject();store.addProperty("sold",d.sold);if(d.sold){store.addProperty("crowns",d.crowns);store.addProperty("category",d.category);}body.add("store",store);
        if(!d.passId.isBlank()){var season=new JsonObject();season.addProperty("passId",d.passId);season.addProperty("track",d.track);season.addProperty("level",d.level);body.add("season",season);}
        body.add("prefab",prefab);body.add("author",author(author,authorName));return body;
    }
    public static JsonObject seasonUpload(AuthoringDrafts.Season s,UUID author,String authorName){
        var body=new JsonObject();body.addProperty("id",s.id);body.addProperty("name",s.name.strip());body.addProperty("description",s.description.strip());
        if(!s.startsOn.isBlank())body.addProperty("startsOn",s.startsOn.strip());if(!s.endsOn.isBlank())body.addProperty("endsOn",s.endsOn.strip());
        body.addProperty("status",s.live?"live":"draft");
        var thresholds=new JsonArray();s.thresholds.forEach(thresholds::add);body.add("thresholds",thresholds);
        var rewards=new JsonArray();for(var r:s.rewards){var o=new JsonObject();o.addProperty("track",r.track);o.addProperty("level",r.level);o.addProperty("index",r.index);o.addProperty("contentId",r.contentId);o.addProperty("kind",r.kind);o.addProperty("quantity",r.quantity);if(r.name!=null&&!r.name.isBlank())o.addProperty("name",r.name);rewards.add(o);}
        body.add("rewards",rewards);body.add("author",author(author,authorName));return body;
    }
    private static JsonObject author(UUID uuid,String name){var o=new JsonObject();o.addProperty("uuid",uuid.toString());o.addProperty("name",name==null?"Admin":name.length()>64?name.substring(0,64):name);return o;}
    private static void check(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
}
