package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
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
import java.util.UUID;

/**
 * {@code /e land}: land in adventure worlds. {@code map} and {@code guild} open the land map, {@code friends} the friends
 * page of the land you stand on (or your own here), {@code friend NAME} and {@code unfriend NAME} share it by name.
 * Staff use {@code /e land admin reload} and {@code /e land admin remove}.
 */
public final class WildLandCommand extends AbstractCommandCollection {
    public WildLandCommand() {
        super("land", "Claim and share land in adventure worlds");
        setPermissionGroups("hytale:Adventurer");
        addSubCommand(new Open("map", false));
        addSubCommand(new Open("guild", true));
        addSubCommand(new Friends());
        addSubCommand(new Share("friend", true));
        addSubCommand(new Share("unfriend", false));
        addSubCommand(new Admin());
    }

    private static final class Open extends AbstractPlayerCommand {
        private final boolean guild;
        Open(String name, boolean guild) { super(name, guild ? "Open the land map on your guild's land" : "Open the land map"); this.guild = guild; }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) return;
            if (!WildMapPage.open(plugin, ref, store, player, guild)) player.sendMessage(Message.raw("Land can only be claimed in adventure worlds."));
        }
    }

    /** The land the player stands on if they may manage it, else their own (or their guild's) land in this world. */
    static WildLandIndex.Land landFor(EterniaModPlugin plugin, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
        UUID me = player.getUuid();
        var standing = WildLandIndex.standing(plugin.getServices(), me);
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t != null) {
            var here = WildLandIndex.at(world.getName(), (int) Math.floor(t.getPosition().x), (int) Math.floor(t.getPosition().z));
            if (here != null && WildAccess.manages(here, me, standing)) return here;
        }
        for (var land : Set.copyOf(WildLandIndex.world(world.getName()).values())) if (land.owner().equals(Owner.player(me))) return land;
        for (var land : Set.copyOf(WildLandIndex.world(world.getName()).values())) if (WildAccess.manages(land, me, standing)) return land;
        return null;
    }

    private static final class Friends extends AbstractPlayerCommand {
        Friends() { super("friends", "Choose who may build on your land"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) return;
            var land = landFor(plugin, store, ref, player, world);
            if (land == null) { player.sendMessage(Message.raw("You have no land in this world. Use /e land map to claim some.")); return; }
            WildFriendsMenu.open(plugin, ref, store, player, land.id());
        }
    }

    private static final class Share extends AbstractPlayerCommand {
        private final boolean add;
        private final RequiredArg<String> name;
        Share(String command, boolean add) {
            super(command, add ? "Let a player build, open chests and use things on your land" : "Stop sharing your land with a player");
            this.add = add;
            this.name = withRequiredArg("player", "Player name", ArgTypes.STRING);
        }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) return;
            var land = landFor(plugin, store, ref, player, world);
            if (land == null) { player.sendMessage(Message.raw("You have no land in this world. Use /e land map to claim some.")); return; }
            String wanted = name.get(context);
            var account = plugin.getServices().accounts().findByName(wanted).orElse(null);
            if (account == null) { player.sendMessage(Message.raw(wanted + " has not played on Eternia yet.")); return; }
            WildLand.trust(plugin, land.id(), player.getUuid(), account.id().toString(), add ? WildPerm.ALL : Set.of()).whenComplete((done, failure) -> world.execute(() -> {
                if (failure != null) {
                    Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                    player.sendMessage(Message.raw(cause.getMessage() == null ? "That could not be saved. Try again." : cause.getMessage()));
                } else {
                    player.sendMessage(Message.raw(add ? account.displayName() + " can now build, open chests and use things on your land. Change that with /e land friends."
                        : account.displayName() + " can no longer build on your land."));
                }
            }));
        }
    }

    /** Staff only: read the settings file again, or end the claim you stand on. */
    private static final class Admin extends AbstractCommandCollection {
        Admin() {
            super("admin", "Staff tools for land in adventure worlds");
            setPermissionGroups("hytale:WorldEditor");
            addSubCommand(new Reload());
            addSubCommand(new Remove());
        }
    }

    private static final class Reload extends AbstractPlayerCommand {
        Reload() { super("reload", "Read WildLand.json again"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) return;
            com.hexvane.eterniamod.socialui.SocialUiBootstrap.supply(plugin.getServices(), () -> WildLand.reloadSettings(plugin)).whenComplete((s, failure) -> world.execute(() ->
                player.sendMessage(Message.raw(failure != null ? "WildLand.json could not be read: " + failure.getMessage()
                    : "Land settings read. Player land: up to " + s.player().maxCells() + " hexagons. Guild land: up to " + s.guild().maxCells() + ". Gap: " + s.bufferRings() + "."))));
        }
    }

    private static final class Remove extends AbstractPlayerCommand {
        Remove() { super("remove", "End the claim you stand on, without refunds"); setPermissionGroups("hytale:WorldEditor"); }
        @Override protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) return;
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            var land = t == null ? null : WildLandIndex.at(world.getName(), (int) Math.floor(t.getPosition().x), (int) Math.floor(t.getPosition().z));
            if (land == null) { player.sendMessage(Message.raw("Stand on the land you want to remove.")); return; }
            com.hexvane.eterniamod.socialui.SocialUiBootstrap.supply(plugin.getServices(), () -> {
                boolean removed = plugin.getServices().wildLand().remove(land.id());
                WildLandIndex.reload(plugin.getServices());
                return removed;
            }).whenComplete((removed, failure) -> world.execute(() ->
                player.sendMessage(Message.raw(failure == null && Boolean.TRUE.equals(removed) ? land.ownerName() + "'s land was removed." : "That land could not be removed."))));
        }
    }
}
