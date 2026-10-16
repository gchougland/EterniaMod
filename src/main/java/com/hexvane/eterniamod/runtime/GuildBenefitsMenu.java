package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** The confirmed guild and purchase source are captured on the server, then rechecked at redemption. */
final class GuildBenefitsMenu {
    private record Snapshot(com.hexvane.eterniamod.domain.GuildService.Guild guild,boolean permitted,List<com.hexvane.eterniamod.domain.GuildBenefitService.Voucher> vouchers) {}
    static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        var services=plugin.getServices();UUID actor=player.getUuid();
        var snapshot=services.batch(()->{
            var membership=services.guilds().membership(actor).orElseThrow(()->new IllegalStateException("Join a guild first."));
            var guild=services.guilds().find(membership.guildId()).orElseThrow();boolean permitted=services.guilds().can(actor,guild.id(),"commerce.purchase_for_guild");
            return new Snapshot(guild,permitted,permitted?services.guildBenefits().available(actor):List.of());
        });
        var guild=snapshot.guild();var guildId=guild.id();
        if(!snapshot.permitted()){player.sendMessage(Message.raw("Only the guild leader can redeem guild upgrades."));return;}
        var choices=new ArrayList<ChoicePage.Choice>();
        for(var voucher:snapshot.vouchers()){
            UUID receipt=UUID.randomUUID();
            choices.add(new ChoicePage.Choice(voucher.benefit().name()+" · "+voucher.available()+" available","Review",(r,s)->
                ChoicePage.open(r,s,player,"Upgrade "+guild.name(),"Use one purchased charter to give "+guild.name()+" the "+voucher.benefit().name()+" benefit? This donation stays with that guild when you leave.",List.of(
                    new ChoicePage.Choice("Donate to "+guild.name(),"Confirm",(rr,ss)->{
                        var redemption=services.guildBenefits().redeem(actor,guildId,voucher.grantId(),"guild-ui:"+receipt);
                        player.sendMessage(Message.raw(redemption.active()?voucher.benefit().name()+" is available to "+guild.name()+".":"This donation is no longer active because its purchase was reversed."));
                        if(redemption.active()&&redemption.benefitId().equals(com.hexvane.eterniamod.domain.GuildBenefitService.WAYGATE))player.sendMessage(Message.raw("Your guild portal is in Guild decorations. Stand on the estate and open Housing inventory to place it."));
                        open(plugin,rr,ss,player);
                    }),
                    new ChoicePage.Choice("Keep this voucher","Back",(rr,ss)->open(plugin,rr,ss,player))))));
        }
        ChoicePage.open(ref,store,player,"Guild upgrades","Redeem a guild charter from the Crown Store for "+guild.name()+". Guild leaders choose where their purchased charters are used.",choices);
    }
}
