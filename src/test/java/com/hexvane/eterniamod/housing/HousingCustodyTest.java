package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HousingCustodyTest {
    @Test void attachedGuildCustodyRequiresBothPlotAndCurrentInventoryAuthority(){
        Owner player=Owner.player(UUID.randomUUID()),guild=Owner.guild(UUID.randomUUID());
        assertTrue(HousingCustody.allows(player,null,player,true,null,false));
        assertTrue(HousingCustody.allows(player,guild.id(),guild,true,guild.id(),true));
        assertFalse(HousingCustody.allows(player,guild.id(),guild,false,guild.id(),true));
        assertFalse(HousingCustody.allows(player,guild.id(),guild,true,guild.id(),false));
        assertFalse(HousingCustody.allows(player,guild.id(),guild,true,null,true));
        assertFalse(HousingCustody.allows(player,guild.id(),guild,true,UUID.randomUUID(),true));
        assertFalse(HousingCustody.allows(player,null,guild,true,guild.id(),true));
        assertFalse(HousingCustody.allows(player,UUID.randomUUID(),guild,true,guild.id(),true));
        assertFalse(HousingCustody.allows(player,guild.id(),Owner.player(UUID.randomUUID()),true,guild.id(),true));
    }

    @Test void seededArchitectCanPlaceAndPackButRevocationAndDepartureInvalidateSelection(){
        var services=new EterniaServices(new InMemoryStore());
        UUID leader=UUID.randomUUID(),member=UUID.randomUUID();
        services.accounts().recordAuthenticatedLogin(leader,"Leader");
        services.accounts().recordAuthenticatedLogin(member,"Builder");
        UUID guildId=services.guilds().create(leader,"Citadel builders","create").id();
        services.guilds().acceptInvite(member,services.guilds().invite(leader,member));
        services.guilds().assignRole(leader,member,"architect");
        Owner guild=Owner.guild(guildId),personal=Owner.player(member);
        assertTrue(services.guilds().can(member,guildId,HousingCustody.PLACE));
        assertTrue(services.guilds().can(member,guildId,HousingCustody.PACK));
        assertFalse(services.guilds().can(member,guildId,"housing.prop.remove"));
        assertTrue(allowed(services,member,guild,guild,HousingCustody.PACK));
        assertTrue(allowed(services,member,personal,guild,HousingCustody.PACK));
        services.guilds().assignRole(leader,member,"member");
        assertFalse(allowed(services,member,guild,guild,HousingCustody.PACK));
        assertFalse(allowed(services,member,personal,guild,HousingCustody.PACK));
        services.guilds().assignRole(leader,member,"architect");
        services.guilds().leave(member);
        assertFalse(allowed(services,member,personal,guild,HousingCustody.PLACE));
    }

    @Test void guildRootStillRequiresInventoryPermissionAndPackagingPreservesGuildCustody(){
        Owner guild=Owner.guild(UUID.randomUUID());UUID builder=UUID.randomUUID(),property=UUID.randomUUID();
        assertFalse(HousingCustody.allows(guild,null,guild,true,guild.id(),false));
        assertFalse(HousingCustody.allows(guild,null,guild,true,null,true));
        assertTrue(HousingCustody.allows(guild,null,guild,true,guild.id(),true));
        var services=new EterniaServices(new InMemoryStore());String content="eternia:prop/potion_shelf";
        services.ownership().grant(new OwnershipService.GrantRequest("grant",guild,content,OwnershipService.Kind.QUANTITY,1,null));
        var reservation=services.ownership().reserve(guild,content,1,"placement");
        var instance=services.provenance().recordVerifiedPlacement(UUID.randomUUID(),guild,builder,property,content,reservation.id(),Map.of("kind","PROP"),"verified");
        var packed=services.provenance().acknowledgePacked(instance.id(),instance.revision(),"verified-snapshot");
        assertEquals(guild,packed.owner());assertEquals(reservation.id(),packed.sourceReference());
        assertEquals(0,services.ownership().available(guild,content));
        assertEquals(1,services.provenance().ownedInstances(guild).stream().filter(i->i.state().equals("PACKED")).count());
        assertTrue(services.provenance().ownedInstances(Owner.player(builder)).isEmpty());
        var restored=services.provenance().acknowledgeRestored(packed.id(),packed.revision(),UUID.randomUUID());
        assertEquals(guild,restored.owner());assertEquals(reservation.id(),restored.sourceReference());
    }

    private boolean allowed(EterniaServices services,UUID actor,Owner property,Owner guild,String capability){
        UUID membership=services.guilds().membership(actor).map(GuildService.Membership::guildId).orElse(null);
        boolean plotPermission=property.kind()==Owner.Kind.PLAYER&&property.id().equals(actor)||services.guilds().can(actor,guild.id(),capability);
        return HousingCustody.allows(property,property.kind()==Owner.Kind.PLAYER?guild.id():null,guild,plotPermission,membership,services.guilds().can(actor,guild.id(),HousingCustody.RESERVE));
    }
}
