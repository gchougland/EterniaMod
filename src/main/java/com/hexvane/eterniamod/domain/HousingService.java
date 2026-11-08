package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.hexplots.*;
import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class HousingService extends DomainSupport {
    public static final String MOVE_CREDIT="eternia:plot_move_credit";
    public enum State { CLAIMING, ACTIVE, MOVING, PACKED, RESTORING }
    public enum ClaimScope { PUBLIC, GUILD }
    /**
     * Where a property stands. Rectangle plots use the box; hex plots also carry {@code cells} (lattice id and cells,
     * saved as the row fields {@code lattice} and {@code cells}) and their box is always the bounds of the cells.
     */
    public record ClaimLocation(String worldId,int minX,int minZ,int width,int depth,ClaimScope scope,UUID guildId,boolean buildingPresent,PlotCells cells) {
        public ClaimLocation { text(worldId,"world id",160);require(width>0&&depth>0&&width<=1024&&depth<=1024,INVALID_INPUT,"Invalid claim dimensions");Objects.requireNonNull(scope);require(scope!=ClaimScope.GUILD||guildId!=null,INVALID_INPUT,"Guild claim requires a guild id");
            if(cells!=null){var box=cells.bounds();require(box.x()==minX&&box.z()==minZ&&box.width()==width&&box.depth()==depth,INVALID_INPUT,"Claim box must match its cells");} }
        public ClaimLocation(String worldId,int minX,int minZ,int width,int depth,ClaimScope scope,UUID guildId,boolean buildingPresent){this(worldId,minX,minZ,width,depth,scope,guildId,buildingPresent,null);}
        /** A hex plot location; the box is derived from the cells. */
        public static ClaimLocation ofCells(String worldId,PlotCells cells,ClaimScope scope,UUID guildId,boolean buildingPresent){var box=cells.bounds();return new ClaimLocation(worldId,box.x(),box.z(),box.width(),box.depth(),scope,guildId,buildingPresent,cells);}
        public boolean isHex(){return cells!=null;}
        public PlotRect box(){return new PlotRect(minX,minZ,width,depth);}
        /** True when column (x, z) belongs to this property: its cells for hex plots, its box otherwise. */
        public boolean containsColumn(int x,int z){return x>=minX&&z>=minZ&&(long)x<(long)minX+width&&(long)z<(long)minZ+depth&&(cells==null||cells.containsColumn(x,z));}
        /** Hex plots on one lattice overlap only by sharing a cell; anything else falls back to the boxes. */
        public boolean overlaps(ClaimLocation other){
            if(!worldId.equals(other.worldId)||!box().overlaps(other.box()))return false;
            if(cells!=null&&other.cells!=null&&cells.lattice().equals(other.cells.lattice()))return cells.asSet().stream().anyMatch(other.cells::contains);
            if(cells!=null&&other.cells==null)return cells.lattice().cellsTouching(other.box()).stream().anyMatch(cells::contains);
            if(cells==null&&other.cells!=null)return other.overlaps(this);
            return true;
        }
        /** Same place with new cells (expansion). */
        public ClaimLocation withCells(PlotCells next){return ofCells(worldId,next,scope,guildId,buildingPresent);}
    }
    public record Slot(Owner owner,UUID propertyId,State state,UUID operationId,long revision,String snapshotRef,UUID attachedGuild) {}
    private final OwnershipService ownership;private final JournalService journal;private final EconomyService economy;
    private final Supplier<List<TransactionalStore.Row>> reservedGround;
    /** Parsed view of {@link #reservedGround}, rebuilt only when a housing row commits (the row list is replaced). */
    private volatile GroundView groundView=new GroundView(null,Map.of(),Map.of());
    private record GroundView(List<TransactionalStore.Row> source,Map<String,List<ClaimLocation>> byWorld,Map<String,Set<HexCoord>> reservedCells) {}
    HousingService(com.hexvane.eterniamod.persistence.BatchedStore s,Clock c,Supplier<UUID> i,OwnershipService ownership,JournalService journal,EconomyService economy) {
        super(s,c,i);this.ownership=ownership;this.journal=journal;this.economy=Objects.requireNonNull(economy);reservedGround=s.committedRows("housing_slot");
    }
    /** World-thread protection check, including interrupted claims that have no native plot yet. No I/O. Cell exact for hex plots. */
    public boolean hasReservedGround(String world,int x,int z) {
        for(var location:ground().byWorld().getOrDefault(world,List.of()))if(location.containsColumn(x,z))return true;
        return false;
    }
    /**
     * Hex cells held by unfinished housing work in a world: claims and restores not yet active, and a cell an
     * expansion has paid for that the world has not applied yet. World thread safe, no I/O (committed rows only).
     * {@code HexLand} shows these as RESERVED.
     */
    public Set<HexCoord> reservedCells(String world){return ground().reservedCells().getOrDefault(world,Set.of());}
    private GroundView ground(){
        var rows=reservedGround.get();var view=groundView;if(view.source()==rows)return view;
        var byWorld=new HashMap<String,List<ClaimLocation>>();var reserved=new HashMap<String,Set<HexCoord>>();
        for(var row:rows){
            if(row.value("state").equals("PACKED")||row.value("world").isEmpty())continue;
            var location=location(row);
            byWorld.computeIfAbsent(location.worldId(),k->new ArrayList<>()).add(location);
            if(location.cells()==null)continue;
            if(!row.value("state").equals("ACTIVE"))reserved.computeIfAbsent(location.worldId(),k->new HashSet<>()).addAll(location.cells().asSet());
            else if(!row.value("expansionCell").isEmpty())reserved.computeIfAbsent(location.worldId(),k->new HashSet<>()).add(HexCoord.parse(row.value("expansionCell")));
        }
        var frozen=new HashMap<String,List<ClaimLocation>>();byWorld.forEach((k,v)->frozen.put(k,List.copyOf(v)));
        var frozenCells=new HashMap<String,Set<HexCoord>>();reserved.forEach((k,v)->frozenCells.put(k,Set.copyOf(v)));
        view=new GroundView(rows,Map.copyOf(frozen),Map.copyOf(frozenCells));groundView=view;return view;
    }
    public Optional<Slot> find(Owner owner) { return store.transaction(tx->tx.find("housing_slot",owner.key()).map(HousingService::slot)); }
    public List<Slot> allSlots() { return store.transaction(tx->tx.scan("housing_slot").stream().map(HousingService::slot).toList()); }
    public record Property(Slot slot,ClaimLocation location) {}
    public Optional<Property> property(Owner owner){return store.transaction(tx->tx.find("housing_slot",owner.key()).map(r->new Property(slot(r),r.value("world").isEmpty()?null:location(r))));}
    public List<Property> properties(){return store.transaction(tx->tx.scan("housing_slot").stream().map(r->new Property(slot(r),r.value("world").isEmpty()?null:location(r))).toList());}
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
                    require(!location.overlaps(location(other)),CONFLICT,"Another claim occupies the location");
                }
            }
            journal.prepareIn(tx,operationId,"CLAIM",owner,propertyId.toString());
            var data=fields("property",propertyId,"state",State.CLAIMING,"operation",operationId,"snapshotRef","","moveReservation","","attachedGuild",attachedGuild);
            if(location!=null)data.putAll(locationFields(location));
            return slot(tx.save("housing_slot",owner.key(),0,data));
        });
    }
    public Slot beginRelocation(Owner owner,UUID operationId,boolean forced) {
        return store.transaction(tx->{var r=row(tx,"housing_slot",owner.key());
            if(r.value("operation").equals(operationId.toString())&&Set.of("MOVING","PACKED","RESTORING").contains(r.value("state")))return slot(r);
            require(r.value("state").equals("ACTIVE"),INVALID_STATE,"Property is already in an operation");
            require(r.value("expansion").isEmpty(),INVALID_STATE,"A new hexagon is still being added to this plot");
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
                require(!destination.overlaps(location(other)),CONFLICT,"Destination overlaps another property");
            }
            var data=changed(r,"attachedGuild",owner.kind()==Owner.Kind.PLAYER?destination.guildId():null);data.putAll(locationFields(destination));
            return slot(tx.save(r.namespace(),r.key(),r.revision(),data));});
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
    // ---------------------------------------------------------------------------------------------------------------
    // Hex plots. Rules are HexClaimRules; land ownership is read from the housing rows inside the same transaction, so
    // two claims or expansions racing for one cell can never both commit. Street and blocked cells come from the
    // caller (world infrastructure is not domain data): pass a fresh HexContext built on the world thread.
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * World facts a hex commit needs that the domain store does not hold: the lattice, public street cells, cells
     * blocked by unfinished road work or guild roads, and the tuning data. Build it with {@code HexLand.context(...)}.
     */
    public record HexContext(HexLattice lattice,Set<HexCoord> street,Set<HexCoord> blocked,HexPlotSettings settings) {
        public HexContext{Objects.requireNonNull(lattice);street=Set.copyOf(street);blocked=blocked==null?Set.of():Set.copyOf(blocked);Objects.requireNonNull(settings);}
    }
    /**
     * Result of a hex claim or expansion. When {@code result} is not valid nothing was saved or charged; show the
     * player {@code result.reason().langKey()}. When valid, {@code slot} is the saved row, {@code cells} the plot's
     * cells after the change and {@code charged} the coins taken (0 for a replay of an already saved operation).
     */
    public record HexOutcome(HexClaimRules.Result result,Slot slot,PlotCells cells,long charged,String world) {
        public boolean ok(){return result.valid();}
        static HexOutcome rejected(HexClaimRules.Result result){return new HexOutcome(result,null,null,0,null);}
        static HexOutcome rejected(HexClaimRules.Reason reason){return rejected(HexClaimRules.Result.of(reason));}
    }
    /** A paid expansion the world has not applied yet (see {@link #completeExpansion}). */
    public record PendingExpansion(Owner owner,UUID propertyId,UUID operationId,HexCoord cell,ClaimLocation location) {}

    /**
     * Reserves a new hex plot: validates {@code location.cells()} with {@link HexClaimRules#claim} against every
     * housing row in the world inside this transaction, charges {@code settings.claimCoins()} to {@code payer} when
     * above zero, journals a CLAIM operation and saves the row as CLAIMING (exactly like {@link #beginClaim}). The
     * native adapter then adds the plot projection ({@code HubPlotFootprint.forCells}), advances the journal to
     * WORLD_APPLIED and calls {@link #activate}; {@code NativeClaimCoordinator.recoverClaims} finishes it after a crash.
     * Replaying the same operation id with the same arguments returns the saved slot without charging again.
     */
    public HexOutcome claimCells(Owner owner,UUID propertyId,UUID operationId,UUID attachedGuild,ClaimLocation location,HexClaimRules.Candidate candidate,HexContext context,Owner payer) {
        Objects.requireNonNull(location);Objects.requireNonNull(candidate);Objects.requireNonNull(context);
        require(location.cells()!=null,INVALID_INPUT,"Hex claim needs cells");
        if(!location.cells().lattice().equals(context.lattice()))return HexOutcome.rejected(HexClaimRules.Reason.CHANGED);
        var outcome=store.transaction(tx->{
            var existing=tx.find("housing_slot",owner.key());
            if(existing.isPresent()) {
                var r=existing.get();
                require(r.value("property").equals(propertyId.toString())&&r.value("operation").equals(operationId.toString())&&!r.value("world").isEmpty()&&Objects.equals(location(r),location),CONFLICT,"Owner already has a housing slot or claim arguments changed");
                return new HexOutcome(HexClaimRules.Result.of(HexClaimRules.Reason.ALLOWED),slot(r),location.cells(),0,location.worldId());
            }
            if(attachedGuild!=null) { require(owner.kind()==Owner.Kind.PLAYER,INVALID_INPUT,"Only player homes attach to guilds");GuildService.requireMember(tx,owner.id(),attachedGuild); }
            require(Objects.equals(attachedGuild,location.guildId())||owner.kind()==Owner.Kind.GUILD&&owner.id().equals(location.guildId()),INVALID_INPUT,"Claim guild differs from housing owner/attachment");
            var rows=worldRows(tx,location.worldId(),null);
            var land=land(context,rows);
            Set<HexCoord> anchors=candidate.kind()==HexClaimRules.Kind.GUILD_MEMBER&&attachedGuild!=null?guildLand(rows,attachedGuild):Set.of();
            var verdict=HexClaimRules.claim(land,candidate,location.cells().cells(),anchors,context.settings());
            if(!verdict.valid())return HexOutcome.rejected(verdict);
            long price=context.settings().claimCoins();
            if(price>0) {
                require(payer!=null,INVALID_INPUT,"A paid claim needs a payer");
                if(economy.balanceIn(tx,payer).available()<price)return HexOutcome.rejected(HexClaimRules.Result.of(HexClaimRules.Reason.COINS,price));
                economy.spendIn(tx,payer,price,"hexplot-claim:"+operationId);
            }
            journal.prepareIn(tx,operationId,"CLAIM",owner,propertyId.toString());
            var data=fields("property",propertyId,"state",State.CLAIMING,"operation",operationId,"snapshotRef","","moveReservation","","attachedGuild",attachedGuild);
            data.putAll(locationFields(location));
            return new HexOutcome(verdict,slot(tx.save("housing_slot",owner.key(),0,data)),location.cells(),price,location.worldId());
        });
        if(outcome.ok())HexLandEvents.fire(location.worldId(),location.cells().asSet(),HexLandEvents.Cause.RESERVATION);
        return outcome;
    }

    /**
     * Adds one cell to an ACTIVE hex plot. Checks the row revision ({@code expectedRevision} from {@link Slot#revision()}
     * when the player looked; a mismatch returns CHANGED), validates with {@link HexClaimRules#expand} against every
     * row in the world, charges {@code settings.expansionPrice(cells owned)} to {@code payer}, journals an EXPAND
     * operation and saves the new cells (the durable truth). The native adapter then updates the plot projection
     * ({@code HubPlotRecord.setFootprint(footprint.withCells(outcome.cells()))}) and calls {@link #completeExpansion}.
     * Replaying the same operation id returns the saved result without charging again.
     */
    public HexOutcome expandPlot(Owner owner,UUID propertyId,HexCoord cell,long expectedRevision,UUID operationId,HexClaimRules.Kind kind,HexContext context,Owner payer) {
        Objects.requireNonNull(cell);Objects.requireNonNull(kind);Objects.requireNonNull(context);
        var outcome=store.transaction(tx->{
            var found=tx.find("housing_slot",owner.key());
            if(found.isEmpty()||!found.get().value("property").equals(propertyId.toString()))return HexOutcome.rejected(HexClaimRules.Reason.NO_PLOT);
            var r=found.get();
            var previous=tx.find("operation",operationId.toString());
            if(previous.isPresent()) {
                require(previous.get().value("kind").equals("EXPAND")&&previous.get().value("detail").equals(expansionDetail(propertyId,cell)),CONFLICT,"Operation id reused");
                var saved=location(r);return new HexOutcome(HexClaimRules.Result.of(HexClaimRules.Reason.EXPAND_ALLOWED),slot(r),saved.cells(),0,saved.worldId());
            }
            if(r.revision()!=expectedRevision||!r.value("state").equals("ACTIVE")||!r.value("expansion").isEmpty())return HexOutcome.rejected(HexClaimRules.Reason.CHANGED);
            var location=location(r);
            if(location.cells()==null)return HexOutcome.rejected(HexClaimRules.Reason.NO_LATTICE);
            if(!location.cells().lattice().equals(context.lattice()))return HexOutcome.rejected(HexClaimRules.Reason.CHANGED);
            var land=land(context,worldRows(tx,location.worldId(),null));
            var verdict=HexClaimRules.expand(land,kind,location.cells().asSet(),cell,context.settings());
            if(!verdict.valid())return HexOutcome.rejected(verdict);
            long price=context.settings().expansionPrice(location.cells().size());
            if(price>0) {
                require(payer!=null,INVALID_INPUT,"A paid expansion needs a payer");
                if(economy.balanceIn(tx,payer).available()<price)return HexOutcome.rejected(HexClaimRules.Result.of(HexClaimRules.Reason.COINS,price));
                economy.spendIn(tx,payer,price,"hexplot-expand:"+operationId);
            }
            journal.prepareIn(tx,operationId,"EXPAND",owner,expansionDetail(propertyId,cell));
            var next=location.withCells(location.cells().with(cell));
            var data=changed(r,"expansion",operationId,"expansionCell",cell);data.putAll(locationFields(next));
            return new HexOutcome(verdict,slot(tx.save(r.namespace(),r.key(),r.revision(),data)),next.cells(),price,next.worldId());
        });
        if(outcome.ok())HexLandEvents.fire(outcome.world(),Set.of(cell),HexLandEvents.Cause.RESERVATION);
        return outcome;
    }

    /** The native adapter applied a paid expansion to the world projection. Idempotent. */
    public Slot completeExpansion(Owner owner,UUID operationId) {
        return store.transaction(tx->{
            var r=row(tx,"housing_slot",owner.key());var op=row(tx,"operation",operationId.toString());
            require(op.value("kind").equals("EXPAND")&&op.value("owner").equals(owner.key()),CONFLICT,"Not an expansion of this property");
            if(op.value("state").equals("COMPLETED"))return slot(r);
            require(r.value("expansion").equals(operationId.toString()),CONFLICT,"Housing operation changed");
            var applied=journal.advanceIn(tx,operationId,op.revision(),JournalService.State.WORLD_APPLIED);
            journal.advanceIn(tx,operationId,applied.revision(),JournalService.State.COMPLETED);
            return slot(save(tx,r,"expansion","","expansionCell",""));
        });
    }

    /** Paid expansions not yet applied to the world, for recovery on world load. */
    public List<PendingExpansion> pendingExpansions(String world) {
        return store.transaction(tx->tx.scan("housing_slot").stream().filter(r->!r.value("expansion").isEmpty()&&r.value("world").equals(world))
            .map(r->new PendingExpansion(Owner.parse(r.key()),UUID.fromString(r.value("property")),UUID.fromString(r.value("expansion")),HexCoord.parse(r.value("expansionCell")),location(r))).toList());
    }

    private static String expansionDetail(UUID propertyId,HexCoord cell){return propertyId+" "+cell;}
    /** Unpacked rows in a world, optionally without one owner. */
    private static List<TransactionalStore.Row> worldRows(TransactionalStore.Transaction tx,String world,Owner except) {
        return tx.scan("housing_slot").stream().filter(r->!r.value("state").equals("PACKED")&&r.value("world").equals(world)&&(except==null||!r.key().equals(except.key()))).toList();
    }
    /** Land as the rules see it from durable rows: active cells are owned, unfinished ones reserved, rectangles cover every cell they touch. */
    private static HexClaimRules.Land land(HexContext context,List<TransactionalStore.Row> rows) {
        var owners=new HashMap<HexCoord,UUID>();var reserved=new HashSet<HexCoord>();
        for(var r:rows) {
            var location=location(r);UUID property=UUID.fromString(r.value("property"));
            Collection<HexCoord> cells=location.cells()!=null&&location.cells().lattice().equals(context.lattice())?location.cells().asSet():context.lattice().cellsTouching(location.box());
            if(r.value("state").equals("ACTIVE"))for(var c:cells)owners.put(c,property);else reserved.addAll(cells);
            if(!r.value("expansionCell").isEmpty())reserved.add(HexCoord.parse(r.value("expansionCell")));
        }
        return new HexClaimRules.Land(context.lattice(),context.street(),owners,context.blocked(),reserved);
    }
    /** Cells a new member plot of {@code guild} may touch: the estate plus member plots joined to it. */
    private static Set<HexCoord> guildLand(List<TransactionalStore.Row> rows,UUID guild) {
        Set<HexCoord> estate=new HashSet<>();var members=new ArrayList<Set<HexCoord>>();
        for(var r:rows) {
            var location=location(r);if(location.cells()==null||!r.value("state").equals("ACTIVE"))continue;
            if(r.key().equals(Owner.guild(guild).key()))estate.addAll(location.cells().asSet());
            else if(r.value("attachedGuild").equals(guild.toString()))members.add(location.cells().asSet());
        }
        return HexClaimRules.joinedGuildLand(estate,members);
    }

    private static TransactionalStore.Row matching(TransactionalStore.Transaction tx,Owner owner,UUID operationId) { var r=row(tx,"housing_slot",owner.key());require(r.value("operation").equals(operationId.toString()),CONFLICT,"Housing operation changed");return r; }
    private static Slot slot(TransactionalStore.Row r) { return new Slot(Owner.parse(r.key()),UUID.fromString(r.value("property")),State.valueOf(r.value("state")),UUID.fromString(r.value("operation")),r.revision(),r.value("snapshotRef"),r.value("attachedGuild").isEmpty()?null:UUID.fromString(r.value("attachedGuild"))); }
    private static ClaimLocation location(TransactionalStore.Row r) { return new ClaimLocation(r.value("world"),Integer.parseInt(r.value("minX")),Integer.parseInt(r.value("minZ")),Integer.parseInt(r.value("width")),Integer.parseInt(r.value("depth")),ClaimScope.valueOf(r.value("scope")),r.value("guildId").isEmpty()?null:UUID.fromString(r.value("guildId")),Boolean.parseBoolean(r.value("buildingPresent")),r.value("cells").isEmpty()?null:PlotCells.parse(r.value("lattice"),r.value("cells"))); }
    private static Map<String,String> locationFields(ClaimLocation l) {
        return fields("world",l.worldId(),"minX",l.minX(),"minZ",l.minZ(),"width",l.width(),"depth",l.depth(),"scope",l.scope(),"guildId",l.guildId(),"buildingPresent",l.buildingPresent(),
            "lattice",l.cells()==null?null:l.cells().latticeId(),"cells",l.cells()==null?null:l.cells().text());
    }
}
