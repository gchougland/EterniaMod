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
    // If saving the acknowledgement fails, retry it without sending another packet in this session.
    private final Set<String> sent=new HashSet<>();
    QuestToasts(SeasonService seasons){this.seasons=seasons;}
    void deliver(){
        var universe=Universe.get();if(universe==null)return;
        var online=new HashMap<UUID,PlayerRef>();
        for(var player:universe.getPlayers())if(player.getWorldUuid()!=null)online.put(player.getUuid(),player);
        for(var notice:seasons.pendingQuestNotices(online.keySet(),100)) {
            var player=online.get(notice.actor());
            if(universe.getPlayer(notice.actor())!=player||player.getWorldUuid()==null)continue;
            if(!sent.contains(notice.id())){player.getPacketHandler().writeNoCache(packet(notice));sent.add(notice.id());}
            seasons.acknowledgeQuestNotice(notice.actor(),notice.id());sent.remove(notice.id());
        }
    }
    static Notification packet(SeasonService.QuestNotice notice){
        String quest=UiPresentation.questTitle(notice.activity());
        var packet=new Notification();packet.style=NotificationStyle.Success;
        packet.tag="eternia:quest:"+notice.id();
        packet.icon="UI/Custom/EterniaMod/Icons/"+(notice.completion()?"season":"coins")+".png";
        packet.message=Message.raw(notice.completion()?"Quest complete: "+quest:"Quest Coins received").getFormattedMessage();
        packet.secondaryMessage=Message.raw(notice.completion()?CurrencyUi.amount(notice.xp())+" XP and "+CurrencyUi.amount(notice.coins())+" Coins":quest+": "+CurrencyUi.amount(notice.coins())+" Coins").getFormattedMessage();
        return packet;
    }
}
