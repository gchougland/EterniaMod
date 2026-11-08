package com.hexvane.eterniamod.claimmap;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.housing.HexClaimCheck;
import com.hexvane.eterniamod.housing.HousingRules;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The claim map's only way to change land: claim a start plot, or add one hexagon to a plot. The real commits live in
 * the plots engine ({@code NativeClaimCoordinator.claimCells / expandPlot}); they are wired in at plugin setup with
 * one line (see {@link #adapt}). Until then a development stand in checks the claim with the same rules and changes
 * nothing.
 *
 * <p>Both methods may be called from any thread; their futures complete on the world thread and never fail
 * (failures come back as a {@link Result} with a friendly message).
 */
public interface HexClaimCommits {
    /** Validates again at commit, reserves, runs the native world tail, completes on the world thread. */
    CompletableFuture<Result> claimCells(PlayerRef player, World world, PlotCells cells, HexClaimRules.Kind kind);

    /** Adds one side sharing cell to the player's plot (or their guild estate when allowed), charging the price. */
    CompletableFuture<Result> expandPlot(PlayerRef player, World world, String plotId, HexCoord cell);

    /**
     * What a commit did. {@code message} is the friendly line for the page (the commit has already told the player in
     * chat); {@code plotId} is the plot's id text when land changed hands; {@code reason} explains a refusal.
     */
    record Result(boolean ok, Message message, String plotId, HexClaimRules.Result reason) {
        public Result {
            Objects.requireNonNull(message);
        }
    }

    /** The commits in use. */
    static HexClaimCommits current() { return Holder.current; }

    /** Plugin setup: install the real commits. */
    static void install(HexClaimCommits commits) { Holder.current = Objects.requireNonNull(commits); }

    /**
     * Wraps the plots engine's two methods and result type, so plugin setup needs one line:
     * <pre>{@code HexClaimCommits.install(HexClaimCommits.adapt(claims::claimCells, claims::expandPlot,
     *     r -> new HexClaimCommits.Result(r.ok(), r.message(), r.plotId(), r.reason())));}</pre>
     */
    static <R> HexClaimCommits adapt(Claim<R> claim, Expand<R> expand, Function<R, Result> result) {
        return new HexClaimCommits() {
            @Override public CompletableFuture<Result> claimCells(PlayerRef player, World world, PlotCells cells, HexClaimRules.Kind kind) {
                return claim.apply(player, world, cells, kind).thenApply(result);
            }

            @Override public CompletableFuture<Result> expandPlot(PlayerRef player, World world, String plotId, HexCoord cell) {
                return expand.apply(player, world, plotId, cell).thenApply(result);
            }
        };
    }

    @FunctionalInterface interface Claim<R> { CompletableFuture<R> apply(PlayerRef player, World world, PlotCells cells, HexClaimRules.Kind kind); }

    @FunctionalInterface interface Expand<R> { CompletableFuture<R> apply(PlayerRef player, World world, String plotId, HexCoord cell); }

    final class Holder {
        private static volatile HexClaimCommits current = new Development();

        private Holder() {}
    }

    /**
     * Development stand in: checks the claim with {@link HexClaimCheck#validate} (the commit's own rules) and replies
     * that claiming is being finished, without touching the world or any account.
     */
    final class Development implements HexClaimCommits {
        static final String FINISHING = "eternia_hexplots.map.dev.finishing";

        @Override public CompletableFuture<Result> claimCells(PlayerRef player, World world, PlotCells cells, HexClaimRules.Kind kind) {
            return onWorld(world, () -> {
                EterniaModPlugin plugin = EterniaModPlugin.get();
                if (plugin == null) return refused(HexClaimRules.Result.of(HexClaimRules.Reason.WORLD));
                HousingRules.Scope scope = switch (kind) {
                    case PUBLIC -> HousingRules.Scope.PUBLIC;
                    case GUILD_ESTATE -> HousingRules.Scope.GUILD_ROOT;
                    case GUILD_MEMBER -> HousingRules.Scope.GUILD_MEMBER;
                };
                boolean paid = cells.size() > com.hexvane.eterniamod.hexplots.HexPlotSettings.current().startCells(kind, false);
                var verdict = HexClaimCheck.validate(plugin, world, player.getUuid(), cells.cells(), scope, paid);
                return verdict.valid() ? finishing(verdict) : refused(verdict);
            });
        }

        @Override public CompletableFuture<Result> expandPlot(PlayerRef player, World world, String plotId, HexCoord cell) {
            return onWorld(world, () -> {
                var view = HexLand.view(world).orElse(null);
                if (view == null) return refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_LATTICE));
                var plot = view.plots().get(java.util.UUID.fromString(plotId));
                if (plot == null) return refused(HexClaimRules.Result.of(HexClaimRules.Reason.NO_PLOT));
                var kind = com.hexvane.eterniamod.hexplots.HexLandView.kindOf(plot);
                var verdict = HexClaimRules.expand(view.land(), kind, plot.cells().asSet(), cell, com.hexvane.eterniamod.hexplots.HexPlotSettings.current());
                return verdict.valid() ? finishing(verdict) : refused(verdict);
            });
        }

        private static Result finishing(HexClaimRules.Result verdict) { return new Result(false, Message.translation(FINISHING), null, verdict); }

        private static Result refused(HexClaimRules.Result verdict) { return new Result(false, HexLand.message(verdict), null, verdict); }

        private static CompletableFuture<Result> onWorld(World world, java.util.function.Supplier<Result> work) {
            CompletableFuture<Result> out = new CompletableFuture<>();
            world.execute(() -> {
                try {
                    out.complete(work.get());
                } catch (RuntimeException failure) {
                    out.complete(refused(HexClaimRules.Result.of(HexClaimRules.Reason.CHANGED)));
                }
            });
            return out;
        }
    }
}
