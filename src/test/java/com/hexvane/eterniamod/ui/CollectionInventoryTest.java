package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.domain.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectionInventoryTest {
    @Test void packedAndPlacedDecorationsRemainVisibleWithoutAddingSpendableCopies(){
        var packed=new ProvenanceService.Instance(UUID.randomUUID(),owner,owner.id(),UUID.randomUUID(),"eternia:prop/aqua_lamp","source",Map.of(),"PACKED",1,"saved");
        var placed=new ProvenanceService.Instance(UUID.randomUUID(),owner,owner.id(),UUID.randomUUID(),"eternia:prop/aqua_lamp","source2",Map.of(),"PLACED",1,"");
        var row=CollectionInventory.summarize(List.of(grant("unused",2,null,false)),List.of(packed,placed,packed),now).getFirst();
        assertEquals(2,row.available());assertEquals(1,row.packed());assertEquals(1,row.placed());
        assertEquals("2 available • 1 packed • 1 placed",row.status());
        var savedOnly=CollectionInventory.summarize(List.of(),List.of(packed),now).getFirst();
        assertEquals(0,savedOnly.available());assertEquals(1,savedOnly.packed());
    }
    private final Owner owner=Owner.player(UUID.randomUUID());
    private final Instant now=Instant.parse("2026-09-30T00:00:00Z");
    private OwnershipService.Grant grant(String id,long available,Instant until,boolean revoked){return new OwnershipService.Grant(id,owner,"eternia:prop/aqua_lamp",OwnershipService.Kind.QUANTITY,10,available,until,revoked);}
    @Test void combinesRewardsAndPurchasesWithoutCountingSpentExpiredOrRefundedUnits(){
        var rows=CollectionInventory.summarize(List.of(grant("reward",1,null,false),grant("purchase",1,null,false),grant("spent",0,null,false),grant("expired",9,now,false),grant("refunded",8,null,true)),now);
        assertEquals(1,rows.size());assertEquals(2,rows.getFirst().available());assertEquals("2 available",rows.getFirst().status());
    }
    @Test void preservesExpiryNoticeAndDoesNotMixDifferentItems(){
        var future=now.plusSeconds(3600);
        var unlock=new OwnershipService.Grant("waygate",owner,"eternia:convenience/teleporter",OwnershipService.Kind.CAPABILITY,1,1,null,false);
        var rows=CollectionInventory.summarize(List.of(grant("temporary",2,future,false),grant("permanent",1,null,false),unlock),now);
        assertEquals(2,rows.size());assertEquals("Owned",rows.getFirst().status());
        assertEquals(3,rows.getLast().available());assertEquals(future,rows.getLast().earliestExpiry());
        assertEquals("Inactive",CollectionInventory.summarize(List.of(grant("expired",8,now,false)),now).getFirst().status());
    }
}
