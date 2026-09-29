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
    static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player){
        var services=plugin.getServices();UUID actor=player.getUuid();
        var membership=services.guilds().membership(actor).orElseThrow(()->new IllegalStateException("Join a guild first."));
        UUID guildId=membership.guildId();var guild=services.guilds().find(guildId).orElseThrow();
        if(!services.guilds().can(actor,guildId,"commerce.purchase_for_guild")){player.sendMessage(Message.raw("Only the guild leader can redeem guild upgrades."));return;}
        var choices=new ArrayList<ChoicePage.Choice>();
        for(var voucher:services.guildBenefits().available(actor)){
            UUID receipt=UUID.randomUUID();
            choices.add(new ChoicePage.Choice(voucher.benefit().name()+" · "+voucher.available()+" available","Review",(r,s)->
                ChoicePage.open(r,s,player,"Upgrade "+guild.name(),"Use one purchased charter to give "+guild.name()+" the "+voucher.benefit().name()+" benefit? This donation stays with that guild when you leave.",List.of(
                    new ChoicePage.Choice("Donate to "+guild.name(),"Confirm",(rr,ss)->{
                        var redemption=services.guildBenefits().redeem(actor,guildId,voucher.grantId(),"guild-ui:"+receipt);
                        player.sendMessage(Message.raw(redemption.active()?voucher.benefit().name()+" is available to "+guild.name()+".":"This donation is no longer active because its purchase was reversed."));
                        open(plugin,rr,ss,player);
                    }),
                    new ChoicePage.Choice("Keep this voucher","Back",(rr,ss)->open(plugin,rr,ss,player))))));
        }
        ChoicePage.open(ref,store,player,"Guild upgrades","Redeem a guild charter from the Crown Store for "+guild.name()+". Guild leaders choose where their purchased charters are used.",choices);
    }
}
