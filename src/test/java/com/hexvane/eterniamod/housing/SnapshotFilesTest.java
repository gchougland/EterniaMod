package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.housing.relocation.SnapshotFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

final class SnapshotFilesTest {
    @TempDir Path temporary;
    @Test void refusesTamperedSnapshotInsteadOfReturningEmptyTerrain()throws Exception{
        var files=new SnapshotFiles(temporary);var saved=files.write("home.json","original container contents".getBytes(StandardCharsets.UTF_8));
        Files.writeString(files.resolve(saved.reference()),"different contents");
        assertThrows(IOException.class,()->files.read(saved));
    }
    @Test void immutableWriteSurvivesNewStoreAndRejectsConflictingReplay()throws Exception{
        var files=new SnapshotFiles(temporary);byte[] state={0,1,2,3};var saved=files.write("home.json",state);
        assertArrayEquals(state,new SnapshotFiles(temporary).read(saved));
        assertEquals(saved,files.write("home.json",state));
        assertThrows(IOException.class,()->files.write("home.json",new byte[]{4}));
        assertArrayEquals(state,files.read(saved));
    }
    @Test void incompleteWriteIsNeverTreatedAsCommittedSnapshot()throws Exception{
        Files.writeString(temporary.resolve("home.json.pending"),"interrupted partial data");var files=new SnapshotFiles(temporary);
        assertThrows(IOException.class,()->files.write("home.json",new byte[]{1}));
        assertFalse(Files.exists(temporary.resolve("home.json")));
    }
    @Test void referencesCannotEscapeSnapshotDirectory()throws Exception{
        var files=new SnapshotFiles(temporary);
        assertThrows(IOException.class,()->files.resolve("../other.json"));
        assertThrows(IOException.class,()->files.write("../other.json",new byte[]{1}));
    }
}
