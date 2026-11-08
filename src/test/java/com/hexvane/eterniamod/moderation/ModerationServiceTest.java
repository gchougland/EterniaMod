package com.hexvane.eterniamod.moderation;

import static org.junit.jupiter.api.Assertions.*;

import com.hexvane.eterniamod.persistence.InMemoryStore;
import com.hexvane.eterniamod.persistence.LocalFileStore;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModerationServiceTest {
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-07T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration by) { now = now.plus(by); }
    }

    static final BanLadder LADDER = BanLadder.parse(new StringReader("{\"Steps\":[\"1d\",\"3d\",\"7d\",\"30d\",\"perm\"]}"));
    final MovingClock clock = new MovingClock();
    final ModerationService service = new ModerationService(new InMemoryStore(), clock, () -> LADDER);
    final UUID player = UUID.randomUUID();
    final ModerationService.Actor staff = new ModerationService.Actor(UUID.randomUUID(), "Warden");

    private ModerationService.Banned ban(BanDuration length, String reason) {
        return assertInstanceOf(ModerationService.Banned.class, service.ban(player, "Griefer", staff, length, reason));
    }

    // ---- durations ------------------------------------------------------------------------------------------------

    @Test void friendlyDurationsParse() {
        assertEquals(Duration.ofMinutes(30), BanDuration.parse("30m").orElseThrow().length());
        assertEquals(Duration.ofHours(2), BanDuration.parse("2h").orElseThrow().length());
        assertEquals(Duration.ofDays(7), BanDuration.parse("7d").orElseThrow().length());
        assertEquals(Duration.ofDays(14), BanDuration.parse("2w").orElseThrow().length());
        assertEquals(Duration.ofHours(36), BanDuration.parse("1d12h").orElseThrow().length());
        assertEquals(Duration.ofDays(3), BanDuration.parse("3 days").orElseThrow().length());
        assertEquals(Duration.ofMinutes(45), BanDuration.parse("45MIN").orElseThrow().length());
        assertTrue(BanDuration.parse("perm").orElseThrow().permanent());
        assertTrue(BanDuration.parse("Permanent").orElseThrow().permanent());
    }

    @Test void nonsenseDurationsAreRefused() {
        for (String bad : List.of("", "griefing", "0d", "2x", "d", "7d!", "12", "99999y", "1d banana")) {
            assertTrue(BanDuration.parse(bad).isEmpty(), bad);
        }
        assertTrue(BanDuration.looksLikeTime("2x"));
        assertTrue(BanDuration.looksLikeTime("perma"));
        assertFalse(BanDuration.looksLikeTime("griefing"));
    }

    @Test void durationsReadAsPlainWords() {
        assertEquals("3 days", BanDuration.parse("3d").orElseThrow().words());
        assertEquals("1 day 12 hours", BanDuration.parse("36h").orElseThrow().words());
        assertEquals("30 minutes", BanDuration.parse("30m").orElseThrow().words());
        assertEquals("permanent", BanDuration.PERMANENT.words());
        assertEquals("1d12h", BanDuration.parse("36h").orElseThrow().compact());
        assertEquals("2 days 4 hours", TimeWords.of(Duration.ofHours(52).plusMinutes(10)));
        assertEquals("1 minute", TimeWords.of(Duration.ofSeconds(60)));
        assertEquals("less than a minute", TimeWords.of(Duration.ofSeconds(20)));
    }

    // ---- ladder ---------------------------------------------------------------------------------------------------

    @Test void ladderClimbsThenStaysOnTheLastStep() {
        assertEquals(Duration.ofDays(1), LADDER.forOffence(1).length());
        assertEquals(Duration.ofDays(3), LADDER.forOffence(2).length());
        assertEquals(Duration.ofDays(7), LADDER.forOffence(3).length());
        assertEquals(Duration.ofDays(30), LADDER.forOffence(4).length());
        assertTrue(LADDER.forOffence(5).permanent());
        assertTrue(LADDER.forOffence(12).permanent());
    }

    @Test void bundledLadderMatchesTheAgreedSteps() throws Exception {
        var bundled = BanLadder.load(null, getClass().getClassLoader());
        assertEquals(List.of("1d", "3d", "7d", "30d", "perm"), bundled.steps().stream().map(BanDuration::compact).toList());
    }

    @Test void serverOverrideReplacesTheLadder(@TempDir Path data) throws Exception {
        java.nio.file.Files.writeString(data.resolve(BanLadder.OVERRIDE_FILE), "{\"Steps\":[\"2h\",\"perm\"]}");
        var ladder = BanLadder.load(data, getClass().getClassLoader());
        assertEquals(List.of("2h", "perm"), ladder.steps().stream().map(BanDuration::compact).toList());
        assertThrows(IllegalArgumentException.class, () -> BanLadder.parse(new StringReader("{\"Steps\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> BanLadder.parse(new StringReader("{\"Steps\":[\"soon\"]}")));
    }

    @Test void repeatOffencesWithoutATimeEscalate() {
        List<Duration> seen = new java.util.ArrayList<>();
        for (int offence = 1; offence <= 5; offence++) {
            var banned = ban(null, "offence " + offence);
            assertTrue(banned.fromLadder());
            assertEquals(offence, banned.ban().offence());
            seen.add(banned.length().length());
            if (!banned.ban().permanent()) clock.advance(banned.length().length().plusSeconds(1));
        }
        assertEquals(java.util.Arrays.asList(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7), Duration.ofDays(30), null), seen);
        assertTrue(service.active(player).orElseThrow().permanent());
    }

    @Test void explicitBansCountTowardTheLadder() {
        ban(BanDuration.parse("30m").orElseThrow(), "spam");
        clock.advance(Duration.ofHours(1));
        var second = ban(null, "spam again");
        assertEquals(2, second.ban().offence());
        assertEquals(Duration.ofDays(3), second.length().length());
    }

    // ---- bans, unbans, expiry and history ---------------------------------------------------------------------------

    @Test void bansExpireOnTheirOwnAndAreRecorded() {
        ban(BanDuration.parse("2h").orElseThrow(), "rude");
        clock.advance(Duration.ofMinutes(119));
        assertTrue(service.active(player).isPresent());
        clock.advance(Duration.ofMinutes(2));
        assertEquals(1, service.expireDue());
        assertTrue(service.active(player).isEmpty());
        assertEquals(0, service.expireDue());
        var history = service.history(player);
        assertEquals(List.of(ModerationService.Kind.BAN, ModerationService.Kind.EXPIRE), history.stream().map(ModerationService.Event::kind).toList());
        var expiry = history.get(1);
        assertEquals(ModerationService.SYSTEM, expiry.by().name());
        assertEquals(Instant.parse("2026-11-07T14:00:00Z"), expiry.at(), "recorded when it ended, not when it was noticed");
    }

    @Test void expiryIsNoticedAtLoginWithoutTheSweep() {
        ban(BanDuration.parse("1h").orElseThrow(), "");
        clock.advance(Duration.ofHours(2));
        assertTrue(service.active(player).isEmpty());
        assertEquals(ModerationService.Kind.EXPIRE, service.history(player).getLast().kind());
        assertTrue(service.activeBans().isEmpty());
    }

    @Test void unbanLiftsAndAuditsWhoDidIt() {
        ban(BanDuration.PERMANENT, "cheating");
        var helper = new ModerationService.Actor(UUID.randomUUID(), "Helper");
        assertTrue(service.unban(player, helper, "appeal accepted").isPresent());
        assertTrue(service.active(player).isEmpty());
        assertTrue(service.unban(player, helper, "").isEmpty(), "nothing to lift twice");
        var unban = service.history(player).getLast();
        assertEquals(ModerationService.Kind.UNBAN, unban.kind());
        assertEquals("Helper", unban.by().name());
        assertEquals(helper.id(), unban.by().id());
        assertEquals("appeal accepted", unban.reason());
        assertEquals(1, service.offences(player));
    }

    @Test void aRunningBanIsNotReplacedOrCountedTwice() {
        ban(BanDuration.parse("7d").orElseThrow(), "first");
        var again = service.ban(player, "Griefer", staff, null, "second");
        assertInstanceOf(ModerationService.AlreadyBanned.class, again);
        assertEquals(1, service.offences(player));
        assertEquals("first", service.active(player).orElseThrow().reason());
    }

    @Test void staffCannotBanThemselves() {
        assertThrows(IllegalArgumentException.class, () -> service.ban(staff.id(), "Warden", staff, null, ""));
        assertEquals(0, service.offences(staff.id()));
    }

    @Test void listsAndNamesShowOnlyRunningBans() {
        UUID other = UUID.randomUUID();
        ban(BanDuration.parse("1h").orElseThrow(), "");
        clock.advance(Duration.ofMinutes(1));
        service.ban(other, "Spammer", staff, BanDuration.PERMANENT, "spam");
        assertEquals(List.of("Spammer", "Griefer"), service.activeBans().stream().map(ModerationService.Ban::name).toList());
        assertEquals(Optional.of(other), service.findBannedByName("spammer"));
        clock.advance(Duration.ofHours(2));
        assertEquals(List.of("Spammer"), service.activeNames());
        assertEquals(List.of("Spammer"), service.activeBans().stream().map(ModerationService.Ban::name).toList());
    }

    @Test void reasonsAreTidied() {
        var banned = ban(null, "  line one\nline two  " + "x".repeat(400));
        assertFalse(banned.ban().reason().contains("\n"));
        assertTrue(banned.ban().reason().length() <= ModerationService.MAX_REASON);
    }

    @Test void bansSurviveARestart(@TempDir Path data) {
        Path file = data.resolve("authority.bin");
        try (var store = new LocalFileStore(file)) {
            new ModerationService(store, clock, () -> LADDER).ban(player, "Griefer", staff, BanDuration.parse("2d").orElseThrow(), "griefing");
        }
        try (var store = new LocalFileStore(file)) {
            var reopened = new ModerationService(store, clock, () -> LADDER);
            var ban = reopened.active(player).orElseThrow();
            assertEquals("griefing", ban.reason());
            assertEquals("Warden", ban.by().name());
        }
    }

    // ---- login refusal ----------------------------------------------------------------------------------------------

    @Test void bannedPlayerIsRefusedWithReasonAndTimeLeft() {
        ban(BanDuration.parse("2d").orElseThrow(), "griefing the town square");
        clock.advance(Duration.ofHours(20));
        var text = BanNotice.refusal(service.active(player), clock.instant(), key -> null).orElseThrow();
        assertTrue(text.contains("You are banned from Eternia."), text);
        assertTrue(text.contains("Reason: griefing the town square"), text);
        assertTrue(text.contains("Your ban ends in 1 day 4 hours."), text);
        assertFalse(text.contains(" - "), "no dashes in player text");
    }

    @Test void permanentBanSaysSo() {
        ban(BanDuration.PERMANENT, "");
        var text = BanNotice.refusal(service.active(player), clock.instant(), key -> null).orElseThrow();
        assertTrue(text.contains("This ban is permanent."), text);
        assertTrue(text.contains("No reason was given."), text);
    }

    @Test void unbannedOrExpiredPlayersAreLetIn() {
        assertTrue(BanNotice.refusal(service.active(player), clock.instant(), key -> null).isEmpty());
        ban(BanDuration.parse("1h").orElseThrow(), "");
        clock.advance(Duration.ofHours(1));
        assertTrue(BanNotice.refusal(service.active(player), clock.instant(), key -> null).isEmpty());
        ban(BanDuration.PERMANENT, "");
        service.unban(player, staff, "");
        assertTrue(BanNotice.refusal(service.active(player), clock.instant(), key -> null).isEmpty());
    }

    @Test void refusalUsesTheLanguageFileWhenItHasTheText() {
        ban(BanDuration.parse("3h").orElseThrow(), "spam");
        Map<String, String> lang = Map.of("eternia_moderation.notice.title", "Banned!", "eternia_moderation.notice.ends", "Back in {time}.");
        var text = BanNotice.refusal(service.active(player), clock.instant(), lang::get).orElseThrow();
        assertTrue(text.startsWith("Banned!\n"), text);
        assertTrue(text.contains("Back in 3 hours."), text);
        assertTrue(text.contains("Reason: spam"), "missing keys fall back to English");
    }

    @Test void languageFileHasEveryNoticeKeyAndNoDashes() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("Server/Languages/en-US/eternia_moderation.lang")) {
            assertNotNull(stream);
            String lang = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String key : BanNotice.FALLBACK.keySet()) assertTrue(lang.contains("\n" + key + " = ") || lang.startsWith(key + " = "), key);
            assertFalse(lang.contains(" - "), "player text has no dashes");
        }
    }

    @Test void reasonAfterTheTimeIsReadFromTheTypedLine() {
        assertEquals("griefing at spawn", ModerationCommands.trailing("eternia admin ban Bob 2d griefing at spawn", "ban", 2));
        assertEquals("griefing", ModerationCommands.trailing("/e admin ban Bob perm griefing", "ban", 2));
        assertEquals("", ModerationCommands.trailing("eternia admin ban Bob 2d", "ban", 2));
        assertEquals("appeal ok", ModerationCommands.trailing("eternia admin unban Bob appeal ok", "unban", 1));
        assertTrue(ModerationCommands.isLadderWord("auto"));
        assertFalse(ModerationCommands.isLadderWord("griefing"));
    }
}
