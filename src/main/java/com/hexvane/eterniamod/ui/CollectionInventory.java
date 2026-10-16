package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.domain.OwnershipService;
import com.hexvane.eterniamod.domain.ProvenanceService;
import java.time.Instant;
import java.util.*;

/** A display projection only. Individual grants retain their purchase, expiry and refund history. */
public final class CollectionInventory {
    private CollectionInventory() {}
    public record Entry(String contentId,long available,boolean unlocked,boolean active,Instant earliestExpiry,long packed,long placed) {
        public String status(){return (!active&&packed==0&&placed==0?"Inactive":unlocked?"Owned":available+" available")+(packed>0?" • "+packed+" packed":"")+(placed>0?" • "+placed+" placed":"");}
    }
    public static List<Entry> summarize(List<OwnershipService.Grant> grants,Instant now){
        return summarize(grants,List.of(),now);
    }
    public static List<Entry> summarize(List<OwnershipService.Grant> grants,List<ProvenanceService.Instance> instances,Instant now){
        var result=new TreeMap<String,Entry>();
        for(var grant:grants){
            var previous=result.getOrDefault(grant.contentId(),new Entry(grant.contentId(),0,false,false,null,0,0));
            boolean active=!grant.revoked()&&(grant.validUntil()==null||grant.validUntil().isAfter(now));
            long count=active&&grant.kind()==OwnershipService.Kind.QUANTITY?grant.available():0;
            boolean usable=active&&(grant.kind()!=OwnershipService.Kind.QUANTITY||count>0);
            Instant expiry=previous.earliestExpiry();
            if(usable&&grant.validUntil()!=null&&(expiry==null||grant.validUntil().isBefore(expiry)))expiry=grant.validUntil();
            result.put(grant.contentId(),new Entry(grant.contentId(),Math.addExact(previous.available(),count),previous.unlocked()||active&&grant.kind()!=OwnershipService.Kind.QUANTITY,previous.active()||active,expiry,0,0));
        }
        var seen=new HashSet<UUID>();
        for(var instance:instances){
            if(!seen.add(instance.id())||!instance.contentId().startsWith("eternia:prop/")||!Set.of("PACKED","PLACED").contains(instance.state()))continue;
            var previous=result.getOrDefault(instance.contentId(),new Entry(instance.contentId(),0,false,false,null,0,0));
            result.put(instance.contentId(),new Entry(previous.contentId(),previous.available(),previous.unlocked(),previous.active(),previous.earliestExpiry(),previous.packed()+(instance.state().equals("PACKED")?1:0),previous.placed()+(instance.state().equals("PLACED")?1:0)));
        }
        return List.copyOf(result.values());
    }
}
