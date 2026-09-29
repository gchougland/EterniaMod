package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class JournalService extends DomainSupport {
    public enum State { PREPARED, SNAPSHOT_READY, WORLD_APPLIED, PACKED, COMPLETED, RECOVERY_REQUIRED, CANCELLED }
    public record Operation(UUID id,String kind,Owner owner,State state,long revision,String snapshotRef,String snapshotHash,String detail) {}
    JournalService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    public Operation prepare(UUID id,String kind,Owner owner,String detail) { return store.transaction(tx->prepareIn(tx,id,kind,owner,detail)); }
    Operation prepareIn(TransactionalStore.Transaction tx,UUID id,String kind,Owner owner,String detail) {
        text(kind,"operation kind",80);text(detail,"operation detail",100000);
        var previous=tx.find("operation",id.toString());
        if(previous.isPresent()) { var p=previous.get();require(p.value("kind").equals(kind)&&p.value("owner").equals(owner.key())&&p.value("detail").equals(detail),CONFLICT,"Operation id reused");return operation(p); }
        return operation(tx.save("operation",id.toString(),0,fields("kind",kind,"owner",owner.key(),"detail",detail,"state",State.PREPARED,"snapshotRef","","snapshotHash","","at",clock.instant())));
    }
    public Operation attachVerifiedSnapshot(UUID id,long revision,String reference,String sha256) {
        text(reference,"snapshot reference",2000);require(sha256!=null&&sha256.matches("[0-9a-f]{64}"),INVALID_INPUT,"Expected SHA256");
        return store.transaction(tx->{var r=row(tx,"operation",id.toString());require(r.revision()==revision,CONFLICT,"Operation changed");
            require(r.value("state").equals("PREPARED"),INVALID_STATE,"Snapshot can only be attached before applying world changes");
            return operation(save(tx,r,"snapshotRef",reference,"snapshotHash",sha256,"state",State.SNAPSHOT_READY));});
    }
    /** WORLD_APPLIED is an acknowledgement by the native adapter, never a simulated world mutation. */
    public Operation advance(UUID id,long revision,State target) { return store.transaction(tx->advanceIn(tx,id,revision,target)); }
    Operation advanceIn(TransactionalStore.Transaction tx,UUID id,long revision,State target) {
        var r=row(tx,"operation",id.toString());require(r.revision()==revision,CONFLICT,"Operation changed");State from=State.valueOf(r.value("state"));
        boolean valid=switch(from) {
            case PREPARED -> target==State.SNAPSHOT_READY||target==State.WORLD_APPLIED||target==State.CANCELLED||target==State.RECOVERY_REQUIRED;
            case SNAPSHOT_READY -> target==State.WORLD_APPLIED||target==State.CANCELLED||target==State.RECOVERY_REQUIRED;
            case WORLD_APPLIED -> target==State.COMPLETED||target==State.PACKED||target==State.RECOVERY_REQUIRED;
            case PACKED -> target==State.WORLD_APPLIED||target==State.RECOVERY_REQUIRED;
            case RECOVERY_REQUIRED -> target==State.SNAPSHOT_READY||target==State.WORLD_APPLIED||target==State.PACKED||target==State.CANCELLED;
            default -> false;
        };
        require(valid,INVALID_STATE,"Illegal journal transition");
        if(target==State.SNAPSHOT_READY||target==State.PACKED)require(!r.value("snapshotHash").isEmpty(),INVALID_STATE,"Verified snapshot is required");
        return operation(save(tx,r,"state",target,"at",clock.instant()));
    }
    public Optional<Operation> find(UUID id) { return store.transaction(tx->tx.find("operation",id.toString()).map(JournalService::operation)); }
    public List<Operation> unfinished() { return store.transaction(tx->tx.scan("operation").stream().filter(r->!Set.of("COMPLETED","CANCELLED").contains(r.value("state"))).map(JournalService::operation).toList()); }
    static Operation operation(TransactionalStore.Row r) { return new Operation(UUID.fromString(r.key()),r.value("kind"),Owner.parse(r.value("owner")),State.valueOf(r.value("state")),r.revision(),r.value("snapshotRef"),r.value("snapshotHash"),r.value("detail")); }
}
