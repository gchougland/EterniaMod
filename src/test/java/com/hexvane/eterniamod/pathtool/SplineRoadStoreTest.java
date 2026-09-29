package com.hexvane.eterniamod.pathtool;

import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.hexvane.eterniamod.pathtool.SplineRoadStore.*;

class SplineRoadStoreTest {
    @TempDir Path temp;
    private Road pending(){var before=new SnapshotFiles.Saved("before.json","a".repeat(64));var after=new SnapshotFiles.Saved("after.json","b".repeat(64));var nodes=List.of(new SplineGeometry.Node(.5,1,.5),new SplineGeometry.Node(.5,1,5.5));var cells=SplineGeometry.footprint(nodes,3).stream().map(h->new SplineGeometry.Cell(h.column().x(),0,h.column().z())).toList();var version=new Version(nodes,3,"stone","Rock_Stone_Cobble",cells,Map.of(),before,after);return new Road(UUID.randomUUID(),UUID.randomUUID(),"hub",1,State.PENDING,null,new Change(before,after,cells,version));}
    @Test void durablePendingAndCompletedVersionSurviveRestart()throws Exception{var p=pending();var store=new SplineRoadStore(temp);store.put(0,p);assertEquals(p,new SplineRoadStore(temp).get(p.id()));var active=new Road(p.id(),p.actor(),p.world(),2,State.ACTIVE,p.change().next(),null);store.put(1,active);assertEquals(active,new SplineRoadStore(temp).get(p.id()));assertThrows(IOException.class,()->store.put(0,p));}
    @Test void clearanceCustodySurvivesRestartAndCannotBeDroppedFromRecovery()throws Exception{
        var legacy=pending();var old=legacy.change().next();
        assertEquals(old.cells(),old.snapshotCells(),"Legacy snapshots own only their saved ground");
        var next=new Version(old.nodes(),old.width(),old.style(),old.block(),old.cells(),old.areas(),old.before(),old.after(),old.connections(),2);
        assertEquals(old.cells().size()*3,next.snapshotCells().size());
        var pending=new Road(legacy.id(),legacy.actor(),legacy.world(),1,State.PENDING,null,new Change(old.before(),old.after(),next.snapshotCells(),next));
        var store=new SplineRoadStore(temp);store.put(0,pending);
        assertEquals(pending,new SplineRoadStore(temp).get(pending.id()));
        assertThrows(IllegalArgumentException.class,()->new Road(legacy.id(),legacy.actor(),legacy.world(),1,State.PENDING,null,new Change(old.before(),old.after(),old.cells(),next)),"A crash record must retain the cleared plants as well as paving");
    }
    @Test void tallClearanceRetainsExactCellsAndRejectsOutOfFootprintOwnership()throws Exception{
        var legacy=pending();var old=legacy.change().next();var ground=old.cells().getFirst();var tall=new SplineGeometry.Cell(ground.x(),40,ground.z());
        var next=new Version(old.nodes(),old.width(),old.style(),old.block(),old.cells(),old.areas(),old.before(),old.after(),old.connections(),2,List.of(tall));
        assertEquals(old.cells().size()*3+1,next.snapshotCells().size(),"Empty sky is not added to the journal");
        var p=new Road(legacy.id(),legacy.actor(),legacy.world(),1,State.PENDING,null,new Change(old.before(),old.after(),next.snapshotCells(),next));var store=new SplineRoadStore(temp);store.put(0,p);assertEquals(p,new SplineRoadStore(temp).get(p.id()));
        assertThrows(IllegalArgumentException.class,()->new Road(p.id(),p.actor(),p.world(),1,State.PENDING,null,new Change(old.before(),old.after(),SparseRoadSnapshots.withClearance(old.cells(),2),next)));
        for(var invalid:List.of(new SplineGeometry.Cell(100,40,100),new SplineGeometry.Cell(ground.x(),0,ground.z()),new SplineGeometry.Cell(ground.x(),320,ground.z())))assertThrows(IllegalArgumentException.class,()->new Version(old.nodes(),old.width(),old.style(),old.block(),old.cells(),old.areas(),old.before(),old.after(),old.connections(),2,List.of(invalid)));
    }
    @Test void pendingRemovalKeepsExactFootprintAcrossRestart()throws Exception{var p=pending();var store=new SplineRoadStore(temp);store.put(0,p);store.put(1,new Road(p.id(),p.actor(),p.world(),2,State.ACTIVE,p.change().next(),null));var edit=new Road(p.id(),p.actor(),p.world(),3,State.PENDING,p.change().next(),new Change(p.change().after(),p.change().before(),p.change().cells(),null));store.put(2,edit);assertEquals(edit.protectedCells(),new SplineRoadStore(temp).get(p.id()).protectedCells());}
    @Test void pendingMaskCannotDropOldRoadColumns(){var p=pending();assertThrows(IllegalArgumentException.class,()->new Road(p.id(),p.actor(),p.world(),3,State.PENDING,p.change().next(),new Change(p.change().before(),p.change().after(),List.of(new SplineGeometry.Cell(4,0,6)),null)));}
    @Test void missingPointerWithGenerationFailsClosed()throws Exception{var store=new SplineRoadStore(temp);store.put(0,pending());Files.delete(temp.resolve("current.pointer"));assertThrows(IOException.class,()->new SplineRoadStore(temp));}
    @Test void corruptGenerationCannotBecomeAnEmptyRegistry()throws Exception{var store=new SplineRoadStore(temp);store.put(0,pending());Path generation;try(var files=Files.list(temp)){generation=files.filter(p->p.getFileName().toString().startsWith("roads-")).findFirst().orElseThrow();}Files.writeString(generation,"{}");assertThrows(IOException.class,()->new SplineRoadStore(temp));}
    @Test void externalAuthorityChangeAndWrongRevisionFailClosed()throws Exception{var p=pending();var store=new SplineRoadStore(temp);store.put(0,p);Files.writeString(temp.resolve("current.pointer"),"{}");assertThrows(IOException.class,()->store.put(1,new Road(p.id(),p.actor(),p.world(),2,State.ACTIVE,p.change().next(),null)));}
}
