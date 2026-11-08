package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.debug.DebugLineCylinderUtil;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.hexplots.HexClaimRules;
import com.hexvane.eterniamod.hexplots.HexCoord;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.HexLandView;
import com.hexvane.eterniamod.hexplots.HexLattice;
import com.hexvane.eterniamod.hexplots.HexPlotSettings;
import com.hexvane.eterniamod.hexplots.PlotShape;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Locale;
import java.util.Set;
import org.joml.Vector3f;

/**
 * {@code /e admin setup lattice show|here|origin|hex|rect}: preview and change a world's hexagon grid and plot shape.
 * Changes go through the normal setup review and confirm; {@link InfrastructureRules} refuses them while the world has
 * any plot or unfinished claim. Admin only, world thread.
 */
public final class LatticeSetup {
    private static final int PREVIEW_RADIUS = 2;

    private LatticeSetup() {}

    public static void run(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, String mode) {
        SetupAccess.require(player.getUuid());
        World world = store.getExternalData().getWorld();
        var snapshot = plugin.getInfrastructure().snapshot();
        var plan = snapshot.worlds().get(world.getName());
        if (plan == null) throw input("Choose this world's role in /e admin setup first.");
        var pos = position(ref, store);
        HexLattice current = plan.hexLattice().orElse(HexPlotSettings.current().defaultLattice());
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "show" -> {
                draw(plugin, world, player, current, pos);
                player.sendMessage(Message.raw(describe(plugin, world, plan, current, pos)));
            }
            case "here" -> {
                HexLattice next = current.centredOn(pos[0], pos[2]);
                var after = plan.withPlots(plan.plotShape() == null && plan.supportsHousing() ? PlotShape.HEX : plan.plotShape(), next);
                InfrastructureSetup.review(plugin, ref, store, player, snapshot, plan, after,
                    "Move the hexagon grid so a cell is centred where you stand (origin X " + next.originX() + ", Z " + next.originZ() + "). Allowed only while the world has no plots.", null, pos[1]);
                draw(plugin, world, player, next, pos);
            }
            case "origin" -> {
                HexLattice next = current.withOrigin(0, 0);
                var after = plan.withPlots(plan.plotShape() == null && plan.supportsHousing() ? PlotShape.HEX : plan.plotShape(), next);
                InfrastructureSetup.review(plugin, ref, store, player, snapshot, plan, after, "Put the hexagon grid back at origin X 0, Z 0. Allowed only while the world has no plots.", null, pos[1]);
                draw(plugin, world, player, next, pos);
            }
            case "hex" -> {
                if (!plan.supportsHousing()) throw input("Turn on housing for this world first.");
                InfrastructureSetup.review(plugin, ref, store, player, snapshot, plan, plan.withPlots(PlotShape.HEX, current),
                    "Plots in this world become hexagons on a grid " + current.cellWidth() + " blocks wide (origin X " + current.originX() + ", Z " + current.originZ() + "). Allowed only while the world has no plots.", null, pos[1]);
                draw(plugin, world, player, current, pos);
            }
            case "rect" -> InfrastructureSetup.review(plugin, ref, store, player, snapshot, plan, plan.withPlots(PlotShape.RECT, plan.lattice()),
                "Plots in this world go back to rectangles. The grid is kept for later. Allowed only while the world has no plots.", null, pos[1]);
            default -> throw input("Use lattice show, here, origin, hex or rect.");
        }
    }

    private static String describe(EterniaModPlugin plugin, World world, HousingInfrastructure.WorldPlan plan, HexLattice lattice, int[] pos) {
        HexCoord here = lattice.cellAt(pos[0], pos[2]);
        var street = HexClaimRules.streetCells(lattice, plan.roads(), plan.portals());
        int plots = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin).listPlots().size();
        return "Plots here are " + (plan.hexPlots() ? "hexagons" : "rectangles") + ". Grid: " + lattice.cellWidth() + " wide, "
            + lattice.cellDepth() + " tip to tip, origin X " + lattice.originX() + ", Z " + lattice.originZ()
            + (plan.lattice() == null ? " (preview, not saved)" : "") + ". You stand in cell " + here + ". "
            + street.size() + " street cells. " + plots + " plots" + (plots > 0 ? ", so the grid is locked." : ".")
            + " Outline shown for 20 seconds: green claimable, stone street, blue claimed, amber reserved.";
    }

    /** Draws the cells around the admin as straight six sided outlines coloured by state. */
    private static void draw(EterniaModPlugin plugin, World world, PlayerRef player, HexLattice lattice, int[] pos) {
        var plan = plugin.getInfrastructure().world(world.getName()).orElse(null);
        HexLandView view = plan != null && lattice.equals(plan.lattice()) ? HexLand.view(plugin, world).orElse(null) : null;
        Set<HexCoord> street = view != null ? view.streetCells() : plan == null ? Set.of() : HexClaimRules.streetCells(lattice, plan.roads(), plan.portals());
        var viewer = HexLandView.Viewer.browsing(player.getUuid(), null);
        double y = pos[1] + .1;
        for (HexCoord cell : HexCoord.disk(lattice.cellAt(pos[0], pos[2]), PREVIEW_RADIUS)) {
            HexLandView.CellState state;
            if (view != null) {
                state = view.cell(cell, viewer, null).state();
                if (state == HexLandView.CellState.FREE && view.isFrontage(cell)) state = HexLandView.CellState.FRONTAGE_FREE;
            } else state = street.contains(cell) ? HexLandView.CellState.STREET : HexLandView.CellState.FRONTAGE_FREE;
            Vector3f color = rgb(HexLandView.suggestedColor(state));
            double[][] c = lattice.corners(cell);
            for (int i = 0; i < 6; i++) line(player, c[i][0], y, c[i][1], c[(i + 1) % 6][0], c[(i + 1) % 6][1], color);
        }
    }

    private static Vector3f rgb(int color) {
        return new Vector3f(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f);
    }

    private static void line(PlayerRef player, double x, double y, double z, double ex, double ez, Vector3f color) {
        var matrix = DebugLineCylinderUtil.segmentMatrix(x, y, z, ex, y, ez, .04, Math.hypot(ex - x, ez - z));
        if (matrix != null) player.getPacketHandler().write(new DisplayDebug(DebugShape.Cylinder, Matrix4dUtil.asFloatData(matrix), color, 20f, (byte) DebugUtils.FLAG_NO_WIREFRAME, null, .85f));
    }

    private static int[] position(Ref<EntityStore> ref, Store<EntityStore> store) {
        var transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) throw input("Your current position is unavailable.");
        var p = transform.getPosition();
        return new int[] {(int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)};
    }

    private static DomainException input(String message) { return new DomainException(DomainException.Code.INVALID_INPUT, message); }
}
