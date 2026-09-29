package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Records custody/provenance proven by the native adapter. This service never discovers owners from
 * a plot's location and never mints grants on pack/restore. Unknown legacy objects require review. */
public final class ProvenanceService extends DomainSupport {
    public record Instance(UUID id,Owner owner,UUID placedBy,UUID propertyId,String contentId,String sourceReference,Map<String,String> nativeData,String state,long revision,String snapshotRef){public Instance{nativeData=Map.copyOf(nativeData);}}
    ProvenanceService(TransactionalStore s,Clock c,Supplier<UUID> i){super(s,c,i);}
    public Instance recordVerifiedPlacement(UUID instanceId,Owner owner,UUID placedBy,UUID propertyId,String contentId,String sourceReference,Map<String,String> nativeData,String receiptId){
        Objects.requireNonNull(instanceId);Objects.requireNonNull(owner);Objects.requireNonNull(placedBy);Objects.requireNonNull(propertyId);text(contentId,"placed content id",200);text(sourceReference,"source reference",300);
        var data=fields("instance",instanceId,"owner",owner.key(),"placedBy",placedBy,"property",propertyId,"content",contentId,"source",sourceReference);
        require(nativeData.size()<=100,INVALID_INPUT,"Too many native descriptor fields");nativeData.forEach((k,v)->{text(k,"descriptor key",100);text(v,"descriptor value",100000);data.put("native."+k,v);});
        return store.transaction(tx->{if(!receipt(tx,"instance_receipt",receiptId,data))return instance(row(tx,"placed_instance",instanceId.toString()));
            require(tx.find("placed_instance",instanceId.toString()).isEmpty(),CONFLICT,"Instance already has provenance");
            var stored=new TreeMap<>(data);stored.put("state","PLACED");stored.put("snapshot","");return instance(tx.save("placed_instance",instanceId.toString(),0,stored));});
    }
    public List<Instance> instances(UUID propertyId){return store.transaction(tx->tx.scan("placed_instance").stream().filter(r->r.value("property").equals(propertyId.toString())).map(ProvenanceService::instance).toList());}
    public List<Instance> ownedInstances(Owner owner){Objects.requireNonNull(owner);return store.transaction(tx->tx.scan("placed_instance").stream().filter(r->r.value("owner").equals(owner.key())).map(ProvenanceService::instance).toList());}
    public Optional<Instance> find(UUID id){return store.transaction(tx->tx.find("placed_instance",id.toString()).map(ProvenanceService::instance));}
    public Instance acknowledgePacked(UUID id,long revision,String snapshotRef){
        text(snapshotRef,"snapshot reference",2000);return store.transaction(tx->{var r=row(tx,"placed_instance",id.toString());require(r.revision()==revision,CONFLICT,"Instance changed");require(r.value("state").equals("PLACED"),INVALID_STATE,"Instance is not placed");return instance(save(tx,r,"state","PACKED","snapshot",snapshotRef));});
    }
    public Instance acknowledgeRestored(UUID id,long revision,UUID destinationPropertyId){
        return store.transaction(tx->{var r=row(tx,"placed_instance",id.toString());require(r.revision()==revision,CONFLICT,"Instance changed");require(r.value("state").equals("PACKED"),INVALID_STATE,"Instance is not packed");return instance(save(tx,r,"state","PLACED","property",destinationPropertyId));});
    }
    /** Native adapter supplies descriptors verified after translation; provenance and grant source stay unchanged. */
    public Instance acknowledgeRestored(UUID id,long revision,UUID destinationPropertyId,Map<String,String> verifiedNativeData){
        Objects.requireNonNull(destinationPropertyId);Objects.requireNonNull(verifiedNativeData);
        require(verifiedNativeData.size()<=100,INVALID_INPUT,"Too many native descriptor fields");
        var replacement=new TreeMap<String,String>();verifiedNativeData.forEach((k,v)->{text(k,"descriptor key",100);text(v,"descriptor value",100000);replacement.put("native."+k,v);});
        return store.transaction(tx->{var r=row(tx,"placed_instance",id.toString());require(r.revision()==revision,CONFLICT,"Instance changed");require(r.value("state").equals("PACKED"),INVALID_STATE,"Instance is not packed");
            var updated=new TreeMap<>(r.fields());updated.keySet().removeIf(k->k.startsWith("native."));updated.putAll(replacement);updated.put("state","PLACED");updated.put("property",destinationPropertyId.toString());
            return instance(tx.save(r.namespace(),r.key(),r.revision(),updated));});
    }
    public Instance acknowledgeCustomization(UUID id,long revision,Map<String,String> verifiedNativeData){
        Objects.requireNonNull(verifiedNativeData);require(verifiedNativeData.size()<=100,INVALID_INPUT,"Too many native descriptor fields");var replacement=new TreeMap<String,String>();
        verifiedNativeData.forEach((k,v)->{text(k,"descriptor key",100);text(v,"descriptor value",100000);replacement.put("native."+k,v);});
        return store.transaction(tx->{var r=row(tx,"placed_instance",id.toString());require(r.revision()==revision,CONFLICT,"Instance changed");require(r.value("state").equals("PLACED"),INVALID_STATE,"Instance is not placed");
            var updated=new TreeMap<>(r.fields());updated.keySet().removeIf(k->k.startsWith("native."));updated.putAll(replacement);return instance(tx.save(r.namespace(),r.key(),r.revision(),updated));});
    }
    private static Instance instance(TransactionalStore.Row r){var data=new TreeMap<String,String>();r.fields().forEach((k,v)->{if(k.startsWith("native."))data.put(k.substring(7),v);});return new Instance(UUID.fromString(r.key()),Owner.parse(r.value("owner")),UUID.fromString(r.value("placedBy")),UUID.fromString(r.value("property")),r.value("content"),r.value("source"),data,r.value("state"),r.revision(),r.value("snapshot"));}
}
