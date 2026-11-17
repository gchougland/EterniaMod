package com.hexvane.eterniamod.inventory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

class WorldInventoryBookTest {
    private static BsonDocument bag(String label) { return new BsonDocument("Hotbar", new BsonString(label)); }

    @Test void hubAndHousingShareAndEachAdventureWorldIsItsOwn() {
        assertEquals(Optional.of(WorldInventoryBook.SHARED), WorldInventoryBook.groupFor("Hub", Optional.of("hub")));
        assertEquals(Optional.of(WorldInventoryBook.SHARED), WorldInventoryBook.groupFor("Housing", Optional.of("housing")));
        assertEquals(Optional.of("world:Orbis"), WorldInventoryBook.groupFor("Orbis", Optional.of("adventure")));
        assertNotEquals(WorldInventoryBook.groupFor("Orbis", Optional.of("adventure")), WorldInventoryBook.groupFor("Aetherhaven", Optional.of("adventure")));
        assertEquals(Optional.empty(), WorldInventoryBook.groupFor("instance-crypt-1", Optional.empty()), "an instance keeps what you brought");
    }

    @Test void theFirstWorldKeepsWhatThePlayerCarries() {
        var book = new WorldInventoryBook();
        assertFalse(book.needsSwitch(WorldInventoryBook.SHARED));
        assertEquals(Optional.empty(), book.switchTo(WorldInventoryBook.SHARED, bag("unused")));
        assertEquals(WorldInventoryBook.SHARED, book.current());
        assertTrue(book.stashes().isEmpty());
    }

    @Test void inventoriesComeBackWhenYouReturnAndAreKeptInOnePlace() {
        var book = new WorldInventoryBook(WorldInventoryBook.SHARED, new BsonDocument());
        assertTrue(book.needsSwitch("world:Orbis"));
        assertEquals(Optional.empty(), book.switchTo("world:Orbis", bag("hub things")), "a new adventure world starts empty");
        assertEquals(Optional.empty(), book.switchTo("world:Aetherhaven", bag("orbis things")));
        assertEquals(Optional.of(bag("hub things")), book.switchTo(WorldInventoryBook.SHARED, bag("aether things")));
        assertFalse(book.stashes().containsKey(WorldInventoryBook.SHARED), "the carried set is not also kept in the book");
        assertEquals(bag("orbis things"), book.stashes().getDocument("world:Orbis"));
        assertEquals(Optional.of(bag("orbis things")), book.switchTo("world:Orbis", bag("hub things again")));
        assertEquals(bag("hub things again"), book.stashes().getDocument(WorldInventoryBook.SHARED));
        assertEquals(bag("aether things"), book.stashes().getDocument("world:Aetherhaven"));
    }

    @Test void stayingInTheSameGroupChangesNothing() {
        var book = new WorldInventoryBook("world:Orbis", new BsonDocument());
        assertFalse(book.needsSwitch("world:Orbis"));
        assertEquals(Optional.empty(), book.switchTo("world:Orbis", bag("x")));
        assertTrue(book.stashes().isEmpty());
    }

    @Test void aCopyDoesNotShareChanges() {
        var book = new WorldInventoryBook(WorldInventoryBook.SHARED, new BsonDocument());
        var copy = book.copy();
        copy.switchTo("world:Orbis", bag("hub"));
        assertEquals(WorldInventoryBook.SHARED, book.current());
        assertTrue(book.stashes().isEmpty());
    }
}
