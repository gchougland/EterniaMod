package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class EconomyService extends DomainSupport {
    public static final String COINS="eternia:coins";
    public record Balance(long available,long reserved) {}
    EconomyService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    public Balance balance(Owner owner) { return store.transaction(tx->balanceIn(tx,owner)); }
    Balance balanceIn(TransactionalStore.Transaction tx,Owner owner) { var r=tx.find("wallet",owner.key());return r.map(v->new Balance(v.number("available"),v.number("reserved"))).orElse(new Balance(0,0)); }
    public boolean credit(Owner owner,long amount,String sourceReceipt) { positive(amount);return store.transaction(tx->creditIn(tx,owner,amount,sourceReceipt)); }
    boolean creditIn(TransactionalStore.Transaction tx,Owner owner,long amount,String receipt) {
        positive(amount);if(!receipt(tx,"ledger_receipt",receipt,fields("kind","MINT","to",owner.key(),"amount",amount)))return false;
        var balance=balanceIn(tx,owner);write(tx,owner,Math.addExact(balance.available,amount),balance.reserved);
        tx.save("ledger",key(receipt),0,fields("kind","MINT","to",owner.key(),"amount",amount,"at",clock.instant()));return true;
    }
    public boolean transfer(Owner from,Owner to,long amount,String receipt) { return store.transaction(tx->transferIn(tx,from,to,amount,receipt)); }
    boolean transferIn(TransactionalStore.Transaction tx,Owner from,Owner to,long amount,String receipt) {
        positive(amount);require(!from.equals(to),INVALID_INPUT,"Cannot transfer to self");
        if(!receipt(tx,"ledger_receipt",receipt,fields("kind","TRANSFER","from",from.key(),"to",to.key(),"amount",amount)))return false;
        var a=balanceIn(tx,from);var b=balanceIn(tx,to);require(a.available>=amount,INSUFFICIENT_BALANCE,"Insufficient coins");
        write(tx,from,a.available-amount,a.reserved);write(tx,to,Math.addExact(b.available,amount),b.reserved);
        tx.save("ledger",key(receipt),0,fields("kind","TRANSFER","from",from.key(),"to",to.key(),"amount",amount,"at",clock.instant()));return true;
    }
    public String reserve(Owner owner,long amount,String receipt) { return store.transaction(tx->reserveIn(tx,owner,amount,receipt)); }
    String reserveIn(TransactionalStore.Transaction tx,Owner owner,long amount,String receipt) {
        positive(amount);String id=key(receipt);
        if(!receipt(tx,"coin_reserve_receipt",receipt,fields("owner",owner.key(),"amount",amount)))return id;
        var balance=balanceIn(tx,owner);require(balance.available>=amount,INSUFFICIENT_BALANCE,"Insufficient coins");
        write(tx,owner,balance.available-amount,Math.addExact(balance.reserved,amount));tx.save("coin_reservation",id,0,fields("owner",owner.key(),"amount",amount,"state","RESERVED"));return id;
    }
    public void release(String reservationId) { store.transaction(tx->{releaseIn(tx,reservationId);return null;}); }
    void releaseIn(TransactionalStore.Transaction tx,String id) {
        var r=row(tx,"coin_reservation",id);if(r.value("state").equals("RELEASED"))return;
        require(r.value("state").equals("RESERVED"),INVALID_STATE,"Coin reserve already settled");Owner owner=Owner.parse(r.value("owner"));var b=balanceIn(tx,owner);
        write(tx,owner,Math.addExact(b.available,r.number("amount")),b.reserved-r.number("amount"));save(tx,r,"state","RELEASED");
    }
    void settleIn(TransactionalStore.Transaction tx,String id,Owner recipient) {
        var r=row(tx,"coin_reservation",id);
        if(r.value("state").equals("SETTLED")) { require(r.value("recipient").equals(recipient.key()),CONFLICT,"Settlement recipient changed");return; }
        require(r.value("state").equals("RESERVED"),INVALID_STATE,"Coin reserve already completed");
        Owner owner=Owner.parse(r.value("owner"));var a=balanceIn(tx,owner);long amount=r.number("amount");
        write(tx,owner,a.available,a.reserved-amount);
        var b=balanceIn(tx,recipient);write(tx,recipient,Math.addExact(b.available,amount),b.reserved);
        save(tx,r,"state","SETTLED","recipient",recipient.key());tx.save("ledger","settle-"+id,0,fields("kind","SETTLE","from",owner.key(),"to",recipient.key(),"amount",amount,"at",clock.instant()));
    }
    private void write(TransactionalStore.Transaction tx,Owner owner,long available,long reserved) {
        require(available>=0&&reserved>=0,INVALID_STATE,"Negative wallet");var old=tx.find("wallet",owner.key());
        tx.save("wallet",owner.key(),old.map(TransactionalStore.Row::revision).orElse(0L),fields("available",available,"reserved",reserved));
    }
}
