package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.PlotCells;
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
        validate(before,after,claims,List.of(),pending,oldNetwork,newNetwork);
    }
    /**
     * A hex plot as the strand check sees it. {@code label} names it for the administrator ("Mira's plot at hexagon 3:1").
     * Guild member plots hang off their guild's land, not a road, so only their cells matter.
     */
    public record HexClaim(UUID id,String label,PlotCells cells,HousingRules.Scope scope,UUID guildId) {
        public HexClaim{Objects.requireNonNull(id);Objects.requireNonNull(label);Objects.requireNonNull(cells);Objects.requireNonNull(scope);}
    }
    /** {@code claims} are rectangle plots; {@code hexClaims} are hex plots, checked by cells instead of boxes. */
    public static void validate(HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,List<HousingRules.Anchor> claims,List<HexClaim> hexClaims,List<PlotRect> pending,PublicRoadNetwork oldNetwork,PublicRoadNetwork newNetwork) {
        Objects.requireNonNull(after);
        boolean anyClaims=!claims.isEmpty()||!hexClaims.isEmpty();
        if(anyClaims&&!after.supportsHousing())throw new IllegalArgumentException("This world has housing claims. Move them before disabling housing.");
        if(anyClaims&&before!=null&&(before.shape()!=after.shape()||!Objects.equals(before.lattice(),after.lattice())))throw new IllegalArgumentException("This world has plots. Pack or unclaim every plot before changing its plot shape or hexagon grid.");
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
            if(claims.stream().anyMatch(claim->claim.rect().contains(x,z))||hexClaims.stream().anyMatch(claim->claim.cells().containsColumn(x,z)))throw new IllegalArgumentException("A public arrival must be outside claimed plots.");
            if(pending.stream().anyMatch(rect->rect.contains(x,z)))throw new IllegalArgumentException("A public arrival must be outside protected construction operations.");
        }
        if(before!=null)for(var claim:claims)if(supported(claim,claims,before,oldNetwork)&&!supported(claim,claims,after,newNetwork))throw new IllegalArgumentException("This change would leave plot "+claim.id()+" without its nearby road or guild house.");
        validateHex(before,after,hexClaims);
    }
    /**
     * Strand checks for hex plots. A road or plaza turns every hexagon it touches into street, so an edit may never
     * touch a claimed hexagon, and may never take away the last hexagon beside a street from a public plot or guild
     * estate that had one. Plots that already had no road keep whatever they have (an unrelated edit is not blocked).
     */
    public static void validateHex(HousingInfrastructure.WorldPlan before,HousingInfrastructure.WorldPlan after,List<HexClaim> claims) {
        if(claims.isEmpty())return;
        var lattice=after.hexLattice().orElse(null);
        if(lattice==null)throw new IllegalArgumentException("This world has hexagon plots. Keep its hexagon grid.");
        Set<HexCoord> street=HexClaimRules.streetCells(lattice,after.roads(),after.portals());
        Set<HexCoord> oldStreet=before==null||before.hexLattice().filter(lattice::equals).isEmpty()?Set.of():HexClaimRules.streetCells(lattice,before.roads(),before.portals());
        for(var claim:claims){
            if(!claim.cells().lattice().equals(lattice))continue;
            var paved=claim.cells().cells().stream().filter(street::contains).findFirst();
            if(paved.isPresent())throw new IllegalArgumentException("This road or plaza would cover hexagon "+paved.get()+" of "+claim.label()+". Move it so it only touches free hexagons, or move the plot first.");
        }
        if(before==null)return;
        for(var claim:claims){
            if(claim.scope()==HousingRules.Scope.GUILD_MEMBER||!claim.cells().lattice().equals(lattice))continue;
            if(frontage(claim.cells(),oldStreet)&&!frontage(claim.cells(),street))throw new IllegalArgumentException("This change would leave "+claim.label()+" with no hexagon beside a road. Keep a road or plaza next to it.");
        }
    }
    private static boolean frontage(PlotCells cells,Set<HexCoord> street){
        for(var c:cells.cells())for(var n:c.neighbors())if(street.contains(n)&&!cells.contains(n))return true;
        return false;
    }
    private static boolean supported(HousingRules.Anchor claim,List<HousingRules.Anchor> claims,HousingInfrastructure.WorldPlan plan,PublicRoadNetwork network) {
        if(claim.scope()!=HousingRules.Scope.GUILD_MEMBER)return network.near(claim.rect());
        var reached=HousingRules.reachable(claims,plan.portals(),claim.scope(),claim.guildId(),network);
        return reached.contains(claim.id())||claims.stream().anyMatch(anchor->reached.contains(anchor.id())&&anchor.rect().gap(claim.rect())<=5);
    }
}
