package com.hexvane.eterniamod.premium;

import com.hexvane.eterniamod.domain.*;
import java.time.Instant;
import java.util.*;

/** Detached catalog data. Loading and checkout are called only by the service-menu worker.
 * {@code sales} holds the sale status of season items (keyed by item ID) as of {@code at}. */
public record PremiumShopSnapshot(PremiumService.Balance balance,List<PremiumService.Item> items,Set<String> owned,Map<String,SeasonService.SaleStatus> sales,Instant at) {
    public PremiumShopSnapshot {owned=Set.copyOf(owned);sales=Map.copyOf(sales);at=at==null?Instant.now():at;items=ordered(items,owned,sales);}
    public PremiumShopSnapshot(PremiumService.Balance balance,List<PremiumService.Item> items,Set<String> owned){this(balance,items,owned,Map.of(),null);}
    private record CacheKey(UUID actor) {}
    public static PremiumShopSnapshot empty(){return new PremiumShopSnapshot(new PremiumService.Balance(0,0),List.of(),Set.of());}
    public static PremiumShopSnapshot load(EterniaServices services,UUID actor){
        return services.readView(new CacheKey(actor),java.time.Duration.ofSeconds(2),() -> loadInBatch(services,actor));
    }
    private static PremiumShopSnapshot loadInBatch(EterniaServices services,UUID actor){
        var premium=services.premium();var items=premium.items();var owned=new HashSet<String>();
        for(var item:items)if(premium.alreadyOwned(actor,item))owned.add(item.id());
        Instant now=services.seasons().now();
        return new PremiumShopSnapshot(premium.balance(actor),items,owned,premium.sales(items,now),now);
    }
    /** Sale status of a season item, or null for an ordinary item. */
    public SeasonService.SaleStatus sale(PremiumService.Item item){return sales.get(item.id());}
    /** True when a player who does not own the item could buy it now. */
    public boolean onSale(PremiumService.Item item){var sale=sale(item);return sale==null||sale.purchasable();}
    /** Items a player cannot buy any more sink to the end, so the store leads with what is on sale. Order is otherwise kept. */
    private static List<PremiumService.Item> ordered(List<PremiumService.Item> items,Set<String> owned,Map<String,SeasonService.SaleStatus> sales){
        var sorted=new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(item->!owned.contains(item.id())&&sales.containsKey(item.id())&&sales.get(item.id()).state()==SeasonService.SaleState.ENDED?1:0));
        return List.copyOf(sorted);
    }
    public record Checkout(PremiumService.Order order,PremiumShopSnapshot snapshot) {}
    /** A refresh failure after a committed order is safe to retry with this exact request id. */
    public static Checkout checkout(EterniaServices services,UUID actor,PremiumService.Item selected,String requestId){
        var order=services.premium().purchase(actor,selected.id(),selected.revision(),selected.price(),requestId);
        return new Checkout(order,load(services,actor));
    }
}
