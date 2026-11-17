package com.hexvane.eterniamod.production;

import java.util.ArrayList;
import java.util.List;

/**
 * What a whole plot remembers about production, beside its stations: Seals and the Estate tree, the daily streak, the
 * day's hand work energy and today's orders. Plain data with its small rules, so it is unit tested.
 */
public final class PlotProduction {
    public static final int ESTATE_RANKS = 10;

    public int seals;
    /** Estate tree ranks in path order: bounty, fortune, vigor. */
    public final int[] estate = new int[3];
    public int streak;
    /** Day number (days since 1970, server time) of the last collect, 0 for never. */
    public long lastCollectDay;
    public long energyDay;
    public int energyUsed;
    public long ordersDay;
    public final List<Order> orders = new ArrayList<>();
    public int goldenCaught;
    /** Hexagon roles in {@link PlotRoles#text()} form, empty until the plot is first looked at. */
    public String roles = "";

    /** One of today's jobs. {@code kind} is collect, golden, levels or hand. */
    public static final class Order {
        public String kind;
        public double goal;
        public double done;
        public int seals;
        public boolean paid;

        public Order() {}

        public Order(String kind, double goal, int seals) { this.kind = kind; this.goal = goal; this.seals = seals; }

        public boolean complete() { return done >= goal; }
    }

    /** Counts a collect on {@code day}. Returns true when it is the plot's first collect of that day. */
    public boolean countCollect(long day) {
        if (day == lastCollectDay) return false;
        long missed = lastCollectDay == 0 ? 0 : day - lastCollectDay - 1;
        streak = Math.max(0, streak - 2 * (int) Math.clamp(missed, 0, 1000)) + 1;
        lastCollectDay = day;
        return true;
    }

    public int energyLeft(ProductionRules r, long day) {
        if (day != energyDay) { energyDay = day; energyUsed = 0; }
        return Math.max(0, r.dailyEnergy() + 30 * estate[2] - energyUsed);
    }

    public boolean spendEnergy(ProductionRules r, long day) {
        if (energyLeft(r, day) <= 0) return false;
        energyUsed++;
        return true;
    }

    public static int estateCost(int rank) { return rank + 1; }

    public boolean buyEstate(int branch) {
        if (branch < 0 || branch > 2 || estate[branch] >= ESTATE_RANKS) return false;
        int cost = estateCost(estate[branch]);
        if (cost > seals) return false;
        seals -= cost;
        estate[branch]++;
        return true;
    }

    /** Deals today's three orders if the day changed. {@code perMinute} is the plot's best station, to size the goal. */
    public void dealOrders(long day, double perMinute) {
        if (day == ordersDay && !orders.isEmpty()) return;
        ordersDay = day;
        orders.clear();
        orders.add(new Order("collect", Math.max(50, Math.ceil(perMinute * 20)), 2));
        orders.add(new Order("golden", 2, 1));
        orders.add(new Order(day % 2 == 0 ? "levels" : "hand", day % 2 == 0 ? 10 : 40, 1));
    }

    /** Adds progress to every unfinished order of {@code kind} and pays the ones it completes. Returns Seals paid. */
    public int progress(String kind, double amount) {
        int paid = 0;
        for (Order order : orders) {
            if (!order.kind.equals(kind) || order.paid) continue;
            order.done = Math.min(order.goal, order.done + amount);
            if (order.complete()) { order.paid = true; seals += order.seals; paid += order.seals; }
        }
        return paid;
    }

    public static String orderText(Order order, String unit) {
        return switch (order.kind) {
            case "collect" -> "Collect " + ProductionMath.format(order.goal) + " " + unit;
            case "golden" -> "Catch " + (int) order.goal + " golden moments";
            case "levels" -> "Buy " + (int) order.goal + " levels";
            default -> "Work a station by hand " + (int) order.goal + " times";
        };
    }
}
