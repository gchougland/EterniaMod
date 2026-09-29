package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Small persistent activity groups. Membership and capacity are serialized in the authority. */
public final class PartyService extends DomainSupport {
    public record Party(UUID id,UUID leader,List<UUID> members) {}
    PartyService(TransactionalStore store,Clock clock,Supplier<UUID> ids){super(store,clock,ids);}
    public Optional<Party> find(UUID player){return store.transaction(tx->findIn(tx,player));}
    private Optional<Party> findIn(TransactionalStore.Transaction tx,UUID player){return tx.find("party_member",player.toString()).filter(r->!r.value("party").isEmpty()).map(r->party(row(tx,"party",r.value("party"))));}
    private Party party(TransactionalStore.Row row){return new Party(UUID.fromString(row.key()),UUID.fromString(row.value("leader")),Arrays.stream(row.value("members").split(",")).filter(v->!v.isEmpty()).map(UUID::fromString).toList());}
    private void member(TransactionalStore.Transaction tx,UUID player,String party){var old=tx.find("party_member",player.toString());tx.save("party_member",player.toString(),old.map(TransactionalStore.Row::revision).orElse(0L),fields("party",party));}
    public Party create(UUID actor){return store.transaction(tx->{row(tx,"account",actor.toString());var existing=findIn(tx,actor);if(existing.isPresent())return existing.get();UUID id=ids.get();var p=party(tx.save("party",id.toString(),0,fields("leader",actor,"members",actor,"state","ACTIVE")));member(tx,actor,id.toString());return p;});}
    public void invite(UUID actor,UUID target){store.transaction(tx->{require(!actor.equals(target),INVALID_INPUT,"Choose another player");row(tx,"account",target.toString());var party=findIn(tx,actor).orElseThrow(()->new DomainException(NOT_FOUND,"Create a party first"));
        require(party.leader.equals(actor),FORBIDDEN,"Only the party leader can invite");require(party.members.size()<8,CAPACITY,"Party is full");require(findIn(tx,target).isEmpty(),CONFLICT,"Player already has a party");
        String id=party.id+":"+target;var prior=tx.find("party_invite",id);tx.save("party_invite",id,prior.map(TransactionalStore.Row::revision).orElse(0L),fields("party",party.id,"target",target,"expires",clock.instant().plus(Duration.ofMinutes(15))));return null;});}
    public Party accept(UUID actor,UUID leader){return store.transaction(tx->{var target=findIn(tx,leader).orElseThrow(()->new DomainException(NOT_FOUND,"Party no longer exists"));var current=findIn(tx,actor);if(current.isPresent()){require(current.get().id.equals(target.id),CONFLICT,"Leave your current party first");return current.get();}
        var invite=row(tx,"party_invite",target.id+":"+actor);require(Instant.parse(invite.value("expires")).isAfter(clock.instant()),INVALID_STATE,"Party invitation expired");require(target.members.size()<8,CAPACITY,"Party is full");
        var members=new ArrayList<>(target.members);members.add(actor);var record=row(tx,"party",target.id.toString());var updated=party(save(tx,record,"members",String.join(",",members.stream().map(UUID::toString).toList())));member(tx,actor,target.id.toString());save(tx,invite,"expires",clock.instant());return updated;});}
    public void leave(UUID actor){store.transaction(tx->{var current=findIn(tx,actor);if(current.isEmpty())return null;var party=current.get();var members=new ArrayList<>(party.members);members.remove(actor);var record=row(tx,"party",party.id.toString());
        save(tx,record,"members",String.join(",",members.stream().map(UUID::toString).toList()),"leader",members.isEmpty()?party.leader:members.contains(party.leader)?party.leader:members.getFirst(),"state",members.isEmpty()?"CLOSED":"ACTIVE");member(tx,actor,"");return null;});}
}
