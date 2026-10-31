package com.hexvane.eterniamod.socialui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JournalKeyTest {
    @Test void adminsKeepGameModeUnlessCrouching() {
        assertFalse(JournalKey.opensJournal(true,false));
        assertTrue(JournalKey.opensJournal(true,true));
    }
    @Test void playersWithoutGameModeAlwaysOpenTheJournal() {
        assertTrue(JournalKey.opensJournal(false,false));
        assertTrue(JournalKey.opensJournal(false,true));
    }
}
