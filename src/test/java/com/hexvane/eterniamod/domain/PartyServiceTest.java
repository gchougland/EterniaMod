package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartyServiceTest {
    @Test void invitationCannotJoinTwoPartiesAndLeaderDepartureTransfersLeadership(){
        var services=new EterniaServices(new InMemoryStore());UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        for(UUID id:List.of(a,b,c))services.accounts().recordAuthenticatedLogin(id,id.toString());
        services.parties().create(a);services.parties().invite(a,b);services.parties().create(c);services.parties().invite(c,b);
        services.parties().accept(b,a);
        assertThrows(DomainException.class,()->services.parties().accept(b,c));
        services.parties().leave(a);assertEquals(b,services.parties().find(b).orElseThrow().leader());
        services.parties().leave(b);assertTrue(services.parties().find(b).isEmpty());assertThrows(DomainException.class,()->services.parties().accept(b,a));
    }
    @Test void onlyLeaderInvitesAndConsumedInviteCannotRejoin(){
        var services=new EterniaServices(new InMemoryStore());UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        for(UUID id:List.of(a,b,c))services.accounts().recordAuthenticatedLogin(id,id.toString());
        services.parties().create(a);services.parties().invite(a,b);services.parties().accept(b,a);
        assertThrows(DomainException.class,()->services.parties().invite(b,c));
        services.parties().leave(b);assertThrows(DomainException.class,()->services.parties().accept(b,a));
    }
    @Test void invitationExpiresAtFifteenMinutes(){
        var storage=new InMemoryStore();Instant now=Instant.parse("2026-01-01T00:00:00Z");var services=new EterniaServices(storage,Clock.fixed(now,ZoneOffset.UTC),UUID::randomUUID);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(a,"Leader");services.accounts().recordAuthenticatedLogin(b,"Guest");
        services.parties().create(a);services.parties().invite(a,b);
        var later=new EterniaServices(storage,Clock.fixed(now.plusSeconds(900),ZoneOffset.UTC),UUID::randomUUID);
        assertThrows(DomainException.class,()->later.parties().accept(b,a));
    }
}
