package com.hexvane.eterniamod.portals;

import com.hexvane.eterniamod.domain.AccountService;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hexvane.eterniamod.domain.PortalService;
import com.hexvane.eterniamod.domain.PortalService.Portal;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * The portal menu, opened by using or walking onto a Teleporter pad. Travel lists the player's own portal list (pads in
 * this world can be reached; pads in other worlds are shown but need a trip there first). Settings, for the owner and
 * staff, renames the pad, makes it private and picks who may use it. Domain work runs on the menu executor.
 */
public final class PortalPage extends EterniaInteractiveCustomUIPage<PortalPage.Data> {
    static final String ICON_ITEM = "Teleporter";
    static final int ROWS = 7;
    /** How far from the pad a player may stand and still travel from it. */
    static final double REACH = 6;
    private enum Tab { TRAVEL, SETTINGS }

    private final EterniaServices services;
    private final UUID actor;
    private final boolean staff;
    private final Map<String, String> routes = new HashMap<>();
    private final Map<String, Integer> chips = new HashMap<>();
    private Portal here;
    private List<Portal> known = List.of();
    private List<Person> allowed = List.of();
    private Tab tab = Tab.TRAVEL;
    private boolean busy, initial = true;
    private int page;
    private long generation;
    private String nameDraft, allowDraft = "", status, confirm = "";

