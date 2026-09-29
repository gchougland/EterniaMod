package com.hexvane.eterniamod.housing;

import java.util.*;

/** Server-authoritative topology. Existing disconnected claims retain ownership but cannot anchor expansion. */
public final class HousingRules {
    public enum Scope { PUBLIC, GUILD_ROOT, GUILD_MEMBER }
    public record Anchor(UUID id, PlotRect rect, Scope scope, UUID guildId, boolean hasHouse) {
        public Anchor { Objects.requireNonNull(id); Objects.requireNonNull(rect); Objects.requireNonNull(scope);
            if (scope != Scope.PUBLIC) Objects.requireNonNull(guildId); }
    }
    public record Candidate(PlotRect rect, Scope scope, UUID guildId, boolean upgraded, int guildMemberCount) {}
    public record Result(boolean valid, String code, String message) {
        static Result yes() { return new Result(true, "allowed", "This plot can be claimed."); }
        static Result no(String code, String message) { return new Result(false, code, message); }
    }
    private HousingRules() {}
    public static Result claim(Candidate candidate, List<Anchor> plots, List<PlotRect> roads, List<PlotRect> portals) {
        return claim(candidate,plots,roads,portals,PublicRoadNetwork.rectangles(roads));
    }
    public static Result claim(Candidate candidate,List<Anchor> plots,List<PlotRect> roads,List<PlotRect> portals,PublicRoadNetwork network) {
        Objects.requireNonNull(candidate); Objects.requireNonNull(candidate.rect); Objects.requireNonNull(candidate.scope);
        if (!allowedSize(candidate.rect, candidate.scope == Scope.GUILD_ROOT, candidate.upgraded))
            return Result.no("size", "This plot size requires the matching plot entitlement.");
        if (candidate.scope != Scope.PUBLIC && candidate.guildId == null)
            return Result.no("guild", "You must belong to this guild.");
        if (plots.stream().anyMatch(p -> p.rect.overlaps(candidate.rect))) return Result.no("overlap", "Another plot occupies this space.");
        if (portals.stream().anyMatch(p -> p.overlaps(candidate.rect))) return Result.no("portal", "Public portal land must remain clear.");
        if (candidate.scope == Scope.GUILD_ROOT) {
            if (candidate.guildMemberCount < 5) return Result.no("members", "A guild needs at least five members to claim a guild plot.");
            if (plots.stream().anyMatch(p -> p.scope == Scope.GUILD_ROOT && p.guildId.equals(candidate.guildId)))
                return Result.no("guildSlot", "This guild already has a plot.");
            if (!near(candidate.rect, portals, 240)) return Result.no("guildPortal", "Place the guild plot within 240 blocks of a hub portal.");
            if (!network.near(candidate.rect)) return Result.no("connector", "An administrator must approve a connecting road before this guild plot can be claimed.");
            return Result.yes();
        }
        if (candidate.scope == Scope.PUBLIC && !network.near(candidate.rect))
            return Result.no("road", "Place the plot within five blocks of the centerline of a public road.");
        Set<UUID> reachable = reachable(plots, portals, candidate.scope, candidate.guildId,network);
        boolean anchored = candidate.scope == Scope.PUBLIC && (near(candidate.rect, portals, 5)||network.anchored(candidate.rect,portals))
            || plots.stream().anyMatch(p -> reachable.contains(p.id) && p.rect.gap(candidate.rect) <= 5);
        if (!anchored) return Result.no("anchor", candidate.scope == Scope.PUBLIC
            ? "Use a road connected to a Hub portal, or place within five blocks of a connected public house or portal."
            : "Place the plot within five blocks of your guild house or a connected member house.");
        return Result.yes();
    }
    public static Set<UUID> reachable(List<Anchor> plots, List<PlotRect> portals, Scope scope, UUID guildId) {
        return reachable(plots,portals,scope,guildId,PublicRoadNetwork.rectangles(List.of()));
    }
    public static Set<UUID> reachable(List<Anchor> plots,List<PlotRect> portals,Scope scope,UUID guildId,PublicRoadNetwork network) {
        List<Anchor> eligible = plots.stream().filter(Anchor::hasHouse).filter(p -> scope == Scope.PUBLIC
            ? p.scope == Scope.PUBLIC : p.scope != Scope.PUBLIC && Objects.equals(p.guildId, guildId)).toList();
        Set<UUID> reached = new HashSet<>();
        for (Anchor p : eligible) if (scope == Scope.PUBLIC ? near(p.rect, portals, 5)||network.anchored(p.rect,portals) : p.scope == Scope.GUILD_ROOT && near(p.rect, portals, 240)) reached.add(p.id);
        boolean changed;
        do {
            changed = false;
            for (Anchor p : eligible) if (!reached.contains(p.id) && eligible.stream().anyMatch(a -> reached.contains(a.id) && a.rect.gap(p.rect) <= 5)) changed |= reached.add(p.id);
        } while (changed);
        return Set.copyOf(reached);
    }
    public static Result structure(PlotRect plot, PlotRect building, List<PlotRect> roads) {
        if (!plot.containsWithSetback(building, 5)) return Result.no("setback", "Houses and additions need five clear blocks inside every plot border.");
        if (roads.stream().anyMatch(r -> r.overlaps(building) || r.gap(building) < 5)) return Result.no("roadSetback", "Houses and additions need five clear blocks from roads.");
        return Result.yes();
    }
    public static boolean allowedSize(PlotRect rect, boolean guild, boolean upgraded) {
        int w = rect.width(), d = rect.depth();
        if (!guild) return w == (upgraded ? 32 : 24) && d == w;
        int square = upgraded ? 64 : 48, shortEdge = upgraded ? 32 : 36, longEdge = upgraded ? 128 : 64;
        return w == square && d == square || w == shortEdge && d == longEdge || d == shortEdge && w == longEdge;
    }
    private static boolean near(PlotRect rect, List<PlotRect> targets, int distance) { return targets.stream().anyMatch(p -> p.gap(rect) <= distance); }
}
