package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalFileStoreTest {
    @TempDir Path directory;
    @Test void committedOwnershipSurvivesRestartAndSecondProcessCannotOpen(){
        Path data=directory.resolve("eternia.data");UUID player=UUID.randomUUID();
        try(var first=new LocalFileStore(data)){var services=new EterniaServices(first);services.accounts().recordAuthenticatedLogin(player,"Mira");services.ownership().grant(new OwnershipService.GrantRequest("starter",Owner.player(player),"eternia:chair",OwnershipService.Kind.QUANTITY,2,null));assertThrows(DomainException.class,()->new LocalFileStore(data));}
        try(var reopened=new LocalFileStore(data)){var services=new EterniaServices(reopened);assertEquals("Mira",services.accounts().find(player).orElseThrow().displayName());assertEquals(2,services.ownership().available(Owner.player(player),"eternia:chair"));}
    }
    @Test void callbackFailureRollsBackMemoryAndDisk()throws Exception{
        Path data=directory.resolve("eternia.data");try(var store=new LocalFileStore(data)){
            store.transaction(tx->{tx.save("test","one",0,Map.of("value","before"));return null;});byte[] before=Files.readAllBytes(data);
            assertThrows(IllegalStateException.class,()->store.transaction(tx->{tx.save("test","one",1,Map.of("value","after"));throw new IllegalStateException("stop");}));
            assertEquals("before",store.transaction(tx->tx.find("test","one").orElseThrow().value("value")));assertArrayEquals(before,Files.readAllBytes(data));
        }
    }
    @Test void failedDurableWriteDoesNotCommitMemory()throws Exception{
        Path data=directory.resolve("eternia.data");AtomicBoolean fail=new AtomicBoolean();
        try(var store=new LocalFileStore(data,()->{if(fail.get())throw new IOException("Injected disk failure");})){
            store.transaction(tx->{tx.save("test","one",0,Map.of("value","before"));return null;});fail.set(true);
            assertThrows(DomainException.class,()->store.transaction(tx->{tx.save("test","one",1,Map.of("value","after"));return null;}));
            assertEquals("before",store.transaction(tx->tx.find("test","one").orElseThrow().value("value")));
        }
        try(var reopened=new LocalFileStore(data)){assertEquals("before",reopened.transaction(tx->tx.find("test","one").orElseThrow().value("value")));}
    }
    @Test void corruptedSnapshotFailsClosed()throws Exception{
        Path data=directory.resolve("eternia.data");Files.write(data,new byte[]{1,2,3});
        assertThrows(DomainException.class,()->new LocalFileStore(data));
    }
    @Test void transactionCannotEscapeItsLifetime(){
        try(var store=new LocalFileStore(directory.resolve("eternia.data"))){var escaped=store.transaction(tx->tx);assertThrows(IllegalStateException.class,()->escaped.scan("test"));}
    }
}
