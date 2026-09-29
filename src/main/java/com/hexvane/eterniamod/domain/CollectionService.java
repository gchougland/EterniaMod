package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Durable selections and pet assignments. Native appearance/follower adapters apply these choices;
 * selecting an entry does not claim its game asset has rendered successfully. */
public final class CollectionService extends DomainSupport {
    public enum Kind { PREFIX_TITLE, SUFFIX_TITLE, OUTFIT, WEARABLE, PET }
    public record Definition(String id,String name,Kind kind,String slot,String assetId){
        public Definition{content(id);text(name,"collection name",100);Objects.requireNonNull(kind);slot=slot==null?"":slot;text(assetId,"asset id",200);require(kind!=Kind.WEARABLE||slot.matches("[a-z][a-z0-9_-]{0,30}"),INVALID_INPUT,"Wearable needs a slot");}}
    public record Selection(String prefix,String suffix,String outfit,Map<String,String> wearables){public Selection{wearables=Map.copyOf(wearables);}}
    public record Pet(UUID id,Owner owner,String contentId,String sourceGrant,long sourceIndex,String assignment,UUID propertyId){}
    private final OwnershipService ownership;
    CollectionService(TransactionalStore s,Clock c,Supplier<UUID> i,OwnershipService ownership){super(s,c,i);this.ownership=ownership;}
    public void register(Definition definition){store.transaction(tx->{var data=fields("name",definition.name,"kind",definition.kind,"slot",definition.slot,"asset",definition.assetId);var old=tx.find("collection_definition",definition.id);if(old.isPresent())require(old.get().fields().equals(data),CONFLICT,"Collection definition already registered differently");else tx.save("collection_definition",definition.id,0,data);return null;});}
    public List<Definition> definitions(){return store.transaction(tx->tx.scan("collection_definition").stream().map(CollectionService::definition).toList());}
    public Selection selection(UUID actor){return store.transaction(tx->selection(tx.find("collection_selection",actor.toString()).map(TransactionalStore.Row::fields).orElse(Map.of())));}
    public Selection equip(UUID actor,String contentId){
        return store.transaction(tx->{row(tx,"account",actor.toString());var definition=definition(row(tx,"collection_definition",contentId));require(definition.kind!=Kind.PET,INVALID_INPUT,"Pets use assignments");require(ownership.ownsIn(tx,Owner.player(actor),contentId),FORBIDDEN,"Collection item is not owned");
            var old=tx.find("collection_selection",actor.toString());var data=new TreeMap<>(old.map(TransactionalStore.Row::fields).orElse(Map.of()));
            switch(definition.kind){case PREFIX_TITLE->data.put("prefix",contentId);case SUFFIX_TITLE->data.put("suffix",contentId);case OUTFIT->{data.keySet().removeIf(k->k.startsWith("wearable."));data.put("outfit",contentId);}case WEARABLE->{data.put("outfit","");data.put("wearable."+definition.slot,contentId);}default->throw new DomainException(INVALID_INPUT,"Not equippable");}
            tx.save("collection_selection",actor.toString(),old.map(TransactionalStore.Row::revision).orElse(0L),data);return selection(data);});
    }
    public Selection unequip(UUID actor,Kind kind,String slot){return store.transaction(tx->{var old=tx.find("collection_selection",actor.toString());var data=new TreeMap<>(old.map(TransactionalStore.Row::fields).orElse(Map.of()));String k=switch(kind){case PREFIX_TITLE->"prefix";case SUFFIX_TITLE->"suffix";case OUTFIT->"outfit";case WEARABLE->"wearable."+slot;case PET->throw new DomainException(INVALID_INPUT,"Pets use assignments");};data.remove(k);tx.save("collection_selection",actor.toString(),old.map(TransactionalStore.Row::revision).orElse(0L),data);return selection(data);});}
    public Pet materializePet(Owner owner,String sourceGrant,long index){
        require(index>=0,INVALID_INPUT,"Invalid pet index");return store.transaction(tx->{var grant=row(tx,"grant",sourceGrant);require(grant.value("owner").equals(owner.key())&&index<grant.number("quantity"),FORBIDDEN,"Pet grant does not belong to owner");requireActiveGrant(grant);
            var definition=definition(row(tx,"collection_definition",grant.value("content")));require(definition.kind==Kind.PET,INVALID_INPUT,"Grant is not a pet");
            String source=key(sourceGrant,Long.toString(index));var old=tx.find("pet_source",source);if(old.isPresent())return pet(row(tx,"pet",old.get().value("pet")));
            UUID id=ids.get();tx.save("pet_source",source,0,fields("pet",id));return pet(tx.save("pet",id.toString(),0,fields("owner",owner.key(),"content",definition.id,"source",sourceGrant,"index",index,"assignment","UNASSIGNED","property","")));});
    }
    public List<Pet> pets(Owner owner){return store.transaction(tx->tx.scan("pet").stream().filter(r->r.value("owner").equals(owner.key())).map(CollectionService::pet).toList());}
    /** Server renderer projection; do not expose the complete registry through a public account endpoint. */
    public List<Pet> allPets(){return store.transaction(tx->tx.scan("pet").stream().map(CollectionService::pet).toList());}
    public Pet follow(UUID actor,UUID petId){
        return store.transaction(tx->{var r=row(tx,"pet",petId.toString());require(r.value("owner").equals(Owner.player(actor).key()),FORBIDDEN,"Only an owned personal pet may follow");requireActiveGrant(row(tx,"grant",r.value("source")));
            for(var previous:tx.scan("pet"))if(previous.value("owner").equals(r.value("owner"))&&previous.value("assignment").equals("FOLLOWER"))save(tx,previous,"assignment","UNASSIGNED","property","");
            r=row(tx,"pet",petId.toString());return pet(save(tx,r,"assignment","FOLLOWER","property",""));});
    }
    public Pet assignProperty(UUID actor,UUID petId,UUID propertyId){
        return store.transaction(tx->{var r=row(tx,"pet",petId.toString());Owner owner=Owner.parse(r.value("owner"));authorizeOwner(tx,actor,owner);requireActiveGrant(row(tx,"grant",r.value("source")));
            var plot=tx.scan("housing_slot").stream().filter(p->p.value("property").equals(propertyId.toString())&&p.value("state").equals("ACTIVE")).findFirst().orElseThrow(()->new DomainException(NOT_FOUND,"Active property not found"));
            Owner propertyOwner=Owner.parse(plot.key());authorizeOwner(tx,actor,propertyOwner);
            long residents=tx.scan("pet").stream().filter(p->p.value("assignment").equals("PROPERTY")&&p.value("property").equals(propertyId.toString())&&!p.key().equals(petId.toString())).count();require(residents<12,CAPACITY,"Property pet limit reached");
            return pet(save(tx,r,"assignment","PROPERTY","property",propertyId));});
    }
    public Pet unassign(UUID actor,UUID petId){return store.transaction(tx->{var r=row(tx,"pet",petId.toString());authorizeOwner(tx,actor,Owner.parse(r.value("owner")));return pet(save(tx,r,"assignment","UNASSIGNED","property",""));});}
    public int reconcileEntitlements(){
        return store.transaction(tx->{int changes=0;for(var r:tx.scan("pet"))if(!r.value("assignment").equals("UNASSIGNED")){var grant=row(tx,"grant",r.value("source"));boolean invalidProperty=false;
            if(r.value("assignment").equals("PROPERTY")){var property=tx.scan("housing_slot").stream().filter(p->p.value("property").equals(r.value("property"))).findFirst();
                if(property.isEmpty())invalidProperty=true;else{Owner petOwner=Owner.parse(r.value("owner")),propertyOwner=Owner.parse(property.get().key());
                    if(!petOwner.equals(propertyOwner)){
                        Owner player=petOwner.kind()==Owner.Kind.PLAYER?petOwner:propertyOwner, guild=petOwner.kind()==Owner.Kind.GUILD?petOwner:propertyOwner;
                        invalidProperty=player.kind()!=Owner.Kind.PLAYER||guild.kind()!=Owner.Kind.GUILD||tx.find("guild_member",player.id().toString()).filter(m->m.value("active").equals("true")&&m.value("guild").equals(guild.id().toString())).isEmpty();
                    }
                }}
            if(grant.value("revoked").equals("true")||!future(grant.value("until"),clock.instant())||invalidProperty){save(tx,r,"assignment","UNASSIGNED","property","");changes++;}}
            for(var r:tx.scan("collection_selection")){Owner owner=Owner.player(UUID.fromString(r.key()));var data=new TreeMap<>(r.fields());data.entrySet().removeIf(e->!e.getValue().isEmpty()&&!ownership.ownsIn(tx,owner,e.getValue()));if(!data.equals(r.fields())){tx.save(r.namespace(),r.key(),r.revision(),data);changes++;}}return changes;});
    }
    private void requireActiveGrant(TransactionalStore.Row grant){require(!grant.value("revoked").equals("true")&&future(grant.value("until"),clock.instant()),FORBIDDEN,"Pet entitlement is not active");}
    private static void authorizeOwner(TransactionalStore.Transaction tx,UUID actor,Owner owner){if(owner.kind()==Owner.Kind.PLAYER)require(owner.id().equals(actor),FORBIDDEN,"Property or pet belongs to another player");else if(owner.kind()==Owner.Kind.GUILD)GuildService.requireCapability(tx,actor,owner.id(),"housing.prop.place");else throw new DomainException(FORBIDDEN,"Server-owned pet cannot be managed");}
    private static Definition definition(TransactionalStore.Row r){return new Definition(r.key(),r.value("name"),Kind.valueOf(r.value("kind")),r.value("slot"),r.value("asset"));}
    private static Selection selection(Map<String,String> data){var wearables=new TreeMap<String,String>();data.forEach((k,v)->{if(k.startsWith("wearable."))wearables.put(k.substring(9),v);});return new Selection(data.getOrDefault("prefix",""),data.getOrDefault("suffix",""),data.getOrDefault("outfit",""),wearables);}
    private static Pet pet(TransactionalStore.Row r){return new Pet(UUID.fromString(r.key()),Owner.parse(r.value("owner")),r.value("content"),r.value("source"),r.number("index"),r.value("assignment"),r.value("property").isEmpty()?null:UUID.fromString(r.value("property")));}
}
