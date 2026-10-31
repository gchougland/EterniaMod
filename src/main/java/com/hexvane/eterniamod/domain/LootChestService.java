package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** One durable copy of each world loot chest per player. A record is never deleted, so emptying a chest cannot earn a fresh roll. */
public final class LootChestService extends DomainSupport {
    private static final String NAMESPACE="loot_chest";
    private static final int MAX_CONTENTS=1024*1024;
    public record Chest(UUID worldId,int x,int y,int z,String instance,String droplist) {
        public Chest {Objects.requireNonNull(worldId);text(instance,"chest instance",64);text(droplist,"chest droplist",200);}
    }
    public record Opened(String contents,boolean firstOpen) {}
    LootChestService(TransactionalStore store,Clock clock,Supplier<UUID> ids){super(store,clock,ids);}
    /** Rolls outside the transaction, because rolling reads the game. If two opens race, the first saved roll wins. */
    public Opened open(UUID player,Chest chest,Supplier<String> roll) {
        Objects.requireNonNull(player);Objects.requireNonNull(chest);Objects.requireNonNull(roll);String id=id(player,chest);
        var existing=store.transaction(tx->tx.find(NAMESPACE,id).map(r->r.value("contents")));
        if(existing.isPresent())return new Opened(existing.get(),false);
        String rolled=text(roll.get(),"chest contents",MAX_CONTENTS);
        return store.transaction(tx->{
            var raced=tx.find(NAMESPACE,id);
            if(raced.isPresent())return new Opened(raced.get().value("contents"),false);
            var now=clock.instant();
            tx.save(NAMESPACE,id,0,fields("player",player,"world",chest.worldId,"x",chest.x,"y",chest.y,"z",chest.z,"instance",chest.instance,"droplist",chest.droplist,"contents",rolled,"rolledAt",now,"updatedAt",now));
            return new Opened(rolled,true);
        });
    }
    public void save(UUID player,Chest chest,String contents) {
        Objects.requireNonNull(player);Objects.requireNonNull(chest);text(contents,"chest contents",MAX_CONTENTS);String id=id(player,chest);
        store.transaction(tx->save(tx,row(tx,NAMESPACE,id),"contents",contents,"updatedAt",clock.instant()));
    }
    private static String id(UUID player,Chest chest){return key(player.toString(),chest.worldId.toString(),Integer.toString(chest.x),Integer.toString(chest.y),Integer.toString(chest.z),chest.instance);}
}
