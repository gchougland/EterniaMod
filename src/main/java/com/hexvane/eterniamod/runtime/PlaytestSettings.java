package com.hexvane.eterniamod.runtime;

import java.util.Map;

/**
 * Playtest settings from {@code eternia-server.properties} or the environment.
 *
 * @param alpha          {@code ETERNIA_ALPHA=true}: everyone who joins is remembered as an alpha tester
 * @param startingCrowns {@code ETERNIA_STARTING_CROWNS}: Crowns each player gets once, the first time they join
 * @param startingCoins  {@code ETERNIA_STARTING_COINS}: Coins each player gets once, on top of the usual welcome Coins
 */
public record PlaytestSettings(boolean alpha, long startingCrowns, long startingCoins) {
    public static final PlaytestSettings OFF = new PlaytestSettings(false, 0, 0);
    static final long MOST = 10_000_000;

    public static PlaytestSettings from(Map<String, String> env) {
        String alpha = env.getOrDefault("ETERNIA_ALPHA", "false").trim().toLowerCase(java.util.Locale.ROOT);
        if (!alpha.isEmpty() && !alpha.equals("true") && !alpha.equals("false")) throw new IllegalArgumentException("ETERNIA_ALPHA must be true or false");
        return new PlaytestSettings(alpha.equals("true"), amount(env, "ETERNIA_STARTING_CROWNS"), amount(env, "ETERNIA_STARTING_COINS"));
    }

    private static long amount(Map<String, String> env, String key) {
        String text = env.getOrDefault(key, "").trim();
        if (text.isEmpty()) return 0;
        final long value;
        try { value = Long.parseLong(text); } catch (NumberFormatException notANumber) { throw new IllegalArgumentException(key + " must be a whole number"); }
        if (value < 0 || value > MOST) throw new IllegalArgumentException(key + " must be between 0 and " + MOST);
        return value;
    }

    @Override public String toString() {
        return "playtest[alpha=" + alpha + ", starting crowns=" + startingCrowns + ", starting coins=" + startingCoins + "]";
    }
}
