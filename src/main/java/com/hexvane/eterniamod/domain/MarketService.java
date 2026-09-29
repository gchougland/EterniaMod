package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

public final class MarketService extends DomainSupport {
    public record Listing(String id,UUID seller,UUID propertyId,String itemId,long unitPrice,long stock,String state,long revision) {}
    public record Purchase(String id,String listingId,UUID buyer,long quantity,long total,String deliveryId) {}
    private final EconomyService economy;private final EscrowService escrow;
    MarketService(TransactionalStore s,Clock c,Supplier<UUID> i,EconomyService economy,EscrowService escrow){super(s,c,i);this.economy=economy;this.escrow=escrow;}
    public Listing list(UUID seller,String escrowId,long unitPrice,String receiptId){
        positive(unitPrice);
        return store.transaction(tx->{String id=key(receiptId);if(!receipt(tx,"listing_receipt",receiptId,fields("seller",seller,"escrow",escrowId,"price",unitPrice)))return listing(row(tx,"listing",id));
            var home=activeHome(tx,seller);var stock=escrow.holdIn(tx,escrowId,Owner.player(seller),"listing:"+id);require(Boolean.parseBoolean(stock.value("transferable")),FORBIDDEN,"Bound items cannot be listed");
            return listing(tx.save("listing",id,0,fields("seller",seller,"property",home.value("property"),"escrow",escrowId,"item",stock.value("item"),"unitPrice",unitPrice,"stock",stock.number("remaining"),"state","ACTIVE")));});
    }
    public List<Listing> search(String itemQuery){
        String query=itemQuery==null?"":itemQuery.toLowerCase(Locale.ROOT);
        return store.transaction(tx->tx.scan("listing").stream().filter(r->r.value("state").equals("ACTIVE")&&r.value("item").toLowerCase(Locale.ROOT).contains(query)).map(MarketService::listing).toList());
    }
    public Purchase buy(UUID buyer,String listingId,long quantity,long quotedRevision,String receiptId){
        positive(quantity);
        return store.transaction(tx->{var input=fields("buyer",buyer,"listing",listingId,"quantity",quantity,"quote",quotedRevision);String id=key(receiptId);
            if(!receipt(tx,"purchase_receipt",receiptId,input))return purchase(row(tx,"purchase",id));
            row(tx,"account",buyer.toString());var offer=row(tx,"listing",listingId);require(offer.revision()==quotedRevision,CONFLICT,"Listing changed; request a fresh quote");
            require(offer.value("state").equals("ACTIVE"),INVALID_STATE,"Listing is unavailable");UUID seller=UUID.fromString(offer.value("seller"));require(!seller.equals(buyer),INVALID_INPUT,"Cannot buy your own listing");
            var home=activeHome(tx,seller);require(home.value("property").equals(offer.value("property")),INVALID_STATE,"Shop moved; listing must be refreshed");
            require(quantity<=offer.number("stock"),INSUFFICIENT_BALANCE,"Not enough listing stock");long total=Math.multiplyExact(quantity,offer.number("unitPrice"));
            economy.transferIn(tx,Owner.player(buyer),Owner.player(seller),total,"market:"+id);
            var delivery=escrow.deliverHeldIn(tx,offer.value("escrow"),"listing:"+listingId,Owner.player(buyer),quantity,"market-delivery:"+id);
            long stock=offer.number("stock")-quantity;save(tx,offer,"stock",stock,"state",stock==0?"SOLD":"ACTIVE");
            return purchase(tx.save("purchase",id,0,fields("buyer",buyer,"listing",listingId,"quantity",quantity,"total",total,"delivery",delivery.id())));});
    }
    public void cancel(UUID seller,String listingId){
        store.transaction(tx->{var r=row(tx,"listing",listingId);require(r.value("seller").equals(seller.toString()),FORBIDDEN,"Not your listing");if(r.value("state").equals("CANCELLED"))return null;
            require(r.value("state").equals("ACTIVE"),INVALID_STATE,"Listing is already completed");var stock=row(tx,"escrow",r.value("escrow"));
            escrow.deliverHeldIn(tx,r.value("escrow"),"listing:"+listingId,Owner.player(seller),stock.number("remaining"),"listing-return:"+listingId);save(tx,r,"state","CANCELLED","stock",0);return null;});
    }
    private static TransactionalStore.Row activeHome(TransactionalStore.Transaction tx,UUID seller){var r=row(tx,"housing_slot",Owner.player(seller).key());require(r.value("state").equals("ACTIVE")&&r.value("buildingPresent").equals("true"),INVALID_STATE,"Seller needs an active furnished house");return r;}
    private static Listing listing(TransactionalStore.Row r){return new Listing(r.key(),UUID.fromString(r.value("seller")),UUID.fromString(r.value("property")),r.value("item"),r.number("unitPrice"),r.number("stock"),r.value("state"),r.revision());}
    private static Purchase purchase(TransactionalStore.Row r){return new Purchase(r.key(),r.value("listing"),UUID.fromString(r.value("buyer")),r.number("quantity"),r.number("total"),r.value("delivery"));}
}
