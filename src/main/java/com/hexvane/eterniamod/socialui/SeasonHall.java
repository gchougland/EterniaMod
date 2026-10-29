package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.domain.SeasonService;
import com.hexvane.eterniamod.ui.ContentImages;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** The Banner Hall season screens: one hanging banner per pass, and a pass page with every level on one track. */
final class SeasonHall {
    private SeasonHall() {}

    static final int TRACK_WIDTH = 900, TILE_WIDTH = 84, TILE_HEIGHT = 96;
    private static final String[] STYLES = {"Hearth", "Tide", "Ember", "Grove"};
    private static final Map<String, String> COLORS = Map.of("Hearth", "#d8b66b", "Tide", "#6fc3cf", "Ember", "#e58b5a", "Grove", "#93c47d");
    private static final Map<String, String> KNOWN = Map.of("eternia:foundations", "Hearth");

    record Banner(String id, String name, String style, boolean active, boolean paid, int level, int maxLevel, long intoLevel, long levelSpan, long toNext,
                  String nextLabel, String nextName, String nextContent, int ready) {}
    record Tile(int level, boolean free, String name, String contentId, String state, String route) {}
    record Quest(String name, String goal, long count, long total, long xp) {}
    record Screen(List<Banner> banners, Banner focus, List<Tile> tiles, List<Quest> quests) {
        static Screen hall(List<Banner> banners) { return new Screen(banners, null, List.of(), List.of()); }
    }

    /** Each pass keeps the same look; unknown passes take one of the four styles from their id. */
    static String style(String seasonId) {
        return KNOWN.getOrDefault(seasonId, STYLES[Math.floorMod(seasonId.hashCode(), STYLES.length)]);
    }

    static Banner banner(SeasonService.Definition season, SeasonService.Progress progress, Set<SeasonService.Reward> claimed, Function<String, String> names) {
        var thresholds = season.thresholds();
        int max = thresholds.size(), level = progress.level();
        long previous = level == 0 ? 0 : thresholds.get(level - 1), next = level < max ? thresholds.get(level) : previous;
        int ready = 0;
        SeasonService.Reward upcoming = null;
        for (var reward : season.rewards()) {
            boolean usable = reward.track() == SeasonService.Track.FREE || progress.paid();
            if (usable && reward.level() <= level && !claimed.contains(reward)) ready++;
            if (usable && reward.level() > level && (upcoming == null || reward.level() < upcoming.level()
                || reward.level() == upcoming.level() && reward.track() == SeasonService.Track.FREE && upcoming.track() != SeasonService.Track.FREE)) upcoming = reward;
        }
        return new Banner(season.id(), season.name(), style(season.id()), progress.active(), progress.paid(), level, max,
            progress.xp() - previous, next - previous, Math.max(0, next - progress.xp()),
            upcoming == null ? "Rewards" : "Next at level " + upcoming.level(),
            upcoming == null ? (ready > 0 ? "Ready to claim" : "All earned") : names.apply(upcoming.contentId()),
            upcoming == null ? "" : upcoming.contentId(), ready);
    }

    static List<Tile> tiles(SeasonService.Definition season, SeasonService.Progress progress, Set<SeasonService.Reward> claimed, Function<String, String> names) {
        var rewards = new ArrayList<>(season.rewards());
        rewards.sort(Comparator.comparingInt(SeasonService.Reward::level).thenComparingInt(SeasonService.Reward::index));
        var tiles = new ArrayList<Tile>();
        for (var reward : rewards) {
            boolean free = reward.track() == SeasonService.Track.FREE, done = claimed.contains(reward);
            boolean ready = !done && progress.level() >= reward.level() && (free || progress.paid());
            String state = done ? "claimed" : ready ? "ready" : !free && !progress.paid() ? "premium" : "locked";
            String name = names.apply(reward.contentId()) + (reward.quantity() > 1 ? " x" + reward.quantity() : "");
            tiles.add(new Tile(reward.level(), free, name, reward.contentId(), state, ready ? "reward:" + reward.track() + ":" + reward.level() + ":" + reward.index() : ""));
        }
        return tiles;
    }

    static Quest quest(SeasonService.QuestProgress progress, String name, String goal) {
        return new Quest(name, goal, Math.min(progress.count(), progress.quest().required()), progress.quest().required(), progress.quest().bonusXp());
    }

