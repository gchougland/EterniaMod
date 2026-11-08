package com.hexvane.eterniamod.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.chat.ChatRouting.Listener;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatRoutingTest {
    static final UUID HUB = UUID.randomUUID(), ADVENTURE = UUID.randomUUID();
    final Listener alice = new Listener(UUID.randomUUID(), HUB, 0, 64, 0);
    final Listener bob = new Listener(UUID.randomUUID(), HUB, 30, 64, 30);       // about 42 blocks away
    final Listener carol = new Listener(UUID.randomUUID(), HUB, 200, 64, 0);     // far, same world
    final Listener dave = new Listener(UUID.randomUUID(), ADVENTURE, 0, 64, 0);  // same spot, other world
    final Listener erin = new Listener(UUID.randomUUID(), null, 0, 64, 0);       // between worlds
    final List<Listener> everyone = List.of(alice, bob, carol, dave, erin);

    List<UUID> route(ChatChannel channel, Set<UUID> members) {
        return ChatRouting.recipients(channel, alice, everyone, members, 50);
    }

    @Test void serverReachesEveryWorld() {
        assertEquals(List.of(alice.id(), bob.id(), carol.id(), dave.id(), erin.id()), route(ChatChannel.SERVER, Set.of()));
    }

    @Test void worldStaysInTheSendersWorld() {
        assertEquals(List.of(alice.id(), bob.id(), carol.id()), route(ChatChannel.WORLD, Set.of()));
    }

    @Test void nearbyIsSameWorldAndWithinTheRadius() {
        assertEquals(List.of(alice.id(), bob.id()), route(ChatChannel.NEARBY, Set.of()));
        // Exactly on the edge still hears; just past it does not.
        var edge = new Listener(UUID.randomUUID(), HUB, 50, 64, 0);
        var past = new Listener(UUID.randomUUID(), HUB, 50.01, 64, 0);
        var above = new Listener(UUID.randomUUID(), HUB, 0, 64 + 60, 0);
        assertEquals(List.of(alice.id(), edge.id()), ChatRouting.recipients(ChatChannel.NEARBY, alice, List.of(edge, past, above), Set.of(), 50));
    }

    @Test void partyAndGuildCrossWorldsButOnlyReachMembers() {
        var members = Set.of(alice.id(), dave.id(), erin.id());
        assertEquals(List.of(alice.id(), dave.id(), erin.id()), route(ChatChannel.PARTY, members));
        assertEquals(List.of(alice.id(), dave.id(), erin.id()), route(ChatChannel.GUILD, members));
        // A member who is offline or has hidden the sender is simply not in the audience.
        assertEquals(List.of(alice.id(), dave.id()), ChatRouting.recipients(ChatChannel.PARTY, alice, List.of(bob, dave), members, 50));
    }

    @Test void senderAlwaysSeesTheirOwnMessageOnce() {
        var alone = ChatRouting.recipients(ChatChannel.NEARBY, alice, List.of(alice, carol, dave), Set.of(), 50);
        assertEquals(List.of(alice.id()), alone);
        assertTrue(ChatRouting.onlySender(alone));
        assertFalse(ChatRouting.onlySender(route(ChatChannel.WORLD, Set.of())));
        // Even with no audience at all (the sender hid from everyone) they still see their line.
        assertEquals(List.of(alice.id()), ChatRouting.recipients(ChatChannel.SERVER, alice, List.of(), Set.of(), 50));
    }

    @Test void aSenderBetweenWorldsReachesNobodyByWorld() {
        var recipients = ChatRouting.recipients(ChatChannel.WORLD, erin, everyone, Set.of(), 50);
        assertEquals(List.of(erin.id()), recipients);
    }
}
