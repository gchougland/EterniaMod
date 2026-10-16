package com.hexvane.eterniamod.pathtool;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RoadStyleStoreTest {
    @TempDir Path directory;
    private final RoadStyle base=RoadStyle.uniform("cobble","Cobblestone","Stone");
    @Test void savedStylesSurviveRestartAndEditsKeepSelectionOrder()throws Exception{
        var file=directory.resolve("styles.json");var store=new RoadStyleStore(file,List.of(base));var custom=new RoadStyle("custom","Garden road",1,base.columns());var other=new RoadStyle("other","Town road",1,base.columns());store.save(custom,0);store.save(other,0);store.save(new RoadStyle("custom","Garden path",2,base.columns()),1);
        var reloaded=new RoadStyleStore(file,List.of(base));assertEquals(List.of("cobble","custom","other"),reloaded.all().stream().map(RoadStyle::id).toList());assertEquals("Garden path",reloaded.all().get(1).name());assertEquals(2,reloaded.all().get(1).revision());
        reloaded.save(new RoadStyle(base.id(),"Edited cobble",1,base.columns()),0);assertEquals("Edited cobble",new RoadStyleStore(file,List.of(base)).all().getFirst().name());
    }
    @Test void rejectsStaleEditorsDuplicateNamesAndExternalChanges()throws Exception{
        var file=directory.resolve("styles.json");var store=new RoadStyleStore(file,List.of(base));store.save(new RoadStyle(base.id(),base.name(),1,base.columns()),0);
        assertThrows(IOException.class,()->store.save(new RoadStyle(base.id(),"Stale",1,base.columns()),0));assertThrows(IOException.class,()->store.save(new RoadStyle("copy","cobblestone",1,base.columns()),0));
        Files.writeString(file,"{}");assertThrows(IOException.class,()->store.save(new RoadStyle("copy","New",1,base.columns()),0));
    }
    @Test void malformedDiskDataFailsWithoutOverwritingIt()throws Exception{var file=directory.resolve("styles.json");Files.writeString(file,"{\"version\":99,\"styles\":[]}");assertThrows(IOException.class,()->new RoadStyleStore(file,List.of(base)));assertTrue(Files.readString(file).contains("99"));}
}
