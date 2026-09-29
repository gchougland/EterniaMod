package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Durable join between console execution and signature-verified provider lines. Neither half grants alone. */
public final class CommerceIngressService extends DomainSupport {
    private final CommerceService commerce;
    CommerceIngressService(TransactionalStore s,Clock c,Supplier<UUID> i,CommerceService commerce){super(s,c,i);this.commerce=commerce;}
    public void expectConsole(String transaction,String packageId,UUID recipient,long quantity,int productRevision){
        text(transaction,"transaction",160);text(packageId,"package",100);positive(quantity);require(quantity<=1000&&productRevision>0,INVALID_INPUT,"Invalid mapped purchase");
        var data=fields("transaction",transaction,"package",packageId,"recipient",Owner.player(recipient).key(),"quantity",quantity,"revision",productRevision);
        store.transaction(tx->{receipt(tx,"tebex_console",key(transaction,packageId),data);return null;});
    }
    public void stageVerifiedLine(CommerceService.VerifiedPurchase purchase){
        stageVerifiedLines(List.of(purchase));
    }
    public void stageVerifiedLines(List<CommerceService.VerifiedPurchase> purchases){
        purchases=List.copyOf(purchases);require(purchases.size()<=100,CAPACITY,"Too many payment lines");var staged=new LinkedHashMap<String,Map<String,String>>();
        for(var purchase:purchases){require(purchase.provider().equals("tebex"),INVALID_INPUT,"Wrong provider");String id=key(purchase.transactionId(),purchase.packageId());
            require(!staged.containsKey(id),CONFLICT,"Duplicate package lines require review");
            staged.put(id,fields("transaction",purchase.transactionId(),"line",purchase.lineId(),"package",purchase.packageId(),"recipient",purchase.recipient().key(),"quantity",purchase.quantity(),"revision",purchase.productRevision(),"subscription",purchase.subscriptionId(),"start",purchase.periodStart(),"end",purchase.paidUntil()));}
        store.transaction(tx->{staged.forEach((id,data)->receipt(tx,"tebex_verified",id,data));return null;});
    }
    /** May be retried after a crash between grant commit and queue acknowledgement. */
    public int reconcile(int limit){
        require(limit>0&&limit<=100,CAPACITY,"Reconciliation batch must be 1..100");
        var candidates=store.transaction(tx->tx.scan("tebex_verified").stream().filter(r->tx.find("tebex_done",r.key()).isEmpty()&&tx.find("tebex_review",r.key()).isEmpty()&&
            (tx.find("tebex_reversal",r.value("transaction")).isPresent()||tx.find("tebex_console",r.key()).isPresent()&&recipientExists(tx,r)&&tx.find("commerce_product",r.value("package")+":"+r.value("revision")).isPresent())).limit(limit).toList());int delivered=0;
        for(var verified:candidates){
            var decision=store.transaction(tx->{if(tx.find("tebex_reversal",verified.value("transaction")).isPresent())return "REVERSED";
                if(tx.find("tebex_review",verified.key()).isPresent())return "REVIEW";
                var expected=tx.find("tebex_console",verified.key());if(expected.isEmpty())return "WAITING";
                for(String field:List.of("transaction","package","recipient","quantity","revision"))if(!expected.get().value(field).equals(verified.value(field))){tx.save("tebex_review",verified.key(),0,fields("transaction",verified.value("transaction"),"package",verified.value("package"),"reason","CONSOLE_MISMATCH"));return "REVIEW";}return "READY";});
            if(decision.equals("WAITING"))continue;
            if(decision.equals("REVIEW"))continue;
            if(decision.equals("REVERSED")){var reversal=store.transaction(tx->row(tx,"tebex_reversal",verified.value("transaction")));commerce.reverseTebexTransaction(verified.value("transaction"),reversal.value("reason"));}
            if(decision.equals("READY")){
                var result=commerce.fulfill(new CommerceService.VerifiedPurchase("tebex",verified.value("transaction"),verified.value("line"),verified.value("package"),Integer.parseInt(verified.value("revision")),Owner.parse(verified.value("recipient")),verified.number("quantity"),verified.value("subscription"),instant(verified.value("start")),instant(verified.value("end"))));
                if(result.state().equals("PENDING"))continue;
                // A reversal arriving during fulfillment is replayed after fulfillment, never forgotten.
                var reversal=store.transaction(tx->tx.find("tebex_reversal",verified.value("transaction")));
                if(reversal.isPresent())commerce.reverse(result.id(),"tebex-reversal:"+result.id(),reversal.get().value("reason"));
                delivered++;
            }
            store.transaction(tx->{if(tx.find("tebex_done",verified.key()).isEmpty())tx.save("tebex_done",verified.key(),0,fields("state",decision));return null;});
        }reconcileControls(limit);return delivered;
    }
    public record Review(String transaction,String packageId,String reason){}
    public List<Review> reviews(){return store.transaction(tx->tx.scan("tebex_review").stream().map(r->new Review(r.value("transaction"),r.value("package"),r.value("reason"))).toList());}
    private static boolean recipientExists(TransactionalStore.Transaction tx,TransactionalStore.Row line){Owner owner=Owner.parse(line.value("recipient"));return owner.kind()==Owner.Kind.PLAYER?tx.find("account",owner.id().toString()).isPresent():owner.kind()==Owner.Kind.GUILD&&tx.find("guild",owner.id().toString()).isPresent();}
    public void stageSubscriptionControl(String reference,String state,Instant at,String eventId){
        text(reference,"subscription reference",160);text(eventId,"provider event",160);Objects.requireNonNull(at);require(Set.of("CANCEL_REQUESTED","CANCEL_ABORTED","ENDED").contains(state),INVALID_INPUT,"Invalid subscription control");
        store.transaction(tx->{String id=key(reference);var previous=tx.find("tebex_sub_control",id);if(previous.isPresent()&&Instant.parse(previous.get().value("at")).isAfter(at))return null;
            if(state.equals("ENDED")){String sid=key("tebex",reference);var ended=tx.find("subscription_end",sid);if(ended.isEmpty()||Instant.parse(ended.get().value("at")).isBefore(at))tx.save("subscription_end",sid,ended.map(TransactionalStore.Row::revision).orElse(0L),fields("at",at));}
            tx.save("tebex_sub_control",id,previous.map(TransactionalStore.Row::revision).orElse(0L),fields("reference",reference,"state",state,"at",at,"event",eventId));return null;});
    }
    private void reconcileControls(int limit){
        var controls=store.transaction(tx->tx.scan("tebex_sub_control").stream().filter(r->tx.find("tebex_control_done",key(r.value("event"))).isEmpty()&&tx.find("subscription",key("tebex",r.value("reference"))).isPresent()).limit(limit).toList());
        for(var control:controls){commerce.applySubscriptionControl("tebex",control.value("reference"),control.value("state"),Instant.parse(control.value("at")),"tebex:"+control.value("event"));
            store.transaction(tx->{String id=key(control.value("event"));if(tx.find("tebex_control_done",id).isEmpty())tx.save("tebex_control_done",id,0,fields("state","APPLIED"));return null;});}
    }
    public void reverseTransaction(String transaction,String reason){
        commerce.reverseTebexTransaction(transaction,reason);
    }
    private static Instant instant(String value){return value.isEmpty()?null:Instant.parse(value);}
}
