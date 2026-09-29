package com.hexvane.eterniamod.setup.paving;

import com.hexvane.eterniamod.housing.PlotRect;
import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.setup.paving.PavingRegistry.*;

class PavingRegistryTest {
    @TempDir Path temp;
    private Operation operation(){return new Operation(UUID.randomUUID(),UUID.randomUUID(),"hub",new PlotRect(0,0,4,16),2,new SnapshotFiles.Saved("before.json","a".repeat(64)),new SnapshotFiles.Saved("after.json","b".repeat(64)),State.PENDING);}
    @Test void pendingAndCompletionAndRollbackPersistAcrossRestart()throws Exception {
        var op=operation();var registry=new PavingRegistry(temp);registry.put(op);
        assertEquals(op,new PavingRegistry(temp).get(op.id()));
        registry.put(op.state(State.COMPLETE));registry=new PavingRegistry(temp);
        assertEquals(State.COMPLETE,registry.get(op.id()).state());
        assertThrows(IOException.class,()->new PavingRegistry(temp).put(op.state(State.RESTORED)));
        registry.put(op);registry.put(op.state(State.RESTORED));
        assertEquals(State.RESTORED,new PavingRegistry(temp).get(op.id()).state());
        assertThrows(IOException.class,()->new PavingRegistry(temp).put(op));
    }
    @Test void refusesChangedIdentityAndOutOfBandJournalEdit()throws Exception {
        var op=operation();var registry=new PavingRegistry(temp);registry.put(op);
        var moved=new Operation(op.id(),op.actor(),op.world(),new PlotRect(1,0,4,16),op.y(),op.before(),op.after(),State.COMPLETE);
        assertThrows(IOException.class,()->registry.put(moved));
        Files.writeString(temp.resolve("operations.json"),"{}");
        assertThrows(IOException.class,()->registry.put(op.state(State.COMPLETE)));
        assertThrows(IOException.class,()->new PavingRegistry(temp));
    }
    @Test void rectangleLimitsAndFullColumnOverlapLeaveAdjacentConnectionsUsable(){
        assertEquals(new PlotRect(-2,3,4,8),PavingRegistry.rectangle(1,10,-2,3,12));
        assertEquals(512,PavingRegistry.rectangle(0,0,127,3,0).area());
        assertThrows(IllegalArgumentException.class,()->PavingRegistry.rectangle(0,0,127,4,0));
        assertThrows(IllegalArgumentException.class,()->PavingRegistry.rectangle(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0,0));
        assertThrows(IllegalArgumentException.class,()->PavingRegistry.rectangle(0,0,2,2,317));
        var road=new PlotRect(0,0,4,16);var next=new PlotRect(0,16,4,16);
        assertDoesNotThrow(()->PavingRegistry.requireClear(next,List.of(road)));
        assertThrows(IllegalStateException.class,()->PavingRegistry.requireClear(new PlotRect(0,15,4,16),List.of(road)));
        assertThrows(IllegalStateException.class,()->PavingRegistry.requireClear(new PlotRect(5,5,2,2),List.of(new PlotRect(4,4,24,24))));
    }
    @Test void aValidSnapshotPointerCannotAuthorizeAnotherFootprintOrElevation(){
        var rect=new PlotRect(20,40,4,8);
        assertDoesNotThrow(()->PavingRegistry.requireBounds(rect,3,List.of(20,3,40,24,4,48)));
        assertThrows(IllegalArgumentException.class,()->PavingRegistry.requireBounds(rect,3,List.of(21,3,40,25,4,48)));
        assertThrows(IllegalArgumentException.class,()->PavingRegistry.requireBounds(rect,3,List.of(20,4,40,24,5,48)));
        assertThrows(IllegalArgumentException.class,()->PavingRegistry.requireBounds(rect,3,List.of(20,3,40,24,6,48)));
    }
}
