package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class HousingService extends DomainSupport {
    public static final String MOVE_CREDIT="eternia:plot_move_credit";
    public enum State { CLAIMING, ACTIVE, MOVING, PACKED, RESTORING }
    public enum ClaimScope { PUBLIC, GUILD }
    public record ClaimLocation(String worldId,int minX,int minZ,int width,int depth,ClaimScope scope,UUID guildId,boolean buildingPresent) {
        public ClaimLocation { text(worldId,"world id",160);require(width>0&&depth>0&&width<=1024&&depth<=1024,INVALID_INPUT,"Invalid claim dimensions");Objects.requireNonNull(scope);require(scope!=ClaimScope.GUILD||guildId!=null,INVALID_INPUT,"Guild claim requires a guild id"); }
    }
    public record Slot(Owner owner,UUID propertyId,State state,UUID operationId,long revision,String snapshotRef,UUID attachedGuild) {}
    private final OwnershipService ownership;private final JournalService journal;
    HousingService(TransactionalStore s,Clock c,Supplier<UUID> i,OwnershipService ownership,JournalService journal) { super(s,c,i);this.ownership=ownership;this.journal=journal; }
    public Optional<Slot> find(Owner owner) { return store.transaction(tx->tx.find("housing_slot",owner.key()).map(HousingService::slot)); }
    public List<Slot> allSlots() { return store.transaction(tx->tx.scan("housing_slot").stream().map(HousingService::slot).toList()); }
    public Optional<ClaimLocation> location(Owner owner) { return store.transaction(tx->tx.find("housing_slot",owner.key()).filter(r->!r.value("world").isEmpty()).map(HousingService::location)); }
    /** Called only after native geometry/anchor checks; durable uniqueness is enforced here. */
    public Slot beginClaim(Owner owner,UUID propertyId,UUID operationId,UUID attachedGuild) {
        return beginClaim(owner,propertyId,operationId,attachedGuild,null);
    }
    public Slot beginClaim(Owner owner,UUID propertyId,UUID operationId,UUID attachedGuild,ClaimLocation location) {
        return store.transaction(tx->{
            var existing=tx.find("housing_slot",owner.key());
            if(existing.isPresent()) { var r=existing.get();require(r.value("property").equals(propertyId.toString())&&r.value("operation").equals(operationId.toString())&&r.value("state").equals("CLAIMING")&&Objects.equals(slot(r).attachedGuild(),attachedGuild)&&Objects.equals(r.value("world").isEmpty()?null:location(r),location),CONFLICT,"Owner already has a housing slot or claim arguments changed");return slot(r); }
            if(attachedGuild!=null) { require(owner.kind()==Owner.Kind.PLAYER,INVALID_INPUT,"Only player homes attach to guilds");GuildService.requireMember(tx,owner.id(),attachedGuild); }
            if(location!=null) {
                require(Objects.equals(attachedGuild,location.guildId())||owner.kind()==Owner.Kind.GUILD&&owner.id().equals(location.guildId()),INVALID_INPUT,"Claim guild differs from housing owner/attachment");
                for(var other:tx.scan("housing_slot"))if(!other.value("state").equals("PACKED")&&other.value("world").equals(location.worldId())) {
                    var b=location(other);require((long)location.minX()+location.width()<=b.minX()||(long)b.minX()+b.width()<=location.minX()||(long)location.minZ()+location.depth()<=b.minZ()||(long)b.minZ()+b.depth()<=location.minZ(),CONFLICT,"Another claim occupies the location");
                }
            }
            journal.prepareIn(tx,operationId,"CLAIM",owner,propertyId.toString());
            var data=fields("property",propertyId,"state",State.CLAIMING,"operation",operationId,"snapshotRef","","moveReservation","","attachedGuild",attachedGuild);
            if(location!=null)data.putAll(fields("world",location.worldId(),"minX",location.minX(),"minZ",location.minZ(),"width",location.width(),"depth",location.depth(),"scope",location.scope(),"guildId",location.guildId(),"buildingPresent",location.buildingPresent()));
            return slot(tx.save("housing_slot",owner.key(),0,data));
        });
    }
    public Slot beginRelocation(Owner owner,UUID operationId,boolean forced) {
        return store.transaction(tx->{var r=row(tx,"housing_slot",owner.key());
            if(r.value("operation").equals(operationId.toString())&&Set.of("MOVING","PACKED","RESTORING").contains(r.value("state")))return slot(r);
            require(r.value("state").equals("ACTIVE"),INVALID_STATE,"Property is already in an operation");
            String reservation=forced?"":ownership.reserveIn(tx,owner,MOVE_CREDIT,1,"move:"+operationId).id();
            journal.prepareIn(tx,operationId,forced?"FORCED_RETURN":"MOVE",owner,r.value("property"));
            return slot(save(tx,r,"state",State.MOVING,"operation",operationId,"moveReservation",reservation));
        });
    }
    /** After the adapter captured a verified snapshot and removed/restored source terrain. */
    public Slot acknowledgePacked(Owner owner,UUID operationId) {
        return store.transaction(tx->{var r=matching(tx,owner,operationId);if(r.value("state").equals("PACKED"))return slot(r);
            require(r.value("state").equals("MOVING"),INVALID_STATE,"Property is not moving");var op=row(tx,"operation",operationId.toString());
            require(op.value("state").equals("WORLD_APPLIED")&&!op.value("snapshotHash").isEmpty(),INVALID_STATE,"Durable snapshot and world acknowledgement required");
            journal.advanceIn(tx,operationId,op.revision(),JournalService.State.PACKED);
            return slot(save(tx,r,"state",State.PACKED,"snapshotRef",op.value("snapshotRef")));
        });
    }
    public Slot beginRestore(Owner owner,UUID operationId) { return store.transaction(tx->{var r=matching(tx,owner,operationId);
        require(r.value("state").equals("PACKED"),INVALID_STATE,"Property is not packed");return slot(save(tx,r,"state",State.RESTORING));}); }
    public Slot updateRestoreLocation(Owner owner,UUID operationId,ClaimLocation destination) {
        Objects.requireNonNull(destination);return store.transaction(tx->{var r=matching(tx,owner,operationId);require(r.value("state").equals("RESTORING"),INVALID_STATE,"Restore must be started before destination reservation");
            if(destination.guildId()!=null&&owner.kind()==Owner.Kind.PLAYER)GuildService.requireMember(tx,owner.id(),destination.guildId());
            for(var other:tx.scan("housing_slot"))if(!other.key().equals(owner.key())&&!other.value("state").equals("PACKED")&&other.value("world").equals(destination.worldId())) {
                var b=location(other);require((long)destination.minX()+destination.width()<=b.minX()||(long)b.minX()+b.width()<=destination.minX()||(long)destination.minZ()+destination.depth()<=b.minZ()||(long)b.minZ()+b.depth()<=destination.minZ(),CONFLICT,"Destination overlaps another property");
            }
            return slot(save(tx,r,"world",destination.worldId(),"minX",destination.minX(),"minZ",destination.minZ(),"width",destination.width(),"depth",destination.depth(),"scope",destination.scope(),"guildId",destination.guildId(),"attachedGuild",owner.kind()==Owner.Kind.PLAYER?destination.guildId():null,"buildingPresent",destination.buildingPresent()));});
    }
    public Slot updateBuildingPresent(Owner owner,UUID propertyId,boolean present) { return store.transaction(tx->{var r=row(tx,"housing_slot",owner.key());require(r.value("state").equals("ACTIVE")&&r.value("property").equals(propertyId.toString()),CONFLICT,"Active property changed");return slot(save(tx,r,"buildingPresent",present));}); }
    /** Adapter has verified no terrain/native mutation occurred. Recovery-required operations cannot cancel here. */
    public Slot cancelRelocationBeforeMutation(Owner owner,UUID operationId) {return store.transaction(tx->{var r=matching(tx,owner,operationId);var op=row(tx,"operation",operationId.toString());
        if(r.value("state").equals("ACTIVE")&&op.value("state").equals("CANCELLED"))return slot(r);
        require(r.value("state").equals("MOVING")&&Set.of("PREPARED","SNAPSHOT_READY").contains(op.value("state")),INVALID_STATE,"Cannot cancel after mutation or uncertain recovery");
        if(!r.value("moveReservation").isEmpty())ownership.finishReservationIn(tx,r.value("moveReservation"),false);
        journal.advanceIn(tx,operationId,op.revision(),JournalService.State.CANCELLED);return slot(save(tx,r,"state",State.ACTIVE,"moveReservation",""));});}
    /** Acknowledges verified final world state; never performs or pretends to perform terrain writes. */
    public Slot activate(Owner owner,UUID operationId) {
        return store.transaction(tx->{var r=matching(tx,owner,operationId);if(r.value("state").equals("ACTIVE"))return slot(r);
            require(Set.of("CLAIMING","MOVING","RESTORING").contains(r.value("state")),INVALID_STATE,"Property cannot activate");
            var op=row(tx,"operation",operationId.toString());require(op.value("state").equals("WORLD_APPLIED"),INVALID_STATE,"World adapter has not acknowledged completion");
            if(!r.value("state").equals("CLAIMING"))require(!op.value("snapshotHash").isEmpty(),INVALID_STATE,"Relocation requires verified recovery snapshot");
            if(!r.value("moveReservation").isEmpty())ownership.finishReservationIn(tx,r.value("moveReservation"),true);
            journal.advanceIn(tx,operationId,op.revision(),JournalService.State.COMPLETED);
            return slot(save(tx,r,"state",State.ACTIVE,"moveReservation",""));
        });
    }
    private static TransactionalStore.Row matching(TransactionalStore.Transaction tx,Owner owner,UUID operationId) { var r=row(tx,"housing_slot",owner.key());require(r.value("operation").equals(operationId.toString()),CONFLICT,"Housing operation changed");return r; }
    private static Slot slot(TransactionalStore.Row r) { return new Slot(Owner.parse(r.key()),UUID.fromString(r.value("property")),State.valueOf(r.value("state")),UUID.fromString(r.value("operation")),r.revision(),r.value("snapshotRef"),r.value("attachedGuild").isEmpty()?null:UUID.fromString(r.value("attachedGuild"))); }
    private static ClaimLocation location(TransactionalStore.Row r) { return new ClaimLocation(r.value("world"),Integer.parseInt(r.value("minX")),Integer.parseInt(r.value("minZ")),Integer.parseInt(r.value("width")),Integer.parseInt(r.value("depth")),ClaimScope.valueOf(r.value("scope")),r.value("guildId").isEmpty()?null:UUID.fromString(r.value("guildId")),Boolean.parseBoolean(r.value("buildingPresent"))); }
}
