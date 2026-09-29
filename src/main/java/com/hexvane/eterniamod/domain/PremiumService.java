package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

/** Non-transferable Crowns. Price, debit, receipt and delivery commit in one authority transaction. */
public final class PremiumService extends DomainSupport {
    public static final String CROWNS="eternia:currency/crowns";
    public record Balance(long available,long owed) {}
    public record Item(String id,int revision,String name,String description,String category,long price,
                       List<CommerceService.Benefit> benefits) {
        public Item {
            content(id);require(revision>0,INVALID_INPUT,"Invalid store revision");text(name,"item name",80);
            text(description,"description",240);text(category,"category",40);positive(price);
            benefits=List.copyOf(benefits);require(!benefits.isEmpty()&&benefits.size()<=20,INVALID_INPUT,"Invalid item benefits");
            require(benefits.stream().noneMatch(b->b.expiresWithSubscription()||b.contentId().equals(CROWNS)),INVALID_INPUT,"Crown items must grant permanent content, never currency");
        }
    }
    public record Order(String id,String itemId,String name,long crowns,String state) {}
    private final OwnershipService ownership;
    PremiumService(TransactionalStore s,Clock c,Supplier<UUID> i,OwnershipService ownership){super(s,c,i);this.ownership=ownership;}
    public Balance balance(UUID player){return store.transaction(tx->{long n=net(tx,player);return new Balance(Math.max(0,n),n<0?Math.negateExact(n):0);});}
    private long net(TransactionalStore.Transaction tx,UUID player){return tx.find("premium_wallet",player.toString()).map(r->r.number("balance")).orElse(0L);}
    private void adjust(TransactionalStore.Transaction tx,UUID player,long delta){var r=tx.find("premium_wallet",player.toString());tx.save("premium_wallet",player.toString(),r.map(TransactionalStore.Row::revision).orElse(0L),fields("balance",Math.addExact(net(tx,player),delta)));}
    void creditIn(TransactionalStore.Transaction tx,UUID player,long amount,String source){
        positive(amount);row(tx,"account",player.toString());
        if(!receipt(tx,"premium_credit",source,fields("player",player,"amount",amount)))return;
        adjust(tx,player,amount);tx.save("premium_credit_state",key(source),0,fields("player",player,"amount",amount,"reversed",false,"at",clock.instant()));
    }
    /** Only local setup adapters may call this; production credit enters through verified CommerceService. */
    public void creditLocalExample(UUID player,long amount,String source){require(source.startsWith("local-playground:"),INVALID_INPUT,"Local credit requires a playground receipt");store.transaction(tx->{creditIn(tx,player,amount,source);return null;});}
    void reverseCreditIn(TransactionalStore.Transaction tx,String source){
        var r=tx.find("premium_credit_state",key(source));if(r.isEmpty()||r.get().value("reversed").equals("true"))return;
        adjust(tx,UUID.fromString(r.get().value("player")),Math.negateExact(r.get().number("amount")));save(tx,r.get(),"reversed",true,"reversedAt",clock.instant());
    }
    public void register(Item item){store.transaction(tx->{var data=encode(item);String key=item.id()+":"+item.revision();var r=tx.find("premium_item",key);if(r.isPresent())require(r.get().fields().equals(data),CONFLICT,"Crown item revisions are immutable");else tx.save("premium_item",key,0,data);return null;});}
    public List<Item> items(){return store.transaction(tx->{var latest=new TreeMap<String,Item>();for(var r:tx.scan("premium_item")){Item i=decode(r);latest.merge(i.id(),i,(a,b)->a.revision()>b.revision()?a:b);}return List.copyOf(latest.values());});}
    public boolean alreadyOwned(UUID player,Item item){return store.transaction(tx->owned(tx,player,item));}
    private boolean owned(TransactionalStore.Transaction tx,UUID player,Item item){return item.benefits().stream().allMatch(b->b.kind()!=OwnershipService.Kind.QUANTITY&&ownership.ownsIn(tx,Owner.player(player),b.contentId()));}
    /** Expected price/revision comes from the server-rendered confirmation; never from a client price field. */
    public Order purchase(UUID player,String itemId,int revision,long expectedPrice,String requestId){
        Objects.requireNonNull(player);content(itemId);text(requestId,"purchase request",160);
        return store.transaction(tx->{row(tx,"account",player.toString());String orderId=key(player.toString(),requestId);
            var input=fields("player",player,"item",itemId,"revision",revision,"price",expectedPrice);
            var previous=tx.find("premium_order",orderId);if(previous.isPresent()){for(var e:input.entrySet())require(previous.get().value(e.getKey()).equals(e.getValue()),CONFLICT,"Purchase request was reused");return order(previous.get());}
            Item item=decode(row(tx,"premium_item",itemId+":"+revision));
            require(tx.scan("premium_item").stream().map(PremiumService::decode).noneMatch(i->i.id().equals(itemId)&&i.revision()>revision),CONFLICT,"This item has changed. Reopen the store to review its current price.");
            require(item.price()==expectedPrice,CONFLICT,"This price has changed. Reopen the store.");
            require(!owned(tx,player,item),INVALID_STATE,"You already own this item.");
            require(net(tx,player)>=item.price(),INSUFFICIENT_BALANCE,"You need more Crowns for this item.");
            adjust(tx,player,-item.price());int n=0;var grants=new ArrayList<String>();
            for(var b:item.benefits()){String grant="crown-order:"+orderId+":"+(n++);ownership.grantIn(tx,new OwnershipService.GrantRequest(grant,Owner.player(player),b.contentId(),b.kind(),b.quantity(),null));grants.add(grant);}
            input.putAll(fields("name",item.name(),"state","DELIVERED","at",clock.instant(),"grants",String.join("\n",grants)));return order(tx.save("premium_order",orderId,0,input));
        });
    }
    public List<Order> orders(UUID player){return store.transaction(tx->tx.scan("premium_order").stream().filter(r->r.value("player").equals(player.toString())).map(PremiumService::order).toList());}
    private static Order order(TransactionalStore.Row r){return new Order(r.key(),r.value("item"),r.value("name"),r.number("price"),r.value("state"));}
    private static Map<String,String> encode(Item i){var f=fields("id",i.id(),"revision",i.revision(),"name",i.name(),"description",i.description(),"category",i.category(),"price",i.price(),"count",i.benefits().size());for(int n=0;n<i.benefits().size();n++){var b=i.benefits().get(n);f.putAll(fields("b"+n+".id",b.contentId(),"b"+n+".kind",b.kind(),"b"+n+".quantity",b.quantity()));}return f;}
    private static Item decode(TransactionalStore.Row r){var bs=new ArrayList<CommerceService.Benefit>();for(int n=0;n<r.number("count");n++)bs.add(new CommerceService.Benefit(r.value("b"+n+".id"),OwnershipService.Kind.valueOf(r.value("b"+n+".kind")),r.number("b"+n+".quantity"),false));return new Item(r.value("id"),(int)r.number("revision"),r.value("name"),r.value("description"),r.value("category"),r.number("price"),bs);}
}
