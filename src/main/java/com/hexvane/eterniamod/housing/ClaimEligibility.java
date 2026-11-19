package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.domain.*;
import java.util.UUID;

/**
 * Immutable account-only preview data. Loading this never touches a world or native entity. An owner may hold several
 * plots: {@code slot} is the plot the next claim works on (their oldest packed plot, which is set down again) or null
 * for a new plot; {@code plots} is how many they hold and {@code busy} whether one is part way through a move.
 */
record ClaimEligibility(UUID actor,HousingRules.Scope scope,boolean paid,UUID guild,Owner owner,
                        HousingService.Slot slot,HousingService.ClaimLocation location,
                        boolean canClaim,boolean hasUpgrade,int members,int plots,boolean busy) {
    static ClaimEligibility load(EterniaServices services,UUID actor,HousingRules.Scope scope,boolean paid){
        return services.batch(()->{
            UUID guild=scope==HousingRules.Scope.PUBLIC?null:services.guilds().membership(actor).map(GuildService.Membership::guildId).orElse(null);
            Owner owner=scope==HousingRules.Scope.GUILD_ROOT&&guild!=null?Owner.guild(guild):Owner.player(actor);
            var all=services.housing().slots(owner);
            var slot=all.stream().filter(s->s.state()==HousingService.State.PACKED).findFirst().orElse(null);
            boolean busy=all.stream().anyMatch(s->s.state()!=HousingService.State.PACKED&&s.state()!=HousingService.State.ACTIVE);
            var location=slot==null?null:services.housing().property(slot.propertyId()).map(HousingService.Property::location).orElse(null);
            // The guild's first plot is claimed by whoever may claim for it; later ones are bought with guild Crowns.
            boolean canClaim=scope!=HousingRules.Scope.GUILD_ROOT||guild!=null&&(services.guilds().can(actor,guild,"housing.claim")||!all.isEmpty()&&services.guilds().can(actor,guild,GuildCrownService.SPEND));
            boolean upgraded=!paid||services.ownership().owns(owner,scope==HousingRules.Scope.GUILD_ROOT?"eternia:plot/guild_64":"eternia:plot/personal_32");
            int members=scope==HousingRules.Scope.GUILD_ROOT&&guild!=null?services.guilds().roster(actor).size():0;
            return new ClaimEligibility(actor,scope,paid,guild,owner,slot,location,canClaim,upgraded,members,all.size(),busy);
        });
    }
    boolean matches(HousingRules.Scope wantedScope,boolean wantedPaid){return scope==wantedScope&&paid==wantedPaid;}
    /** Hex form of {@link #check}: account level reasons a hex claim is refused before any land rule, or null. */
    com.hexvane.eterniamod.hexplots.HexClaimRules.Result checkCells(com.hexvane.eterniamod.hexplots.PlotCells cells){
        if(busy)return com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.RECOVERY);
        if(scope==HousingRules.Scope.GUILD_MEMBER)return com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.ANCHOR);
        var tier=com.hexvane.eterniamod.hexplots.PlotRules.current().tier(owner.kind()==Owner.Kind.GUILD);
        if(slot==null&&plots>=tier.maxPlots())return com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(owner.kind()==Owner.Kind.GUILD?com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.GUILD_PLOT_LIMIT:com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.PLOT_LIMIT,tier.maxPlots());
        if(location!=null&&(location.cells()==null||!com.hexvane.eterniamod.hexplots.HexClaimRules.sameShape(location.cells().cells(),cells.cells())))return com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.RESTORE_SHAPE);
        if(!canClaim)return com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.PERMISSION);
        if(!hasUpgrade)return com.hexvane.eterniamod.hexplots.HexClaimRules.Result.of(com.hexvane.eterniamod.hexplots.HexClaimRules.Reason.UPGRADE);
        return null;
    }
    /** The hex rule kind for this claim scope. */
    com.hexvane.eterniamod.hexplots.HexClaimRules.Kind hexKind(){return HexClaimCheck.kind(scope);}
    HousingRules.Result check(PlotRect rect){
        if(busy)return new HousingRules.Result(false,"slot","Your plot needs recovery. Open My plots to see its status.");
        if(slot==null&&plots>=com.hexvane.eterniamod.hexplots.PlotRules.current().tier(owner.kind()==Owner.Kind.GUILD).maxPlots())return new HousingRules.Result(false,"slot","You already have the most plots you can have.");
        if(location!=null&&(rect.width()<location.width()||rect.depth()<location.depth()||(rect.width()-location.width())%2!=0||(rect.depth()-location.depth())%2!=0))return new HousingRules.Result(false,"restoreSize","Choose your packed plot's size or a larger size that fits its contents.");
        if(!canClaim)return new HousingRules.Result(false,"permission","Your guild role cannot claim a guild plot.");
        if(!hasUpgrade)return new HousingRules.Result(false,"upgrade","Unlock the larger plot size before claiming it.");
        return null;
    }
}
