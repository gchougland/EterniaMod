package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class OwnershipService extends DomainSupport {
    public enum Kind { UNLOCK, CAPABILITY, QUANTITY }
    public record GrantRequest(String receipt,Owner owner,String contentId,Kind kind,long quantity,Instant validUntil) {
        public GrantRequest { text(receipt,"receipt",240);Objects.requireNonNull(owner);content(contentId);Objects.requireNonNull(kind);positive(quantity);
            require(kind==Kind.QUANTITY||quantity==1,INVALID_INPUT,"Unlock/capability grants have quantity one"); }
    }
    public record Grant(String id,Owner owner,String contentId,Kind kind,long quantity,long available,Instant validUntil,boolean revoked) {}
    public record Reservation(String id,Owner owner,String contentId,long quantity,String state) {}
    public Optional<Reservation> findReservation(String id) { return store.transaction(tx->tx.find("quantity_reservation",id).map(OwnershipService::reservation)); }
    OwnershipService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    public Grant grant(GrantRequest request) { return store.transaction(tx->grantIn(tx,request)); }
    Grant grantIn(TransactionalStore.Transaction tx,GrantRequest r) {
        var data=fields("owner",r.owner.key(),"content",r.contentId,"kind",r.kind,"quantity",r.quantity,"until",r.validUntil);
        boolean fresh=receipt(tx,"grant_receipt",r.receipt,data);String id=key(r.receipt);
        if(fresh)tx.save("grant",id,0,fields("owner",r.owner.key(),"content",r.contentId,"kind",r.kind,"quantity",r.quantity,"available",r.quantity,"until",r.validUntil,"revoked",false,"receipt",r.receipt));
        return grant(row(tx,"grant",id));
    }
    public boolean owns(Owner owner,String contentId) { return store.transaction(tx->ownsIn(tx,owner,contentId)); }
    boolean ownsIn(TransactionalStore.Transaction tx,Owner owner,String contentId) {
        return tx.scan("grant").stream().anyMatch(r->matches(r,owner,contentId)&&active(r)&&( !r.value("kind").equals(Kind.QUANTITY.name())||r.number("available")>0));
    }
    public long available(Owner owner,String contentId) { return store.transaction(tx->availableIn(tx,owner,contentId)); }
    long availableIn(TransactionalStore.Transaction tx,Owner owner,String contentId) {
        long sum=0;for(var r:tx.scan("grant"))if(matches(r,owner,contentId)&&active(r)&&r.value("kind").equals("QUANTITY"))sum=Math.addExact(sum,r.number("available"));return sum;
    }
    public List<Grant> grants(Owner owner) { return store.transaction(tx->tx.scan("grant").stream().filter(r->r.value("owner").equals(owner.key())).map(OwnershipService::grant).toList()); }
    public Reservation reserve(Owner owner,String contentId,long quantity,String receipt) { return store.transaction(tx->reserveIn(tx,owner,contentId,quantity,receipt)); }
    Reservation reserveIn(TransactionalStore.Transaction tx,Owner owner,String contentId,long quantity,String receipt) {
        positive(quantity);content(contentId);var input=fields("owner",owner.key(),"content",contentId,"quantity",quantity);
        String id=key(receipt);
        if(!receipt(tx,"quantity_reserve_receipt",receipt,input))return reservation(row(tx,"quantity_reservation",id));
        require(availableIn(tx,owner,contentId)>=quantity,INSUFFICIENT_BALANCE,"Not enough available content");
        var allocation=new TreeMap<>(input);long left=quantity;
        for(var r:tx.scan("grant")) {
            if(left==0)break;
            if(!matches(r,owner,contentId)||!active(r)||!r.value("kind").equals("QUANTITY"))continue;
            long take=Math.min(left,r.number("available"));if(take==0)continue;
            allocation.put("grant."+r.key(),Long.toString(take));save(tx,r,"available",r.number("available")-take);left-=take;
        }
        allocation.put("state","RESERVED");return reservation(tx.save("quantity_reservation",id,0,allocation));
    }
    public void consumeReservation(String id) { store.transaction(tx->{finishReservationIn(tx,id,true);return null;}); }
    public void releaseReservation(String id) { store.transaction(tx->{finishReservationIn(tx,id,false);return null;}); }
    void finishReservationIn(TransactionalStore.Transaction tx,String id,boolean consume) {
        var reservation=row(tx,"quantity_reservation",id);String target=consume?"CONSUMED":"RELEASED";
        if(reservation.value("state").equals(target))return;
        require(reservation.value("state").equals("RESERVED"),INVALID_STATE,"Reservation has already completed");
        for(var entry:reservation.fields().entrySet()) if(entry.getKey().startsWith("grant.")) {
            var grant=row(tx,"grant",entry.getKey().substring(6));
            if(consume)require(active(grant),INVALID_STATE,"A reserved grant is no longer active");
            else if(active(grant))save(tx,grant,"available",Math.addExact(grant.number("available"),Long.parseLong(entry.getValue())));
        }
        save(tx,reservation,"state",target);
    }
    public void revokeSource(String receipt) { store.transaction(tx->{revokeIn(tx,receipt);return null;}); }
    void revokeIn(TransactionalStore.Transaction tx,String receipt) {
        var pending=new ArrayDeque<String>();var visited=new HashSet<String>();pending.add(key(receipt));
        var links=tx.scan("grant_derivation");
        while(!pending.isEmpty()){
            String id=pending.removeFirst();if(!visited.add(id))continue;
            var grant=row(tx,"grant",id);if(!grant.value("revoked").equals("true"))save(tx,grant,"revoked",true,"available",0);
            for(var link:links)if(link.value("parent").equals(id))pending.add(link.value("child"));
        }
    }
    private boolean active(TransactionalStore.Row r) { return !r.value("revoked").equals("true")&&future(r.value("until"),clock.instant()); }
    private static boolean matches(TransactionalStore.Row r,Owner o,String content) { return r.value("owner").equals(o.key())&&r.value("content").equals(content); }
    private static Grant grant(TransactionalStore.Row r) { return new Grant(r.key(),Owner.parse(r.value("owner")),r.value("content"),Kind.valueOf(r.value("kind")),r.number("quantity"),r.number("available"),r.value("until").isEmpty()?null:Instant.parse(r.value("until")),Boolean.parseBoolean(r.value("revoked"))); }
    private static Reservation reservation(TransactionalStore.Row r) { return new Reservation(r.key(),Owner.parse(r.value("owner")),r.value("content"),r.number("quantity"),r.value("state")); }
}
