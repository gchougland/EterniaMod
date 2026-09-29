package com.hexvane.eterniamod.discovery;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {
    @TempDir Path directory;
    final DiscoveryService.Definition definition=new DiscoveryService.Definition("first-light","adventure",12,80,34,"Eternia_Discovery_Cache",new DiscoveryService.Reward("eternia:prop/aqua_lamp",OwnershipService.Kind.QUANTITY,1));
    DiscoveryService.Evidence evidence(UUID world){return new DiscoveryService.Evidence(world,"adventure",12,80,34,"Eternia_Discovery_Cache",true,true,4);}
    UUID account(EterniaServices services){UUID actor=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(actor,"Explorer");return actor;}
    @Test void concurrentUseGrantsOnceAndEachPlayerHasTheirOwnCollection()throws Exception{
        var services=new EterniaServices(new InMemoryStore());UUID actor=account(services),world=UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<8;i++)tasks.add(pool.submit(()->services.discoveries().redeem(actor,definition,evidence(world)).firstCollection()));
            int first=0;for(var task:tasks)if(task.get())first++;assertEquals(1,first);
        }
        assertEquals(1,services.ownership().available(Owner.player(actor),definition.reward().contentId()));
        UUID other=account(services);assertTrue(services.discoveries().redeem(other,definition,evidence(world)).firstCollection());
    }
    @Test void restartAndWorldReplacementDoNotResetAStableDiscovery(){
        Path file=directory.resolve("discoveries.data");UUID actor,originalWorld=UUID.randomUUID();
        try(var store=new LocalFileStore(file)){var services=new EterniaServices(store);actor=account(services);assertTrue(services.discoveries().redeem(actor,definition,evidence(originalWorld)).firstCollection());}
        try(var store=new LocalFileStore(file)){var services=new EterniaServices(store);assertFalse(services.discoveries().redeem(actor,definition,evidence(UUID.randomUUID())).firstCollection());assertEquals(1,services.ownership().available(Owner.player(actor),definition.reward().contentId()));
            assertEquals(originalWorld.toString(),store.transaction(tx->tx.scan("discovery_claim").getFirst().value("worldUuid")));}
    }
    @Test void movedCopiedOrReplacedMarkerAndCreativeModeCannotMint(){
        var services=new EterniaServices(new InMemoryStore());UUID actor=account(services),world=UUID.randomUUID();
        var invalid=List.of(
            new DiscoveryService.Evidence(world,"adventure",13,80,34,"Eternia_Discovery_Cache",true,true,4),
            new DiscoveryService.Evidence(world,"housing",12,80,34,"Eternia_Discovery_Cache",true,false,4),
            new DiscoveryService.Evidence(world,"adventure",12,80,34,"Air",true,true,4),
            new DiscoveryService.Evidence(world,"adventure",12,80,34,"Eternia_Discovery_Cache",false,true,4),
            new DiscoveryService.Evidence(world,"adventure",12,80,34,"Eternia_Discovery_Cache",true,true,100));
        for(var target:invalid)assertThrows(DomainException.class,()->services.discoveries().redeem(actor,definition,target));
        assertEquals(0,services.ownership().available(Owner.player(actor),definition.reward().contentId()));
        var registry=new DiscoveryRegistry(List.of(definition));assertTrue(registry.at("adventure",13,80,34).isEmpty());
    }
    @Test void collectedDiscoveryRewardCannotChangeForExistingOrNewPlayers(){
        var services=new EterniaServices(new InMemoryStore());UUID actor=account(services),world=UUID.randomUUID();services.discoveries().redeem(actor,definition,evidence(world));
        var changed=new DiscoveryService.Definition(definition.id(),definition.world(),12,80,34,definition.markerBlockId(),new DiscoveryService.Reward(definition.reward().contentId(),OwnershipService.Kind.QUANTITY,2));
        assertThrows(DomainException.class,()->services.discoveries().redeem(actor,changed,evidence(world)));UUID other=account(services);assertThrows(DomainException.class,()->services.discoveries().redeem(other,changed,evidence(world)));assertEquals(0,services.ownership().available(Owner.player(other),definition.reward().contentId()));
    }
    @Test void registryRejectsDuplicateIdsAndPositions()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new DiscoveryRegistry(List.of(definition,definition)));
        var samePosition=new DiscoveryService.Definition("another",definition.world(),12,80,34,definition.markerBlockId(),definition.reward());
        assertThrows(IllegalArgumentException.class,()->new DiscoveryRegistry(List.of(definition,samePosition)));
        assertTrue(DiscoveryRegistry.load(directory.resolve("discoveries.json")).all().isEmpty());
    }
}
