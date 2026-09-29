package com.hexvane.eterniamod.premium;

import com.hexvane.eterniamod.domain.*;
import java.util.*;

/** Detached catalog data. Loading and checkout are called only by the service-menu worker. */
public record PremiumShopSnapshot(PremiumService.Balance balance,List<PremiumService.Item> items,Set<String> owned) {
    public PremiumShopSnapshot {items=List.copyOf(items);owned=Set.copyOf(owned);}
    public static PremiumShopSnapshot empty(){return new PremiumShopSnapshot(new PremiumService.Balance(0,0),List.of(),Set.of());}
    public static PremiumShopSnapshot load(EterniaServices services,UUID actor){
        var premium=services.premium();var items=premium.items();var owned=new HashSet<String>();
        for(var item:items)if(premium.alreadyOwned(actor,item))owned.add(item.id());
        return new PremiumShopSnapshot(premium.balance(actor),items,owned);
    }
    public record Checkout(PremiumService.Order order,PremiumShopSnapshot snapshot) {}
    /** A refresh failure after a committed order is safe to retry with this exact request id. */
    public static Checkout checkout(EterniaServices services,UUID actor,PremiumService.Item selected,String requestId){
        var order=services.premium().purchase(actor,selected.id(),selected.revision(),selected.price(),requestId);
        return new Checkout(order,load(services,actor));
    }
}