    PortalPage(PlayerRef player, Portal here, PortalService.Visit visit, boolean staff) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.services = PortalBootstrap.plugin().getServices();
        this.actor = player.getUuid(); this.here = here; this.staff = staff; this.nameDraft = here.name();
        this.status = switch (visit) {
            case ADDED -> here.name() + " is now on your portal list and your map.";
            case OWN -> "This is your portal. Pick a place to go, or open Settings.";
            default -> "";
        };
    }

    /** Closing the menu while standing on the pad should not open it again at the next step. */
    @Override public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        super.onDismiss(ref, store);
        PortalBootstrap.arrivals().arrived(actor, here.world(), new Vector3i(here.x(), here.y(), here.z()));
    }

    boolean showing(String portalId) { return !isDismissed() && here.id().equals(portalId); }

    private boolean manages() { return PortalService.mayManage(here, actor, staff); }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("EterniaMod/PortalPage.ui"); bindHome(events); routes.clear(); chips.clear();
        bind(events, "#Close", "close");
        if (!manages()) tab = Tab.TRAVEL;
        ui.set("#HereIcon.ItemId", ICON_ITEM);
        ui.set("#HereName.TextSpans", Message.raw(fit(here.name(), 20)));
        ui.set("#HereOwner.TextSpans", Message.raw(owner(here)));
        ui.set("#HereState.TextSpans", Message.raw(here.isPrivate() ? "Private" : "Anyone can use it"));
        ui.set("#HereState.Style.TextColor", here.isPrivate() ? "#e69085" : "#85c5b3");
        sideTab(ui, events, 0, "Travel (" + known.stream().filter(p -> !p.id().equals(here.id())).count() + ")", "worlds", Tab.TRAVEL);
        if (manages()) sideTab(ui, events, 1, "Settings", "ledger", Tab.SETTINGS);
        ui.set("#TravelPane.Visible", tab == Tab.TRAVEL);
        ui.set("#SettingsPane.Visible", tab == Tab.SETTINGS);
        if (tab == Tab.TRAVEL) drawTravel(ui, events, ref, store); else drawSettings(ui, events);
        if (initial) { initial = false; refresh(status); }
    }

    private void drawTravel(UICommandBuilder ui, UIEventBuilder events, Ref<EntityStore> ref, Store<EntityStore> store) {
        String world = store.getExternalData().getWorld().getName();
        var where = store.getComponent(ref, TransformComponent.getComponentType());
        Vector3d me = where == null ? null : where.getPosition();
        var list = known.stream().filter(p -> !p.id().equals(here.id())).toList();
        ui.set("#TravelTitle.TextSpans", Message.raw("Where to?"));
        int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS); page = Math.min(page, pages - 1);
        int i = 0;
        for (var p : list.subList(page * ROWS, Math.min(list.size(), (page + 1) * ROWS))) {
            String row = "#Portals[" + i++ + "]";
            ui.append("#Portals", "EterniaMod/PortalRow.ui");
            ui.set(row + " #PIcon.ItemId", ICON_ITEM);
            ui.set(row + " #PName.TextSpans", Message.raw(fit(p.name(), 30)));
            boolean sameWorld = p.world().equals(world);
            String distance = sameWorld && me != null ? PortalText.blocks(Math.hypot(p.x() + .5 - me.x, p.z() + .5 - me.z)) : "In " + com.hexvane.eterniamod.runtime.WorldDisplayNames.name(p.world());
            ui.set(row + " #PLine.TextSpans", Message.raw(owner(p) + "  ·  " + distance));
            ui.set(row + " #PTag.TextSpans", Message.raw(p.ownedBy(actor) ? "Yours" : p.isPrivate() ? "Private" : ""));
            button(ui, events, row + " #PGo", sameWorld ? "Travel" : "Other world", sameWorld ? "go:" + p.id() : "", true);
            boolean mine = p.ownedBy(actor);
            String forget = confirm.equals("forget:" + p.id()) ? "Sure?" : "Forget";
            button(ui, events, row + " #PForget", forget, mine ? "" : "forget:" + p.id(), !mine);
        }
        String empty = "No other portals yet. Place a Teleporter or step onto a friend's portal to add it.";
        ui.set("#ListStatus.TextSpans", Message.raw(busy ? "Loading…" : !status.isEmpty() ? status : list.isEmpty() ? empty : pages > 1 ? "Page " + (page + 1) + " of " + pages : "Portals in other worlds can be reached from a portal in that world."));
        button(ui, events, "#ListPrev", "Earlier", "prev", pages > 1); ui.set("#ListPrev.Disabled", busy || page == 0);
        button(ui, events, "#ListNext", "More", "next", pages > 1); ui.set("#ListNext.Disabled", busy || page + 1 >= pages);
    }

    private void drawSettings(UICommandBuilder ui, UIEventBuilder events) {
        ui.set("#NameField.Value", nameDraft);
        button(ui, events, "#NameSave", "Save name", "rename", true);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#NameField", data("rename"), false);
        chip(ui, events, "#Privacy", "Anyone", "private:false", !here.isPrivate());
        chip(ui, events, "#Privacy", "Only people I choose", "private:true", here.isPrivate());
        ui.set("#PrivacyNote.TextSpans", Message.raw(here.isPrivate()
            ? "Only you and the players on the right can use this portal or add it to their list."
            : "Anyone who steps onto this portal can use it and add it to their list."));
        ui.set("#SetStatus.TextSpans", Message.raw(busy ? "Saving…" : status));
        ui.set("#AllowName.Value", allowDraft);
        button(ui, events, "#AllowAdd", "Allow", "allow", true);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#AllowName", data("allow"), false);
        int i = 0;
        for (var person : allowed) {
            if (i >= 8) break;
            String row = "#Allowed[" + i++ + "]";
            ui.append("#Allowed", "EterniaMod/FriendRequest.ui");
            ui.set(row + " #QInitial.TextSpans", Message.raw(person.name.isEmpty() ? "?" : person.name.substring(0, 1).toUpperCase(Locale.ROOT)));
            ui.set(row + " #QName.TextSpans", Message.raw(fit(person.name, 24)));
            ui.set(row + " #QLine.TextSpans", Message.raw(here.isPrivate() ? "Can use this portal" : "Can use it when it is private"));
            ui.set(row + " #QYes.Visible", false);
            button(ui, events, row + " #QNo", "Remove", "disallow:" + person.id, true);
        }
        ui.set("#AllowStatus.TextSpans", Message.raw(allowed.isEmpty() ? "Nobody yet." : allowed.size() > 8 ? "And " + (allowed.size() - 8) + " more." : ""));
    }

    private void sideTab(UICommandBuilder ui, UIEventBuilder events, int i, String label, String icon, Tab target) {
        String row = "#Tabs[" + i + "]";
        ui.append("#Tabs", target == tab ? "EterniaMod/SideTabSelected.ui" : "EterniaMod/SideTab.ui");
        ui.set(row + " #SideTabLabel.TextSpans", Message.raw(label));
        ui.setObject(row + " #SideTabIcon.Background", new PatchStyle(Value.of("EterniaMod/Icons/" + icon + ".png")));
        bind(events, row, "tab:" + target.name());
    }

    private void chip(UICommandBuilder ui, UIEventBuilder events, String list, String label, String route, boolean on) {
        int index = chips.merge(list, 1, Integer::sum) - 1;
        String cell = list + "[" + index + "]";
        ui.append(list, on ? "EterniaMod/GuildChipOn.ui" : "EterniaMod/GuildChip.ui");
        ui.set(cell + ".TextSpans", Message.raw(label));
        ui.set(cell + ".Disabled", busy);
        if (!on) bind(events, cell, route);
    }

    private void button(UICommandBuilder ui, UIEventBuilder events, String selector, String label, String route, boolean visible) {
        ui.set(selector + ".TextSpans", Message.raw(label)); ui.set(selector + ".Visible", visible); ui.set(selector + ".Disabled", busy || route.isEmpty());
        if (visible && !route.isEmpty()) bind(events, selector, route);
    }

    private void bind(UIEventBuilder events, String selector, String route) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, data(route), false);
    }

    private EventData data(String route) {
        String key = generation + ":" + routes.size(); routes.put(key, route);
        var d = new EventData().append("Action", key);
        if (tab == Tab.SETTINGS) d = d.append("@Name", "#NameField.Value").append("@Allow", "#AllowName.Value");
        return d;
    }

    private String owner(Portal p) {
        if (p.ownedBy(actor)) return "Your portal";
        return p.ownerName().isBlank() ? "No owner" : p.ownerName() + "'s";
    }

    private static String fit(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…"; }

    // ------------------------------------------------------------------------------------------------ events

    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (isDismissed() || busy) return;
        String route = routes.get(data.action); if (route == null) return;
        if (data.name != null) nameDraft = data.name.length() > PortalService.NAME_MAX ? data.name.substring(0, PortalService.NAME_MAX) : data.name;
        if (data.allow != null) allowDraft = data.allow.length() > 32 ? data.allow.substring(0, 32) : data.allow;
        String was = confirm; confirm = "";
        int colon = route.indexOf(':');
        String verb = colon < 0 ? route : route.substring(0, colon), arg = colon < 0 ? "" : route.substring(colon + 1);
        String id = here.id();
        switch (verb) {
            case "close" -> close();
            case "tab" -> { tab = Tab.valueOf(arg); status = ""; redraw(); }
            case "prev" -> { page = Math.max(0, page - 1); redraw(); }
            case "next" -> { page++; redraw(); }
            case "go" -> travel(ref, store, arg);
            case "forget" -> {
                if (!was.equals(route)) { confirm = route; status = "Press Sure? to take it off your list. You can add it again by visiting it."; redraw(); return; }
                act(() -> { services.portals().forget(actor, arg); return null; }, v -> PortalBootstrap.index().changed(), "Taken off your list.");
            }
            case "rename" -> { String name = nameDraft; act(() -> services.portals().rename(actor, staff, id, name), this::changed, "Name saved."); }
            case "private" -> { boolean on = arg.equals("true"); act(() -> services.portals().setPrivate(actor, staff, id, on), this::changed, on ? "Only you and the players you allow can use it now." : "Anyone can use it now."); }
            case "allow" -> {
                String who = allowDraft.strip();
                if (who.isEmpty()) { status = "Type a player's name first."; redraw(); return; }
                act(() -> {
                    var account = services.accounts().findByName(who).orElseThrow(() -> new IllegalArgumentException("Nobody called " + who + " has played here yet."));
                    return services.portals().allow(actor, staff, id, account.id());
                }, p -> { allowDraft = ""; changed(p); }, who + " can use this portal.");
            }
            case "disallow" -> { UUID other = UUID.fromString(arg); act(() -> services.portals().disallow(actor, staff, id, other), this::changed, "Removed."); }
            default -> { }
        }
    }

    private void changed(Portal p) { here = p; nameDraft = p.name(); PortalBootstrap.index().put(p); }

    /** Rechecks everything, then sends the player to the destination pad in this world. */
    private void travel(Ref<EntityStore> ref, Store<EntityStore> store, String destination) {
        World world = store.getExternalData().getWorld();
        var where = store.getComponent(ref, TransformComponent.getComponentType());
        if (where == null) return;
        var me = where.getPosition();
        if (!here.world().equals(world.getName()) || Math.hypot(here.x() + .5 - me.x, here.z() + .5 - me.z) > REACH || Math.abs(here.y() - me.y) > REACH) {
            status = "Step back onto the portal to travel."; redraw(); return;
        }
        busy = true; long version = ++generation; rebuild();
        SocialUiBootstrap.supply(services, () -> {
            var from = services.portals().find(here.id()).filter(p -> p.usableBy(actor)).orElseThrow(() -> new IllegalStateException("You can no longer use this portal."));
            var to = services.portals().find(destination).orElseThrow(() -> new IllegalStateException("That portal is gone."));
            if (!to.usableBy(actor)) throw new IllegalStateException("That portal is private now.");
            if (!to.world().equals(from.world())) throw new IllegalStateException("That portal is in another world. Go there first.");
            return to;
        }).whenComplete((to, error) -> world.execute(() -> {
            if (isDismissed() || version != generation) return;
            if (error != null) { busy = false; status = message(error); refresh(status); return; }
            var pad = new Vector3i(to.x(), to.y(), to.z());
            com.hexvane.eterniamod.runtime.TravelLanding.load(world, new Vector3d(to.x() + .5, to.y(), to.z() + .5), 1).whenComplete((loaded, loadError) -> world.execute(() -> {
                if (isDismissed() || version != generation || !ref.isValid()) return;
                if (loadError != null || !PortalBootstrap.isPortalBlock(PortalBootstrap.blockId(world, pad))) {
                    // The pad was removed in a way the game did not report (an old pad, or an edit tool). Clean it up.
                    if (loadError == null) SocialUiBootstrap.supply(services, () -> { services.portals().remove(to.id()); return null; }).thenRun(() -> PortalBootstrap.index().removed(to.id()));
                    busy = false; status = loadError == null ? "That portal is gone." : "That portal could not be reached. Try again in a moment.";
                    refresh(status); return;
                }
                close();
                PortalBootstrap.arrivals().arrived(actor, world.getName(), pad);
                var at = new Transform(new Vector3d(to.x() + .5, to.y() + .65, to.z() + .5), new Rotation3f(Float.NaN, facing(world, pad), Float.NaN));
                PortalBootstrap.plugin().getTravel().place(playerRef, world, at).thenAccept(ok -> { if (!ok) playerRef.sendMessage(Message.raw("The portal could not send you. Try again.")); });
            }));
        }));
    }

    /** Step off the pad facing the way it points, as vanilla's warps do. */
    private static float facing(World world, Vector3i p) {
        var sectionRef = world.getChunkStore().getChunkSectionReferenceAtBlock(p.x, p.y, p.z);
        var section = sectionRef == null || !sectionRef.isValid() ? null : world.getChunkStore().getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return Float.NaN;
        var rotation = RotationTuple.get(section.getRotationIndex(p.x, p.y, p.z));
        return (float) (rotation.yaw().getRadians() + Math.PI);
    }

    // ------------------------------------------------------------------------------------------------ loading

    private void refresh(String notice) { act(() -> null, v -> {}, notice); }

    private <T> void act(Supplier<T> action, Consumer<T> after, String notice) {
        busy = true; long version = ++generation; rebuild();
        var ref = playerRef.getReference(); if (ref == null || !ref.isValid()) return;
        var world = ref.getStore().getExternalData().getWorld();
        SocialUiBootstrap.supply(services, () -> { T result = action.get(); return new Object[] {result, load()}; })
            .whenComplete((pair, error) -> world.execute(() -> {
                if (isDismissed() || !ref.isValid() || version != generation) return;
                busy = false;
                if (error != null) { status = message(error); redraw(); return; }
                var snapshot = (Snapshot) pair[1];
                if (snapshot.here == null) { status = "This portal is gone."; close(); playerRef.sendMessage(Message.raw("This portal is gone.")); return; }
                here = snapshot.here; known = snapshot.known; allowed = snapshot.allowed; status = notice == null ? "" : notice;
                @SuppressWarnings("unchecked") T result = (T) pair[0];
                after.accept(result);
                redraw();
            }));
    }

    private Snapshot load() {
        return services.batch(() -> {
            var now = services.portals().find(here.id()).orElse(null);
            var mine = services.portals().known(actor);
            var people = new ArrayList<Person>();
            if (now != null && PortalService.mayManage(now, actor, staff)) {
                var accounts = services.accounts().findAll(new ArrayList<>(now.allowed()));
                for (UUID id : now.allowed()) people.add(new Person(id, Optional.ofNullable(accounts.get(id)).map(AccountService.Account::displayName).orElse("Unknown")));
                people.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
            }
            return new Snapshot(now, mine, List.copyOf(people));
        });
    }

    private static String message(Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        return cause instanceof DomainException || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
            ? cause.getMessage() : "That did not work. Try again in a moment.";
    }

    private void redraw() { generation++; rebuild(); }

    private record Person(UUID id, String name) {}
    private record Snapshot(Portal here, List<Portal> known, List<Person> allowed) {}

    public static final class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("@Name", Codec.STRING), (d, v) -> d.name = v, d -> d.name).add()
            .append(new KeyedCodec<>("@Allow", Codec.STRING), (d, v) -> d.allow = v, d -> d.allow).add().build();
        public String action, name, allow;
    }
}
