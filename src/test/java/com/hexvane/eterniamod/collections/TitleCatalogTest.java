package com.hexvane.eterniamod.collections;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hexvane.eterniamod.domain.CollectionService;
import com.hexvane.eterniamod.domain.QuestTargets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Every bundled title loads like GameplayContent loads it, has player text, and reads well after a name. */
class TitleCatalogTest {
    private static final String ROOT = "Server/EterniaMod/Collections/";

    /** NPC roles shipped in the installed 0.7.6 game that title goals refer to (Server/NPC/Roles). */
    private static final Set<String> ROLES_076 = Set.of(
        "Trork_Brawler", "Trork_Chieftain", "Trork_Warrior", "Trork_Hunter", "Trork_Shaman",
        "Goblin_Scrapper", "Goblin_Miner", "Goblin_Thief", "Goblin_Gobliterator_Phase1", "Goblin_Gobliterator_Phase3",
        "Feran_Burrower", "Feran_Longtooth", "Feran_Sharptooth", "Feran_Windwalker",
        "Outlander_Berserker", "Outlander_Cultist", "Outlander_Priest", "Outlander_Marauder",
        "Skeleton", "Skeleton_Fighter", "Skeleton_Sand_Archer", "Skeleton_Pirate_Captain", "Ghoul", "Zombie", "Zombie_Frost",
        "Shadow_Knight", "Scarak_Fighter", "Scarak_Broodmother", "Scarak_Defender",
        "Void_Spawn", "Crawler_Void", "Eye_Void", "Larva_Void", "Void_Spectre",
        "Dragon_Fire", "Dragon_Frost", "Dungeon_Feastmaster_Boss", "Yeti", "Bear_Grizzly", "Bear_Polar",
        "Spider", "Spider_Cave", "Tiger_Sabertooth", "Crocodile", "Golem_Crystal_Earth", "Golem_Crystal_Frost");

    private static String read(String path) throws IOException {
        try (InputStream in = TitleCatalogTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<CollectionService.Definition> titles() throws IOException {
        var gson = new Gson();
        var titles = new ArrayList<CollectionService.Definition>();
        for (String name : read(ROOT + "catalog.index").lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList()) {
            String json = read(ROOT + name);
            assertFalse(json.startsWith("﻿"), name + " has a byte order mark");
            // Same reader and constructor checks as GameplayContent at startup.
            var definition = gson.fromJson(json, CollectionService.Definition.class);
            if (!Titles.isTitle(definition.kind())) continue;
            assertEquals(name, Titles.slug(definition.id()) + ".json", "file name follows the id");
            titles.add(definition);
        }
        return titles;
    }

    private static Map<String, String> lang() throws IOException {
        var keys = new HashMap<String, String>();
        for (String line : read("Server/Languages/en-US/" + Titles.LANG + ".lang").lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            assertTrue(eq > 0, "malformed lang line " + line);
            assertNull(keys.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim()), "duplicate lang key in " + line);
        }
        return keys;
    }

    @Test void indexHasNoDuplicatesAndTitleIdsAreUnique() throws IOException {
        var names = read(ROOT + "catalog.index").lines().filter(l -> !l.isBlank()).toList();
        assertEquals(names.size(), new HashSet<>(names).size(), "duplicate catalog.index entry");
        Set<String> ids = new HashSet<>(), texts = new HashSet<>();
        for (var title : titles()) {
            assertTrue(ids.add(title.id()), "duplicate id " + title.id());
            assertTrue(texts.add(title.assetId().toLowerCase(Locale.ROOT)), "two titles show the same text: " + title.assetId());
        }
        assertTrue(ids.size() >= 30, "expected the mob title set, found " + ids.size());
    }

    @Test void titleTextReadsWellAfterAName() throws IOException {
        for (var title : titles()) {
            String text = title.assetId();
            assertEquals(title.name(), text, title.id() + ": the card name is what the nameplate shows");
            assertEquals(text.strip(), text, title.id() + ": no outer spaces");
            assertTrue(text.length() <= Titles.MAX_TEXT, title.id() + ": too long for a card or nameplate");
            assertFalse(text.contains("-") || text.contains("–") || text.contains("—"), title.id() + ": no dashes");
            assertTrue(text.matches("[A-Za-z' ]+"), title.id() + ": letters, spaces and apostrophes only");
            if (title.kind() == CollectionService.Kind.SUFFIX_TITLE)
                assertTrue(text.startsWith("the ") || text.startsWith("of "), title.id() + ": a suffix starts with \"the\" or \"of\" so \"Name " + text + "\" reads naturally");
        }
    }

    @Test void everyTitleHasNameAndDescriptionText() throws IOException {
        var lang = lang();
        var expected = new HashSet<String>();
        for (var title : titles()) {
            String slug = Titles.slug(title.id());
            assertNotNull(slug, title.id());
            assertEquals(title.name(), lang.get(slug + ".name"), title.id() + ": lang name matches the definition");
            String description = lang.get(slug + ".description");
            assertNotNull(description, title.id() + ": missing description");
            assertTrue(description.endsWith("."), title.id() + ": description is a sentence");
            assertFalse(description.contains(" - ") || description.contains("—"), title.id() + ": no dashes in player text");
            assertEquals(Titles.LANG + "." + slug + ".description", Titles.descriptionKey(title.id()));
            expected.add(slug + ".name");
            expected.add(slug + ".description");
        }
        assertEquals(expected, lang.keySet(), "lang keys and titles match one to one");
    }

    @Test void goalsCoverEveryMobTitleAndNameRealRoles() throws IOException {
        JsonObject goals = JsonParser.parseString(read("Server/EterniaMod/Titles/goals.json")).getAsJsonObject();
        var titleIds = new HashSet<String>();
        for (var title : titles()) titleIds.add(title.id());
        var seen = new HashSet<String>();
        for (var element : goals.getAsJsonArray("titles")) {
            var goal = element.getAsJsonObject();
            String id = goal.get("title").getAsString();
            assertTrue(titleIds.contains(id), "goal for unknown title " + id);
            assertTrue(seen.add(id), "two goals for " + id);
            assertTrue(Set.of("FREE", "SEASON_PASS", "CROWN_STORE").contains(goal.get("source").getAsString()), id + ": source");
            if (!goal.has("targets")) continue;
            assertTrue(goal.get("required").getAsInt() > 0, id + ": required");
            for (var target : goal.getAsJsonArray("targets")) {
                String selector = target.getAsString();
                assertTrue(ROLES_076.stream().anyMatch(role -> QuestTargets.matches(selector, role)),
                    id + ": selector " + selector + " matches no 0.7.6 role");
            }
        }
        // Wayfarer and of the Hearth predate this set and are already placed in the Foundations pass.
        titleIds.removeAll(seen);
        assertEquals(Set.of("eternia:title/wayfarer", "eternia:title/of_the_hearth"), titleIds);
    }

    @Test void slugRejectsOtherContent() {
        assertNull(Titles.slug("eternia:pet/rootling"));
        assertNull(Titles.slug("eternia:title/../x"));
        assertEquals("trork_slayer", Titles.slug("eternia:title/trork_slayer"));
    }
}
