package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.WildLandService;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who may do what on a piece of land in an adventure world: friends by name, and what everyone may do. Owners (and, on
 * guild land, members whose role may manage it) add friends who are online, or any player with
 * {@code /e land friend <name>}.
 */
public final class WildFriendsMenu {
    private WildFriendsMenu() {}

    public static void open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, UUID claim) {
        WildLandIndex.Land land = WildLandIndex.byId(claim);
        if (land == null) { player.sendMessage(Message.raw("That land is gone.")); return; }
        Map<UUID, String> names = names(plugin, land.trust().keySet());
        List<ChoicePage.Choice> choices = new ArrayList<>();
        for (var entry : land.trust().entrySet()) {
            if (entry.getKey().equals(WildLandService.EVERYONE)) continue;
            String name = names.getOrDefault(UUID.fromString(entry.getKey()), "A player");
            choices.add(new ChoicePage.Choice(name + ": " + describe(entry.getValue()), "Change",
                (r, s) -> edit(plugin, r, s, player, claim, entry.getKey(), name)));
        }
        Set<WildPerm> everyone = land.trust().getOrDefault(WildLandService.EVERYONE, Set.of());
        choices.add(new ChoicePage.Choice("Everyone: " + (everyone.isEmpty() ? "nothing" : describe(everyone)), "Change",
            (r, s) -> edit(plugin, r, s, player, claim, WildLandService.EVERYONE, "Everyone")));
        choices.add(new ChoicePage.Choice("Add a friend who is online now. Use /e land friend NAME for anyone else.", "Add friend",
            (r, s) -> pickOnline(plugin, r, s, player, claim)));
        choices.add(new ChoicePage.Choice("Give up all of this land. You get back part of what it cost.", "Give it up",
            (r, s) -> ChoicePage.open(r, s, player, "Give up all of this land?", "Anyone may claim it after. Your blocks stay where they are.",
                List.of(new ChoicePage.Choice("Give up the whole claim", "Give it up", (rr, ss) -> abandon(plugin, rr, ss, player, claim)),
                    new ChoicePage.Choice("Keep your land", "Back", (rr, ss) -> open(plugin, rr, ss, player, claim))))));
        String who = land.owner().kind() == com.hexvane.eterniamod.domain.Owner.Kind.GUILD ? land.ownerName() + "'s land" : "your land";
        ChoicePage.open(ref, store, player, "Friends on " + who,
            "Friends can build, open chests or use doors and benches here, as you choose. On guild land, guild roles decide what members may do.", choices);
    }

    private static void edit(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, UUID claim, String who, String name) {
        WildLandIndex.Land land = WildLandIndex.byId(claim);
        if (land == null) return;
        Set<WildPerm> has = land.trust().getOrDefault(who, Set.of());
        List<ChoicePage.Choice> choices = new ArrayList<>();
        for (WildPerm perm : WildPerm.values()) {
            boolean on = has.contains(perm);
            choices.add(new ChoicePage.Choice(perm.label() + ": " + (on ? "allowed" : "not allowed"), on ? "Take away" : "Allow", (r, s) -> {
                EnumSet<WildPerm> next = has.isEmpty() ? EnumSet.noneOf(WildPerm.class) : EnumSet.copyOf(has);
                if (on) next.remove(perm); else next.add(perm);
                save(plugin, r, s, player, claim, who, next, () -> edit(plugin, r, s, player, claim, who, name));
            }));
        }
        if (!who.equals(WildLandService.EVERYONE) && !has.isEmpty())
            choices.add(new ChoicePage.Choice("Stop sharing this land with " + name, "Remove", (r, s) -> save(plugin, r, s, player, claim, who, Set.of(), () -> open(plugin, r, s, player, claim))));
        choices.add(new ChoicePage.Choice("Back to your friends", "Back", (r, s) -> open(plugin, r, s, player, claim)));
        ChoicePage.open(ref, store, player, name, "What " + (who.equals(WildLandService.EVERYONE) ? "everyone" : name) + " may do on this land.", choices);
    }

    private static void pickOnline(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, UUID claim) {
        WildLandIndex.Land land = WildLandIndex.byId(claim);
        if (land == null) return;
        List<ChoicePage.Choice> choices = new ArrayList<>();
        for (PlayerRef other : Universe.get().getPlayers()) {
            if (other.getUuid().equals(player.getUuid()) || land.trust().containsKey(other.getUuid().toString())) continue;
            String name = other.getUsername();
            choices.add(new ChoicePage.Choice(name, "Add", (r, s) -> save(plugin, r, s, player, claim, other.getUuid().toString(), WildPerm.ALL,
                () -> edit(plugin, r, s, player, claim, other.getUuid().toString(), name))));
        }
        if (choices.isEmpty()) choices.add(new ChoicePage.Choice("Nobody else is online. Use /e land friend NAME to add anyone.", "Back", (r, s) -> open(plugin, r, s, player, claim)));
        ChoicePage.open(ref, store, player, "Add a friend", "New friends may do everything at first. You can change that after.", choices);
    }

    private static void save(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, UUID claim, String who, Set<WildPerm> perms, Runnable then) {
        var world = store.getExternalData().getWorld();
        WildLand.trust(plugin, claim, player.getUuid(), who, perms).whenComplete((done, failure) -> world.execute(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                player.sendMessage(Message.raw(cause.getMessage() == null ? "That could not be saved. Try again." : cause.getMessage()));
            }
            if (ref.isValid()) then.run();
        }));
    }

    private static void abandon(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, UUID claim) {
        var land = WildLandIndex.byId(claim);
        var world = store.getExternalData().getWorld();
        WildLand.abandon(plugin, land == null ? world.getName() : land.world(), claim, player.getUuid()).whenComplete((done, failure) -> world.execute(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                player.sendMessage(Message.raw(cause.getMessage() == null ? "That could not be done. Try again." : cause.getMessage()));
            } else if (!done.ok()) player.sendMessage(Message.raw(done.message()));
            else player.sendMessage(Message.raw(done.coins() == 0 ? "You gave up the land." : "You gave up the land and got back " + WildMapLogic.coins(done.coins()) + "."));
        }));
    }

    static String describe(Set<WildPerm> perms) {
        if (perms.isEmpty()) return "nothing";
        StringBuilder out = new StringBuilder();
        for (WildPerm p : WildPerm.values()) if (perms.contains(p)) out.append(out.isEmpty() ? "" : ", ").append(p.label().toLowerCase(java.util.Locale.ROOT));
        return out.toString();
    }

    private static Map<UUID, String> names(EterniaModPlugin plugin, Set<String> keys) {
        List<UUID> ids = new ArrayList<>();
        for (String k : keys) if (!k.equals(WildLandService.EVERYONE)) try { ids.add(UUID.fromString(k)); } catch (IllegalArgumentException ignored) { }
        Map<UUID, String> out = new java.util.HashMap<>();
        try { plugin.getServices().accounts().findAll(ids).forEach((id, a) -> out.put(id, a.displayName())); } catch (RuntimeException unavailable) { }
        return out;
    }
}
