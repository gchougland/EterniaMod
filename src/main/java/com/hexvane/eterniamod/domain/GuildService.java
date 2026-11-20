package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class GuildService extends DomainSupport {
    public record Guild(UUID id,String name,UUID leader) {}
    public record Membership(UUID playerId,UUID guildId,String role,boolean active,long revision) {}
    public record Role(String id,String name,int rank,Set<String> capabilities) { public Role { capabilities=Set.copyOf(capabilities); } }
    public record DepartureJob(String id,UUID playerId,UUID guildId,Instant dueAt,String state,String worker,long revision) {}
    private static final Set<String> LEADER_ONLY=Set.of("role.edit","leadership.transfer","guild.disband","housing.claim","housing.move","housing.resize","commerce.purchase_for_guild");
    private static final Set<String> BASE=Set.of("guild.view","guild.chat","board.read","board.post","party.create","inventory.deposit","treasury.deposit","housing.door.use","housing.bed.use","housing.bench.use","convenience.use","housing.visit","wild.build","wild.containers","wild.use");
    GuildService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    public Guild create(UUID leader,String name,String receiptId) {
        text(name,"guild name",48);return store.transaction(tx->guild(createIn(tx,leader,name,receiptId)));
    }
    /** Starts a guild with its tag, motto, banner and join rule, all in one step. */
    public Profile create(UUID leader,String name,String tag,String motto,GuildBanner banner,JoinRule join,String receiptId) {
        String cleanName=oneLine(name,"guild name",48);String cleanTag=tag(tag);String cleanMotto=motto(motto);
        Objects.requireNonNull(banner,"banner");Objects.requireNonNull(join,"join");
        return store.transaction(tx->{
            var existing=tx.find("guild_create_receipt",key(receiptId));
            if(existing.isPresent())return profile(createIn(tx,leader,cleanName,receiptId));
            requireTagFree(tx,cleanTag,null);
            var row=createIn(tx,leader,cleanName,receiptId);
            return profile(save(tx,row,"tag",cleanTag,"motto",cleanMotto,"banner",banner.encode(),"join",join.name()));
        });
    }
    private TransactionalStore.Row createIn(TransactionalStore.Transaction tx,UUID leader,String name,String receiptId) {
        row(tx,"account",leader.toString());String rkey=key(receiptId);var prior=tx.find("guild_create_receipt",rkey);
        if(prior.isPresent()) { require(prior.get().value("leader").equals(leader.toString())&&prior.get().value("name").equals(name),CONFLICT,"Guild receipt reused");return row(tx,"guild",prior.get().value("guild")); }
        require(tx.find("guild_member",leader.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"You are already in a guild.");
        require(tx.scan("guild").stream().noneMatch(r->r.value("name").equalsIgnoreCase(name)),CONFLICT,"That guild name is taken.");
        UUID guildId=ids.get();var guild=tx.save("guild",guildId.toString(),0,fields("name",name,"leader",leader));
        tx.save("guild_create_receipt",rkey,0,fields("leader",leader,"name",name,"guild",guildId));seedRoles(tx,guildId);
        putMember(tx,leader,guildId,"leader");return guild;
    }
    public String invite(UUID actor,UUID target) {
        return store.transaction(tx->{var member=currentMember(tx,actor);requireCapability(tx,actor,UUID.fromString(member.value("guild")),"member.invite");row(tx,"account",target.toString());
            require(!FriendService.blockedIn(tx,target,actor),FORBIDDEN,"That player is not taking guild invites from you.");
            require(tx.find("guild_member",target.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"Player already belongs to a guild");
            String invite=ids.get().toString();tx.save("guild_invite",invite,0,fields("guild",member.value("guild"),"target",target,"expires",clock.instant().plus(Duration.ofDays(7)),"state","PENDING"));return invite;});
    }
    public Membership acceptInvite(UUID player,String inviteId) {
        return store.transaction(tx->{var invite=pendingInvite(tx,player,inviteId);require(invite.value("target").equals(player.toString()),FORBIDDEN,"Invite belongs to another player");
            if(invite.value("state").equals("ACCEPTED"))return membership(currentMember(tx,player));
            require(invite.value("state").equals("PENDING")&&future(invite.value("expires"),clock.instant()),INVALID_STATE,"Invite expired");
            require(tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"Already in a guild");
            UUID guild=UUID.fromString(invite.value("guild"));var result=admit(tx,player,guild);save(tx,invite,"state","ACCEPTED");
            return membership(result);});
    }
    /** Every way into a guild ends here: an accepted invitation, an open guild, or an accepted request. */
    private TransactionalStore.Row admit(TransactionalStore.Transaction tx,UUID player,UUID guild) {
        row(tx,"guild",guild.toString());
        require(tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"Already in a guild");
        var result=putMember(tx,player,guild,"member");
        for(var job:tx.scan("guild_departure"))if(job.value("player").equals(player.toString())&&job.value("guild").equals(guild.toString())&&!job.value("state").equals("COMPLETED")&&clock.instant().isBefore(Instant.parse(job.value("due"))))save(tx,job,"state","CANCELLED");
        for(var request:tx.scan("guild_request"))if(request.value("player").equals(player.toString())&&request.value("state").equals("PENDING"))save(tx,request,"state",request.value("guild").equals(guild.toString())?"ACCEPTED":"CLOSED");
        return result;
    }
    public Optional<Membership> membership(UUID player) { return store.transaction(tx->tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).map(GuildService::membership)); }
    public List<Membership> roster(UUID actor) { return store.transaction(tx->{var me=currentMember(tx,actor);return tx.scan("guild_member").stream().filter(r->r.value("active").equals("true")&&r.value("guild").equals(me.value("guild"))).map(GuildService::membership).toList();}); }
    public List<Role> roles(UUID actor) { return store.transaction(tx->{var me=currentMember(tx,actor);UUID guild=UUID.fromString(me.value("guild"));return tx.scan("guild_role").stream().filter(r->r.value("guild").equals(guild.toString())).map(r->role(tx,guild,r.value("id"))).sorted(Comparator.comparingInt(Role::rank)).toList();}); }
    public Optional<Guild> find(UUID guildId) { return store.transaction(tx->tx.find("guild",guildId.toString()).map(GuildService::guild)); }

    // ------------------------------------------------------------------------------------------------ profile, browsing and joining

    /** Who may join without an invitation. Guilds made before join rules existed take invitations only, as before. */
    public enum JoinRule { OPEN("Anyone can join"), ASK("Ask to join"), INVITE("Invite only");
        public final String label; JoinRule(String label){this.label=label;}
        static JoinRule of(String stored){try{return stored.isEmpty()?INVITE:valueOf(stored);}catch(IllegalArgumentException old){return INVITE;}} }
    public record Profile(UUID id,String name,String tag,String motto,GuildBanner banner,JoinRule join,UUID leader) {}
    public record Listing(Profile profile,int members) {}
    public record JoinRequest(UUID guild,UUID player,Instant at) {}
    public record Invitation(String id,UUID guild,Instant expires) {}
    public static final int TAG_MAX=4,MOTTO_MAX=80;

    public Optional<Profile> profile(UUID guildId) { return store.transaction(tx->tx.find("guild",guildId.toString()).map(GuildService::profile)); }

    /** Every guild with its member count, counted in one pass. */
    public List<Listing> directory() {
        return store.transaction(tx->{
            var counts=new HashMap<String,Integer>();
            for(var m:tx.scan("guild_member"))if(m.value("active").equals("true"))counts.merge(m.value("guild"),1,Integer::sum);
            var out=new ArrayList<Listing>();
            for(var g:tx.scan("guild")){int n=counts.getOrDefault(g.key(),0);if(n>0)out.add(new Listing(profile(g),n));}
            return List.copyOf(out);
        });
    }

    /** Members of any guild, for its public page: who they are and their role, no permissions. */
    public List<Membership> members(UUID guildId) {
        return store.transaction(tx->tx.scan("guild_member").stream().filter(r->r.value("active").equals("true")&&r.value("guild").equals(guildId.toString())).map(GuildService::membership).toList());
    }

    /** Changes the guild's tag, motto, banner and join rule. Needs the leader's role editing power. */
    public Profile updateProfile(UUID actor,String tag,String motto,GuildBanner banner,JoinRule join) {
        String cleanTag=tag(tag);String cleanMotto=motto(motto);Objects.requireNonNull(banner,"banner");Objects.requireNonNull(join,"join");
        return store.transaction(tx->{var me=currentMember(tx,actor);UUID guild=UUID.fromString(me.value("guild"));
            require(role(tx,guild,me.value("role")).capabilities.contains("role.edit"),FORBIDDEN,"Only the guild leader can change the banner and settings.");
            requireTagFree(tx,cleanTag,guild);
            return profile(save(tx,row(tx,"guild",guild.toString()),"tag",cleanTag,"motto",cleanMotto,"banner",banner.encode(),"join",join.name()));});
    }

    /** Joins a guild that anyone can join. */
    public Membership join(UUID player,UUID guildId) {
        return store.transaction(tx->{var g=row(tx,"guild",guildId.toString());
            require(JoinRule.of(g.value("join"))==JoinRule.OPEN,FORBIDDEN,"This guild is not open to everyone.");
            return membership(admit(tx,player,guildId));});
    }

    /** Asks to join a guild whose officers accept requests. Asking twice keeps one request. */
    public void requestJoin(UUID player,UUID guildId) {
        store.transaction(tx->{var g=row(tx,"guild",guildId.toString());row(tx,"account",player.toString());
            require(JoinRule.of(g.value("join"))==JoinRule.ASK,FORBIDDEN,"This guild does not take requests.");
            require(tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"You are already in a guild.");
            String k=guildId+":"+player;var old=tx.find("guild_request",k);
            if(old.isPresent()&&old.get().value("state").equals("PENDING"))return null;
            tx.save("guild_request",k,old.map(TransactionalStore.Row::revision).orElse(0L),fields("guild",guildId,"player",player,"at",clock.instant(),"state","PENDING"));return null;});
    }

    public void cancelRequest(UUID player,UUID guildId) {
        store.transaction(tx->{tx.find("guild_request",guildId+":"+player).filter(r->r.value("state").equals("PENDING")).ifPresent(r->save(tx,r,"state","CANCELLED"));return null;});
    }

    /** Guilds this player has asked to join and not heard back from. */
    public Set<UUID> pendingRequests(UUID player) {
        return store.transaction(tx->{var out=new HashSet<UUID>();for(var r:tx.scan("guild_request"))if(r.value("player").equals(player.toString())&&r.value("state").equals("PENDING"))out.add(UUID.fromString(r.value("guild")));return Set.copyOf(out);});
    }

    /** Open requests to the actor's guild, oldest first; empty for members who may not invite. */
    public List<JoinRequest> requests(UUID actor) {
        return store.transaction(tx->{var me=tx.find("guild_member",actor.toString()).filter(r->r.value("active").equals("true"));
            if(me.isEmpty())return List.<JoinRequest>of();String guild=me.get().value("guild");
            if(!role(tx,UUID.fromString(guild),me.get().value("role")).capabilities.contains("member.invite"))return List.<JoinRequest>of();
            return tx.scan("guild_request").stream().filter(r->r.value("guild").equals(guild)&&r.value("state").equals("PENDING"))
                .map(r->new JoinRequest(UUID.fromString(guild),UUID.fromString(r.value("player")),Instant.parse(r.value("at")))).sorted(Comparator.comparing(JoinRequest::at)).toList();});
    }

    /** Accepts or declines a request. Returns false when the player had already joined another guild. */
    public boolean answerRequest(UUID actor,UUID player,boolean accept) {
        return store.transaction(tx->{var me=currentMember(tx,actor);UUID guild=UUID.fromString(me.value("guild"));requireCapability(tx,actor,guild,"member.invite");
            var request=tx.find("guild_request",guild+":"+player).filter(r->r.value("state").equals("PENDING")).orElseThrow(()->new DomainException(NOT_FOUND,"That request is no longer open."));
            if(!accept){save(tx,request,"state","DECLINED");return true;}
            if(tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).isPresent()){save(tx,request,"state","CLOSED");return false;}
            admit(tx,player,guild);return true;});
    }

    /** Invitations this player can still accept. */
    public List<Invitation> invitations(UUID player) {
        Instant now=clock.instant();
        return store.transaction(tx->tx.scan("guild_invite").stream().filter(r->r.value("target").equals(player.toString())&&r.value("state").equals("PENDING")&&future(r.value("expires"),now))
            .map(r->new Invitation(r.key(),UUID.fromString(r.value("guild")),Instant.parse(r.value("expires")))).toList());
    }

    private static Profile profile(TransactionalStore.Row r) {
        UUID id=UUID.fromString(r.key());
        return new Profile(id,r.value("name"),r.value("tag"),r.value("motto"),GuildBanner.decode(r.value("banner")).orElseGet(()->GuildBanner.forGuild(id)),JoinRule.of(r.value("join")),UUID.fromString(r.value("leader")));
    }
    private static void requireTagFree(TransactionalStore.Transaction tx,String tag,UUID self) {
        if(tag.isEmpty())return;
        require(tx.scan("guild").stream().noneMatch(g->g.value("tag").equalsIgnoreCase(tag)&&(self==null||!g.key().equals(self.toString()))),CONFLICT,"Another guild already uses the tag "+tag+".");
    }
    /** A short tag of letters and digits, shown in capitals. Blank means no tag. */
    static String tag(String input) {
        String t=input==null?"":input.strip().toUpperCase(Locale.ROOT);
        require(t.isEmpty()||(t.length()>=2&&t.length()<=TAG_MAX&&t.chars().allMatch(c->c<128&&Character.isLetterOrDigit(c))),INVALID_INPUT,"A tag is 2 to 4 letters or numbers.");
        return t;
    }
    static String motto(String input) {
        String m=input==null?"":input.strip();
        if(m.isEmpty())return m;
        return oneLine(m,"motto",MOTTO_MAX);
    }
    static String oneLine(String input,String what,int max) {
        String v=input==null?"":input.strip();
        require(!v.isEmpty(),INVALID_INPUT,"Enter a "+what+".");
        require(v.length()<=max,INVALID_INPUT,"The "+what+" can be at most "+max+" characters.");
        require(v.codePoints().noneMatch(c->Character.isISOControl(c)||c==0x2028||c==0x2029),INVALID_INPUT,"Keep the "+what+" on one line.");
        return v;
    }
    /** A player's current guild and what their role there may do, in one read; empty outside a guild. */
    public record Standing(UUID guildId,Set<String> capabilities) { public Standing { capabilities=Set.copyOf(capabilities); } }
    public Optional<Standing> standing(UUID player) {
        return store.transaction(tx->{
            var m=tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true"));
            if(m.isEmpty())return Optional.<Standing>empty();
            UUID guild=UUID.fromString(m.get().value("guild"));
            try{return Optional.of(new Standing(guild,role(tx,guild,m.get().value("role")).capabilities));}
            catch(DomainException missing){return Optional.of(new Standing(guild,Set.of()));}
        });
    }
    public boolean can(UUID actor,UUID guildId,String capability) { return store.transaction(tx->{try{requireCapability(tx,actor,guildId,capability);return true;}catch(DomainException e){if(e.code()==FORBIDDEN||e.code()==NOT_FOUND)return false;throw e;}}); }
    /** Members a guild needs before it can claim its estate. */
    public static final int GUILD_ESTATE_MEMBERS = 3;
    public boolean eligibleForRoot(UUID actor) { return store.transaction(tx->{var me=currentMember(tx,actor);UUID guild=UUID.fromString(me.value("guild"));requireCapability(tx,actor,guild,"housing.claim");return tx.scan("guild_member").stream().filter(r->r.value("active").equals("true")&&r.value("guild").equals(guild.toString())).count()>=GUILD_ESTATE_MEMBERS;}); }
    public void defineRole(UUID leader,Role role) {
        text(role.id,"role id",50);text(role.name,"role name",50);require(role.id.matches("[a-z][a-z0-9_-]*")&&role.rank>0,INVALID_INPUT,"Invalid custom role");require(Collections.disjoint(role.capabilities,LEADER_ONLY),FORBIDDEN,"Custom roles cannot receive leader powers");
        for(String cap:role.capabilities){text(cap,"capability",100);require(cap.matches("[a-z][a-z0-9_.-]*"),INVALID_INPUT,"Invalid capability id");}
        store.transaction(tx->{var me=currentMember(tx,leader);UUID guild=UUID.fromString(me.value("guild"));requireCapability(tx,leader,guild,"role.edit");
            require(!Set.of("leader","officer","architect","quartermaster","member").contains(role.id),FORBIDDEN,"Built-in roles cannot be replaced");
            String k=roleKey(guild,role.id);var old=tx.find("guild_role",k);tx.save("guild_role",k,old.map(TransactionalStore.Row::revision).orElse(0L),fields("guild",guild,"id",role.id,"name",role.name,"rank",role.rank,"caps",String.join("\n",new TreeSet<>(role.capabilities))));return null;});
    }
    public void assignRole(UUID actor,UUID target,String roleId) {
        store.transaction(tx->{var a=currentMember(tx,actor);UUID guild=UUID.fromString(a.value("guild"));requireCapability(tx,actor,guild,"member.role.assign");var t=requireMember(tx,target,guild);
            var ar=role(tx,guild,a.value("role"));var tr=role(tx,guild,t.value("role"));var desired=role(tx,guild,roleId);
            require(ar.rank<tr.rank&&ar.rank<desired.rank,FORBIDDEN,"Cannot assign peer or higher roles");
            require(ar.capabilities.containsAll(desired.capabilities),FORBIDDEN,"Cannot grant permissions you lack");save(tx,t,"role",roleId);return null;});
    }
    public void transferLeadership(UUID leader,UUID next) {
        store.transaction(tx->{var me=currentMember(tx,leader);UUID guild=UUID.fromString(me.value("guild"));requireCapability(tx,leader,guild,"leadership.transfer");require(!leader.equals(next),INVALID_INPUT,"Choose another leader");
            var target=requireMember(tx,next,guild);save(tx,me,"role","officer");save(tx,target,"role","leader");save(tx,row(tx,"guild",guild.toString()),"leader",next);return null;});
    }
    public void leave(UUID player) { store.transaction(tx->{depart(tx,currentMember(tx,player));return null;}); }
    public void remove(UUID actor,UUID target) {
        store.transaction(tx->{var a=currentMember(tx,actor);UUID guild=UUID.fromString(a.value("guild"));requireCapability(tx,actor,guild,"member.remove");var t=requireMember(tx,target,guild);
            require(role(tx,guild,a.value("role")).rank<role(tx,guild,t.value("role")).rank,FORBIDDEN,"Cannot remove peer or higher role");depart(tx,t);return null;});
    }
    private void depart(TransactionalStore.Transaction tx,TransactionalStore.Row member) {
        require(!member.value("role").equals("leader"),FORBIDDEN,"Transfer leadership before leaving");
        var changed=save(tx,member,"active",false);        // A plot joined to the guild (no longer made, but old ones still leave with their owner).
        var slot=HousingService.ownerRowsIn(tx,Owner.player(UUID.fromString(member.key()))).stream().filter(r->r.value("attachedGuild").equals(member.value("guild"))).findFirst();
        if(slot.isPresent()) {
            String id=key(member.key(),member.value("guild"),Long.toString(changed.revision()));
            tx.save("guild_departure",id,0,fields("player",member.key(),"guild",member.value("guild"),"memberRevision",changed.revision(),"due",clock.instant().plus(Duration.ofHours(48)),"state","PENDING","worker","","leaseUntil",""));
        }
    }
    public List<DepartureJob> leaseDueDepartures(String worker,int limit) {
        text(worker,"worker",100);require(limit>0&&limit<=100,INVALID_INPUT,"Invalid lease limit");
        return store.transaction(tx->{var jobs=new ArrayList<DepartureJob>();Instant now=clock.instant();
            for(var r:tx.scan("guild_departure")) { if(jobs.size()>=limit)break;
                if(Instant.parse(r.value("due")).isAfter(now)||Set.of("COMPLETED","CANCELLED").contains(r.value("state")))continue;
                if(r.value("state").equals("RUNNING")&&future(r.value("leaseUntil"),now))continue;
                var member=tx.find("guild_member",r.value("player"));
                if(member.isPresent()&&member.get().value("active").equals("true")&&member.get().value("guild").equals(r.value("guild"))&&!member.get().value("joinedAt").isEmpty()&&Instant.parse(member.get().value("joinedAt")).isBefore(Instant.parse(r.value("due")))) {save(tx,r,"state","CANCELLED");continue;}
                jobs.add(job(save(tx,r,"state","RUNNING","worker",worker,"leaseUntil",now.plus(Duration.ofMinutes(5)))));
            }return List.copyOf(jobs);});
    }
    public void acknowledgeDeparture(String jobId,String worker,UUID packedOperation) {
        store.transaction(tx->{var job=row(tx,"guild_departure",jobId);if(job.value("state").equals("COMPLETED"))return null;
            require(job.value("state").equals("RUNNING")&&job.value("worker").equals(worker)&&future(job.value("leaseUntil"),clock.instant()),CONFLICT,"Departure lease is not held");
            var op=row(tx,"operation",packedOperation.toString());require(op.value("kind").equals("FORCED_RETURN")&&op.value("state").equals("PACKED")&&op.value("owner").equals(Owner.player(UUID.fromString(job.value("player"))).key()),INVALID_STATE,"Player property has not been durably packed");
            save(tx,job,"state","COMPLETED","operation",packedOperation);return null;});
    }
    static TransactionalStore.Row currentMember(TransactionalStore.Transaction tx,UUID player) { var r=row(tx,"guild_member",player.toString());require(r.value("active").equals("true"),FORBIDDEN,"Not a current guild member");return r; }
    static TransactionalStore.Row requireMember(TransactionalStore.Transaction tx,UUID player,UUID guild) { var r=currentMember(tx,player);require(r.value("guild").equals(guild.toString()),FORBIDDEN,"Not a member of this guild");return r; }
    static void requireCapability(TransactionalStore.Transaction tx,UUID actor,UUID guild,String capability) {var m=requireMember(tx,actor,guild);require(role(tx,guild,m.value("role")).capabilities.contains(capability),FORBIDDEN,"Missing guild permission: "+capability);}
    private static String roleKey(UUID guild,String id) { return guild+":"+id; }
    /**
     * The invitation to accept: the one named by its code, or with no code the player's newest open invitation.
     * A blank or unknown code used to reach storage as an invalid key; now it reads as "no invitation".
     */
    private TransactionalStore.Row pendingInvite(TransactionalStore.Transaction tx,UUID player,String inviteId) {
        String code=inviteId==null?"":inviteId.trim();
        if(!code.isEmpty()){
            try{UUID.fromString(code);}catch(IllegalArgumentException e){throw new DomainException(NOT_FOUND,"That invitation code was not found.");}
            return tx.find("guild_invite",code).orElseThrow(()->new DomainException(NOT_FOUND,"That invitation code was not found."));
        }
        Instant now=clock.instant();
        return tx.scan("guild_invite").stream()
            .filter(r->r.value("target").equals(player.toString())&&r.value("state").equals("PENDING")&&future(r.value("expires"),now))
            .max(Comparator.comparing(r->r.value("expires")))
            .orElseThrow(()->new DomainException(NOT_FOUND,"You have no open guild invitation. Ask a guild member to invite you."));
    }
    private static Role role(TransactionalStore.Transaction tx,UUID guild,String id) {var r=row(tx,"guild_role",roleKey(guild,id));return new Role(id,r.value("name"),Integer.parseInt(r.value("rank")),new HashSet<>(Arrays.asList(r.value("caps").split("\n"))));}
    private TransactionalStore.Row putMember(TransactionalStore.Transaction tx,UUID player,UUID guild,String role) {var old=tx.find("guild_member",player.toString());return tx.save("guild_member",player.toString(),old.map(TransactionalStore.Row::revision).orElse(0L),fields("guild",guild,"role",role,"active",true,"joinedAt",clock.instant()));}
    /**
     * Guilds made before guild Crowns existed: gives the leader and officer roles the permission to spend them, once.
     * Returns how many roles changed.
     */
    public int grantCrownSpending() {
        return store.transaction(tx->{int changed=0;
            for(var r:tx.scan("guild_role")){
                if(!Set.of("leader","officer").contains(r.value("id")))continue;
                var caps=new TreeSet<>(Arrays.asList(r.value("caps").split("\n")));
                if(caps.add(GuildCrownService.SPEND)){save(tx,r,"caps",String.join("\n",caps));changed++;}
            }
            return changed;});
    }
    /**
     * Guilds made before land in adventure worlds: every built in role may build, open chests and use things on guild
     * land, and leaders and officers may claim and share it. Custom roles are left as their leader set them.
     */
    public int grantWildLand() {
        return store.transaction(tx->{int changed=0;
            // Once only, so a leader who later takes these from a role keeps it that way.
            if(tx.find("migration","wild-land-roles").isPresent())return 0;
            tx.save("migration","wild-land-roles",0,fields("at",clock.millis()));
            for(var r:tx.scan("guild_role")){
                String id=r.value("id");
                if(!Set.of("leader","officer","architect","quartermaster","member").contains(id))continue;
                var caps=new TreeSet<>(Arrays.asList(r.value("caps").split("\n")));
                boolean grew=caps.addAll(Set.of("wild.build","wild.containers","wild.use"));
                if(Set.of("leader","officer").contains(id))grew|=caps.add(WildLandService.MANAGE);
                if(grew){save(tx,r,"caps",String.join("\n",caps));changed++;}
            }
            return changed;});
    }
    private void seedRoles(TransactionalStore.Transaction tx,UUID guild) {
        var officer=new HashSet<>(BASE);officer.addAll(Set.of("board.moderate","member.invite","member.remove","member.role.assign","audit.view",GuildCrownService.SPEND,WildLandService.MANAGE));
        var architect=new HashSet<>(BASE);architect.addAll(Set.of("housing.structure","housing.palette","housing.addition","housing.prop.place","housing.prop.move","housing.prop.pack","housing.block.build","housing.block.break","housing.road.manage","inventory.reserve_for_build"));
        var quarter=new HashSet<>(BASE);quarter.addAll(Set.of("inventory.withdraw","shop.manage","mail.attachments.claim","treasury.withdraw","housing.container.open","audit.view"));
        var leader=new HashSet<>(officer);leader.addAll(architect);leader.addAll(quarter);leader.addAll(LEADER_ONLY);
        for(var r:List.of(new Role("leader","Leader",0,leader),new Role("officer","Officer",10,officer),new Role("architect","Architect",20,architect),new Role("quartermaster","Quartermaster",30,quarter),new Role("member","Member",100,BASE)))
            tx.save("guild_role",roleKey(guild,r.id),0,fields("guild",guild,"id",r.id,"name",r.name,"rank",r.rank,"caps",String.join("\n",new TreeSet<>(r.capabilities))));
    }
    private static Guild guild(TransactionalStore.Row r) {return new Guild(UUID.fromString(r.key()),r.value("name"),UUID.fromString(r.value("leader")));}
    private static Membership membership(TransactionalStore.Row r) {return new Membership(UUID.fromString(r.key()),UUID.fromString(r.value("guild")),r.value("role"),Boolean.parseBoolean(r.value("active")),r.revision());}
    private static DepartureJob job(TransactionalStore.Row r) {return new DepartureJob(r.key(),UUID.fromString(r.value("player")),UUID.fromString(r.value("guild")),Instant.parse(r.value("due")),r.value("state"),r.value("worker"),r.revision());}
}
