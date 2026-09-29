package com.hexvane.eterniamod.discovery;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.localplayground.PlaygroundActivities;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalDiscoveryRegistryTest {
    @TempDir Path folder;
    @Test void savedExampleRequiresItsWorldIdentityAndDoesNotGrantUntilCollected()throws Exception{
        var world=UUID.randomUUID();var definition=PlaygroundActivities.DISCOVERY;var path=folder.resolve("examples.json");var registry=new LocalDiscoveryRegistry(path);var entry=new LocalDiscoveryRegistry.Entry(world,definition);
        registry.ensure(entry);registry.ensure(entry);var reloaded=new LocalDiscoveryRegistry(path);assertEquals(1,reloaded.all().size());assertTrue(reloaded.at(UUID.randomUUID(),definition.world(),definition.x(),definition.y(),definition.z()).isEmpty());
        var stored=reloaded.at(world,definition.world(),definition.x(),definition.y(),definition.z()).orElseThrow();var services=new EterniaServices(new InMemoryStore());var actor=UUID.randomUUID();services.accounts().recordAuthenticatedLogin(actor,"Explorer");assertEquals(0,services.ownership().available(Owner.player(actor),definition.reward().contentId()));
        var evidence=new DiscoveryService.Evidence(world,definition.world(),definition.x(),definition.y(),definition.z(),definition.markerBlockId(),true,true,4);
        assertTrue(services.discoveries().redeem(actor,stored,evidence).firstCollection());assertFalse(services.discoveries().redeem(actor,stored,evidence).firstCollection());assertEquals(1,services.ownership().available(Owner.player(actor),definition.reward().contentId()));
        assertThrows(IllegalArgumentException.class,()->registry.ensure(new LocalDiscoveryRegistry.Entry(UUID.randomUUID(),definition)));
    }
    @Test void corruptionCannotReplaceAnAuthoredRewardOrSilentlyEraseTheRegistry()throws Exception{
        Path path=folder.resolve("examples.json");var registry=new LocalDiscoveryRegistry(path);registry.ensure(new LocalDiscoveryRegistry.Entry(UUID.randomUUID(),PlaygroundActivities.DISCOVERY));Files.writeString(path,Files.readString(path).replace("aqua_lamp","potion_shelf"));assertThrows(java.io.IOException.class,()->new LocalDiscoveryRegistry(path));
    }
}