    /** Fills #Hall or #PassView of ServicesSeason.ui. bind(selector, route) registers a click. */
    static void render(UICommandBuilder c, Screen screen, BiConsumer<String, String> bind, boolean busy) {
        boolean pass = screen.focus() != null;
        c.set("#Hall.Visible", !pass);
        c.set("#PassView.Visible", pass);
        c.set("#Rows.Visible", false);
        c.set("#SeasonProgress.Visible", false);
        if (!pass) {
            for (int i = 0; i < screen.banners().size(); i++) {
                Banner banner = screen.banners().get(i);
                String selector = "#Hall[" + i + "]";
                c.append("#Hall", "EterniaMod/SeasonBanner.ui");
                fillBanner(c, selector, banner);
                if (!busy) bind.accept(selector, "season_view:" + banner.id());
            }
            return;
        }
        Banner focus = screen.focus();
        c.append("#PassBanner", "EterniaMod/SeasonBanner.ui");
        fillBanner(c, "#PassBanner[0]", focus);
        int levels = Math.max(1, focus.maxLevel());
        c.set("#TrackTitle.Text", "All " + levels + " levels");
        c.set("#TrackHint.Text", "Free rewards above the line, premium below");
        int markerCenter = center(Math.max(1, focus.level()), levels);
        bar(c, "#TrackFill", "#TrackRest", focus.level() == 0 ? 0 : markerCenter + 14, TRACK_WIDTH);
        c.setObject("#TrackMarker.Anchor", box(markerCenter - 14, 125, 28, 28));
        c.set("#TrackMarker.Visible", focus.level() > 0);
        for (int level = 1; level <= levels; level++) {
            if (level != 1 && level % 5 != 0 && level != levels) continue;
            String color = level <= focus.level() ? "#f0e8d5" : "#b5c3b8";
            c.appendInline("#TrackMarks", "Label { Anchor: (Left: " + (center(level, levels) - 20) + ", Top: 150, Width: 40, Height: 18); Text: \"" + level
                + "\"; Style: (FontSize: 12, TextColor: " + color + ", RenderBold: true, HorizontalAlignment: Center, VerticalAlignment: Center); }");
        }
        int[] free = place(screen.tiles(), true, levels), paid = place(screen.tiles(), false, levels);
        int freeIndex = 0, paidIndex = 0;
        Tile spotlight = null, upcoming = null;
        for (int i = 0; i < screen.tiles().size(); i++) {
            Tile tile = screen.tiles().get(i);
            int left = tile.free() ? free[freeIndex++] : paid[paidIndex++], top = tile.free() ? 14 : 190;
            String line = tile.level() <= focus.level() ? "#d8b66b" : "#45615d";
            c.appendInline("#TrackMarks", "Group { Anchor: (Left: " + (left + TILE_WIDTH / 2 - 1) + ", Top: " + (tile.free() ? 110 : 170) + ", Width: 2, Height: " + (tile.free() ? 22 : 20) + "); Background: " + line + "; }");
            String selector = "#TrackTiles[" + i + "]";
            c.append("#TrackTiles", "EterniaMod/SeasonTile.ui");
            c.setObject(selector + ".Anchor", box(left, top, TILE_WIDTH, TILE_HEIGHT));
            ContentImages.row(c, selector, tile.contentId());
            c.set(selector + " #TileName.Text", tile.name());
            c.set(selector + " #TileTag.Text", switch (tile.state()) { case "claimed" -> "Claimed"; case "ready" -> "Claim"; case "premium" -> "Premium"; default -> "Level " + tile.level(); });
            c.set(selector + " #TileGlow.Visible", tile.state().equals("ready"));
            c.set(selector + " #TileButton.Disabled", busy || tile.route().isEmpty());
            if (!tile.route().isEmpty()) bind.accept(selector + " #TileButton", tile.route());
            if (spotlight == null && tile.state().equals("ready")) spotlight = tile;
            if (upcoming == null && (tile.state().equals("locked") || tile.state().equals("premium")) && tile.level() > focus.level()) upcoming = tile;
        }
        Tile shown = spotlight != null ? spotlight : upcoming;
        c.set("#SpotClaim.Visible", spotlight != null);
        c.set("#SpotClaim.Disabled", busy);
        if (spotlight != null) bind.accept("#SpotClaim", spotlight.route());
        c.set("#SpotKicker.Text", shown == null ? "Rewards" : (spotlight != null ? "Ready to claim" : "Next reward") + " · Level " + shown.level() + " · " + (shown.free() ? "Free" : "Premium"));
        c.set("#SpotName.Text", shown == null ? "Every reward claimed" : shown.name());
        ContentImages.row(c, "#SpotReward", shown == null ? "" : shown.contentId());
        c.set("#SpotDetail.Text", shown == null ? "Well done. Your rewards are in your collection."
            : spotlight != null ? "It goes straight to your collection."
            : shown.state().equals("premium") ? "Needs the premium pass."
            : (shown.level() - focus.level()) + (shown.level() - focus.level() == 1 ? " level to go." : " levels to go."));
        c.set("#QuestReset.Text", "Dailies reset at 00:00 UTC");
        var quests = screen.quests();
        if (quests.isEmpty()) c.appendInline("#QuestLines", "Label { Anchor: (Height: 24); Text: \"No quests for this pass yet.\"; Style: (FontSize: 14, TextColor: #b5c3b8); }");
        for (int i = 0; i < quests.size(); i++) {
            Quest quest = quests.get(i);
            String selector = "#QuestLines[" + i + "]";
            boolean done = quest.count() >= quest.total();
            c.append("#QuestLines", "EterniaMod/SeasonQuestLine.ui");
            c.set(selector + " #QstName.Text", quest.name());
            c.set(selector + " #QstGoal.Text", quest.goal());
            c.set(selector + " #QstCount.Text", quest.count() + "/" + quest.total());
            c.set(selector + " #QstReward.Text", done ? "Done" : "+" + CurrencyUi.amount(quest.xp()) + " XP");
            bar(c, selector + " #QstFill", selector + " #QstRest", quest.count(), quest.total());
        }
    }

