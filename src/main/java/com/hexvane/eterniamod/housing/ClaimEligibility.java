package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.domain.*;
import java.util.UUID;

/** Immutable account-only preview data. Loading this never touches a world or native entity. */
record ClaimEligibility(UUID actor,HousingRules.Scope scope,boolean paid,UUID guild,Owner owner,
                        HousingService.Slot slot,HousingService.ClaimLocation location,
                        boolean canClaim,boolean hasUpgrade,int members) {
    static ClaimEligibility load(EterniaServices services,UUID actor,HousingRules.Scope scope,boolean paid){
        return services.batch(()->{
            UUID guild=scope==HousingRules.Scope.PUBLIC?null:services.guilds().membership(actor).map(GuildService.Membership::guildId).orElse(null);
            Owner owner=scope==HousingRules.Scope.GUILD_ROOT&&guild!=null?Owner.guild(guild):Owner.player(actor);
            var slot=services.housing().find(owner).orElse(null);
            var location=slot!=null&&slot.state()==HousingService.State.PACKED?services.housing().location(owner).orElse(null):null;
            boolean canClaim=scope!=HousingRules.Scope.GUILD_ROOT||guild!=null&&services.guilds().can(actor,guild,"housing.claim");
            boolean upgraded=!paid||services.ownership().owns(owner,scope==HousingRules.Scope.GUILD_ROOT?"eternia:plot/guild_64":"eternia:plot/personal_32");
            int members=scope==HousingRules.Scope.GUILD_ROOT&&guild!=null?services.guilds().roster(actor).size():0;
            return new ClaimEligibility(actor,scope,paid,guild,owner,slot,location,canClaim,upgraded,members);
        });
    }
    boolean matches(HousingRules.Scope wantedScope,boolean wantedPaid){return scope==wantedScope&&paid==wantedPaid;}
    HousingRules.Result check(PlotRect rect){
        if(slot!=null&&slot.state()!=HousingService.State.PACKED)return new HousingRules.Result(false,"slot",slot.state()==HousingService.State.ACTIVE?"You already have a plot. Open My plots to find it or move it.":"Your plot needs recovery. Open My plots to see its status.");
        if(location!=null&&(rect.width()<location.width()||rect.depth()<location.depth()||(rect.width()-location.width())%2!=0||(rect.depth()-location.depth())%2!=0))return new HousingRules.Result(false,"restoreSize","Choose your packed plot's size or a larger size that fits its contents.");
        if(!canClaim)return new HousingRules.Result(false,"permission","Your guild role cannot claim a guild plot.");
        if(!hasUpgrade)return new HousingRules.Result(false,"upgrade","Unlock the larger plot size before claiming it.");
        return null;
    }
}
