package com.hexvane.eterniamod.inventory;

import java.time.Duration;
import java.util.concurrent.*;

/** Never timeout/cancel the storage provider's future: it may also be its read/write barrier. */
final class NativeSaveBarrier {
    private NativeSaveBarrier() {}
    static void await(CompletableFuture<Void> storageWrite,Duration timeout) {
        try {storageWrite.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted while waiting for native inventory storage",failure);}
        catch(ExecutionException|TimeoutException failure){throw new IllegalStateException("Native inventory storage has not confirmed the save",failure);}
    }
}
