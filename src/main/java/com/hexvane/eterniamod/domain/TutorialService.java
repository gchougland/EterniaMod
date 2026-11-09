package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/**
 * The Hub tutorial: which townspeople a player has met, and whether they finished, skipped or hid the walkthrough.
 * One {@code tutorial} row per player. Meeting an NPC is recorded the first time only, so a first meeting is reported
 * once even if two interactions race.
 */
public final class TutorialService extends DomainSupport {
    static final String NAMESPACE = "tutorial";
    /** The guide, met first and last. */
    public static final String GUIDE = "greeter";
    /** The townspeople to meet, in the order the walkthrough lists them. */
    public static final List<String> TOWNSPEOPLE = List.of("rules", "housing", "gatehouse", "store", "shop", "guild", "minigame");

    public record State(Set<String> met, boolean finished, boolean skipped, boolean hidden) {
        public State { met = Set.copyOf(met); }
        static final State NEW = new State(Set.of(), false, false, false);
        public boolean metEveryone() { return metEveryone(TOWNSPEOPLE); }
        /** Met the guide and every townsperson in {@code required} (the ones who stand in this Hub). */
        public boolean metEveryone(Collection<String> required) { return met.contains(GUIDE) && met.containsAll(required); }
        /** The walkthrough panel is wanted: not finished, skipped or hidden. */
        public boolean active() { return !finished && !skipped && !hidden; }
        public int townspeopleMet() { return townspeopleMet(TOWNSPEOPLE); }
        public int townspeopleMet(Collection<String> required) { return (int) required.stream().filter(met::contains).count(); }
    }

    /** What a meeting changed: whether this was the first time, whether it finished the tutorial, and the new state. */
    public record Meeting(boolean first, boolean finishedNow, State state) {}

    TutorialService(TransactionalStore store, Clock clock, Supplier<UUID> ids) { super(store, clock, ids); }

    public State state(UUID player) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> tx.find(NAMESPACE, player.toString()).map(TutorialService::state).orElse(State.NEW));
    }

    /**
     * Records talking to {@code npc}. Talking to the guide after meeting every townsperson finishes the tutorial.
     * Unknown NPC keys are ignored.
     */
    public Meeting meet(UUID player, String npc) { return meet(player, npc, TOWNSPEOPLE); }

    /** As {@link #meet(UUID, String)}, finishing once the guide is met after everyone in {@code required}. */
    public Meeting meet(UUID player, String npc, Collection<String> required) {
        Objects.requireNonNull(player);
        var needed = List.copyOf(required);
        if (!npc.equals(GUIDE) && !TOWNSPEOPLE.contains(npc)) { State s = state(player); return new Meeting(false, false, s); }
        return store.transaction(tx -> {
            var row = tx.find(NAMESPACE, player.toString());
            State before = row.map(TutorialService::state).orElse(State.NEW);
            var met = new TreeSet<>(before.met());
            boolean first = met.add(npc);
            boolean finishedNow = npc.equals(GUIDE) && !before.finished() && !first && before.metEveryone(needed);
            State after = new State(met, before.finished() || finishedNow, before.skipped(), before.hidden());
            if (first || finishedNow) write(tx, row, player, after);
            return new Meeting(first, finishedNow, after);
        });
    }

    public State hide(UUID player, boolean hidden) { return update(player, s -> new State(s.met(), s.finished(), s.skipped(), hidden)); }
    public State skip(UUID player) { return update(player, s -> new State(s.met(), s.finished(), true, s.hidden())); }
    /** Starts over: forgets every meeting, so each NPC greets the player as new again. */
    public State restart(UUID player) { return update(player, s -> State.NEW); }

    private State update(UUID player, java.util.function.UnaryOperator<State> change) {
        Objects.requireNonNull(player);
        return store.transaction(tx -> {
            var row = tx.find(NAMESPACE, player.toString());
            State after = change.apply(row.map(TutorialService::state).orElse(State.NEW));
            write(tx, row, player, after);
            return after;
        });
    }

    private void write(TransactionalStore.Transaction tx, Optional<TransactionalStore.Row> row, UUID player, State s) {
        var data = fields("met", String.join(",", new TreeSet<>(s.met())), "finished", s.finished(), "skipped", s.skipped(), "hidden", s.hidden(), "updatedAt", clock.instant());
        tx.save(NAMESPACE, player.toString(), row.map(TransactionalStore.Row::revision).orElse(0L), data);
    }

    private static State state(TransactionalStore.Row r) {
        String met = r.value("met");
        return new State(met.isEmpty() ? Set.of() : Set.of(met.split(",")), r.value("finished").equals("true"), r.value("skipped").equals("true"), r.value("hidden").equals("true"));
    }
}
