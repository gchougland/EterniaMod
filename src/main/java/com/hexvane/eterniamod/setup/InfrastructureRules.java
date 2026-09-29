package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.housing.*;
import java.util.*;

/** Pure preview/commit rules. Registration changes protection and topology, never physical blocks. */
public final class InfrastructureRules {
    private InfrastructureRules() {}
    public static PlotRect selection(int x1,int z1,int x2,int z2) {
        var result=new PlotRect(Math.min(x1,x2),Math.min(z1,z2),Math.addExact(Math.abs(Math.subtractExact(x2,x1)),1),Math.addExact(Math.abs(Math.subtractExact(z2,z1)),1));
        if(result.area()>65_536)throw new IllegalArgumentException("Select at most 65,536 columns per infrastructure area.");
        return result;
    }
    public static void validate(HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,List<HousingRules.Anchor> claims,List<PlotRect> pending) {
        validate(before,after,claims,pending,PublicRoadNetwork.rectangles(before==null?List.of():before.roads()),PublicRoadNetwork.rectangles(after.roads()));
    }
    public static void validate(HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,List<HousingRules.Anchor> claims,List<PlotRect> pending,PublicRoadNetwork oldNetwork,PublicRoadNetwork newNetwork) {
        Objects.requireNonNull(after);
        if(!claims.isEmpty()&&!after.supportsHousing())throw new IllegalArgumentException("This world has housing claims. Move them before disabling housing.");
        if(!pending.isEmpty()&&(before==null||!before.role().equals(after.role())||before.supportsHousing()!=after.supportsHousing()))throw new IllegalArgumentException("Finish or remove protected construction operations before changing this world's role or housing policy.");
        Map<String,HousingInfrastructure.Area> oldAreas=before==null?Map.of():before.areas();
        var ids=new HashSet<>(oldAreas.keySet());ids.addAll(after.areas().keySet());
        for(String id:ids) {
            var old=oldAreas.get(id);var next=after.areas().get(id);if(Objects.equals(old,next))continue;
            for(var area:old==null?List.of(next):next==null?List.of(old):List.of(old,next)) {
                if(claims.stream().anyMatch(claim->claim.rect().overlaps(area.rect())))throw new IllegalArgumentException("Area "+id+" overlaps an existing claim. Its protection cannot be changed here.");
                if(pending.stream().anyMatch(rect->rect.overlaps(area.rect())))throw new IllegalArgumentException("Area "+id+" overlaps an unfinished construction operation. Recover it first.");
            }
        }
        if(after.arrival()!=null&&(before==null||!Objects.equals(before.arrival(),after.arrival()))) {
            var point=after.arrival();int x=(int)Math.floor(point.x()),z=(int)Math.floor(point.z());
            if(claims.stream().anyMatch(claim->claim.rect().contains(x,z)))throw new IllegalArgumentException("A public arrival must be outside claimed plots.");
            if(pending.stream().anyMatch(rect->rect.contains(x,z)))throw new IllegalArgumentException("A public arrival must be outside protected construction operations.");
        }
        if(before!=null)for(var claim:claims)if(supported(claim,claims,before,oldNetwork)&&!supported(claim,claims,after,newNetwork))throw new IllegalArgumentException("This change would disconnect plot "+claim.id()+" from its required road or portal anchors.");
    }
    private static boolean supported(HousingRules.Anchor claim,List<HousingRules.Anchor> claims,HousingInfrastructure.WorldPlan plan,PublicRoadNetwork network) {
        if(claim.scope()!=HousingRules.Scope.GUILD_MEMBER&&plan.roads().stream().noneMatch(road->road.gap(claim.rect())<=5))return false;
        if(claim.scope()==HousingRules.Scope.GUILD_ROOT)return plan.portals().stream().anyMatch(portal->portal.gap(claim.rect())<=240);
        if(claim.scope()==HousingRules.Scope.PUBLIC&&(plan.portals().stream().anyMatch(portal->portal.gap(claim.rect())<=5)||network.anchored(claim.rect(),plan.portals())))return true;
        var reached=HousingRules.reachable(claims,plan.portals(),claim.scope(),claim.guildId(),network);
        return reached.contains(claim.id())||claims.stream().anyMatch(anchor->reached.contains(anchor.id())&&anchor.rect().gap(claim.rect())<=5);
    }
}
