package com.hexvane.eterniamod.gatehouse;

import com.hexvane.eterniamod.domain.SeasonService;
import com.hexvane.eterniamod.socialui.SeasonHall;
import com.hexvane.eterniamod.ui.UiPresentation;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * What Varek's desk at the Gatehouse shows: every season pass as a banner, which one earns XP, the active pass's
 * bounties (its daily and weekly quests) and the way to the premium track. Built from SeasonService data only;
 * SeasonService itself is read, never changed here.
 */
public record GatehouseBoard(String greeting, List<SeasonHall.Banner> passes, Bounties bounties, Premium premium) {

    /** One quest on the board, with its reward and how far along the player is. */
    public record Bounty(String name, String goal, long count, long total, long xp, long coins, boolean done) {}

    /** The active pass's quests, split the way they reset. Null on a board with no active pass. */
    public record Bounties(String passId, String passName, List<Bounty> daily, List<Bounty> weekly, List<Bounty> lasting,
                           String dailyReset, String weeklyReset) {
        public int open() { return (int) all().filter(bounty -> !bounty.done()).count(); }
        public int size() { return (int) all().count(); }
        private java.util.stream.Stream<Bounty> all() { return java.util.stream.Stream.of(daily, weekly, lasting).flatMap(List::stream); }
    }

    /**
     * The premium track card for the active pass, or for the first pass when none is active. {@code saleNote} is a
     * sentence about when it can be bought and {@code closedLabel} the button text while it cannot ("" when on sale);
     * both stay empty until season sales are merged (see notes/gatehouse.md, REQUESTS).
     */
    public record Premium(String passId, String passName, boolean owned, String saleNote, String closedLabel) {
        public Premium(String passId, String passName, boolean owned) { this(passId, passName, owned, "", ""); }
        public boolean onSale() { return closedLabel.isEmpty(); }
        public Premium sale(String note, String closed) { return new Premium(passId, passName, owned, note == null ? "" : note, closed == null ? "" : closed); }
    }

    public Optional<SeasonHall.Banner> active() { return passes.stream().filter(SeasonHall.Banner::active).findFirst(); }
    public int readyToClaim() { return passes.stream().mapToInt(SeasonHall.Banner::ready).sum(); }

    /** Reads the player's seasons. Runs off the world thread, inside the page's store batch. */
    public static GatehouseBoard load(SeasonService seasons, Function<String, String> names, UUID actor, Instant now) {
        var definitions = seasons.definitions();
        var progress = seasons.progress(actor);
        var claimed = new HashMap<String, Set<SeasonService.Reward>>();
        for (var definition : definitions) claimed.put(definition.id(), seasons.claimedRewards(actor, definition));
        var active = progress.stream().filter(SeasonService.Progress::active).findFirst();
        List<SeasonService.QuestProgress> quests = active.isPresent() && definitions.stream().anyMatch(d -> d.id().equals(active.get().id()))
            ? seasons.quests(actor, active.get().id()) : List.of();
        var board = of(definitions, progress, id -> claimed.getOrDefault(id, Set.of()), quests, names, now);
        if (board.premium == null || board.premium.owned()) return board;
        var status = seasons.saleStatus(board.premium.passId(), now);
        var premium = board.premium.sale(com.hexvane.eterniamod.seasonsales.SeasonSaleText.note(status),
            status.purchasable() ? "" : com.hexvane.eterniamod.seasonsales.SeasonSaleText.closedButton(status.state()));
        return new GatehouseBoard(board.greeting, board.passes, board.bounties, premium);
    }

    /** Pure: the same data in, the same board out. */
    public static GatehouseBoard of(List<SeasonService.Definition> definitions, List<SeasonService.Progress> progress,
                                    Function<String, Set<SeasonService.Reward>> claimed, List<SeasonService.QuestProgress> quests,
                                    Function<String, String> names, Instant now) {
        var byId = new HashMap<String, SeasonService.Definition>();
        definitions.forEach(definition -> byId.put(definition.id(), definition));
        var passes = new ArrayList<SeasonHall.Banner>();
        SeasonService.Progress active = null;
        for (var pass : progress) {
            var definition = byId.get(pass.id());
            if (definition == null) continue;
            passes.add(SeasonHall.banner(definition, pass, claimed.apply(pass.id()), names));
            if (pass.active()) active = pass;
        }
        Bounties bounties = null;
        if (active != null) {
            var daily = new ArrayList<Bounty>(); var weekly = new ArrayList<Bounty>(); var lasting = new ArrayList<Bounty>();
            for (var quest : quests) {
                var q = quest.quest();
                var bounty = new Bounty(q.name().isBlank() ? UiPresentation.questTitle(q.activity()) : q.name(), UiPresentation.questGoal(q),
                    Math.min(quest.count(), q.required()), q.required(), q.bonusXp(), q.coins(), quest.completed() || quest.count() >= q.required());
                (switch (q.cadence()) { case DAILY -> daily; case WEEKLY -> weekly; case ONCE -> lasting; }).add(bounty);
            }
            Comparator<Bounty> openFirst = Comparator.comparing(Bounty::done);
            daily.sort(openFirst); weekly.sort(openFirst); lasting.sort(openFirst);
            bounties = new Bounties(active.id(), active.name(), List.copyOf(daily), List.copyOf(weekly), List.copyOf(lasting),
                "New in " + until(now, SeasonService.resetsAt(SeasonService.Cadence.DAILY, now)),
                "New in " + until(now, SeasonService.resetsAt(SeasonService.Cadence.WEEKLY, now)));
        }
        SeasonHall.Banner premiumPass = passes.stream().filter(SeasonHall.Banner::active).findFirst().orElse(passes.isEmpty() ? null : passes.getFirst());
        Premium premium = premiumPass == null ? null : new Premium(premiumPass.id(), premiumPass.name(), premiumPass.paid());
        var board = new GatehouseBoard("", List.copyOf(passes), bounties, premium);
        return new GatehouseBoard(greeting(board), board.passes, bounties, premium);
    }

    /** Varek's welcome changes with what the player most needs to hear. */
    static String greeting(GatehouseBoard board) {
        if (board.passes.isEmpty())
            return "Welcome to the Gatehouse. The road beyond this gate is quiet for now. When a new season opens, its pass will be waiting for you here.";
        int ready = board.readyToClaim();
        if (ready > 0)
            return "Good to see you back. You have " + ready + (ready == 1 ? " reward" : " rewards") + " waiting. Claim them below, or open a banner to see every level.";
        var active = board.active();
        if (active.isEmpty())
            return "Welcome to the Gatehouse. One day this gate will open on the road to Castle Aeterna. Until then I keep the season passes. Choose one to start earning XP.";
        return "Everything you do out there counts toward " + active.get().name() + ". Take a bounty from the board, or switch passes whenever you like.";
    }

    /** "5 hours", "40 minutes", "3 days": the larger unit, rounded up, never zero. */
    static String until(Instant now, Instant at) {
        long minutes = Math.max(1, (Duration.between(now, at).toSeconds() + 59) / 60);
        if (minutes < 60) return minutes + (minutes == 1 ? " minute" : " minutes");
        long hours = (minutes + 59) / 60;
        if (hours < 48) return hours + (hours == 1 ? " hour" : " hours");
        long days = (hours + 23) / 24;
        return days + " days";
    }
}
