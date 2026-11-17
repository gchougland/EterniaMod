package com.hexvane.eterniamod.production;

/**
 * Tuning shared by every station. Level prices come from {@code tierHours}: how long each tier should take a station
 * with no boosts, with the last level of a tier taking {@code tierSpread} times as long as its first
 * ({@code levelCostBase} and {@code levelCostGrowth} are no longer read).
 *
 * <p>Tuning shared by every station. One place to turn the knobs; nothing here is per profession (that lives in the
 * profession's own definition file). Values are the design's starting numbers, with the level cost growth lowered from
 * 12% to 7% so a doubling every milestone does not leave the last tiers out of reach.
 */
public record ProductionRules(
    double levelCostBase,
    double levelCostGrowth,
    int[] milestones,
    double stockpileMinutes,
    int toolRanks,
    double toolCostBase,
    double toolCostGrowth,
    double paceStep,
    double haulStep,
    double storesStep,
    double fortuneStep,
    double veinChance,
    int veinCycles,
    double veinBoost,
    double findsPerHour,
    double findBonus,
    int renownLevel,
    double renownBonus,
    double fullCartBonus,
    double streakStep,
    int streakDays,
    double handShare,
    double rushStep,
    double rushBoost,
    double rushSeconds,
    int dailyEnergy,
    double goldenMinSeconds,
    double goldenMaxSeconds,
    double goldenWindowSeconds,
    double windfallMinutes,
    double frenzyBoost,
    double frenzySeconds,
    int setSize,
    double pairBonus,
    double reshuffleCostCycles,
    double[] tierHours,
    double tierSpread) {

    public static final int PACE = 0, HAUL = 1, STORES = 2, FORTUNE = 3;

    public static final ProductionRules DEFAULT = new ProductionRules(
        10, 1.07, new int[] {10, 25, 50, 75, 100, 150, 200},
        30, 10, 100, 3,
        0.04, 0.10, 1.5, 0.10,
        0.02, 10, 3,
        1, 0.01,
        100, 0.5,
        0.10, 0.03, 7,
        0.5, 0.05, 2, 60, 300,
        300, 900, 30, 10, 7, 30,
        3, 0.20, 600,
        // Hours of plain work each tier takes, from its first level to the next milestone. Tools, talents, the
        // Estate, veins and hand work all speed this up, more and more as a station grows, so the real hours are
        // lower: about 60 hours of play to reach the last tier, a month at two hours a day.
        new double[] {0.4, 2.1, 7.2, 15, 28, 90, 154}, 4);

    public int tiers() { return milestones.length + 1; }
}
