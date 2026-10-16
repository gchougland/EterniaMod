package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class SeasonService extends DomainSupport {
    public enum Track { FREE, PAID }
    public enum ActivityKind { KILL, MINE, HARVEST, ACQUIRE, MINIGAME, INTEGRATION }
    public enum Cadence { ONCE, DAILY, WEEKLY }
    public enum ObjectiveKind { COUNT, DISTINCT }
    public record Reward(Track track,int level,int index,String contentId,OwnershipService.Kind kind,long quantity) {
        public Reward{Objects.requireNonNull(track);require(level>0&&index>=0,INVALID_INPUT,"Invalid reward tier");content(contentId);Objects.requireNonNull(kind);positive(quantity);}
    }
    public record Quest(String id,ActivityKind activity,ObjectiveKind kind,Set<String> targets,long required,long bonusXp,Long coins,Cadence cadence,String name,String goal) {
        public Quest(String id,ActivityKind activity,ObjectiveKind kind,Set<String> targets,long required,long bonusXp,Long coins){this(id,activity,kind,targets,required,bonusXp,coins,Cadence.ONCE,"","");}
        public Quest(String id,ActivityKind activity,ObjectiveKind kind,Set<String> targets,long required,long bonusXp){this(id,activity,kind,targets,required,bonusXp,null);}
        public Quest{cadence=cadence==null?Cadence.ONCE:cadence;name=name==null?"":name;goal=goal==null?"":goal;coins=coins==null?100L:coins;require(coins>=0,INVALID_INPUT,"Negative quest Coins");content(id);Objects.requireNonNull(activity);Objects.requireNonNull(kind);targets=Set.copyOf(targets);positive(required);require(bonusXp>=0,INVALID_INPUT,"Negative quest XP");}
    }
    public record Definition(String id,String name,List<Long> thresholds,List<Reward> rewards,List<Quest> quests) {
        public Definition{content(id);text(name,"season name",100);thresholds=List.copyOf(thresholds);rewards=List.copyOf(rewards);quests=List.copyOf(quests);
            require(!thresholds.isEmpty()&&thresholds.size()<=1000,INVALID_INPUT,"Invalid season levels");long previous=0;
            for(long threshold:thresholds){require(threshold>previous,INVALID_INPUT,"Thresholds must increase");previous=threshold;}
            var keys=new HashSet<String>();for(var reward:rewards){require(reward.level<=thresholds.size(),INVALID_INPUT,"Reward exceeds season level");require(keys.add(reward.track+":"+reward.level+":"+reward.index),INVALID_INPUT,"Duplicate tier reward");}
            var questIds=new HashSet<String>();for(var quest:quests)require(questIds.add(quest.id),INVALID_INPUT,"Duplicate quest");}
    }
    /** Produced by trusted adapters AFTER successful gameplay. sourceInstance is a durable NPC death,
     * natural block generation or crop growth-cycle identity, not a new UUID for every callback. */
    public record Activity(UUID eventId,UUID actor,ActivityKind kind,String targetId,long quantity,long xp,String sourceInstance,boolean qualified,Instant occurredAt) {
        public Activity(UUID eventId,UUID actor,ActivityKind kind,String targetId,long quantity,long xp,String sourceInstance,boolean qualified){this(eventId,actor,kind,targetId,quantity,xp,sourceInstance,qualified,null);}
        public Activity{Objects.requireNonNull(eventId);Objects.requireNonNull(actor);Objects.requireNonNull(kind);text(targetId,"activity target",200);positive(quantity);require(xp>=0,INVALID_INPUT,"Negative XP");text(sourceInstance,"activity source identity",240);}
    }
    public record Progress(String id,String name,long xp,long totalXp,int level,boolean paid,boolean active) {}
    public record QuestProgress(Quest quest,long count,boolean completed) {}
    public record QuestNotice(String id,UUID actor,ActivityKind activity,long xp,long coins,boolean completion,String title) {public QuestNotice(String id,UUID actor,ActivityKind activity,long xp,long coins,boolean completion){this(id,actor,activity,xp,coins,completion,"");}}
    private final OwnershipService ownership;
    private final EconomyService economy;
    SeasonService(TransactionalStore s,Clock c,Supplier<UUID> i,OwnershipService ownership,EconomyService economy){super(s,c,i);this.ownership=ownership;this.economy=economy;}
    public static String paidEntitlementId(String seasonId){content(seasonId);return "eternia:season_paid/"+seasonId.replace(':','/');}
    /** Requirements and rewards are immutable; quest display text can be revised. */
    public void register(Definition definition){
        store.transaction(tx->{Map<String,String> encoded=encode(definition);var previous=tx.find("season_definition",definition.id);
            if(previous.isPresent()) {
                // Normalize fields absent from older definitions;
                // thresholds, objectives, XP and all established reward amounts stay immutable.
                var normalized=new TreeMap<>(previous.get().fields());
                for(int n=0;n<previous.get().number("questCount");n++){normalized.putIfAbsent("q"+n+".coins","100");normalized.putIfAbsent("q"+n+".cadence","ONCE");normalized.putIfAbsent("q"+n+".name","");normalized.putIfAbsent("q"+n+".goal","");}
                addFoundationsShirt(definition,normalized,encoded);
                if(prototypeUpgrade(definition,normalized,encoded))normalized=new TreeMap<>(encoded);
                // Copy only presentation fields. IDs, targets, cadence and rewards must still match.
                for(int n=0;n<previous.get().number("questCount");n++)for(String field:List.of("name","goal")){
                    String key="q"+n+"."+field;if(encoded.containsKey(key))normalized.put(key,encoded.get(key));
                }
                require(normalized.equals(encoded),CONFLICT,"Released season definitions are immutable: "+definition.id
                    +"; differing fields (stored -> incoming): "+definitionDifferences(normalized,encoded)
                    +". Restore the released definition or publish the changed season under a new ID; existing progress and rewards must be preserved.");
                if(!previous.get().fields().equals(encoded))tx.save("season_definition",definition.id,previous.get().revision(),encoded);
            }
            else tx.save("season_definition",definition.id,0,encoded);return null;});
    }
    private static String definitionDifferences(Map<String,String> stored,Map<String,String> incoming) {
        var keys=new TreeSet<>(stored.keySet());keys.addAll(incoming.keySet());
        var differences=keys.stream().filter(key->!Objects.equals(stored.get(key),incoming.get(key))).toList();
        return String.join("; ",differences.stream().limit(12)
            .map(key->key+" ["+diagnosticValue(stored.get(key))+" -> "+diagnosticValue(incoming.get(key))+"]").toList())
            +(differences.size()>12?"; ... ("+differences.size()+" differing fields total)":"");
    }
    private static String diagnosticValue(String value) {
        if(value==null)return "<missing>";
        String escaped=value.replace("\r","\\r").replace("\n","\\n").replace("\t","\\t");
        return escaped.length()>240?escaped.substring(0,240)+"...":escaped;
    }
    private static void addFoundationsShirt(Definition definition,Map<String,String> old,Map<String,String> next){
        if(!definition.id.equals("eternia:foundations"))return;
        var shirt=new Reward(Track.PAID,1,1,"eternia:wearable/prowl_shirt",OwnershipService.Kind.UNLOCK,1);
        if(definition.rewards.stream().filter(shirt::equals).count()!=1)return;
        var priorRewards=definition.rewards.stream().filter(r->!r.equals(shirt)).toList();
        var expected=encode(new Definition(definition.id,definition.name,definition.thresholds,priorRewards,definition.quests));
        for(var e:expected.entrySet())if((e.getKey().startsWith("r")&&e.getKey().contains(".")||e.getKey().equals("rewardCount"))&&!Objects.equals(old.get(e.getKey()),e.getValue()))return;
        // Add a new claim index. Existing Cacti claims and all other rewards retain their receipts.
        old.keySet().removeIf(k->k.matches("r[0-9]+\\..*")||k.equals("rewardCount"));
        next.forEach((k,v)->{if(k.matches("r[0-9]+\\..*")||k.equals("rewardCount"))old.put(k,v);});
    }
    public List<Definition> definitions(){return store.transaction(tx->tx.scan("season_definition").stream().map(SeasonService::definition).toList());}
    public void select(UUID actor,String seasonId){
        store.transaction(tx->{row(tx,"account",actor.toString());row(tx,"season_definition",seasonId);var existing=tx.find("active_season",actor.toString());
            tx.save("active_season",actor.toString(),existing.map(TransactionalStore.Row::revision).orElse(0L),fields("season",seasonId));progressRow(tx,actor,seasonId);return null;});
    }
    public boolean recordActivity(Activity event){
        if(!event.qualified)return false;
        return store.transaction(tx->{row(tx,"account",event.actor.toString());
            var input=fields("actor",event.actor,"kind",event.kind,"target",event.targetId,"quantity",event.quantity,"xp",event.xp,"source",event.sourceInstance);
            if(event.occurredAt!=null)input.put("occurredAt",event.occurredAt.toString());
            Instant occurred=event.occurredAt==null?clock.instant():event.occurredAt;
            if(!receipt(tx,"activity_event",event.eventId.toString(),input))return false;
            // A second notification with a different event id must not reuse the same successful source.
            String source=key(event.actor.toString(),event.kind.name(),event.sourceInstance);
            var prior=tx.find("activity_source",source);if(prior.isPresent()){require(prior.get().fields().equals(input),CONFLICT,"Activity source was reused with different data");return false;}
            tx.save("activity_source",source,0,input);updateStats(tx,event);
            var active=tx.find("active_season",event.actor.toString());if(active.isEmpty())return true;
            String seasonId=active.get().value("season");Definition season=definition(row(tx,"season_definition",seasonId));var progress=progressRow(tx,event.actor,seasonId);
            long xp=Math.addExact(progress.number("xp"),event.xp);
            for(var quest:availableQuests(season,occurred)) {
                if(quest.activity!=event.kind||!quest.targets.isEmpty()&&!QuestTargets.matches(quest.targets,event.targetId))continue;
                String id=objectiveKey(event.actor,seasonId,quest,occurred);var existing=tx.find("season_objective",id);var state=existing.map(r->new TreeMap<>(r.fields())).orElseGet(()->new TreeMap<>(fields("count",0,"complete",false)));
                if(state.get("complete").equals("true"))continue;
                long count=Long.parseLong(state.get("count"));
                if(quest.kind==ObjectiveKind.DISTINCT){String targetKey="seen."+key(QuestTargets.distinctTarget(event.kind,event.targetId));if(!state.containsKey(targetKey)){count=Math.addExact(count,1);state.put(targetKey,event.targetId);}}
                else count=Math.addExact(count,event.quantity);
                count=Math.min(count,quest.required);state.put("count",Long.toString(count));
                if(count>=quest.required){state.put("complete","true");xp=Math.addExact(xp,quest.bonusXp);awardQuestCoins(tx,event.actor,seasonId,quest,occurred);queueQuestNotice(tx,event.actor,seasonId,quest,true,occurred);}
                tx.save("season_objective",id,existing.map(TransactionalStore.Row::revision).orElse(0L),state);
            }
            save(tx,progress,"xp",xp);return true;});
    }
    public List<Progress> progress(UUID actor){
        return store.transaction(tx->{String active=tx.find("active_season",actor.toString()).map(r->r.value("season")).orElse("");
            var result=new ArrayList<Progress>();for(var definition:tx.scan("season_definition")){
                Definition season=definition(definition);long xp=tx.find("season_progress",key(actor.toString(),season.id)).map(r->r.number("xp")).orElse(0L);
                int level=(int)season.thresholds.stream().filter(v->v<=xp).count();result.add(new Progress(season.id,season.name,xp,season.thresholds.getLast(),level,ownership.ownsIn(tx,Owner.player(actor),paidEntitlementId(season.id)),active.equals(season.id)));
            }return List.copyOf(result);});
    }
    public List<QuestProgress> quests(UUID actor,String seasonId){
        return store.transaction(tx->{var season=definition(row(tx,"season_definition",seasonId));Instant now=clock.instant();
            var quests=availableQuests(season,now);
            var states=tx.findAll("season_objective",quests.stream().map(q->objectiveKey(actor,seasonId,q,now)).toList());
            return quests.stream().map(q->{var state=Optional.ofNullable(states.get(objectiveKey(actor,seasonId,q,now)));boolean completed=state.map(r->Boolean.parseBoolean(r.value("complete"))).orElse(false);if(completed&&awardQuestCoins(tx,actor,seasonId,q,now))queueQuestNotice(tx,actor,seasonId,q,false,now);return new QuestProgress(q,state.map(r->r.number("count")).orElse(0L),completed);}).toList();});
    }
    private boolean awardQuestCoins(TransactionalStore.Transaction tx,UUID actor,String seasonId,Quest quest,Instant at) {
        return quest.coins>0&&economy.creditIn(tx,Owner.player(actor),quest.coins,"season-quest:"+objectiveKey(actor,seasonId,quest,at));
    }
    private void queueQuestNotice(TransactionalStore.Transaction tx,UUID actor,String seasonId,Quest quest,boolean completion,Instant at) {
        String id=objectiveKey(actor,seasonId,quest,at);
        if(tx.find("quest_notice",id).isEmpty())tx.save("quest_notice",id,0,fields("actor",actor,"activity",quest.activity,"title",quest.name,"xp",completion?quest.bonusXp:0,"coins",quest.coins,"completion",completion,"createdAt",clock.instant(),"state","PENDING"));
    }
    /** One queued toast per online player in each batch. Offline players cannot starve the queue. */
    public List<QuestNotice> pendingQuestNotices(Set<UUID> online,int limit) {
        require(limit>0&&limit<=100,INVALID_INPUT,"Invalid quest notification batch");var actors=Set.copyOf(online);if(actors.isEmpty())return List.of();
        return store.transaction(tx->{var result=new ArrayList<QuestNotice>();var selected=new HashSet<UUID>();
            for(var row:tx.scan("quest_notice").stream().filter(r->r.value("state").equals("PENDING")).sorted(Comparator.comparing(r->r.value("createdAt"))).toList()) {
                UUID actor=UUID.fromString(row.value("actor"));if(!actors.contains(actor)||!selected.add(actor))continue;
                result.add(new QuestNotice(row.key(),actor,ActivityKind.valueOf(row.value("activity")),row.number("xp"),row.number("coins"),Boolean.parseBoolean(row.value("completion")),row.value("title")));if(result.size()==limit)break;
            }return List.copyOf(result);});
    }
    public void acknowledgeQuestNotice(UUID actor,String id) {
        store.transaction(tx->{var notice=row(tx,"quest_notice",id);require(notice.value("actor").equals(actor.toString()),FORBIDDEN,"Quest notification belongs to another player");if(notice.value("state").equals("PENDING"))save(tx,notice,"state","SENT");return null;});
    }
    public OwnershipService.Grant claimReward(UUID actor,String seasonId,Track track,int level,int index){
        return store.transaction(tx->{Definition season=definition(row(tx,"season_definition",seasonId));
            var reward=season.rewards.stream().filter(r->r.track==track&&r.level==level&&r.index==index).findFirst().orElseThrow(()->new DomainException(NOT_FOUND,"Reward not found"));
            require(track==Track.FREE||ownership.ownsIn(tx,Owner.player(actor),paidEntitlementId(seasonId)),FORBIDDEN,"Paid track is not owned");
            require(progressRow(tx,actor,seasonId).number("xp")>=season.thresholds.get(level-1),INVALID_STATE,"Reward level has not been reached");
            return ownership.grantIn(tx,new OwnershipService.GrantRequest("season:"+key(actor.toString(),seasonId,track.name(),Integer.toString(level),Integer.toString(index)),Owner.player(actor),reward.contentId,reward.kind,reward.quantity,null));});
    }
    public boolean isClaimed(UUID actor,String seasonId,Track track,int level,int index){return store.transaction(tx->tx.find("grant_receipt",key("season:"+key(actor.toString(),seasonId,track.name(),Integer.toString(level),Integer.toString(index)))).isPresent());}
    public Set<Reward> claimedRewards(UUID actor,Definition season) {
        return store.transaction(tx->{
            var keys=new HashMap<String,Reward>();
            for(var reward:season.rewards())keys.put(key("season:"+key(actor.toString(),season.id(),reward.track().name(),Integer.toString(reward.level()),Integer.toString(reward.index()))),reward);
            var claimed=new HashSet<Reward>();
            tx.findAll("grant_receipt",keys.keySet()).keySet().forEach(id->claimed.add(keys.get(id)));
            return Set.copyOf(claimed);
        });
    }
    private static TransactionalStore.Row progressRow(TransactionalStore.Transaction tx,UUID actor,String seasonId){String id=key(actor.toString(),seasonId);return tx.find("season_progress",id).orElseGet(()->tx.save("season_progress",id,0,fields("actor",actor,"season",seasonId,"xp",0)));}
    private void updateStats(TransactionalStore.Transaction tx,Activity event){
        var previous=tx.find("activity_stats",event.actor.toString());long kills=previous.map(r->r.number("kills")).orElse(0L),resources=previous.map(r->r.number("resources")).orElse(0L);
        if(event.kind==ActivityKind.KILL)kills=Math.addExact(kills,event.quantity);
        if(event.kind==ActivityKind.MINE||event.kind==ActivityKind.HARVEST)resources=Math.addExact(resources,event.quantity);
        tx.save("activity_stats",event.actor.toString(),previous.map(TransactionalStore.Row::revision).orElse(0L),fields("kills",kills,"resources",resources,"updatedAt",clock.instant()));
    }
    /** Stable choices for each UTC window. One daily and weekly per activity avoids overwhelming the journal. */
    public static List<Quest> availableQuests(Definition season,Instant at){
        var result=new ArrayList<Quest>();
        for(var cadence:List.of(Cadence.DAILY,Cadence.WEEKLY)){
            var groups=new EnumMap<ActivityKind,List<Quest>>(ActivityKind.class);
            for(var quest:season.quests)if(quest.cadence==cadence)groups.computeIfAbsent(quest.activity,k->new ArrayList<>()).add(quest);
            long slot=LocalDate.parse(period(cadence,at)).toEpochDay();if(cadence==Cadence.WEEKLY)slot=Math.floorDiv(slot,7);
            for(var entry:groups.entrySet()){var group=entry.getValue();group.sort(Comparator.comparing(Quest::id));result.add(group.get((int)Math.floorMod(slot+entry.getKey().ordinal()*3,group.size())));}
        }
        for(var quest:season.quests)if(quest.cadence==Cadence.ONCE)result.add(quest);
        return List.copyOf(result);
    }
    public static String period(Cadence cadence,Instant at){
        var date=at.atZone(ZoneOffset.UTC).toLocalDate();
        return switch(cadence){case ONCE->"";case DAILY->date.toString();case WEEKLY->date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();};
    }
    public static Instant resetsAt(Cadence cadence,Instant at){
        if(cadence==Cadence.ONCE)return null;
        var date=LocalDate.parse(period(cadence,at));return date.plusDays(cadence==Cadence.DAILY?1:7).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
    private static String objectiveKey(UUID actor,String season,Quest quest,Instant at){
        return quest.cadence==Cadence.ONCE?key(actor.toString(),season,quest.id):key(actor.toString(),season,quest.id,quest.cadence.name(),period(quest.cadence,at));
    }
    /** The shipped prototype's three lifetime goals may be replaced once, without changing pass rewards or XP. */
    private static boolean prototypeUpgrade(Definition next,Map<String,String> old,Map<String,String> replacement){
        if(!next.id.equals("eternia:foundations")||!old.getOrDefault("questCount","").equals("3"))return false;
        String[] ids={"hunt","miner","harvest"},amounts={"50","12","100"},kinds={"KILL","MINE","HARVEST"};
        for(int i=0;i<3;i++)if(!old.getOrDefault("q"+i+".id","").equals("eternia:quest/"+ids[i])||!old.get("q"+i+".required").equals(amounts[i])||!old.get("q"+i+".activity").equals(kinds[i])||!old.get("q"+i+".bonus").equals("8000")||!old.get("q"+i+".coins").equals("100")||!old.get("q"+i+".targets").isEmpty()||!old.get("q"+i+".cadence").equals("ONCE")||!old.get("q"+i+".kind").equals(i==1?"DISTINCT":"COUNT")||!old.get("q"+i+".name").isEmpty()||!old.get("q"+i+".goal").isEmpty())return false;
        var a=new TreeMap<>(old);var b=new TreeMap<>(replacement);
        a.keySet().removeIf(k->k.equals("questCount")||k.matches("q[0-9]+\\..*"));b.keySet().removeIf(k->k.equals("questCount")||k.matches("q[0-9]+\\..*"));
        return a.equals(b)&&next.quests.stream().allMatch(q->q.cadence!=Cadence.ONCE);
    }
    private static Map<String,String> encode(Definition d){
        var data=fields("name",d.name,"thresholds",String.join(",",d.thresholds.stream().map(Object::toString).toList()),"rewardCount",d.rewards.size(),"questCount",d.quests.size());
        for(int i=0;i<d.rewards.size();i++){var r=d.rewards.get(i);data.putAll(fields("r"+i+".track",r.track,"r"+i+".level",r.level,"r"+i+".index",r.index,"r"+i+".content",r.contentId,"r"+i+".kind",r.kind,"r"+i+".quantity",r.quantity));}
        for(int i=0;i<d.quests.size();i++){var q=d.quests.get(i);data.putAll(fields("q"+i+".id",q.id,"q"+i+".activity",q.activity,"q"+i+".kind",q.kind,"q"+i+".targets",String.join("\n",new TreeSet<>(q.targets)),"q"+i+".required",q.required,"q"+i+".bonus",q.bonusXp,"q"+i+".coins",q.coins,"q"+i+".cadence",q.cadence,"q"+i+".name",q.name,"q"+i+".goal",q.goal));}return data;
    }
    private static Definition definition(TransactionalStore.Row r){
        var rewards=new ArrayList<Reward>();for(int i=0;i<r.number("rewardCount");i++)rewards.add(new Reward(Track.valueOf(r.value("r"+i+".track")),Integer.parseInt(r.value("r"+i+".level")),Integer.parseInt(r.value("r"+i+".index")),r.value("r"+i+".content"),OwnershipService.Kind.valueOf(r.value("r"+i+".kind")),r.number("r"+i+".quantity")));
        var quests=new ArrayList<Quest>();for(int i=0;i<r.number("questCount");i++)quests.add(new Quest(r.value("q"+i+".id"),ActivityKind.valueOf(r.value("q"+i+".activity")),ObjectiveKind.valueOf(r.value("q"+i+".kind")),r.value("q"+i+".targets").isEmpty()?Set.of():Set.of(r.value("q"+i+".targets").split("\n")),r.number("q"+i+".required"),r.number("q"+i+".bonus"),r.fields().containsKey("q"+i+".coins")?r.number("q"+i+".coins"):100L,r.value("q"+i+".cadence").isEmpty()?Cadence.ONCE:Cadence.valueOf(r.value("q"+i+".cadence")),r.value("q"+i+".name"),r.value("q"+i+".goal")));
        return new Definition(r.key(),r.value("name"),Arrays.stream(r.value("thresholds").split(",")).map(Long::parseLong).toList(),rewards,quests);
    }
}
