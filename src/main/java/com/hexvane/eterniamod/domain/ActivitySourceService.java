package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Persistent world-generation provenance and bounded durable activity outbox. Native adapters alone call it. */
public final class ActivitySourceService extends DomainSupport {
    private final SeasonService seasons;
    private final EconomyService economy;
    private volatile Map<SeasonService.ActivityKind,Map<String,Long>> coinRewards=Map.of();
    ActivitySourceService(TransactionalStore s,Clock c,Supplier<UUID> i,SeasonService seasons,EconomyService economy){super(s,c,i);this.seasons=seasons;this.economy=economy;}
    /** Server configuration only. Each source snapshots its reward when first accepted; changing
     * this map never changes queued or already delivered rewards. */
    public void configureCoinRewards(Map<SeasonService.ActivityKind,Map<String,Long>> configured) {
        Objects.requireNonNull(configured);var rewards=new EnumMap<SeasonService.ActivityKind,Map<String,Long>>(SeasonService.ActivityKind.class);
        for(var entry:configured.entrySet()) {
            require(Set.of(SeasonService.ActivityKind.KILL,SeasonService.ActivityKind.MINE,SeasonService.ActivityKind.HARVEST).contains(entry.getKey()),INVALID_INPUT,"Only verified kill, mining and harvest activities can award coins");
            var targets=Map.copyOf(entry.getValue());require(targets.size()<=10000,INVALID_INPUT,"Too many activity coin targets");
            targets.forEach((target,coins)->{text(target,"coin activity target",200);require(coins>=0&&coins<=100000,INVALID_INPUT,"Activity coin rewards must be whole numbers from 0 to 100000");});rewards.put(entry.getKey(),targets);
        }
        coinRewards=Map.copyOf(rewards);
    }
    public record Block(UUID world,int x,int y,int z){public Block{Objects.requireNonNull(world);}}
    /** Persist before a placement is allowed. Even a subsequently cancelled placement conservatively disables mining rewards at this position. */
    public void markPlayerPlacement(Block block){store.transaction(tx->{String id=blockKey(block);var old=tx.find("activity_block",id);if(old.isEmpty())tx.save("activity_block",id,0,fields("placed",true));else if(!old.get().value("placed").equals("true"))save(tx,old.get(),"placed",true);return null;});}
    /** One natural mining award per position in a world UUID; placing and breaking it cannot create a new source. */
    public boolean verifiedNaturalBreak(UUID actor,Block block,String target,long xp){
        return store.transaction(tx->{String id=blockKey(block);if(tx.find("activity_block",id).map(r->r.value("placed").equals("true")).orElse(false))return false;return enqueue(tx,actor,SeasonService.ActivityKind.MINE,target,xp,"mine:"+id);});
    }
    public boolean verifiedDeath(UUID actor,UUID world,UUID entity,String target,long xp){return store.transaction(tx->enqueue(tx,actor,SeasonService.ActivityKind.KILL,target,xp,"death:"+world+":"+entity));}
    /** The verified farming generation and original persistent position identify a crop cycle across restarts. */
    public boolean verifiedHarvest(UUID actor,Block block,String target,int nativeGeneration,long xp){
        require(nativeGeneration>=0,INVALID_INPUT,"Invalid farming generation");
        return store.transaction(tx->enqueue(tx,actor,SeasonService.ActivityKind.HARVEST,target,xp,"harvest:"+blockKey(block)+":"+nativeGeneration));
    }
    private boolean enqueue(TransactionalStore.Transaction tx,UUID actor,SeasonService.ActivityKind kind,String target,long xp,String source){
        Objects.requireNonNull(actor);text(target,"activity target",200);require(xp>=0&&xp<=100000,INVALID_INPUT,"Invalid configured XP");String id=key(source);
        if(tx.find("activity_outbox",id).isPresent())return false;
        if(tx.find("account",actor.toString()).isEmpty())return false;
        UUID event=UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long coins=coinRewards.getOrDefault(kind,Map.of()).getOrDefault(target,0L);
        tx.save("activity_outbox",id,0,fields("event",event,"actor",actor,"kind",kind,"target",target,"xp",xp,"coins",coins,"source",source,"state","READY"));return true;
    }
    /** XP and coins have independent stable receipts. A retry after XP committed still credits
     * pending coins, and a retry after the ledger committed never pays twice. */
    public int drain(int limit){
        require(limit>0&&limit<=500,INVALID_INPUT,"Invalid activity batch");var batch=store.transaction(tx->tx.scan("activity_outbox").stream().filter(r->r.value("state").equals("READY")).limit(limit).toList());
        for(var row:batch){seasons.recordActivity(new SeasonService.Activity(UUID.fromString(row.value("event")),UUID.fromString(row.value("actor")),SeasonService.ActivityKind.valueOf(row.value("kind")),row.value("target"),1,row.number("xp"),row.value("source"),true));
            // Rows written before coin rewards existed intentionally pay zero, even after reconfiguration.
            long coins=row.value("coins").isEmpty()?0:row.number("coins");
            if(coins>0)economy.credit(Owner.player(UUID.fromString(row.value("actor"))),coins,"activity-coins:"+row.key());
            store.transaction(tx->{var latest=row(tx,"activity_outbox",row.key());if(latest.value("state").equals("READY"))save(tx,latest,"state","DELIVERED");return null;});}
        return batch.size();
    }
    private static String blockKey(Block b){return key(b.world.toString(),Integer.toString(b.x),Integer.toString(b.y),Integer.toString(b.z));}
}
