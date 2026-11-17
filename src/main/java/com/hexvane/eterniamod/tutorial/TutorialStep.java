package com.hexvane.eterniamod.tutorial;

import com.hexvane.eterniamod.domain.TutorialService;
import java.util.List;
import java.util.Objects;

/**
 * The one thing the Hub tutorial asks of a player right now: meet the guide, then each townsperson in turn, then return
 * to the guide. Hytale-free so the order and the arrow maths can be tested.
 *
 * @param npc    the tutorial key of the NPC to walk to
 * @param number this step's place in the walkthrough, from 1
 * @param total  how many steps there are: the guide, every townsperson here, and the guide again
 */
public record TutorialStep(Kind kind, String npc, int number, int total) {
    public enum Kind { MEET_GUIDE, MEET_NPC, RETURN_GUIDE }

    /** How many headings the arrow is drawn at; frame k points k / FRAMES of a turn clockwise from straight ahead. */
    public static final int FRAMES = 24;

    public TutorialStep { Objects.requireNonNull(kind); Objects.requireNonNull(npc); }

    /**
     * The step for a player's progress. Townspeople are visited in {@code people} order; someone met out of order
     * still counts, and the walkthrough moves on to the first one not yet met.
     */
    public static TutorialStep next(TutorialService.State state, List<String> people) {
        int total = people.size() + 2;
        if (!state.met().contains(TutorialService.GUIDE)) return new TutorialStep(Kind.MEET_GUIDE, TutorialService.GUIDE, 1, total);
        for (String npc : people) {
            if (!state.met().contains(npc)) return new TutorialStep(Kind.MEET_NPC, npc, 2 + state.townspeopleMet(people), total);
        }
        return new TutorialStep(Kind.RETURN_GUIDE, TutorialService.GUIDE, total, total);
    }

    /**
     * Which arrow frame points from a player at ({@code x}, {@code z}) facing {@code yaw} (radians, the engine's head
     * yaw: 0 faces north, toward negative Z, and it grows turning left) to a target at ({@code tx}, {@code tz}).
     */
    public static int arrowFrame(double x, double z, double yaw, double tx, double tz) {
        double dx = tx - x, dz = tz - z;
        if (dx * dx + dz * dz < 1e-6) return 0;
        double towardTarget = Math.atan2(-dx, -dz);
        double clockwise = yaw - towardTarget;
        int frame = (int) Math.round(clockwise / (2 * Math.PI / FRAMES));
        return Math.floorMod(frame, FRAMES);
    }

    /** Distance across the ground, rounded so the panel does not redraw for every step taken far away. */
    public static int shownDistance(double x, double z, double tx, double tz) {
        double blocks = Math.hypot(tx - x, tz - z);
        if (blocks < 20) return (int) Math.round(blocks);
        return (int) (Math.round(blocks / 5) * 5);
    }
}