    private static void fillBanner(UICommandBuilder c, String selector, Banner banner) {
        boolean complete = banner.level() >= banner.maxLevel();
        c.setObject(selector + " #BanArt.Background", new PatchStyle(Value.of("EterniaMod/SeasonArt/Banner" + banner.style() + (banner.active() ? "Active" : "") + ".png")));
        c.set(selector + " #BanStatus.Text", (banner.active() ? "Active" : "Paused") + " · " + (banner.paid() ? "Premium" : "Free"));
        c.set(selector + " #BanName.Text", banner.name());
        c.set(selector + " #BanLevel.Text", String.valueOf(banner.level()));
        c.set(selector + " #BanMax.Text", "/ " + banner.maxLevel());
        c.setObject(selector + " #BanFill.Background", new PatchStyle().setColor(Value.of(COLORS.get(banner.style()))));
        bar(c, selector + " #BanFill", selector + " #BanRest", complete ? 1 : banner.intoLevel(), complete ? 1 : banner.levelSpan());
        c.set(selector + " #BanLine.Text", complete ? "Every level reached" : CurrencyUi.amount(banner.toNext()) + " XP to level " + (banner.level() + 1));
        c.set(selector + " #BanNextLabel.Text", banner.nextLabel());
        c.set(selector + " #BanNextName.Text", banner.nextName());
        ContentImages.row(c, selector + " #BanNext", banner.nextContent());
        c.set(selector + " #BanBadge.Visible", banner.ready() > 0);
        c.set(selector + " #BanBadge.Text", banner.ready() + " to claim");
    }

    /** Where each tile of one track sits: under its level, nudged apart so tiles never overlap. */
    static int[] place(List<Tile> tiles, boolean free, int levels) {
        var lefts = new ArrayList<Integer>();
        for (Tile tile : tiles) if (tile.free() == free) lefts.add(Math.max(0, Math.min(TRACK_WIDTH - TILE_WIDTH, center(tile.level(), levels) - TILE_WIDTH / 2)));
        int[] result = lefts.stream().mapToInt(Integer::intValue).toArray();
        for (int i = 1; i < result.length; i++) result[i] = Math.max(result[i], result[i - 1] + TILE_WIDTH + 4);
        for (int i = result.length - 1; i >= 0; i--) {
            int limit = i == result.length - 1 ? TRACK_WIDTH - TILE_WIDTH : result[i + 1] - TILE_WIDTH - 4;
            result[i] = Math.max(0, Math.min(result[i], limit));
        }
        return result;
    }

    static int center(int level, int levels) {
        return levels <= 1 ? TRACK_WIDTH / 2 : 14 + Math.round((level - 1) * (TRACK_WIDTH - 28f) / (levels - 1));
    }

    private static Anchor box(int left, int top, int width, int height) {
        var anchor = UiAnchors.size(width, height);
        anchor.setLeft(Value.of(left));
        anchor.setTop(Value.of(top));
        return anchor;
    }

    private static void bar(UICommandBuilder c, String fill, String rest, long value, long total) {
        int amount = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000, (double) value / total * 1000));
        c.set(fill + ".Visible", amount > 0);
        c.set(rest + ".Visible", amount < 1000);
        c.set(fill + ".FlexWeight", Math.max(1, amount));
        c.set(rest + ".FlexWeight", Math.max(1, 1000 - amount));
    }
}
