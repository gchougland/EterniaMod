package com.hexvane.eterniamod.ranks;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.collections.TitleColors;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.persistence.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankTest {
    private final EterniaServices services = new EterniaServices(new InMemoryStore());
    private final UUID player = UUID.randomUUID();

    @Test void aRankGivesItsTitleAndTakingItRevokesIt() {
        services.welcomePlayer(player, "Streamer");
        Owner owner = Owner.player(player);
        assertTrue(services.ranks().give(player, Rank.STREAMER.id(), Rank.STREAMER.titleId()));
        assertFalse(services.ranks().give(player, Rank.STREAMER.id(), Rank.STREAMER.titleId()), "already held");
        assertTrue(services.ownership().owns(owner, Rank.STREAMER.titleId()));
        assertEquals(Set.of("streamer"), services.ranks().ranks(player));
        assertTrue(services.ranks().take(player, Rank.STREAMER.id()));
        assertFalse(services.ownership().owns(owner, Rank.STREAMER.titleId()), "the title goes with the rank");
        assertFalse(services.ranks().take(player, Rank.STREAMER.id()));
        assertTrue(services.ranks().give(player, Rank.STREAMER.id(), Rank.STREAMER.titleId()));
        assertTrue(services.ownership().owns(owner, Rank.STREAMER.titleId()), "given again, the title comes back");
    }

    @Test void staffTitlesFollowTheirGroups() {
        services.welcomePlayer(player, "Staff");
        var staff = Map.of("admin", Rank.ADMIN.titleId(), "mod", Rank.MOD.titleId(), "builder", Rank.BUILDER.titleId());
        assertTrue(services.ranks().syncStaff(player, staff, Set.of("mod", "builder")));
        assertTrue(services.ownership().owns(Owner.player(player), Rank.MOD.titleId()));
        assertFalse(services.ranks().syncStaff(player, staff, Set.of("mod", "builder")), "nothing to do twice");
        assertTrue(services.ranks().syncStaff(player, staff, Set.of("builder")));
        assertFalse(services.ownership().owns(Owner.player(player), Rank.MOD.titleId()), "a removed moderator loses the title");
        assertTrue(services.ownership().owns(Owner.player(player), Rank.BUILDER.titleId()));
        services.ranks().give(player, Rank.FOUNDER.id(), Rank.FOUNDER.titleId());
        services.ranks().syncStaff(player, staff, Set.of());
        assertTrue(services.ownership().owns(Owner.player(player), Rank.FOUNDER.titleId()), "staff syncing never touches other ranks");
    }

    @Test void everyRankShipsATitleWithTextAndAColour() throws Exception {
        String index = Files.readString(Path.of("src/main/resources/Server/EterniaMod/Collections/catalog.index"));
        String lang = Files.readString(Path.of("src/main/resources/Server/Languages/en-US/eternia_titles.lang"));
        for (Rank rank : Rank.values()) {
            String slug = "rank_" + rank.id();
            assertTrue(index.lines().anyMatch(line -> line.equals(slug + ".json")), slug);
            String json = Files.readString(Path.of("src/main/resources/Server/EterniaMod/Collections/" + slug + ".json"));
            assertTrue(json.contains("\"id\":\"" + rank.titleId() + "\"") && json.contains("\"PREFIX_TITLE\""), slug);
            assertTrue(lang.contains(slug + ".name = " + rank.displayName()), slug);
            assertNotNull(TitleColors.get().of(rank.titleId()), slug + " has a colour");
        }
        assertEquals("#ff4d4d", TitleColors.get().of(Rank.ADMIN.titleId()), "admins are red");
        assertEquals("#a970ff", TitleColors.get().of(Rank.STREAMER.titleId()), "streamers are purple");
        assertEquals(Rank.ETERNAL, Rank.find(" Eternal ").orElseThrow());
        assertTrue(Rank.find("emperor").isEmpty());
    }

    @Test void startupNeverSavesThePermissionsFile() throws Exception {
        // Saving group permissions during setup, before the server reads permissions.json, wiped every player's groups.
        String source = Files.readString(Path.of("src/main/java/com/hexvane/eterniamod/ranks/RankPermissions.java"));
        assertFalse(source.contains("addGroupPermission"), "group permissions are registered in memory only");
        assertTrue(RankPermissions.MOD_GROUP.matches("[a-zA-Z0-9][a-zA-Z0-9_:-]*") && RankPermissions.BUILDER_GROUP.matches("[a-zA-Z0-9][a-zA-Z0-9_:-]*"),
            "group names pass the game's rule, or registering them stops the mod loading");
    }
}
