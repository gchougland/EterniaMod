package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.HousingService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotCells;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Hex claims behind the same entitlement checks as {@link HousingRules} and {@link ClaimEligibility}: the hex
 * counterpart of {@link NativeClaimCoordinator#validate}. World thread.
 *
 * <ol>
 *   <li>{@link #validate}: the full preview check for a set of cells (account, world, land). Same rules as the commit.</li>
 *   <li>{@link #reserve}: validates again with fresh account data and commits the durable reservation through
 *       {@link HousingService#claimCells}. The caller then finishes the native side (see {@code hex-plots-api.md}).</li>
 * </ol>
 */
public final class HexClaimCheck {
    private HexClaimCheck() {}

    /** The hex rule kind for a claim scope. */
    public static HexClaimRules.Kind kind(HousingRules.Scope scope) {
        return switch (scope) {
            case PUBLIC -> HexClaimRules.Kind.PUBLIC;
            case GUILD_ROOT -> HexClaimRules.Kind.GUILD_ESTATE;
            case GUILD_MEMBER -> HexClaimRules.Kind.GUILD_MEMBER;
        };
    }

    /** The start shape a claim of this scope must have ({@code paid}: the larger plot entitlement). */
    public static com.hexvane.eterniamod.hexplots.HexShape startShape(HousingRules.Scope scope, boolean paid) {
        return HexPlotSettings.current().startShape(kind(scope), paid);
    }

    /** Full check of a hex claim for {@code actor}. Loads account data (domain read) then checks the land. World thread. */
    public static HexClaimRules.Result validate(EterniaModPlugin plugin, World world, UUID actor, Collection<HexCoord> cells, HousingRules.Scope scope, boolean paid) {
        return validate(plugin, world, cells, ClaimEligibility.load(plugin.getServices(), actor, scope, paid));
    }

    /** Preview overload with account data loaded earlier (off the world thread). The commit always reloads it. */
    static HexClaimRules.Result validate(EterniaModPlugin plugin, World world, Collection<HexCoord> cells, ClaimEligibility eligibility) {
        var plan = plugin.getInfrastructure().world(world.getName()).orElse(null);
        if (plan == null || !plan.supportsHousing()) return HexClaimRules.Result.of(HexClaimRules.Reason.WORLD);
        if (!plan.hexPlots()) return HexClaimRules.Result.of(HexClaimRules.Reason.NO_LATTICE);
        if (!com.hexvane.eterniamod.pathtool.SplineRoadTool.pendingRects(world.getName()).isEmpty()) return HexClaimRules.Result.of(HexClaimRules.Reason.ROAD_WORK);
        HexLandView view = HexLand.view(plugin, world).orElse(null);
        if (view == null || cells.isEmpty()) return HexClaimRules.Result.of(HexClaimRules.Reason.NO_LATTICE);
        PlotCells plotCells = PlotCells.of(view.lattice(), cells);
        HexClaimRules.Result denied = eligibility.checkCells(plotCells);
        if (denied != null) return denied;
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        // Legacy owned plots also occupy a player's slot until migrated; no duplicate free claims.
        if (eligibility.scope() != HousingRules.Scope.GUILD_ROOT && !manager.listPlotsForOwner(eligibility.actor()).isEmpty())
            return HexClaimRules.Result.of(HexClaimRules.Reason.LEGACY);
        UUID guild = eligibility.guild();
        boolean guildHasEstate = guild != null && manager.listPlots().stream().anyMatch(p -> guild.equals(p.getGuildOwnerUuid()));
        var candidate = new HexClaimRules.Candidate(eligibility.hexKind(), eligibility.paid(), eligibility.members(), guildHasEstate, plugin.getRuntimeConfig().local());
        Set<HexCoord> anchors = eligibility.scope() == HousingRules.Scope.GUILD_MEMBER ? view.guildLand(guild) : Set.of();
        return HexClaimRules.claim(view.land(), candidate, cells, anchors, HexPlotSettings.current());
    }

    /**
     * Validates with fresh account data, then commits the durable reservation ({@link HousingService#claimCells}),
     * which re-checks every cell against all housing rows inside its transaction. World thread. On success the row is
     * CLAIMING and the journal PREPARED; the native adapter must then add the plot projection, advance the journal
     * to WORLD_APPLIED and activate (exactly the tail of {@link NativeClaimCoordinator#claim}).
     *
     * <p>Packed plots are not restored here (relocation seam): a packed owner gets {@link HexClaimRules.Reason#PACKED}
     * and must go through the relocation restore path.
     */
    public static HousingService.HexOutcome reserve(EterniaModPlugin plugin, World world, UUID actor, Collection<HexCoord> cells, HousingRules.Scope scope, boolean paid, UUID propertyId, UUID operationId) {
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(operationId);
        var eligibility = ClaimEligibility.load(plugin.getServices(), actor, scope, paid);
        var verdict = validate(plugin, world, cells, eligibility);
        if (!verdict.valid()) return new HousingService.HexOutcome(verdict, null, null, 0, world.getName());
        if (eligibility.slot() != null) return new HousingService.HexOutcome(HexClaimRules.Result.of(HexClaimRules.Reason.PACKED), null, null, 0, world.getName());
        var context = HexLand.context(plugin, world).orElseThrow();
        var plotCells = PlotCells.of(context.lattice(), cells);
        UUID guild = eligibility.guild();
        UUID attachment = scope == HousingRules.Scope.GUILD_MEMBER ? guild : null;
        var location = HousingService.ClaimLocation.ofCells(world.getName(), plotCells, guild == null ? HousingService.ClaimScope.PUBLIC : HousingService.ClaimScope.GUILD, guild, false);
        var manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
        boolean guildHasEstate = guild != null && manager.listPlots().stream().anyMatch(p -> guild.equals(p.getGuildOwnerUuid()));
        var candidate = new HexClaimRules.Candidate(kind(scope), paid, eligibility.members(), guildHasEstate, plugin.getRuntimeConfig().local());
        Owner payer = scope == HousingRules.Scope.GUILD_ROOT ? eligibility.owner() : Owner.player(actor);
        return plugin.getServices().housing().claimCells(eligibility.owner(), propertyId, operationId, attachment, location, candidate, context, payer);
    }
}
