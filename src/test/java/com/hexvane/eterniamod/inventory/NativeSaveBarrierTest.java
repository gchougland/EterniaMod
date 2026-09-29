package com.hexvane.eterniamod.inventory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveBarrierTest {
    @Test void timeoutDoesNotReleaseTheStorageBarrierOrStartAQueuedReconnectLoad(){
        var storageWrite=new CompletableFuture<Void>();var loadRan=new AtomicBoolean();
        var reconnect=storageWrite.thenRun(()->loadRan.set(true));
        assertThrows(IllegalStateException.class,()->NativeSaveBarrier.await(storageWrite,Duration.ofMillis(2)));
        assertFalse(storageWrite.isDone());assertFalse(reconnect.isDone());assertFalse(loadRan.get());
        storageWrite.complete(null);reconnect.join();assertTrue(loadRan.get());
    }
    @Test void storageFailureNeverLooksLikeAConfirmedSave(){
        var failure=new IllegalStateException("disk failed");var storageWrite=CompletableFuture.<Void>failedFuture(failure);
        assertThrows(IllegalStateException.class,()->NativeSaveBarrier.await(storageWrite,Duration.ofSeconds(1)));
        assertTrue(storageWrite.isCompletedExceptionally());assertFalse(storageWrite.isCancelled());
    }
}
