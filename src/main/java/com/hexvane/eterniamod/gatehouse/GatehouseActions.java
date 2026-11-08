package com.hexvane.eterniamod.gatehouse;

import com.hexvane.eterniamod.domain.SeasonService;
import java.util.UUID;

/** The two things Varek does at the desk itself. Runs off the world thread; every check is SeasonService's own. */
public final class GatehouseActions {
    private GatehouseActions() {}

    /** Makes a pass the one that earns XP. Progress on every other pass is kept. */
    public static String activate(SeasonService seasons, UUID actor, String seasonId) {
        var pass = definition(seasons, seasonId);
        seasons.select(actor, seasonId);
        return pass.name() + " is now your active pass. Your other passes keep their progress.";
    }

    /** Claims every reward the player has reached on one pass, free track and (when owned) premium. */
    public static String claimAll(SeasonService seasons, UUID actor, String seasonId) {
        var pass = definition(seasons, seasonId);
        var progress = seasons.progress(actor).stream().filter(p -> p.id().equals(seasonId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("That season pass is no longer available."));
        var claimed = seasons.claimedRewards(actor, pass);
        int count = 0;
        for (var reward : pass.rewards()) {
            if (reward.level() > progress.level() || claimed.contains(reward)) continue;
            if (reward.track() == SeasonService.Track.PAID && !progress.paid()) continue;
            seasons.claimReward(actor, seasonId, reward.track(), reward.level(), reward.index());
            count++;
        }
        if (count == 0) return "Nothing new to claim on " + pass.name() + " yet. Keep playing to reach the next level.";
        return count + (count == 1 ? " reward" : " rewards") + " from " + pass.name() + " added to your collection.";
    }

    private static SeasonService.Definition definition(SeasonService seasons, String seasonId) {
        return seasons.definitions().stream().filter(d -> d.id().equals(seasonId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("That season pass is no longer available."));
    }
}
