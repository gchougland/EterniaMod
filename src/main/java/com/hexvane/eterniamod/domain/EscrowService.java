package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class EscrowService extends DomainSupport {
    /** Metadata is an opaque lossless native-item representation. Adapters must restore the explicit
     * quantity, including when one captured stack is split into several deliveries. */
    public record Item(String itemId,long quantity,String metadataBase64,boolean transferable) {
        public Item { text(itemId,"item id",200);positive(quantity);require(quantity<=1_000_000,INVALID_INPUT,"Stack too large");Objects.requireNonNull(metadataBase64);
            try { require(Base64.getDecoder().decode(metadataBase64).length<=1024*1024,INVALID_INPUT,"Item metadata too large"); }
            catch(IllegalArgumentException e) { throw new DomainException(INVALID_INPUT,"Item metadata must be base64"); } }
    }
    public record Escrow(String id,Owner owner,Item item,long remaining,String state,String holder) {}
    public record Delivery(String id,Owner recipient,Item item,String state,UUID attemptId) {}
    public enum NativeOutcome { INSERTED, NOT_INSERTED, UNKNOWN }
    EscrowService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    /** Explicit local fixture issuance, not evidence of a native inventory removal. Adapter must enforce local WorldEditor access. */
    public Escrow issueLocalExample(Owner owner,Item item,String sourceReceipt) {
        require(sourceReceipt.startsWith("local-playground:"),INVALID_INPUT,"Local items require a playground receipt");
        return store.transaction(tx->{row(tx,"account",owner.id().toString());var data=fields("owner",owner.key(),"item",item.itemId,"quantity",item.quantity,"metadata",item.metadataBase64,"transferable",item.transferable);
            String id=key(sourceReceipt);if(receipt(tx,"local_item_issue",sourceReceipt,data)){data.putAll(fields("remaining",item.quantity,"state","AVAILABLE","holder","","proof","local-fixture-issued"));tx.save("escrow",id,0,data);}return escrow(row(tx,"escrow",id));});
    }
    /** A successful forced native save is a custody checkpoint. An older backup must never be
     * accepted as evidence that an already acknowledged transfer did not happen. */
    public void verifyNativeInventoryCheckpoint(Owner owner,long loadedRevision) {
        require(loadedRevision>=0,INVALID_INPUT,"Invalid native inventory revision");
        store.transaction(tx->{var prior=tx.find("native_inventory_checkpoint",owner.key());
            if(prior.isPresent()){require(prior.get().value("blocked").isEmpty(),INVALID_STATE,"Inventory custody needs administrator review");
                require(loadedRevision>=prior.get().number("checkpoint"),INVALID_STATE,"An older native inventory backup was loaded; custody needs administrator review");}return null;});
    }
    public void acknowledgeNativeInventoryCheckpoint(Owner owner,long savedRevision) {
        require(savedRevision>=0,INVALID_INPUT,"Invalid native inventory revision");
        store.transaction(tx->{var prior=tx.find("native_inventory_checkpoint",owner.key());
            if(prior.isPresent()){require(prior.get().value("blocked").isEmpty(),INVALID_STATE,"Inventory custody needs administrator review");
                require(savedRevision>=prior.get().number("checkpoint"),CONFLICT,"Native inventory checkpoint moved backwards");}
            tx.save("native_inventory_checkpoint",owner.key(),prior.map(TransactionalStore.Row::revision).orElse(0L),fields("checkpoint",savedRevision,"blocked",""));return null;});
    }
    /** Used only when a native mutation throws before its result can be established. */
    public void quarantineNativeInventory(Owner owner,String operation) {
        text(operation,"uncertain native operation",240);store.transaction(tx->{var prior=tx.find("native_inventory_checkpoint",owner.key());
            tx.save("native_inventory_checkpoint",owner.key(),prior.map(TransactionalStore.Row::revision).orElse(0L),fields("checkpoint",prior.map(r->r.number("checkpoint")).orElse(0L),"blocked",operation));return null;});
    }
    public Escrow prepareDeposit(Owner owner,Item item,String sourceReceipt) {
        return store.transaction(tx->{var data=fields("owner",owner.key(),"item",item.itemId,"quantity",item.quantity,"metadata",item.metadataBase64,"transferable",item.transferable);
            String id=key(sourceReceipt);if(receipt(tx,"escrow_deposit_receipt",sourceReceipt,data)) {
                data.putAll(fields("remaining",item.quantity,"state","AWAITING_NATIVE_REMOVAL","holder","","proof",""));tx.save("escrow",id,0,data);
            }return escrow(row(tx,"escrow",id));});
    }
    /** The game adapter must persist its native removal marker before acknowledging. Retrying an
     * uncertain native operation must inspect that marker; this method cannot remove native items. */
    public void acknowledgeNativeRemoval(String escrowId,String nativeProof) {
        text(nativeProof,"native removal proof",240);store.transaction(tx->{var r=row(tx,"escrow",escrowId);
            if(!r.value("proof").isEmpty()) {require(r.value("proof").equals(nativeProof),CONFLICT,"Native proof changed");return null;}
            require(r.value("state").equals("AWAITING_NATIVE_REMOVAL"),INVALID_STATE,"Deposit is not awaiting native removal");save(tx,r,"state","AVAILABLE","proof",nativeProof);return null;});
    }
    public List<Escrow> available(Owner owner) {return store.transaction(tx->tx.scan("escrow").stream().filter(r->r.value("owner").equals(owner.key())&&r.value("state").equals("AVAILABLE")).map(EscrowService::escrow).toList());}
    public Optional<Escrow> find(String id){return store.transaction(tx->tx.find("escrow",id).map(EscrowService::escrow));}
    TransactionalStore.Row holdIn(TransactionalStore.Transaction tx,String id,Owner owner,String holder) {
        var r=row(tx,"escrow",id);require(r.value("owner").equals(owner.key()),FORBIDDEN,"Escrow belongs to another owner");
        require(r.value("state").equals("AVAILABLE"),INVALID_STATE,"Item is not available");return save(tx,r,"state","HELD","holder",holder);
    }
    void releaseIn(TransactionalStore.Transaction tx,String id,String holder) {
        var r=row(tx,"escrow",id);require(r.value("holder").equals(holder),CONFLICT,"Escrow holder changed");
        if(r.value("state").equals("EXHAUSTED"))return;
        require(r.value("state").equals("HELD"),INVALID_STATE,"Escrow is not held");save(tx,r,"state","AVAILABLE","holder","");
    }
    Delivery deliverHeldIn(TransactionalStore.Transaction tx,String escrowId,String holder,Owner recipient,long amount,String sourceReceipt) {
        positive(amount);String id=key(sourceReceipt);var input=fields("escrow",escrowId,"holder",holder,"recipient",recipient.key(),"quantity",amount);
        if(!receipt(tx,"delivery_receipt",sourceReceipt,input))return delivery(row(tx,"delivery",id));
        var r=row(tx,"escrow",escrowId);require(r.value("state").equals("HELD")&&r.value("holder").equals(holder),INVALID_STATE,"Escrow is not held by this operation");
        require(amount<=r.number("remaining"),INSUFFICIENT_BALANCE,"Not enough escrow stock");
        require(r.value("owner").equals(recipient.key())||Boolean.parseBoolean(r.value("transferable")),FORBIDDEN,"Bound item cannot be transferred");
        long remaining=r.number("remaining")-amount;save(tx,r,"remaining",remaining,"state",remaining==0?"EXHAUSTED":"HELD");
        return delivery(tx.save("delivery",id,0,fields("recipient",recipient.key(),"escrow",escrowId,"item",r.value("item"),"quantity",amount,"metadata",r.value("metadata"),"transferable",r.value("transferable"),"state","READY","attempt","","at",clock.instant())));
    }
    public Delivery withdraw(Owner owner,String escrowId,String receipt) {
        return store.transaction(tx->{String id=key(receipt);var existing=tx.find("delivery_receipt",id);
            if(existing.isPresent()) { require(existing.get().value("escrow").equals(escrowId)&&existing.get().value("recipient").equals(owner.key()),CONFLICT,"Withdrawal receipt reused");return delivery(row(tx,"delivery",id)); }
            var r=holdIn(tx,escrowId,owner,"withdraw:"+receipt);return deliverHeldIn(tx,escrowId,"withdraw:"+receipt,owner,r.number("remaining"),receipt);});
    }
    public List<Delivery> deliveries(Owner recipient) {return store.transaction(tx->tx.scan("delivery").stream().filter(r->r.value("recipient").equals(recipient.key())).map(EscrowService::delivery).toList());}
    public Delivery beginNativeDelivery(Owner recipient,String deliveryId,UUID attemptId) {
        return store.transaction(tx->{var r=row(tx,"delivery",deliveryId);require(r.value("recipient").equals(recipient.key()),FORBIDDEN,"Delivery belongs to another owner");
            if(r.value("state").equals("HANDOFF")&&r.value("attempt").equals(attemptId.toString()))return delivery(r);
            require(r.value("state").equals("READY"),INVALID_STATE,"Delivery is already in handoff or delivered");return delivery(save(tx,r,"state","HANDOFF","attempt",attemptId));});
    }
    public Delivery resolveNativeDelivery(String deliveryId,UUID attemptId,NativeOutcome outcome) {
        return store.transaction(tx->{var r=row(tx,"delivery",deliveryId);require(r.value("attempt").equals(attemptId.toString()),CONFLICT,"Native delivery attempt changed");
            if(r.value("state").equals("DELIVERED")&&outcome==NativeOutcome.INSERTED)return delivery(r);
            require(r.value("state").equals("HANDOFF"),INVALID_STATE,"Delivery has no pending native handoff");
            if(outcome==NativeOutcome.UNKNOWN)return delivery(r);
            return delivery(save(tx,r,"state",outcome==NativeOutcome.INSERTED?"DELIVERED":"READY"));});
    }
    static Item item(TransactionalStore.Row r,long amount){return new Item(r.value("item"),amount,r.value("metadata"),Boolean.parseBoolean(r.value("transferable")));}
    private static Escrow escrow(TransactionalStore.Row r){return new Escrow(r.key(),Owner.parse(r.value("owner")),item(r,r.number("quantity")),r.number("remaining"),r.value("state"),r.value("holder"));}
    private static Delivery delivery(TransactionalStore.Row r){return new Delivery(r.key(),Owner.parse(r.value("recipient")),item(r,r.number("quantity")),r.value("state"),r.value("attempt").isEmpty()?null:UUID.fromString(r.value("attempt")));}
}
