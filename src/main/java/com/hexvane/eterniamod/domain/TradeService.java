package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class TradeService extends DomainSupport {
    public record Offer(List<String> escrowIds,long coins) {public Offer{escrowIds=List.copyOf(escrowIds);require(coins>=0&&escrowIds.size()<=12&&new HashSet<>(escrowIds).size()==escrowIds.size(),INVALID_INPUT,"Invalid trade offer");}}
    public record Trade(UUID id,UUID first,UUID second,Offer firstOffer,Offer secondOffer,long offerRevision,boolean firstConfirmed,boolean secondConfirmed,String state,Instant expiresAt){}
    private final EconomyService economy;private final EscrowService escrow;
    TradeService(TransactionalStore s,Clock c,Supplier<UUID> i,EconomyService economy,EscrowService escrow){super(s,c,i);this.economy=economy;this.escrow=escrow;}
    public Trade open(UUID first,UUID second){
        require(!first.equals(second),INVALID_INPUT,"Trade requires two players");
        return store.transaction(tx->{row(tx,"account",first.toString());row(tx,"account",second.toString());UUID id=ids.get();
            return trade(tx.save("trade",id.toString(),0,fields("first",first,"second",second,"aItems","","bItems","","aCoins",0,"bCoins",0,"aReserve","","bReserve","","aConfirmed",false,"bConfirmed",false,"offerRevision",0,"state","OPEN","expires",clock.instant().plus(Duration.ofMinutes(5)))));});
    }
    public Trade offer(UUID actor,UUID tradeId,long expectedOfferRevision,Offer offer){
        return store.transaction(tx->{var r=participant(tx,actor,tradeId);requireOpen(r);require(r.number("offerRevision")==expectedOfferRevision,CONFLICT,"Trade offer changed");
            String side=r.value("first").equals(actor.toString())?"a":"b";releaseSide(tx,r,side);String holder="trade:"+tradeId+":"+side;
            for(String id:offer.escrowIds){var stock=escrow.holdIn(tx,id,Owner.player(actor),holder);require(Boolean.parseBoolean(stock.value("transferable")),FORBIDDEN,"Bound items cannot be traded");}
            long version=Math.addExact(r.number("offerRevision"),1);String reserve=offer.coins>0?economy.reserveIn(tx,Owner.player(actor),offer.coins,"trade:"+tradeId+":"+side+":"+version):"";
            return trade(save(tx,r,side+"Items",String.join("\n",offer.escrowIds),side+"Coins",offer.coins,side+"Reserve",reserve,"aConfirmed",false,"bConfirmed",false,"offerRevision",version));});
    }
    public Trade confirm(UUID actor,UUID tradeId,long expectedOfferRevision){
        return store.transaction(tx->{var r=participant(tx,actor,tradeId);requireOpen(r);require(r.number("offerRevision")==expectedOfferRevision,CONFLICT,"Trade offer changed");
            String side=r.value("first").equals(actor.toString())?"a":"b";r=save(tx,r,side+"Confirmed",true);
            if(r.value("aConfirmed").equals("true")&&r.value("bConfirmed").equals("true")){
                require(!r.value("aItems").isEmpty()||!r.value("bItems").isEmpty()||r.number("aCoins")>0||r.number("bCoins")>0,INVALID_INPUT,"Trade is empty");
                settleSide(tx,r,"a",UUID.fromString(r.value("second")));settleSide(tx,r,"b",UUID.fromString(r.value("first")));r=save(tx,r,"state","COMPLETED");
            }return trade(r);});
    }
    public Trade cancel(UUID actor,UUID tradeId){
        return store.transaction(tx->{var r=participant(tx,actor,tradeId);if(r.value("state").equals("CANCELLED"))return trade(r);require(r.value("state").equals("OPEN"),INVALID_STATE,"Trade has completed");
            releaseSide(tx,r,"a");releaseSide(tx,r,"b");return trade(save(tx,r,"state","CANCELLED"));});
    }
    public int expire(){return store.transaction(tx->{int count=0;for(var r:tx.scan("trade"))if(r.value("state").equals("OPEN")&&!future(r.value("expires"),clock.instant())){releaseSide(tx,r,"a");releaseSide(tx,r,"b");save(tx,r,"state","CANCELLED");count++;}return count;});}
    public Optional<Trade> find(UUID actor,UUID tradeId){return store.transaction(tx->tx.find("trade",tradeId.toString()).map(r->{participant(tx,actor,tradeId);return trade(r);}));}
    public List<Trade> forPlayer(UUID actor){return store.transaction(tx->tx.scan("trade").stream().filter(r->r.value("first").equals(actor.toString())||r.value("second").equals(actor.toString())).map(TradeService::trade).toList());}
    private void releaseSide(TransactionalStore.Transaction tx,TransactionalStore.Row r,String side){
        for(String id:items(r,side))escrow.releaseIn(tx,id,"trade:"+r.key()+":"+side);
        if(!r.value(side+"Reserve").isEmpty())economy.releaseIn(tx,r.value(side+"Reserve"));
    }
    private void settleSide(TransactionalStore.Transaction tx,TransactionalStore.Row r,String side,UUID recipient){
        if(!r.value(side+"Reserve").isEmpty())economy.settleIn(tx,r.value(side+"Reserve"),Owner.player(recipient));
        for(String id:items(r,side)){var stock=row(tx,"escrow",id);escrow.deliverHeldIn(tx,id,"trade:"+r.key()+":"+side,Owner.player(recipient),stock.number("remaining"),"trade-delivery:"+r.key()+":"+side+":"+id);}
    }
    private void requireOpen(TransactionalStore.Row r){require(r.value("state").equals("OPEN")&&future(r.value("expires"),clock.instant()),INVALID_STATE,"Trade is completed or expired");}
    private static TransactionalStore.Row participant(TransactionalStore.Transaction tx,UUID actor,UUID id){var r=row(tx,"trade",id.toString());require(r.value("first").equals(actor.toString())||r.value("second").equals(actor.toString()),FORBIDDEN,"Not a trade participant");return r;}
    private static List<String> items(TransactionalStore.Row r,String side){return r.value(side+"Items").isEmpty()?List.of():List.of(r.value(side+"Items").split("\n"));}
    private static Trade trade(TransactionalStore.Row r){return new Trade(UUID.fromString(r.key()),UUID.fromString(r.value("first")),UUID.fromString(r.value("second")),new Offer(items(r,"a"),r.number("aCoins")),new Offer(items(r,"b"),r.number("bCoins")),r.number("offerRevision"),Boolean.parseBoolean(r.value("aConfirmed")),Boolean.parseBoolean(r.value("bConfirmed")),r.value("state"),Instant.parse(r.value("expires")));}
}
