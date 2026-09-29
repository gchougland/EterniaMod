package com.hexvane.eterniamod.inventory;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.LocalFileStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Crash cut points use native document revisions as evidence without loading the Hytale runtime. */
class NativeCustodyProtocolTest {
    @TempDir Path directory;
    @Test void crashAfterNativeInsertionBeforeDomainAckRecoversOnceAndRejectsOlderBackup(){
        Path file=directory.resolve("custody.data");Owner owner=Owner.player(UUID.randomUUID());UUID attempt=UUID.randomUUID();String deliveryId;
        try(var store=new LocalFileStore(file)){
            var escrow=new EterniaServices(store).escrow();var deposit=escrow.prepareDeposit(owner,new EscrowService.Item("Ore_Iron",3,"",true),"native-removal");
            escrow.acknowledgeNativeInventoryCheckpoint(owner,1);escrow.acknowledgeNativeRemoval(deposit.id(),"native-removal");
            deliveryId=escrow.withdraw(owner,deposit.id(),"withdraw").id();escrow.beginNativeDelivery(owner,deliveryId,attempt);
            // Native document revision 2 contains the inserted stack and exact attempt marker.
            // Process stops before either its checkpoint or delivery acknowledgement commits.
        }
        try(var store=new LocalFileStore(file)){
            var escrow=new EterniaServices(store).escrow();escrow.verifyNativeInventoryCheckpoint(owner,2);escrow.acknowledgeNativeInventoryCheckpoint(owner,2);
            assertEquals("DELIVERED",escrow.resolveNativeDelivery(deliveryId,attempt,EscrowService.NativeOutcome.INSERTED).state());
            assertEquals("DELIVERED",escrow.resolveNativeDelivery(deliveryId,attempt,EscrowService.NativeOutcome.INSERTED).state());
            assertThrows(DomainException.class,()->escrow.beginNativeDelivery(owner,deliveryId,UUID.randomUUID()));
        }
        try(var store=new LocalFileStore(file)){
            var escrow=new EterniaServices(store).escrow();assertThrows(DomainException.class,()->escrow.verifyNativeInventoryCheckpoint(owner,1));
            assertDoesNotThrow(()->escrow.verifyNativeInventoryCheckpoint(owner,2));assertEquals("DELIVERED",escrow.deliveries(owner).getFirst().state());
        }
    }
    @Test void ambiguousNativeMutationStaysQuarantinedAcrossRestartEvenWithANewerDocument(){
        Path file=directory.resolve("uncertain.data");Owner owner=Owner.player(UUID.randomUUID());
        try(var store=new LocalFileStore(file)){var escrow=new EterniaServices(store).escrow();escrow.acknowledgeNativeInventoryCheckpoint(owner,4);escrow.quarantineNativeInventory(owner,"delivery:uncertain");}
        try(var store=new LocalFileStore(file)){var escrow=new EterniaServices(store).escrow();assertThrows(DomainException.class,()->escrow.verifyNativeInventoryCheckpoint(owner,5));assertThrows(DomainException.class,()->escrow.acknowledgeNativeInventoryCheckpoint(owner,5));}
    }
    @Test void checkpointCannotRegressOrAffectAnotherPlayersInventory(){
        try(var store=new LocalFileStore(directory.resolve("owners.data"))){var escrow=new EterniaServices(store).escrow();Owner first=Owner.player(UUID.randomUUID()),second=Owner.player(UUID.randomUUID());
            escrow.acknowledgeNativeInventoryCheckpoint(first,8);assertThrows(DomainException.class,()->escrow.acknowledgeNativeInventoryCheckpoint(first,7));assertDoesNotThrow(()->escrow.verifyNativeInventoryCheckpoint(second,0));}
    }
}
