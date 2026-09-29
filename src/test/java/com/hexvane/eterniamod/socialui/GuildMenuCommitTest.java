package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A visible/confirmed UI action does not retain privileges after membership changes. */
class GuildMenuCommitTest {
    private final EterniaServices services=new EterniaServices(new InMemoryStore());
    private UUID account(String name){UUID id=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(id,name);return id;}
    private void join(UUID leader,UUID member){services.guilds().acceptInvite(member,services.guilds().invite(leader,member));}
    @Test void removalRechecksAuthorityAfterTheConfirmationWasOpened() {
        UUID leader=account("Leader"),officer=account("Officer"),member=account("Member");var guild=services.guilds().create(leader,"Review","create");join(leader,officer);join(leader,member);
        services.guilds().assignRole(leader,officer,"officer");assertTrue(services.guilds().can(officer,guild.id(),"member.remove"));
        services.guilds().assignRole(leader,officer,"member");
        assertThrows(DomainException.class,()->services.guilds().remove(officer,member));assertTrue(services.guilds().membership(member).isPresent());
    }
    @Test void transferRechecksCurrentLeaderAndKeepsExactlyOneLeader() {
        UUID leader=account("Leader"),next=account("Next"),third=account("Third");var guild=services.guilds().create(leader,"Transfer","create");join(leader,next);join(leader,third);
        services.guilds().transferLeadership(leader,next);
        assertEquals(next,services.guilds().find(guild.id()).orElseThrow().leader());assertEquals("officer",services.guilds().membership(leader).orElseThrow().role());
        assertThrows(DomainException.class,()->services.guilds().transferLeadership(leader,third));
        assertEquals(1,services.guilds().roster(next).stream().filter(member->member.role().equals("leader")).count());
    }
}
