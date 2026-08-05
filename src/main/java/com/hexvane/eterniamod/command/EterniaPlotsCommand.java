package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModConstants;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotFootprint;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

public final class EterniaPlotsCommand extends AbstractCommandCollection {
    public EterniaPlotsCommand() {
        super("plots", "eterniamod_commands.commands.eternia.plots.desc");
        this.setPermissionGroups("hytale:WorldEditor");
        this.addSubCommand(new CreateCommand());
        this.addSubCommand(new AssignCommand());
        this.addSubCommand(new UnassignCommand());
        this.addSubCommand(new ListCommand());
        this.addSubCommand(new RemoveCommand());
    }

    private static final class CreateCommand extends AbstractPlayerCommand {
        private final RequiredArg<Integer> widthArg =
            this.withRequiredArg("width", "eterniamod_commands.commands.eternia.plots.create.width", EterniaArgTypes.PLOT_SIZE);
        private final RequiredArg<Integer> depthArg =
            this.withRequiredArg("depth", "eterniamod_commands.commands.eternia.plots.create.depth", EterniaArgTypes.PLOT_SIZE);

        CreateCommand() {
            super("create", "eterniamod_commands.commands.eternia.plots.create.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) {
                return;
            }
            int width = Math.max(4, Math.min(128, widthArg.get(context)));
            int depth = Math.max(4, Math.min(128, depthArg.get(context)));
            Vector3i block = TargetUtil.getTargetBlock(ref, 64.0, store);
            if (block == null) {
                playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.create.noTarget"));
                return;
            }
            HubPlotFootprint footprint =
                new HubPlotFootprint(
                    block.x,
                    block.y,
                    block.z,
                    block.x + width - 1,
                    block.y + EterniaModConstants.HUB_PLOT_DEFAULT_HEIGHT,
                    block.z + depth - 1
                );
            UUID plotId = UUID.randomUUID();
            HubPlotRecord plot = new HubPlotRecord(plotId, world.getName(), footprint, null);
            HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
            manager.addPlot(plot);
            manager.saveIfDirty();
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.plots.create.success")
                    .param("plotId", plotId.toString())
                    .param("width", width)
                    .param("depth", depth)
            );
        }
    }

    private static final class AssignCommand extends AbstractPlayerCommand {
        private final OptionalArg<String> plotIdArg =
            this.withOptionalArg("plotId", "eterniamod_commands.commands.eternia.plots.assign.plotId", EterniaArgTypes.PLOT_ID);
        private final RequiredArg<String> playerArg =
            this.withRequiredArg("player", "eterniamod_commands.commands.eternia.plots.assign.player", EterniaArgTypes.ONLINE_PLAYER_NAME);

        AssignCommand() {
            super("assign", "eterniamod_commands.commands.eternia.plots.assign.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) {
                return;
            }
            PlayerRef target = findPlayer(world, playerArg.get(context));
            if (target == null) {
                playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.assign.playerNotFound"));
                return;
            }
            HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
            HubPlotRecord plot = resolveAssignPlot(context, plotIdArg, store, ref, playerRef, manager);
            if (plot == null) {
                return;
            }
            plot.setOwnerUuid(target.getUuid());
            manager.updatePlot(plot);
            manager.saveIfDirty();
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.plots.assign.success")
                    .param("plotId", plot.getPlotId().toString())
                    .param("player", target.getUsername())
            );
        }
    }

    private static final class UnassignCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> plotIdArg =
            this.withRequiredArg("plotId", "eterniamod_commands.commands.eternia.plots.unassign.plotId", EterniaArgTypes.PLOT_ID);

        UnassignCommand() {
            super("unassign", "eterniamod_commands.commands.eternia.plots.unassign.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) {
                return;
            }
            UUID plotId = parsePlotId(plotIdArg.get(context), playerRef);
            if (plotId == null) {
                return;
            }
            HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
            HubPlotRecord plot = manager.getPlot(plotId);
            if (plot == null) {
                playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.plotNotFound"));
                return;
            }
            plot.setOwnerUuid(null);
            manager.updatePlot(plot);
            manager.saveIfDirty();
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.plots.unassign.success").param("plotId", plotId.toString())
            );
        }
    }

    private static final class ListCommand extends AbstractPlayerCommand {
        private final OptionalArg<String> playerArg =
            this.withOptionalArg("player", "eterniamod_commands.commands.eternia.plots.list.player", EterniaArgTypes.ONLINE_PLAYER_NAME);

        ListCommand() {
            super("list", "eterniamod_commands.commands.eternia.plots.list.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) {
                return;
            }
            HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
            UUID ownerFilter = null;
            if (playerArg.provided(context)) {
                PlayerRef target = findPlayer(world, playerArg.get(context));
                if (target == null) {
                    playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.assign.playerNotFound"));
                    return;
                }
                ownerFilter = target.getUuid();
            }
            List<HubPlotRecord> plots = playerArg.provided(context) ? manager.listPlotsForOwner(ownerFilter) : manager.listPlots();
            playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.list.header").param("count", plots.size()));
            for (HubPlotRecord plot : plots) {
                String owner = plot.getOwnerUuid() != null ? plot.getOwnerUuid().toString() : "none";
                playerRef.sendMessage(
                    Message.translation("eterniamod_commands.commands.eternia.plots.list.line")
                        .param("plotId", plot.getPlotId().toString())
                        .param("owner", owner)
                        .param("building", plot.hasBuilding() ? plot.getBuilding().getBuildingId() : "none")
                );
            }
        }
    }

    private static final class RemoveCommand extends AbstractPlayerCommand {
        private final RequiredArg<String> plotIdArg =
            this.withRequiredArg("plotId", "eterniamod_commands.commands.eternia.plots.remove.plotId", EterniaArgTypes.PLOT_ID);

        RemoveCommand() {
            super("remove", "eterniamod_commands.commands.eternia.plots.remove.desc");
        }

        @Override
        protected void execute(
            @Nonnull CommandContext context,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world
        ) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) {
                return;
            }
            UUID plotId = parsePlotId(plotIdArg.get(context), playerRef);
            if (plotId == null) {
                return;
            }
            HubPlotManager manager = EterniaWorldRegistries.getOrCreateHubPlotManager(world, plugin);
            if (!manager.removePlot(plotId)) {
                playerRef.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.plotNotFound"));
                return;
            }
            manager.saveIfDirty();
            playerRef.sendMessage(
                Message.translation("eterniamod_commands.commands.eternia.plots.remove.success").param("plotId", plotId.toString())
            );
        }
    }

    @Nullable
    private static HubPlotRecord resolveAssignPlot(
        @Nonnull CommandContext context,
        @Nonnull OptionalArg<String> plotIdArg,
        @Nonnull Store<EntityStore> store,
        @Nonnull Ref<EntityStore> ref,
        @Nonnull PlayerRef sender,
        @Nonnull HubPlotManager manager
    ) {
        if (plotIdArg.provided(context)) {
            UUID plotId = parsePlotId(plotIdArg.get(context), sender);
            if (plotId == null) {
                return null;
            }
            HubPlotRecord plot = manager.getPlot(plotId);
            if (plot == null) {
                sender.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.plotNotFound"));
            }
            return plot;
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            sender.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.assign.notInPlot"));
            return null;
        }
        Vector3d pos = transform.getPosition();
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y - 0.01);
        int z = (int) Math.floor(pos.z);
        HubPlotRecord plot = manager.findPlotContaining(x, y, z);
        if (plot == null) {
            sender.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.assign.notInPlot"));
        }
        return plot;
    }

    @Nullable
    private static UUID parsePlotId(@Nonnull String raw, @Nonnull PlayerRef sender) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Message.translation("eterniamod_commands.commands.eternia.plots.invalidPlotId"));
            return null;
        }
    }

    @Nullable
    private static PlayerRef findPlayer(@Nonnull World world, @Nonnull String username) {
        String wanted = username.trim();
        for (PlayerRef player : world.getPlayerRefs()) {
            if (wanted.equalsIgnoreCase(player.getUsername())) {
                return player;
            }
        }
        return null;
    }
}
