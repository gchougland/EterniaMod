package com.hexvane.eterniamod.activities;

import com.hexvane.eterniamod.domain.SeasonService;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestToastsTest {
    @Test void completionUsesNativeSuccessToastWithFriendlyTitleAmountsAndPackagedIcon() {
        var notice=new SeasonService.QuestNotice("receipt",UUID.randomUUID(),SeasonService.ActivityKind.KILL,8000,100,true);
        var packet=QuestToasts.packet(notice);
        assertEquals(NotificationStyle.Success,packet.style);
        assertEquals("Quest complete: On patrol",packet.message.rawText);
        assertEquals("8,000 XP and 100 Coins",packet.secondaryMessage.rawText);
        assertEquals("eternia:quest:receipt",packet.tag);
        assertNotNull(getClass().getClassLoader().getResource("Common/"+packet.icon));
    }
    @Test void recoveredCoinsDoNotClaimToGrantXpAgain() {
        var packet=QuestToasts.packet(new SeasonService.QuestNotice("old",UUID.randomUUID(),SeasonService.ActivityKind.HARVEST,0,100,false));
        assertEquals("Quest Coins received",packet.message.rawText);
        assertEquals("A fruitful harvest: 100 Coins",packet.secondaryMessage.rawText);
        assertFalse(packet.secondaryMessage.rawText.contains("XP"));
        assertNotNull(getClass().getClassLoader().getResource("Common/"+packet.icon));
    }
}
