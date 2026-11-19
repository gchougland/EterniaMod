package com.hexvane.eterniamod.worldborder;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.io.IOException;

/**
 * {@code /e admin border}: the world border of the world you stand in. {@code set <radius>} makes or resizes it (a new
 * border is centred where you stand), {@code centre} moves it to where you stand, {@code off} removes it and
 * {@code show} says where it is.
 */
public final class WorldBorderCommand extends AbstractCommandCollection {
    public WorldBorderCommand() {
        super("border", "Set the edge of this world that players cannot walk past");
        setPermissionGroups("hytale:WorldEditor");
        addSubCommand(new Set());
        addSubCommand(new Centre());
        addSubCommand(new Off());
        addSubCommand(new Show());
    }

    private static WorldBorders borders() {
        var plugin = EterniaModPlugin.get();
        return plugin == null ? null : plugin.getWorldBorders();
    }

    private static int[] standing(Ref<EntityStore> ref, Store<EntityStore> store) {
        var t = store.getComponent(ref, TransformComponent.getComponentType());
        var p = t.getPosition();
        return new int[] {(int) Math.floor(p.x), (int) Math.floor(p.z)};
    }

    static String describe(String world, WorldBorders.Border b) {
        return "The border of " + world + " is " + (b.radius() * 2 + 1) + " blocks across, centred on X " + b.centerX() + ", Z " + b.centerZ()
            + ". Players can walk from X " + (b.centerX() - b.radius()) + " to " + (b.centerX() + b.radius())
            + " and from Z " + (b.centerZ() - b.radius()) + " to " + (b.centerZ() + b.radius()) + ".";
    }

    private static final class Set extends AbstractPlayerCommand {
        private final RequiredArg<Integer> radius = withRequiredArg("radius", "Blocks from the centre to each side", ArgTypes.INTEGER);
        Set() { super("set", "Make or resize this world's border"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var all = borders(); if (all == null) return;
            int r = radius.get(context);
            if (r < WorldBorders.MIN_RADIUS || r > WorldBorders.MAX_RADIUS) {
                player.sendMessage(Message.raw("Choose a radius from " + WorldBorders.MIN_RADIUS + " to " + WorldBorders.MAX_RADIUS + " blocks."));
                return;
            }
            var old = all.get(world.getName()).orElse(null);
            int[] at = standing(ref, store);
            var next = old == null ? new WorldBorders.Border(at[0], at[1], r) : new WorldBorders.Border(old.centerX(), old.centerZ(), r);
            save(player, world, all, next);
        }
    }

    private static final class Centre extends AbstractPlayerCommand {
        Centre() { super("centre", "Move this world's border so it is centred where you stand"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var all = borders(); if (all == null) return;
            var old = all.get(world.getName()).orElse(null);
            if (old == null) { player.sendMessage(Message.raw("This world has no border yet. Use /e admin border set <radius> first.")); return; }
            int[] at = standing(ref, store);
            save(player, world, all, new WorldBorders.Border(at[0], at[1], old.radius()));
        }
    }

    private static final class Off extends AbstractPlayerCommand {
        Off() { super("off", "Remove this world's border"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var all = borders(); if (all == null) return;
            try {
                player.sendMessage(Message.raw(all.remove(world.getName()) ? "The border of " + world.getName() + " is gone. Players can walk anywhere." : "This world has no border."));
            } catch (IOException e) {
                player.sendMessage(Message.raw("The border could not be saved: " + e.getMessage()));
            }
        }
    }

    private static final class Show extends AbstractPlayerCommand {
        Show() { super("show", "Say where this world's border is"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var all = borders(); if (all == null) return;
            player.sendMessage(Message.raw(all.get(world.getName()).map(b -> describe(world.getName(), b)).orElse("This world has no border. Use /e admin border set <radius> to make one.")));
        }
    }

    private static void save(PlayerRef player, World world, WorldBorders all, WorldBorders.Border next) {
        try {
            all.set(world.getName(), next);
            player.sendMessage(Message.raw(describe(world.getName(), next)));
        } catch (IOException e) {
            player.sendMessage(Message.raw("The border could not be saved: " + e.getMessage()));
        }
    }
}
