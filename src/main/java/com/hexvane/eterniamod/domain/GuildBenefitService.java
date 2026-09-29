package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** A purchaser explicitly donates a verified permanent voucher to a named guild.
 * The source grant remains attached to the benefit so refunds revoke both in one transaction. */
public final class GuildBenefitService extends DomainSupport {
    public record Benefit(String voucherId,String name,String entitlementId) {}
    public record Voucher(String grantId,Benefit benefit,long available) {}
    public record Redemption(UUID actor,UUID guild,String sourceGrantId,String benefitId,String grantReceipt,boolean active) {}
    public static final List<Benefit> BENEFITS=List.of(
        new Benefit("eternia:guild_voucher/large_plot","Larger guild estate","eternia:plot/guild_64"),
        new Benefit("eternia:guild_voucher/teleporter","Guild teleporter","eternia:convenience/teleporter"));
    private final OwnershipService ownership;
    GuildBenefitService(TransactionalStore store,Clock clock,Supplier<UUID> ids,OwnershipService ownership){super(store,clock,ids);this.ownership=ownership;}

    public List<Voucher> available(UUID actor){
        return store.transaction(tx->{
            var result=new ArrayList<Voucher>();
            for(var grant:tx.scan("grant"))if(grant.value("owner").equals(Owner.player(actor).key())&&usable(tx,grant))
                result.add(new Voucher(grant.key(),benefit(grant.value("content")),grant.number("available")));
            return List.copyOf(result);
        });
    }

    public Redemption redeem(UUID actor,UUID confirmedGuild,String sourceGrantId,String receiptId){
        Objects.requireNonNull(actor);Objects.requireNonNull(confirmedGuild);text(sourceGrantId,"source grant",100);text(receiptId,"redemption receipt",240);
        return store.transaction(tx->{
            String id=key(receiptId);var input=fields("actor",actor,"guild",confirmedGuild,"source",sourceGrantId);
            var previous=tx.find("guild_redemption",id);
            if(previous.isPresent()){
                for(var e:input.entrySet())require(previous.get().value(e.getKey()).equals(e.getValue()),CONFLICT,"Redemption receipt was reused with different input");
                return redemption(tx,previous.get());
            }
            GuildService.requireCapability(tx,actor,confirmedGuild,"commerce.purchase_for_guild");
            var source=row(tx,"grant",sourceGrantId);
            require(source.value("owner").equals(Owner.player(actor).key()),FORBIDDEN,"This voucher belongs to another player");
            require(usable(tx,source),INVALID_STATE,"This voucher has no available verified purchase unit");
            Benefit benefit=benefit(source.value("content"));
            require(!ownership.ownsIn(tx,Owner.guild(confirmedGuild),benefit.entitlementId),CONFLICT,"This guild already has that benefit");
            save(tx,source,"available",source.number("available")-1);
            String derivedReceipt="guild-redemption:"+id;
            var derived=ownership.grantIn(tx,new OwnershipService.GrantRequest(derivedReceipt,Owner.guild(confirmedGuild),benefit.entitlementId,OwnershipService.Kind.CAPABILITY,1,null));
            tx.save("grant_derivation",derived.id(),0,fields("parent",source.key(),"child",derived.id()));
            input.putAll(fields("benefit",benefit.entitlementId,"grantReceipt",derivedReceipt));
            return redemption(tx,tx.save("guild_redemption",id,0,input));
        });
    }

    private boolean usable(TransactionalStore.Transaction tx,TransactionalStore.Row grant){
        if(benefit(grant.value("content"))==null||!grant.value("kind").equals("QUANTITY")||grant.number("available")<1||grant.value("revoked").equals("true")||!grant.value("until").isEmpty())return false;
        String receipt=grant.value("receipt");
        if(tx.scan("premium_order").stream().anyMatch(p->p.value("state").equals("DELIVERED")&&Owner.player(UUID.fromString(p.value("player"))).key().equals(grant.value("owner"))&&Arrays.asList(p.value("grants").split("\n")).contains(receipt)))return true;
        return tx.scan("commerce_purchase").stream().anyMatch(p->p.value("provider").equals("tebex")&&p.value("state").equals("DELIVERED")&&p.value("subscription").isEmpty()
            &&p.value("recipient").equals(grant.value("owner"))&&Arrays.asList(p.value("grants").split("\n")).contains(receipt));
    }
    private static Benefit benefit(String id){return BENEFITS.stream().filter(b->b.voucherId.equals(id)).findFirst().orElse(null);}
    private Redemption redemption(TransactionalStore.Transaction tx,TransactionalStore.Row row){
        var derived=row(tx,"grant",key(row.value("grantReceipt")));
        boolean active=!derived.value("revoked").equals("true")&&future(derived.value("until"),clock.instant());
        return new Redemption(UUID.fromString(row.value("actor")),UUID.fromString(row.value("guild")),row.value("source"),row.value("benefit"),row.value("grantReceipt"),active);
    }
}
