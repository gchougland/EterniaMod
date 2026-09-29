package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Provider signature/recipient verification belongs to an adapter. Only verified immutable purchase
 * lines enter this service. Browser checkout redirects cannot call it directly. */
public final class CommerceService extends DomainSupport {
    public record Benefit(String contentId,OwnershipService.Kind kind,long quantity,boolean expiresWithSubscription){
        public Benefit{content(contentId);Objects.requireNonNull(kind);positive(quantity);require(kind==OwnershipService.Kind.QUANTITY||quantity==1,INVALID_INPUT,"Unlock quantity must be one");}}
    public record Product(String packageId,int revision,String name,List<Benefit> benefits,boolean subscription){
        public Product{text(packageId,"provider package id",100);require(revision>0,INVALID_INPUT,"Invalid product revision");text(name,"product name",100);benefits=List.copyOf(benefits);require(!benefits.isEmpty()&&benefits.size()<=100,INVALID_INPUT,"Invalid benefit count");require(subscription||benefits.stream().noneMatch(Benefit::expiresWithSubscription),INVALID_INPUT,"Only subscriptions have leased benefits");}}
    public record VerifiedPurchase(String provider,String transactionId,String lineId,String packageId,int productRevision,Owner recipient,long quantity,String subscriptionId,Instant periodStart,Instant paidUntil){
        public VerifiedPurchase{text(provider,"provider",40);text(transactionId,"transaction",160);text(lineId,"line",160);text(packageId,"package",100);Objects.requireNonNull(recipient);positive(quantity);require(quantity<=1000,INVALID_INPUT,"Purchase quantity too large");subscriptionId=subscriptionId==null?"":subscriptionId;}}
    public record Purchase(String id,String transactionId,String lineId,String packageId,Owner recipient,long quantity,String state,String reason,Instant paidUntil){}
    public record Subscription(String id,Owner recipient,Instant paidUntil,String state,boolean cancellationRequested){}
    private final OwnershipService ownership;
    private final PremiumService premium;
    CommerceService(TransactionalStore s,Clock c,Supplier<UUID> i,OwnershipService ownership,PremiumService premium){super(s,c,i);this.ownership=ownership;this.premium=premium;}
    public void register(Product product){store.transaction(tx->{var data=encode(product);String id=product.packageId+":"+product.revision;var old=tx.find("commerce_product",id);if(old.isPresent())require(old.get().fields().equals(data),CONFLICT,"Product revision is immutable");else tx.save("commerce_product",id,0,data);return null;});}
    public List<Product> products(){return store.transaction(tx->tx.scan("commerce_product").stream().map(CommerceService::product).toList());}
    public Purchase fulfill(VerifiedPurchase request){
        return store.transaction(tx->{String id=key(request.provider,request.transactionId,request.lineId);var input=purchaseInput(request);
            var old=tx.find("commerce_purchase",id);if(old.isPresent())for(var e:input.entrySet())require(old.get().value(e.getKey()).equals(e.getValue()),CONFLICT,"Purchase identity reused with different input");
            var purchase=old.orElseGet(()->{var data=new TreeMap<>(input);data.putAll(fields("state","PENDING","reason","","grants",""));return tx.save("commerce_purchase",id,0,data);});
            var reversal=request.provider.equals("tebex")?tx.find("tebex_reversal",request.transactionId):Optional.<TransactionalStore.Row>empty();
            if(reversal.isPresent())return purchase(reverseIn(tx,purchase,reversal.get().value("reason")));
            if(!purchase.value("state").equals("PENDING"))return purchase(purchase);
            var definition=tx.find("commerce_product",request.packageId+":"+request.productRevision);
            if(definition.isEmpty())return purchase(save(tx,purchase,"reason","UNKNOWN_PRODUCT"));
            if(request.recipient.kind()==Owner.Kind.PLAYER&&tx.find("account",request.recipient.id().toString()).isEmpty()||request.recipient.kind()==Owner.Kind.GUILD&&tx.find("guild",request.recipient.id().toString()).isEmpty())return purchase(save(tx,purchase,"reason","RECIPIENT_REQUIRES_SUPPORT"));
            require(request.recipient.kind()!=Owner.Kind.SERVER,INVALID_INPUT,"Store products require player or guild recipient");
            Product product=product(definition.get());
            if(product.benefits.stream().anyMatch(b->b.contentId.equals(PremiumService.CROWNS)))require(request.recipient.kind()==Owner.Kind.PLAYER&&product.benefits.stream().filter(b->b.contentId.equals(PremiumService.CROWNS)).allMatch(b->b.kind==OwnershipService.Kind.QUANTITY&&!b.expiresWithSubscription),INVALID_INPUT,"Crowns are permanent player currency");
            if(product.subscription){require(!request.subscriptionId.isBlank()&&request.periodStart!=null&&request.paidUntil!=null&&request.paidUntil.isAfter(request.periodStart),INVALID_INPUT,"Subscription requires paid period");}
            else require(request.subscriptionId.isEmpty(),INVALID_INPUT,"One-off product has no subscription");
            String periodKey=product.subscription?key(request.provider,request.subscriptionId,request.periodStart.toString(),request.packageId):"";
            if(product.subscription){var period=tx.find("subscription_period",periodKey);if(period.isPresent()){require(period.get().value("recipient").equals(request.recipient.key())&&period.get().value("end").equals(request.paidUntil.toString()),CONFLICT,"Billing period identity changed");return purchase(save(tx,purchase,"state","DUPLICATE_PERIOD","reason","Billing period already granted"));}
                tx.save("subscription_period",periodKey,0,fields("recipient",request.recipient.key(),"purchase",id,"start",request.periodStart,"end",request.paidUntil));}
            var grants=new ArrayList<String>();
            Instant providerEndedAt=product.subscription?tx.find("subscription_end",key(request.provider,request.subscriptionId)).map(r->Instant.parse(r.value("at"))).orElse(null):null;
            boolean endedPeriod=providerEndedAt!=null&&!request.periodStart.isAfter(providerEndedAt);
            for(int unit=0;unit<request.quantity;unit++)for(int n=0;n<product.benefits.size();n++){
                Benefit benefit=product.benefits.get(n);String receipt="commerce:"+id+":"+unit+":"+n;
                Instant validUntil=benefit.expiresWithSubscription?request.paidUntil:null;
                if(benefit.expiresWithSubscription&&endedPeriod&&providerEndedAt.isBefore(validUntil))validUntil=providerEndedAt;
                if(benefit.contentId.equals(PremiumService.CROWNS))premium.creditIn(tx,request.recipient.id(),benefit.quantity,receipt);
                else ownership.grantIn(tx,new OwnershipService.GrantRequest(receipt,request.recipient,benefit.contentId,benefit.kind,benefit.quantity,validUntil));
                grants.add(receipt);
            }
            if(product.subscription){
                String sid=key(request.provider,request.subscriptionId);var prior=tx.find("subscription",sid);
                if(prior.isPresent())require(prior.get().value("recipient").equals(request.recipient.key()),CONFLICT,"Subscription recipient changed");
                Instant end=prior.map(r->Instant.parse(r.value("paidUntil"))).filter(t->t.isAfter(request.paidUntil)).orElse(request.paidUntil);
                Instant lastStart=prior.filter(r->!r.value("lastPeriodStart").isEmpty()).map(r->Instant.parse(r.value("lastPeriodStart"))).filter(t->t.isAfter(request.periodStart)).orElse(request.periodStart);
                String subscriptionState=providerEndedAt!=null&&!lastStart.isAfter(providerEndedAt)?"ENDED":"ACTIVE";
                boolean keepCancellation=prior.filter(r->r.value("cancelRequested").equals("true")&&(r.value("controlAt").isEmpty()||!request.periodStart.isAfter(Instant.parse(r.value("controlAt"))))).isPresent();
                var subscriptionData=prior.map(r->changed(r,"recipient",request.recipient.key(),"paidUntil",end,"lastPeriodStart",lastStart,"state",subscriptionState,"cancelRequested",keepCancellation)).orElseGet(()->fields("recipient",request.recipient.key(),"paidUntil",end,"lastPeriodStart",lastStart,"state",subscriptionState,"cancelRequested",false));
                tx.save("subscription",sid,prior.map(TransactionalStore.Row::revision).orElse(0L),subscriptionData);
            }
            return purchase(save(tx,purchase,"state","DELIVERED","reason","","grants",String.join("\n",grants)));});
    }
    public void requestCancellation(String provider,String subscriptionId,String eventReceipt){
        store.transaction(tx->{String id=key(provider,subscriptionId);if(!receipt(tx,"commerce_event",eventReceipt,fields("type","CANCEL_REQUESTED","subscription",id)))return null;var r=row(tx,"subscription",id);save(tx,r,"cancelRequested",true,"controlAt",clock.instant());return null;});
    }
    /** Verified provider state; a newer billing period is not ended by an older delayed callback. */
    public void applySubscriptionControl(String provider,String subscriptionId,String state,Instant occurredAt,String eventReceipt){
        require(Set.of("CANCEL_REQUESTED","CANCEL_ABORTED","ENDED").contains(state),INVALID_INPUT,"Unknown subscription control");Objects.requireNonNull(occurredAt);
        store.transaction(tx->{String sid=key(provider,subscriptionId);var subscription=row(tx,"subscription",sid);
            if(!subscription.value("controlAt").isEmpty()&&Instant.parse(subscription.value("controlAt")).isAfter(occurredAt))return null;
            if(!receipt(tx,"commerce_control",eventReceipt,fields("subscription",sid,"state",state,"at",occurredAt)))return null;
            if(state.equals("ENDED")){
                var oldEnd=tx.find("subscription_end",sid);if(oldEnd.isEmpty()||Instant.parse(oldEnd.get().value("at")).isBefore(occurredAt))tx.save("subscription_end",sid,oldEnd.map(TransactionalStore.Row::revision).orElse(0L),fields("at",occurredAt));
                for(var purchase:tx.scan("commerce_purchase"))if(purchase.value("provider").equals(provider)&&purchase.value("subscription").equals(subscriptionId)&&purchase.value("state").equals("DELIVERED")&&!Instant.parse(purchase.value("periodStart")).isAfter(occurredAt)){
                    Product product=product(row(tx,"commerce_product",purchase.value("package")+":"+purchase.value("productRevision")));
                    for(int unit=0;unit<purchase.number("quantity");unit++)for(int n=0;n<product.benefits.size();n++)if(product.benefits.get(n).expiresWithSubscription)ownership.revokeIn(tx,"commerce:"+purchase.key()+":"+unit+":"+n);
                }
                if(subscription.value("lastPeriodStart").isEmpty()||!Instant.parse(subscription.value("lastPeriodStart")).isAfter(occurredAt))subscription=save(tx,subscription,"state","ENDED");
            }
            save(tx,subscription,"cancelRequested",state.equals("CANCEL_REQUESTED"),"controlAt",occurredAt);return null;});
    }
    /** Time-bound grants already enforce paidUntil. This records lifecycle without deleting permanent gifts. */
    public int expireSubscriptions(){return store.transaction(tx->{int count=0;for(var r:tx.scan("subscription"))if(r.value("state").equals("ACTIVE")&&!future(r.value("paidUntil"),clock.instant())){save(tx,r,"state","ENDED");count++;}return count;});}
    public Optional<Subscription> subscription(String provider,String subscriptionId){return store.transaction(tx->tx.find("subscription",key(provider,subscriptionId)).map(CommerceService::subscription));}
    public Purchase reverse(String purchaseId,String eventReceipt,String reason){
        text(reason,"reversal reason",500);
        return store.transaction(tx->{var r=row(tx,"commerce_purchase",purchaseId);if(!receipt(tx,"commerce_event",eventReceipt,fields("type","REVERSE","purchase",purchaseId,"reason",reason)))return purchase(row(tx,"commerce_purchase",purchaseId));
            return purchase(reverseIn(tx,r,reason));});
    }
    /** Tombstone and all existing lines commit atomically; fulfillment checks the same tombstone under the same lock. */
    void reverseTebexTransaction(String transaction,String reason){
        text(transaction,"transaction",160);text(reason,"reason",100);
        store.transaction(tx->{var previous=tx.find("tebex_reversal",transaction);String recordedReason=previous.map(r->r.value("reason")).orElse(reason);
            if(previous.isEmpty())tx.save("tebex_reversal",transaction,0,fields("reason",recordedReason));
            for(var purchase:tx.scan("commerce_purchase"))if(purchase.value("provider").equals("tebex")&&purchase.value("transaction").equals(transaction))reverseIn(tx,purchase,recordedReason);return null;});
    }
    private TransactionalStore.Row reverseIn(TransactionalStore.Transaction tx,TransactionalStore.Row purchase,String reason){
        if(purchase.value("state").equals("REVERSED"))return purchase;
        if(!purchase.value("grants").isEmpty())for(String receipt:purchase.value("grants").split("\n")){
            if(tx.find("premium_credit_state",key(receipt)).isPresent())premium.reverseCreditIn(tx,receipt);
            else ownership.revokeIn(tx,receipt);
        }
        return save(tx,purchase,"state","REVERSED","reason",reason);
    }
    public List<Purchase> purchases(Owner recipient){return store.transaction(tx->tx.scan("commerce_purchase").stream().filter(r->r.value("recipient").equals(recipient.key())).map(CommerceService::purchase).toList());}
    public List<Purchase> pending(){return store.transaction(tx->tx.scan("commerce_purchase").stream().filter(r->r.value("state").equals("PENDING")).map(CommerceService::purchase).toList());}
    public List<Purchase> transactionPurchases(String provider,String transaction){return store.transaction(tx->tx.scan("commerce_purchase").stream().filter(r->r.value("provider").equals(provider)&&r.value("transaction").equals(transaction)).map(CommerceService::purchase).toList());}
    private static Map<String,String> purchaseInput(VerifiedPurchase p){return fields("provider",p.provider,"transaction",p.transactionId,"line",p.lineId,"package",p.packageId,"productRevision",p.productRevision,"recipient",p.recipient.key(),"quantity",p.quantity,"subscription",p.subscriptionId,"periodStart",p.periodStart,"paidUntil",p.paidUntil);}
    private static Map<String,String> encode(Product p){var data=fields("package",p.packageId,"revision",p.revision,"name",p.name,"subscription",p.subscription,"count",p.benefits.size());for(int i=0;i<p.benefits.size();i++){var b=p.benefits.get(i);data.putAll(fields("b"+i+".content",b.contentId,"b"+i+".kind",b.kind,"b"+i+".quantity",b.quantity,"b"+i+".expires",b.expiresWithSubscription));}return data;}
    private static Product product(TransactionalStore.Row r){var benefits=new ArrayList<Benefit>();for(int i=0;i<r.number("count");i++)benefits.add(new Benefit(r.value("b"+i+".content"),OwnershipService.Kind.valueOf(r.value("b"+i+".kind")),r.number("b"+i+".quantity"),Boolean.parseBoolean(r.value("b"+i+".expires"))));return new Product(r.value("package"),Integer.parseInt(r.value("revision")),r.value("name"),benefits,Boolean.parseBoolean(r.value("subscription")));}
    private static Purchase purchase(TransactionalStore.Row r){return new Purchase(r.key(),r.value("transaction"),r.value("line"),r.value("package"),Owner.parse(r.value("recipient")),r.number("quantity"),r.value("state"),r.value("reason"),r.value("paidUntil").isEmpty()?null:Instant.parse(r.value("paidUntil")));}
    private static Subscription subscription(TransactionalStore.Row r){return new Subscription(r.key(),Owner.parse(r.value("recipient")),Instant.parse(r.value("paidUntil")),r.value("state"),Boolean.parseBoolean(r.value("cancelRequested")));}
}
