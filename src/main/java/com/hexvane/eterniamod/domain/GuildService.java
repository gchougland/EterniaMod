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
    private static final Set<String> BASE=Set.of("guild.view","guild.chat","board.read","board.post","party.create","inventory.deposit","treasury.deposit","housing.door.use","housing.bed.use","housing.bench.use","convenience.use","housing.visit");
    GuildService(TransactionalStore s,Clock c,Supplier<UUID> i) { super(s,c,i); }
    public Guild create(UUID leader,String name,String receiptId) {
        text(name,"guild name",48);return store.transaction(tx->{
            row(tx,"account",leader.toString());String rkey=key(receiptId);var prior=tx.find("guild_create_receipt",rkey);
            if(prior.isPresent()) { require(prior.get().value("leader").equals(leader.toString())&&prior.get().value("name").equals(name),CONFLICT,"Guild receipt reused");return guild(row(tx,"guild",prior.get().value("guild"))); }
            require(tx.find("guild_member",leader.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"Already in a guild");
            require(tx.scan("guild").stream().noneMatch(r->r.value("name").equalsIgnoreCase(name)),CONFLICT,"Guild name is in use");
            UUID guildId=ids.get();var guild=tx.save("guild",guildId.toString(),0,fields("name",name,"leader",leader));
            tx.save("guild_create_receipt",rkey,0,fields("leader",leader,"name",name,"guild",guildId));seedRoles(tx,guildId);
            putMember(tx,leader,guildId,"leader");return guild(guild);
        });
    }
    public String invite(UUID actor,UUID target) {
        return store.transaction(tx->{var member=currentMember(tx,actor);requireCapability(tx,actor,UUID.fromString(member.value("guild")),"member.invite");row(tx,"account",target.toString());
            require(tx.find("guild_member",target.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"Player already belongs to a guild");
            String invite=ids.get().toString();tx.save("guild_invite",invite,0,fields("guild",member.value("guild"),"target",target,"expires",clock.instant().plus(Duration.ofDays(7)),"state","PENDING"));return invite;});
    }
    public Membership acceptInvite(UUID player,String inviteId) {
        return store.transaction(tx->{var invite=row(tx,"guild_invite",inviteId);require(invite.value("target").equals(player.toString()),FORBIDDEN,"Invite belongs to another player");
            if(invite.value("state").equals("ACCEPTED"))return membership(currentMember(tx,player));
            require(invite.value("state").equals("PENDING")&&future(invite.value("expires"),clock.instant()),INVALID_STATE,"Invite expired");
            require(tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).isEmpty(),CONFLICT,"Already in a guild");
            UUID guild=UUID.fromString(invite.value("guild"));row(tx,"guild",guild.toString());var result=putMember(tx,player,guild,"member");save(tx,invite,"state","ACCEPTED");
            for(var job:tx.scan("guild_departure"))if(job.value("player").equals(player.toString())&&job.value("guild").equals(guild.toString())&&!job.value("state").equals("COMPLETED")&&clock.instant().isBefore(Instant.parse(job.value("due"))))save(tx,job,"state","CANCELLED");
            return membership(result);});
    }
    public Optional<Membership> membership(UUID player) { return store.transaction(tx->tx.find("guild_member",player.toString()).filter(r->r.value("active").equals("true")).map(GuildService::membership)); }
    public List<Membership> roster(UUID actor) { return store.transaction(tx->{var me=currentMember(tx,actor);return tx.scan("guild_member").stream().filter(r->r.value("active").equals("true")&&r.value("guild").equals(me.value("guild"))).map(GuildService::membership).toList();}); }
    public List<Role> roles(UUID actor) { return store.transaction(tx->{var me=currentMember(tx,actor);UUID guild=UUID.fromString(me.value("guild"));return tx.scan("guild_role").stream().filter(r->r.value("guild").equals(guild.toString())).map(r->role(tx,guild,r.value("id"))).sorted(Comparator.comparingInt(Role::rank)).toList();}); }
    public Optional<Guild> find(UUID guildId) { return store.transaction(tx->tx.find("guild",guildId.toString()).map(GuildService::guild)); }
    public boolean can(UUID actor,UUID guildId,String capability) { return store.transaction(tx->{try{requireCapability(tx,actor,guildId,capability);return true;}catch(DomainException e){if(e.code()==FORBIDDEN||e.code()==NOT_FOUND)return false;throw e;}}); }
    public boolean eligibleForRoot(UUID actor) { return store.transaction(tx->{var me=currentMember(tx,actor);UUID guild=UUID.fromString(me.value("guild"));requireCapability(tx,actor,guild,"housing.claim");return tx.scan("guild_member").stream().filter(r->r.value("active").equals("true")&&r.value("guild").equals(guild.toString())).count()>=5;}); }
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
        var changed=save(tx,member,"active",false);var slot=tx.find("housing_slot",Owner.player(UUID.fromString(member.key())).key());
        if(slot.isPresent()&&slot.get().value("attachedGuild").equals(member.value("guild"))) {
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
    private static Role role(TransactionalStore.Transaction tx,UUID guild,String id) {var r=row(tx,"guild_role",roleKey(guild,id));return new Role(id,r.value("name"),Integer.parseInt(r.value("rank")),new HashSet<>(Arrays.asList(r.value("caps").split("\n"))));}
    private TransactionalStore.Row putMember(TransactionalStore.Transaction tx,UUID player,UUID guild,String role) {var old=tx.find("guild_member",player.toString());return tx.save("guild_member",player.toString(),old.map(TransactionalStore.Row::revision).orElse(0L),fields("guild",guild,"role",role,"active",true,"joinedAt",clock.instant()));}
    private void seedRoles(TransactionalStore.Transaction tx,UUID guild) {
        var officer=new HashSet<>(BASE);officer.addAll(Set.of("board.moderate","member.invite","member.remove","member.role.assign","audit.view"));
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
