package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.hexplots.HexLand;
import com.hexvane.eterniamod.hexplots.terraform.PlotTerraformer;
import com.hexvane.eterniamod.hexplots.terraform.TerraformLevel;
import com.hexvane.eterniamod.hexplots.terraform.TerraformSettings;
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
import java.util.Set;

/**
 * /e admin terraform: how newly claimed hexagons are shaped (docs/implementation/plot-terraform.md).
 * {@code status}, {@code mode <off|plateau|smooth|file>} and {@code try <plateau|smooth>} (shape the free hexagon you
 * stand on, without claiming it, to compare the two looks).
 */
final class TerraformCommand extends AbstractCommandCollection {
    TerraformCommand() {
        super("terraform", "How newly claimed hexagons are shaped");
        setPermissionGroups("hytale:WorldEditor");
        addSubCommand(new Status());
        addSubCommand(new Mode());
        addSubCommand(new Try());
    }

    static String describe() {
        var s = TerraformSettings.current();
        return "Plot shaping: " + s.mode().displayName() + (TerraformSettings.overridden() ? " (chosen by an admin)" : " (from PlotTerraform.json)")
            + ". Blend reach " + s.skirtWidth() + ", edge ease " + s.blendWidth() + ", road clearance " + s.roadClearance() + ".";
    }

    private static final class Status extends AbstractPlayerCommand {
        Status() { super("status", "Show how new hexagons are shaped"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            player.sendMessage(Message.raw(describe()));
        }
    }

    private static final class Mode extends AbstractPlayerCommand {
        final RequiredArg<String> mode = withRequiredArg("mode", "off, plateau, smooth, or file to use PlotTerraform.json again", ArgTypes.STRING);
        Mode() { super("mode", "Choose how new hexagons are shaped"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            String text = mode.get(context);
            TerraformSettings.Mode chosen = "file".equalsIgnoreCase(text) ? null : TerraformSettings.Mode.parse(text, null);
            if (chosen == null && !"file".equalsIgnoreCase(text)) { player.sendMessage(Message.raw("Use off, plateau, smooth or file.")); return; }
            try { TerraformSettings.choose(chosen); }
            catch (java.io.IOException e) { player.sendMessage(Message.raw("The choice works now but could not be saved: " + e.getMessage())); }
            player.sendMessage(Message.raw(describe() + " Plots claimed before keep their ground."));
        }
    }

    private static final class Try extends AbstractPlayerCommand {
        final RequiredArg<String> mode = withRequiredArg("mode", "plateau or smooth", ArgTypes.STRING);
        Try() { super("try", "Shape the free hexagon you stand on without claiming it"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            var chosen = TerraformSettings.Mode.parse(mode.get(context), null);
            if (chosen == null || chosen == TerraformSettings.Mode.OFF) { player.sendMessage(Message.raw("Use plateau or smooth.")); return; }
            var plugin = EterniaModPlugin.get();
            var view = HexLand.view(plugin, world).orElse(null);
            var transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (view == null || transform == null) { player.sendMessage(Message.raw("Stand in a world with hexagon plots.")); return; }
            var p = transform.getPosition();
            var cell = view.lattice().cellAt((int) Math.floor(p.x), (int) Math.floor(p.z));
            if (!view.isFree(cell)) { player.sendMessage(Message.raw("Stand on a free hexagon. This one is " + (view.plotAt(cell) != null ? "claimed" : "not open for claims") + ".")); return; }
            var request = new PlotTerraformer.Request(world.getName(), view.lattice(), Set.of(cell), () -> TerraformLevel.playerGroup(player.getUuid()), null);
            player.sendMessage(Message.raw("Shaping hexagon " + cell + " as " + chosen.displayName() + "..."));
            plugin.getClaims().terraformer().shapeThen(world, request, TerraformSettings.current().withMode(chosen), () -> view.isFree(cell), outcome -> {
                player.sendMessage(Message.raw(outcome.shaped()
                    ? "Hexagon " + cell + " shaped to height " + outcome.level() + " (from the " + outcome.source().name().toLowerCase(java.util.Locale.ROOT) + "): " + outcome.changed() + " columns changed, " + outcome.skipped() + " left alone."
                    : "Nothing was shaped: " + outcome.note()));
                return null;
            });
        }
    }
}
