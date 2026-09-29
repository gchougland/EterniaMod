package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class AccountService extends DomainSupport {
    public record Account(UUID id,String displayName,Instant lastSeen,Instant lastDisconnect) {}
    public record Stats(long playtimeSeconds,Long mobsDefeated,Long resourcesGathered,Instant updatedAt) {}
    AccountService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    /** Invoke only after the game adapter has authenticated this UUID. */
    public Account recordAuthenticatedLogin(UUID gameId,String displayName) {
        Objects.requireNonNull(gameId);text(displayName,"display name",64);
        return store.transaction(tx->{var old=tx.find("account",gameId.toString());var values=old.map(r->changed(r,"name",displayName,"lastSeen",clock.instant(),"sessionStarted",clock.instant())).orElseGet(()->fields("name",displayName,"lastSeen",clock.instant(),"lastDisconnect","","sessionStarted",clock.instant(),"playSeconds",0));
            return account(tx.save("account",gameId.toString(),old.map(TransactionalStore.Row::revision).orElse(0L),values));});
    }
    public void recordDisconnect(UUID id) { store.transaction(tx->{var r=accrue(tx,id);save(tx,r,"lastDisconnect",clock.instant(),"lastSeen",clock.instant(),"sessionStarted","");return null;}); }
    public void heartbeat(UUID id) { store.transaction(tx->{accrue(tx,id);return null;}); }
    private TransactionalStore.Row accrue(TransactionalStore.Transaction tx,UUID id) {var r=row(tx,"account",id.toString());long elapsed=r.value("sessionStarted").isEmpty()?0:Math.max(0,Duration.between(Instant.parse(r.value("sessionStarted")),clock.instant()).getSeconds());long total=r.value("playSeconds").isEmpty()?0:r.number("playSeconds");return save(tx,r,"playSeconds",Math.addExact(total,elapsed),"sessionStarted",r.value("sessionStarted").isEmpty()?"":clock.instant(),"lastSeen",clock.instant());}
    public Optional<Stats> stats(UUID id) {return store.transaction(tx->tx.find("account",id.toString()).map(r->{var activity=tx.find("activity_stats",id.toString());return new Stats(r.value("playSeconds").isEmpty()?0:r.number("playSeconds"),activity.map(a->a.number("kills")).orElse(null),activity.map(a->a.number("resources")).orElse(null),Instant.parse(r.value("lastSeen")));}));}
    /** External authentication/claim verification happens before this method; never pass browser-chosen IDs. */
    public void recordVerifiedIdentity(UUID gameId,String issuer,String subject,String proofReceipt) {
        text(issuer,"issuer",500);text(subject,"subject",300);
        store.transaction(tx->{row(tx,"account",gameId.toString());var data=fields("gameId",gameId,"issuer",issuer,"subject",subject);
            if(!receipt(tx,"identity_receipt",proofReceipt,data))return null;
            String k=key(issuer,subject);var old=tx.find("identity",k);
            require(old.isEmpty()||old.get().value("gameId").equals(gameId.toString()),CONFLICT,"Identity belongs to another game account");
            if(old.isEmpty())tx.save("identity",k,0,data);return null;});
    }
    public Optional<Account> find(UUID id) { return store.transaction(tx->tx.find("account",id.toString()).map(AccountService::account)); }
    public Optional<Account> findByName(String name) {text(name,"display name",64);return store.transaction(tx->{var matches=tx.scan("account").stream().filter(r->r.value("name").equalsIgnoreCase(name)).toList();require(matches.size()<=1,CONFLICT,"Display name is ambiguous");return matches.stream().findFirst().map(AccountService::account);});}
    private static Account account(TransactionalStore.Row r) { return new Account(UUID.fromString(r.key()),r.value("name"),Instant.parse(r.value("lastSeen")),r.value("lastDisconnect").isEmpty()?null:Instant.parse(r.value("lastDisconnect"))); }
}
