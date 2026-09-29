package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Authored adventure discoveries. Only a native adapter may construct use evidence. */
public final class DiscoveryService extends DomainSupport {
    public record Reward(String contentId,OwnershipService.Kind kind,long quantity) {
        public Reward {content(contentId);Objects.requireNonNull(kind);positive(quantity);require(quantity<=10000,INVALID_INPUT,"Discovery quantity is too large");require(kind==OwnershipService.Kind.QUANTITY||quantity==1,INVALID_INPUT,"Unlocks and capabilities have quantity one");}
    }
    public record Definition(String id,String world,int x,int y,int z,String markerBlockId,Reward reward) {
        public Definition {text(id,"discovery id",100);require(id.matches("[a-z0-9][a-z0-9_.-]*"),INVALID_INPUT,"Invalid discovery id");text(world,"discovery world",200);text(markerBlockId,"discovery marker block",200);Objects.requireNonNull(reward);}
    }
    public record Evidence(UUID worldId,String world,int x,int y,int z,String nativeBlockId,boolean adventurePlayer,boolean adventureWorld,double distanceSquared) {
        public Evidence {Objects.requireNonNull(worldId);Objects.requireNonNull(world);Objects.requireNonNull(nativeBlockId);}
    }
    public record Result(boolean firstCollection,String discoveryId,Reward reward,String sourceReceipt) {}
    private final OwnershipService ownership;
    DiscoveryService(TransactionalStore store,Clock clock,Supplier<UUID> ids,OwnershipService ownership){super(store,clock,ids);this.ownership=ownership;}
    public Result redeem(UUID actor,Definition definition,Evidence evidence) {
        Objects.requireNonNull(actor);Objects.requireNonNull(definition);Objects.requireNonNull(evidence);
        require(evidence.adventurePlayer&&evidence.adventureWorld,FORBIDDEN,"Discoveries are collected in Adventure worlds while playing Adventure mode");
        require(definition.world.equals(evidence.world)&&definition.x==evidence.x&&definition.y==evidence.y&&definition.z==evidence.z,FORBIDDEN,"This is not the authored discovery location");
        require(definition.markerBlockId.equals(evidence.nativeBlockId),FORBIDDEN,"The discovery marker is missing or changed");
        require(Double.isFinite(evidence.distanceSquared)&&evidence.distanceSquared>=0&&evidence.distanceSquared<=36,FORBIDDEN,"Move closer to the discovery cache");
        return store.transaction(tx->{
            row(tx,"account",actor.toString());String receipt="discovery:"+definition.id+":"+actor;String id=key(receipt);var reward=definition.reward;
            var immutableReward=fields("content",reward.contentId,"kind",reward.kind,"quantity",reward.quantity);
            var authored=tx.find("discovery_reward",definition.id);
            if(authored.isPresent())require(authored.get().fields().equals(immutableReward),CONFLICT,"A discovery with collected rewards cannot change its reward");
            else tx.save("discovery_reward",definition.id,0,immutableReward);
            var previous=tx.find("discovery_claim",id);
            if(previous.isPresent()) {
                var p=previous.get();require(p.value("content").equals(reward.contentId)&&p.value("kind").equals(reward.kind.name())&&p.number("quantity")==reward.quantity,CONFLICT,"An already collected discovery cannot be assigned a different reward");
                return new Result(false,definition.id,reward,receipt);
            }
            ownership.grantIn(tx,new OwnershipService.GrantRequest(receipt,Owner.player(actor),reward.contentId,reward.kind,reward.quantity,null));
            tx.save("discovery_claim",id,0,fields("player",actor,"discovery",definition.id,"content",reward.contentId,"kind",reward.kind,"quantity",reward.quantity,
                "world",evidence.world,"worldUuid",evidence.worldId,"x",evidence.x,"y",evidence.y,"z",evidence.z,"block",evidence.nativeBlockId,"at",clock.instant(),"receipt",receipt));
            return new Result(true,definition.id,reward,receipt);
        });
    }
}
