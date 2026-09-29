package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class SeasonService extends DomainSupport {
    public enum Track { FREE, PAID }
    public enum ActivityKind { KILL, MINE, HARVEST, ACQUIRE, MINIGAME, INTEGRATION }
    public enum ObjectiveKind { COUNT, DISTINCT }
    public record Reward(Track track,int level,int index,String contentId,OwnershipService.Kind kind,long quantity) {
        public Reward{Objects.requireNonNull(track);require(level>0&&index>=0,INVALID_INPUT,"Invalid reward tier");content(contentId);Objects.requireNonNull(kind);positive(quantity);}
    }
    public record Quest(String id,ActivityKind activity,ObjectiveKind kind,Set<String> targets,long required,long bonusXp) {
        public Quest{content(id);Objects.requireNonNull(activity);Objects.requireNonNull(kind);targets=Set.copyOf(targets);positive(required);require(bonusXp>=0,INVALID_INPUT,"Negative quest XP");}
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
    public record Activity(UUID eventId,UUID actor,ActivityKind kind,String targetId,long quantity,long xp,String sourceInstance,boolean qualified) {
        public Activity{Objects.requireNonNull(eventId);Objects.requireNonNull(actor);Objects.requireNonNull(kind);text(targetId,"activity target",200);positive(quantity);require(xp>=0,INVALID_INPUT,"Negative XP");text(sourceInstance,"activity source identity",240);}
    }
    public record Progress(String id,String name,long xp,long totalXp,int level,boolean paid,boolean active) {}
    public record QuestProgress(Quest quest,long count,boolean completed) {}
    private final OwnershipService ownership;
    SeasonService(TransactionalStore s,Clock c,Supplier<UUID> i,OwnershipService ownership){super(s,c,i);this.ownership=ownership;}
    public static String paidEntitlementId(String seasonId){content(seasonId);return "eternia:season_paid/"+seasonId.replace(':','/');}
    /** Definitions are immutable once registered, preserving archived pass requirements and rewards. */
    public void register(Definition definition){
        store.transaction(tx->{Map<String,String> encoded=encode(definition);var previous=tx.find("season_definition",definition.id);
            if(previous.isPresent())require(previous.get().fields().equals(encoded),CONFLICT,"Released season definitions are immutable");
            else tx.save("season_definition",definition.id,0,encoded);return null;});
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
            if(!receipt(tx,"activity_event",event.eventId.toString(),input))return false;
            // A second notification with a different event id must not reuse the same successful source.
            String source=key(event.actor.toString(),event.kind.name(),event.sourceInstance);
            var prior=tx.find("activity_source",source);if(prior.isPresent()){require(prior.get().fields().equals(input),CONFLICT,"Activity source was reused with different data");return false;}
            tx.save("activity_source",source,0,input);updateStats(tx,event);
            var active=tx.find("active_season",event.actor.toString());if(active.isEmpty())return true;
            String seasonId=active.get().value("season");Definition season=definition(row(tx,"season_definition",seasonId));var progress=progressRow(tx,event.actor,seasonId);
            long xp=Math.addExact(progress.number("xp"),event.xp);
            for(var quest:season.quests) {
                if(quest.activity!=event.kind||!quest.targets.isEmpty()&&!quest.targets.contains(event.targetId))continue;
                String id=key(event.actor.toString(),seasonId,quest.id);var existing=tx.find("season_objective",id);var state=existing.map(r->new TreeMap<>(r.fields())).orElseGet(()->new TreeMap<>(fields("count",0,"complete",false)));
                if(state.get("complete").equals("true"))continue;
                long count=Long.parseLong(state.get("count"));
                if(quest.kind==ObjectiveKind.DISTINCT){String targetKey="seen."+key(event.targetId);if(!state.containsKey(targetKey)){count=Math.addExact(count,1);state.put(targetKey,event.targetId);}}
                else count=Math.addExact(count,event.quantity);
                count=Math.min(count,quest.required);state.put("count",Long.toString(count));
                if(count>=quest.required){state.put("complete","true");xp=Math.addExact(xp,quest.bonusXp);}
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
        return store.transaction(tx->{var season=definition(row(tx,"season_definition",seasonId));return season.quests.stream().map(q->{var state=tx.find("season_objective",key(actor.toString(),seasonId,q.id));return new QuestProgress(q,state.map(r->r.number("count")).orElse(0L),state.map(r->Boolean.parseBoolean(r.value("complete"))).orElse(false));}).toList();});
    }
    public OwnershipService.Grant claimReward(UUID actor,String seasonId,Track track,int level,int index){
        return store.transaction(tx->{Definition season=definition(row(tx,"season_definition",seasonId));
            var reward=season.rewards.stream().filter(r->r.track==track&&r.level==level&&r.index==index).findFirst().orElseThrow(()->new DomainException(NOT_FOUND,"Reward not found"));
            require(track==Track.FREE||ownership.ownsIn(tx,Owner.player(actor),paidEntitlementId(seasonId)),FORBIDDEN,"Paid track is not owned");
            require(progressRow(tx,actor,seasonId).number("xp")>=season.thresholds.get(level-1),INVALID_STATE,"Reward level has not been reached");
            return ownership.grantIn(tx,new OwnershipService.GrantRequest("season:"+key(actor.toString(),seasonId,track.name(),Integer.toString(level),Integer.toString(index)),Owner.player(actor),reward.contentId,reward.kind,reward.quantity,null));});
    }
    public boolean isClaimed(UUID actor,String seasonId,Track track,int level,int index){return store.transaction(tx->tx.find("grant_receipt",key("season:"+key(actor.toString(),seasonId,track.name(),Integer.toString(level),Integer.toString(index)))).isPresent());}
    private static TransactionalStore.Row progressRow(TransactionalStore.Transaction tx,UUID actor,String seasonId){String id=key(actor.toString(),seasonId);return tx.find("season_progress",id).orElseGet(()->tx.save("season_progress",id,0,fields("actor",actor,"season",seasonId,"xp",0)));}
    private void updateStats(TransactionalStore.Transaction tx,Activity event){
        var previous=tx.find("activity_stats",event.actor.toString());long kills=previous.map(r->r.number("kills")).orElse(0L),resources=previous.map(r->r.number("resources")).orElse(0L);
        if(event.kind==ActivityKind.KILL)kills=Math.addExact(kills,event.quantity);
        if(event.kind==ActivityKind.MINE||event.kind==ActivityKind.HARVEST)resources=Math.addExact(resources,event.quantity);
        tx.save("activity_stats",event.actor.toString(),previous.map(TransactionalStore.Row::revision).orElse(0L),fields("kills",kills,"resources",resources,"updatedAt",clock.instant()));
    }
    private static Map<String,String> encode(Definition d){
        var data=fields("name",d.name,"thresholds",String.join(",",d.thresholds.stream().map(Object::toString).toList()),"rewardCount",d.rewards.size(),"questCount",d.quests.size());
        for(int i=0;i<d.rewards.size();i++){var r=d.rewards.get(i);data.putAll(fields("r"+i+".track",r.track,"r"+i+".level",r.level,"r"+i+".index",r.index,"r"+i+".content",r.contentId,"r"+i+".kind",r.kind,"r"+i+".quantity",r.quantity));}
        for(int i=0;i<d.quests.size();i++){var q=d.quests.get(i);data.putAll(fields("q"+i+".id",q.id,"q"+i+".activity",q.activity,"q"+i+".kind",q.kind,"q"+i+".targets",String.join("\n",new TreeSet<>(q.targets)),"q"+i+".required",q.required,"q"+i+".bonus",q.bonusXp));}return data;
    }
    private static Definition definition(TransactionalStore.Row r){
        var rewards=new ArrayList<Reward>();for(int i=0;i<r.number("rewardCount");i++)rewards.add(new Reward(Track.valueOf(r.value("r"+i+".track")),Integer.parseInt(r.value("r"+i+".level")),Integer.parseInt(r.value("r"+i+".index")),r.value("r"+i+".content"),OwnershipService.Kind.valueOf(r.value("r"+i+".kind")),r.number("r"+i+".quantity")));
        var quests=new ArrayList<Quest>();for(int i=0;i<r.number("questCount");i++)quests.add(new Quest(r.value("q"+i+".id"),ActivityKind.valueOf(r.value("q"+i+".activity")),ObjectiveKind.valueOf(r.value("q"+i+".kind")),r.value("q"+i+".targets").isEmpty()?Set.of():Set.of(r.value("q"+i+".targets").split("\n")),r.number("q"+i+".required"),r.number("q"+i+".bonus")));
        return new Definition(r.key(),r.value("name"),Arrays.stream(r.value("thresholds").split(",")).map(Long::parseLong).toList(),rewards,quests);
    }
}
