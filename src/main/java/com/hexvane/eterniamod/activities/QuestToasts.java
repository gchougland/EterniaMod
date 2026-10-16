package com.hexvane.eterniamod.activities;

import com.hexvane.eterniamod.domain.SeasonService;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hypixel.hytale.protocol.packets.interface_.Notification;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.*;

/** Called by the single activity worker after reward transactions have committed. */
final class QuestToasts {
    private final SeasonService seasons;
    private final com.hexvane.eterniamod.domain.EterniaServices services;
    private record NoticeKey(Set<UUID> actors) {}
    // If saving the acknowledgement fails, retry it without sending another packet in this session.
    private final Set<String> sent=new HashSet<>();
    QuestToasts(com.hexvane.eterniamod.domain.EterniaServices services){this.services=services;this.seasons=services.seasons();}
    void deliver(){
        var universe=Universe.get();if(universe==null)return;
        var online=new HashMap<UUID,PlayerRef>();
        for(var player:universe.getPlayers())if(player.getWorldUuid()!=null)online.put(player.getUuid(),player);
        if(online.isEmpty())return;
        var acknowledged=new ArrayList<SeasonService.QuestNotice>();
        for(var notice:services.readView(new NoticeKey(Set.copyOf(online.keySet())),java.time.Duration.ofSeconds(5),()->seasons.pendingQuestNotices(online.keySet(),100))) {
            var player=online.get(notice.actor());
            if(universe.getPlayer(notice.actor())!=player||player.getWorldUuid()==null)continue;
            if(!sent.contains(notice.id())){player.getPacketHandler().writeNoCache(packet(notice));sent.add(notice.id());if(notice.completion()){int sound=com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap().getIndex("SFX_Discovery_Z1_Short");if(sound!=Integer.MIN_VALUE)com.hypixel.hytale.server.core.universe.world.SoundUtil.playSoundEvent2dToPlayer(player,sound,com.hypixel.hytale.protocol.SoundCategory.UI);}}
            acknowledged.add(notice);
        }
        if(!acknowledged.isEmpty()){services.batch(()->{for(var notice:acknowledged)seasons.acknowledgeQuestNotice(notice.actor(),notice.id());return null;});acknowledged.forEach(notice->sent.remove(notice.id()));}
    }
    static Notification packet(SeasonService.QuestNotice notice){
        String quest=notice.title()==null||notice.title().isBlank()?UiPresentation.questTitle(notice.activity()):notice.title();
        var packet=new Notification();packet.style=NotificationStyle.Success;
        packet.tag="eternia:quest:"+notice.id();
        packet.icon="UI/Custom/EterniaMod/Icons/"+(notice.completion()?"season":"coins")+".png";
        packet.message=Message.raw(notice.completion()?"Quest complete: "+quest:"Quest Coins received").getFormattedMessage();
        packet.secondaryMessage=Message.raw(notice.completion()?CurrencyUi.amount(notice.xp())+" XP and "+CurrencyUi.amount(notice.coins())+" Coins":quest+": "+CurrencyUi.amount(notice.coins())+" Coins").getFormattedMessage();
        return packet;
    }
}
