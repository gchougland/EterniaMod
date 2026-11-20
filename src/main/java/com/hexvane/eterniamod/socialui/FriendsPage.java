package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * The Friends screen: your friends with who is online and where, one friend's details (visit their plot, invite to your
 * guild, what they may do on your plot), requests in and out, and blocked players. Domain work runs on the menu executor.
 */
public final class FriendsPage extends EterniaInteractiveCustomUIPage<FriendsPage.Data> {
    private static final int ROWS = 10;
    private enum Tab { FRIENDS, REQUESTS, BLOCKED }
    private static final String[] WORLD_COLOURS = {"#85c5b3", "#e8743b", "#7fb6e0", "#c98ad6", "#9fd08a", "#e3a83a"};

    private final SocialUiBootstrap.Registration registration;
    private final EterniaServices services;
    private final UUID actor;
    private final Map<String, String> routes = new HashMap<>();
    private final Map<String, Integer> chips = new HashMap<>();
    private Snapshot snapshot = Snapshot.EMPTY;
    private Tab tab = Tab.FRIENDS;
    private boolean onlineOnly, busy, initial = true, loaded;
    private int page;
    private UUID selected;
    private String addName = "", confirm = "", status = "Opening your friends…";
    private long generation;

    FriendsPage(PlayerRef player, SocialUiBootstrap.Registration registration) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.registration = registration; services = registration.services; actor = player.getUuid();
    }

    /** Opens on the requests tab, for the chat notice. */
    FriendsPage onRequests() { tab = Tab.REQUESTS; return this; }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("EterniaMod/FriendsPage.ui"); bindHome(events); routes.clear(); chips.clear();
        journalTabs(ui, events, EterniaServicesPage.Section.FRIENDS);
        long online = snapshot.friends.stream().filter(Person::online).count();
        ui.set("#FriendsTitle.TextSpans", Message.raw("Friends"));
        ui.set("#FriendsKicker.TextSpans", Message.raw(online + " of " + snapshot.friends.size() + " online"));
        sideTab(ui, events, 0, "Friends (" + snapshot.friends.size() + ")", "members", Tab.FRIENDS);
        sideTab(ui, events, 1, snapshot.incoming.isEmpty() ? "Requests" : "Requests (" + snapshot.incoming.size() + " new)", "mail", Tab.REQUESTS);
        sideTab(ui, events, 2, "Blocked (" + snapshot.blocked.size() + ")", "charter", Tab.BLOCKED);
        chip(ui, events, "#AlertSwitch", snapshot.alerts ? "On" : "Off", "alerts", snapshot.alerts);
        ui.set("#FriendsPane.Visible", tab == Tab.FRIENDS);
        ui.set("#RequestsPane.Visible", tab == Tab.REQUESTS);
        ui.set("#BlockedPane.Visible", tab == Tab.BLOCKED);
        ui.set("#AddName.Value", addName);
        switch (tab) {
            case FRIENDS -> drawFriends(ui, events);
            case REQUESTS -> drawRequests(ui, events);
            case BLOCKED -> drawBlocked(ui, events);
        }
        if (initial) { initial = false; refresh(""); }
    }

    /** The Journal's tabs across the top, with this screen's own tab lit; the others open the Journal there. */
    private void journalTabs(UICommandBuilder ui, UIEventBuilder events, EterniaServicesPage.Section mine) {
        var sections = EterniaServicesPage.journalSections();
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            String tab = com.hexvane.eterniamod.ui.JournalChrome.navTab(ui, i, EterniaServicesPage.journalTabLabel(section), EterniaServicesPage.journalTabIcon(section), section == mine, busy);
            if (section != mine) bind(events, tab, "nav:" + section.name());
        }
        bind(events, "#Close", "close");
    }

    private void sideTab(UICommandBuilder ui, UIEventBuilder events, int i, String label, String icon, Tab target) {
        String row = "#Tabs[" + i + "]";
        ui.append("#Tabs", target == tab ? "EterniaMod/SideTabSelected.ui" : "EterniaMod/SideTab.ui");
        ui.set(row + " #SideTabLabel.TextSpans", Message.raw(label));
        ui.setObject(row + " #SideTabIcon.Background", new PatchStyle(Value.of("EterniaMod/Icons/" + icon + ".png")));
        bind(events, row, "tab:" + target.name());
    }

    private void drawFriends(UICommandBuilder ui, UIEventBuilder events) {
        button(ui, events, "#AddSend", "Send request", "add", true);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#AddName", data("add"), false);
        long online = snapshot.friends.stream().filter(Person::online).count();
        chip(ui, events, "#Filters", "Everyone (" + snapshot.friends.size() + ")", "filter:all", !onlineOnly);
        chip(ui, events, "#Filters", "Online (" + online + ")", "filter:online", onlineOnly);
        var list = onlineOnly ? snapshot.friends.stream().filter(Person::online).toList() : snapshot.friends;
        int pages = Math.max(1, (list.size() + ROWS - 1) / ROWS); page = Math.min(page, pages - 1);
        if (selected == null || snapshot.friends.stream().noneMatch(p -> p.id.equals(selected))) selected = list.isEmpty() ? null : list.getFirst().id;
        int i = 0;
        for (var p : list.subList(page * ROWS, Math.min(list.size(), (page + 1) * ROWS))) {
            String row = "#Friends[" + i++ + "]";
            ui.append("#Friends", p.id.equals(selected) ? "EterniaMod/FriendRowSelected.ui" : "EterniaMod/FriendRow.ui");
            ui.set(row + " #RInitial.TextSpans", Message.raw(initial(p.name)));
            ui.setObject(row + " #RDot.Background", colour(p.online() ? "#6fd08c" : "#5a6b68"));
            ui.set(row + " #RName.TextSpans", Message.raw(fit(p.name, 24)));
            if (p.guild != null) {
                paint(ui, row + " #RGField", row + " #RGPat", row + " #RGEmb", p.guild.banner());
                ui.set(row + " #RGuild.TextSpans", Message.raw(fit(p.guild.name(), 28)));
            } else { ui.set(row + " #RGBanner.Visible", false); ui.set(row + " #RGuild.TextSpans", Message.raw("No guild")); }
            where(ui, row + " #RWorldDot", row + " #RWhere", p);
            bind(events, row, "pick:" + p.id);
        }
        ui.set("#FriendHead.Visible", !list.isEmpty());
        String empty = onlineOnly ? "None of your friends are online right now." : "No friends yet. Type a player's name above and press Send request.";
        ui.set("#ListStatus.TextSpans", Message.raw(busy ? "Loading…" : list.isEmpty() && loaded ? empty : !status.isEmpty() ? status : pages > 1 ? "Page " + (page + 1) + " of " + pages : ""));
        button(ui, events, "#ListPrev", "Earlier", "prev", pages > 1); ui.set("#ListPrev.Disabled", busy || page == 0);
        button(ui, events, "#ListNext", "More", "next", pages > 1); ui.set("#ListNext.Disabled", busy || page + 1 >= pages);

        var f = snapshot.friends.stream().filter(p -> p.id.equals(selected)).findFirst().orElse(null);
        ui.set("#Detail.Visible", f != null);
        if (f == null) return;
        ui.set("#DInitial.TextSpans", Message.raw(initial(f.name)));
        ui.set("#DName.TextSpans", Message.raw(fit(f.name, 22)));
        where(ui, "#DWorldDot", "#DWhere", f);
        if (f.guild != null) { paint(ui, "#DGField", "#DGPat", "#DGEmb", f.guild.banner()); ui.set("#DGuild.TextSpans", Message.raw(fit(f.guild.name(), 30))); }
        else { ui.set("#DGBanner.Visible", false); ui.set("#DGuild.TextSpans", Message.raw("Not in a guild")); }
        ui.set("#DSince.TextSpans", Message.raw("Friends since " + ago(f.since)));
        boolean canVisit = f.iMay.contains(FriendService.Share.VISIT) && f.hasPlot;
        button(ui, events, "#Visit", "Visit their plot", canVisit ? "visit" : "", true);
        boolean canInvite = snapshot.canInvite && f.guild == null;
        button(ui, events, "#InviteGuild", "Invite to my guild", canInvite ? "invite" : "", true);
        String note = !f.hasPlot ? f.name + " has no plot yet."
            : !f.iMay.contains(FriendService.Share.VISIT) ? f.name + " has not shared their plot with you."
            : "";
        if (f.guild != null && snapshot.myGuild != null && f.guild.id().equals(snapshot.myGuild)) note = (note.isEmpty() ? "" : note + " ") + "You are in the same guild.";
        else if (f.guild != null) note = (note.isEmpty() ? "" : note + " ") + f.name + " is already in a guild.";
        else if (!snapshot.canInvite) note = (note.isEmpty() ? "" : note + " ") + (snapshot.myGuild == null ? "Join a guild to invite friends to it." : "Your guild role cannot invite players.");
        ui.set("#DActionNote.TextSpans", Message.raw(note));
        ui.set("#ShareHead.TextSpans", Message.raw("On your plot, " + fit(f.name, 16) + " can"));
        boolean v = f.theyMay.contains(FriendService.Share.VISIT), c = f.theyMay.contains(FriendService.Share.CHESTS);
        chip(ui, events, "#ShareVisit", v ? "Allowed" : "Not allowed", "share:VISIT", v);
        chip(ui, events, "#ShareChests", c ? "Allowed" : "Not allowed", "share:CHESTS", c);
        ui.set("#ShareNote.TextSpans", Message.raw("Share your land in adventure worlds from its own land menu, where you can pick friends from this list."));
        ui.set("#DetailStatus.TextSpans", Message.raw(busy ? "Saving…" : status));
        button(ui, events, "#Remove", confirm.equals("remove") ? "Press again to remove" : "Remove friend", "remove", true);
        button(ui, events, "#Block", confirm.equals("block") ? "Press again to block" : "Block", "block", true);
    }

    private void drawRequests(UICommandBuilder ui, UIEventBuilder events) {
        int i = 0;
        for (var r : snapshot.incoming) {
            if (i >= 8) break;
            String row = "#Incoming[" + i++ + "]";
            request(ui, row, "#Incoming", r);
            button(ui, events, row + " #QYes", "Accept", "accept:" + r.id, true);
            button(ui, events, row + " #QNo", "Decline", "decline:" + r.id, true);
        }
        ui.set("#IncomingNote.TextSpans", Message.raw(snapshot.incoming.isEmpty() ? "Nobody is asking right now." : "Requests you do not answer go away after 7 days."));
        i = 0;
        for (var r : snapshot.outgoing) {
            if (i >= 8) break;
            String row = "#Outgoing[" + i++ + "]";
            request(ui, row, "#Outgoing", r);
            ui.set(row + " #QYes.Visible", false);
            button(ui, events, row + " #QNo", "Cancel request", "cancel:" + r.id, true);
        }
        ui.set("#RequestsStatus.TextSpans", Message.raw(busy ? "Saving…" : !status.isEmpty() ? status : snapshot.outgoing.isEmpty() ? "You have no requests waiting." : ""));
    }

    private void request(UICommandBuilder ui, String row, String list, Person r) {
        ui.append(list, "EterniaMod/FriendRequest.ui");
        ui.set(row + " #QInitial.TextSpans", Message.raw(initial(r.name)));
        ui.set(row + " #QName.TextSpans", Message.raw(fit(r.name, 24)));
        ui.set(row + " #QLine.TextSpans", Message.raw((r.guild == null ? "No guild" : fit(r.guild.name(), 24)) + "  ·  Sent " + ago(r.since)));
    }

    private void drawBlocked(UICommandBuilder ui, UIEventBuilder events) {
        int i = 0;
        for (var b : snapshot.blocked) {
            if (i >= 9) break;
            String row = "#BlockedList[" + i++ + "]";
            ui.append("#BlockedList", "EterniaMod/FriendRequest.ui");
            ui.set(row + " #QInitial.TextSpans", Message.raw(initial(b.name)));
            ui.set(row + " #QName.TextSpans", Message.raw(fit(b.name, 24)));
            ui.set(row + " #QLine.TextSpans", Message.raw("Blocked"));
            ui.set(row + " #QYes.Visible", false);
            button(ui, events, row + " #QNo", "Unblock", "unblock:" + b.id, true);
        }
        ui.set("#BlockedStatus.TextSpans", Message.raw(busy ? "Saving…" : !status.isEmpty() ? status : snapshot.blocked.isEmpty() ? "You have not blocked anyone." : ""));
    }

    private void where(UICommandBuilder ui, String dot, String label, Person p) {
        if (p.online()) {
            String c = worldColour(p.world);
            ui.setObject(dot + ".Background", colour(c));
            ui.set(label + ".TextSpans", Message.raw(p.world));
            ui.set(label + ".Style.TextColor", c);
        } else {
            ui.set(dot + ".Visible", false);
            ui.set(label + ".TextSpans", Message.raw(p.lastSeen == null ? "Offline" : "Last seen " + ago(p.lastSeen)));
            ui.set(label + ".Style.TextColor", "#8a9a94");
        }
    }

    private static void paint(UICommandBuilder ui, String field, String pattern, String emblem, GuildBanner b) {
        ui.setObject(field + ".Background", new PatchStyle(Value.of(GuildBanner.ART + "Field.png")).setColor(Value.of(GuildBanner.hex(b.field()))));
        boolean plain = b.pattern().equals("plain");
        ui.set(pattern + ".Visible", !plain);
        if (!plain) ui.setObject(pattern + ".Background", new PatchStyle(Value.of(GuildBanner.ART + "Pattern_" + b.pattern() + ".png")).setColor(Value.of(GuildBanner.hex(b.patternColor()))));
        ui.setObject(emblem + ".Background", new PatchStyle(Value.of(GuildBanner.ART + "Emblem_" + b.emblem() + ".png")).setColor(Value.of(GuildBanner.hex(b.emblemColor()))));
    }
    private static PatchStyle colour(String hex) { return new PatchStyle().setColor(Value.of(hex)); }

    private void chip(UICommandBuilder ui, UIEventBuilder events, String list, String label, String route, boolean on) {
        int index = chips.merge(list, 1, Integer::sum) - 1;
        String cell = list + "[" + index + "]";
        ui.append(list, on ? "EterniaMod/GuildChipOn.ui" : "EterniaMod/GuildChip.ui");
        ui.set(cell + ".TextSpans", Message.raw(label));
        ui.set(cell + ".Disabled", busy);
        if (!route.isEmpty()) bind(events, cell, route);
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
        if (tab == Tab.FRIENDS) d = d.append("@Add", "#AddName.Value");
        return d;
    }

    private static String initial(String name) { return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT); }
    private static String fit(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…"; }
    private static String worldColour(String world) {
        if (world == null) return "#b5c3b8";
        if (world.toLowerCase(Locale.ROOT).contains("hub")) return "#d8b66b";
        return WORLD_COLOURS[Math.floorMod(world.hashCode(), WORLD_COLOURS.length)];
    }
    private static String ago(Instant at) {
        Duration d = Duration.between(at, Instant.now()); if (d.isNegative()) d = Duration.ZERO;
        if (d.toMinutes() < 2) return "just now";
        if (d.toMinutes() < 60) return d.toMinutes() + " minutes ago";
        if (d.toHours() < 2) return "an hour ago";
        if (d.toHours() < 24) return d.toHours() + " hours ago";
        if (d.toDays() < 2) return "yesterday";
        if (d.toDays() < 14) return d.toDays() + " days ago";
        return d.toDays() / 7 + " weeks ago";
    }

    // ------------------------------------------------------------------------------------------------ events

    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (isDismissed() || registration.closed || busy) return;
        String route = routes.get(data.action); if (route == null) return;
        if (data.add != null) addName = data.add.length() > 32 ? data.add.substring(0, 32) : data.add;
        String was = confirm; confirm = "";
        int colon = route.indexOf(':');
        String verb = colon < 0 ? route : route.substring(0, colon), arg = colon < 0 ? "" : route.substring(colon + 1);
        UUID other = selected;
        switch (verb) {
            case "tab" -> { tab = Tab.valueOf(arg); status = ""; refresh(""); }
            case "nav" -> { try { SocialUiBootstrap.openJournal(ref, store, playerRef, EterniaServicesPage.Section.valueOf(arg)); } catch (IllegalArgumentException unknown) { } }
            case "close" -> close();

            case "filter" -> { onlineOnly = arg.equals("online"); page = 0; status = ""; redraw(); }
            case "pick" -> { selected = UUID.fromString(arg); status = ""; redraw(); }
            case "prev" -> { page = Math.max(0, page - 1); redraw(); }
            case "next" -> { page++; redraw(); }
            case "alerts" -> act(() -> { services.friends().setAlerts(actor, !snapshot.alerts); return null; }, v -> {}, snapshot.alerts ? "Online alerts are off." : "Online alerts are on.");
            case "add" -> add();
            case "accept" -> { UUID from = UUID.fromString(arg); act(() -> { services.friends().accept(actor, from); return null; },
                v -> FriendNotices.tell(from, playerRef.getUsername() + " accepted your friend request."), "You are now friends."); }
            case "decline" -> act(() -> { services.friends().decline(actor, UUID.fromString(arg)); return null; }, v -> {}, "Request declined.");
            case "cancel" -> act(() -> { services.friends().cancel(actor, UUID.fromString(arg)); return null; }, v -> {}, "Request cancelled.");
            case "unblock" -> act(() -> { services.friends().unblock(actor, UUID.fromString(arg)); return null; }, v -> {}, "Unblocked.");
            case "share" -> {
                var f = current(); if (f == null) return;
                var shares = EnumSet.noneOf(FriendService.Share.class); shares.addAll(f.theyMay);
                var s = FriendService.Share.valueOf(arg); if (!shares.remove(s)) shares.add(s);
                act(() -> { services.friends().share(actor, f.id, shares); return null; }, v -> {}, "Saved.");
            }
            case "remove", "block" -> {
                if (other == null) return;
                if (!was.equals(verb)) { confirm = verb; status = verb.equals("block") ? "Blocking also stops their mail and guild invites." : ""; redraw(); return; }
                if (verb.equals("remove")) act(() -> { services.friends().remove(actor, other); return null; }, v -> selected = null, "Friend removed.");
                else act(() -> { services.friends().block(actor, other); return null; }, v -> selected = null, "Blocked.");
            }
            case "visit" -> {
                var f = current(); if (f == null) return;
                var plugin = EterniaModPlugin.get(); if (plugin == null) return;
                close();
                plugin.getTravel().visitPlot(playerRef, Owner.player(f.id));
            }
            case "invite" -> {
                var f = current(); if (f == null) return;
                act(() -> services.guilds().invite(actor, f.id), v -> FriendNotices.tell(f.id, playerRef.getUsername() + " invited you to their guild. Open Guild in your Journal to accept."),
                    "Invitation sent to " + f.name + ".");
            }
            default -> { }
        }
    }

    private void add() {
        String who = addName.strip();
        if (who.isEmpty()) { status = "Type a player's name first."; redraw(); return; }
        String me = playerRef.getUsername();
        act(() -> {
            var account = services.accounts().findByName(who).orElseThrow(() -> new IllegalArgumentException("Nobody called " + who + " has played here yet."));
            boolean nowFriends = services.friends().request(actor, account.id());
            FriendNotices.tell(account.id(), nowFriends ? me + " is now your friend." : me + " wants to be your friend. Open Friends in your Journal.");
            return nowFriends;
        }, nowFriends -> { addName = ""; if (nowFriends) status = "You are now friends with " + who + "."; }, "Request sent to " + who + ".");
    }

    private Person current() { return snapshot.friends.stream().filter(p -> p.id.equals(selected)).findFirst().orElse(null); }

    // ------------------------------------------------------------------------------------------------ loading

    private void refresh(String notice) { act(() -> null, v -> {}, notice); }

    private <T> void act(Supplier<T> action, Consumer<T> after, String notice) {
        busy = true; long version = ++generation; rebuild();
        var ref = playerRef.getReference(); if (ref == null || !ref.isValid()) return;
        var world = ref.getStore().getExternalData().getWorld();
        CompletableFuture.supplyAsync(() -> { T result = action.get(); return new Object[] {result, load()}; }, registration.executor)
            .whenComplete((pair, error) -> world.execute(() -> {
                if (isDismissed() || registration.closed || !ref.isValid() || version != generation) return;
                busy = false;
                if (error != null) { status = message(error); redraw(); return; }
                snapshot = (Snapshot) pair[1]; loaded = true; status = notice;
                @SuppressWarnings("unchecked") T result = (T) pair[0];
                after.accept(result);
                redraw();
            }));
    }

    private static String message(Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        return cause instanceof DomainException || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
            ? cause.getMessage() : "That did not work. Try again in a moment.";
    }

    private void redraw() { generation++; rebuild(); }

    private Snapshot load() {
        // Live worlds first, then every database read in one transaction instead of a round trip per call.
        var online = FriendNotices.onlineWorlds();
        return services.batch(() -> loadRecords(online));
    }

    private Snapshot loadRecords(Map<UUID, String> online) {
        var friends = services.friends();
        var mine = friends.friends(actor);
        var incoming = friends.incoming(actor);
        var outgoing = friends.outgoing(actor);
        var blocked = friends.blocked(actor);
        var ids = new ArrayList<UUID>();
        mine.forEach(f -> ids.add(f.id())); incoming.forEach(r -> ids.add(r.from())); outgoing.forEach(r -> ids.add(r.to())); ids.addAll(blocked);
        var accounts = services.accounts().findAll(ids);
        var guildCache = new HashMap<UUID, GuildService.Profile>();
        java.util.function.Function<UUID, GuildService.Profile> guildOf = id -> services.guilds().membership(id)
            .map(m -> guildCache.computeIfAbsent(m.guildId(), g -> services.guilds().profile(g).orElse(null))).orElse(null);
        java.util.function.Function<UUID, String> nameOf = id -> Optional.ofNullable(accounts.get(id)).map(AccountService.Account::displayName).orElse("Unknown");
        var people = new ArrayList<Person>();
        for (var f : mine) {
            var account = accounts.get(f.id());
            Instant seen = account == null ? null : account.lastDisconnect() != null ? account.lastDisconnect() : account.lastSeen();
            boolean plot = services.housing().home(Owner.player(f.id())).isPresent();
            people.add(new Person(f.id(), nameOf.apply(f.id()), online.get(f.id()), seen, guildOf.apply(f.id()), f.since(), f.theyMay(), f.iMay(), plot));
        }
        people.sort(Comparator.comparing((Person p) -> !p.online()).thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
        var in = incoming.stream().map(r -> new Person(r.from(), nameOf.apply(r.from()), null, null, guildOf.apply(r.from()), r.at(), Set.of(), Set.of(), false)).toList();
        var out = outgoing.stream().map(r -> new Person(r.to(), nameOf.apply(r.to()), null, null, guildOf.apply(r.to()), r.at(), Set.of(), Set.of(), false)).toList();
        var bl = blocked.stream().map(id -> new Person(id, nameOf.apply(id), null, null, null, Instant.now(), Set.of(), Set.of(), false)).toList();
        var standing = services.guilds().standing(actor);
        return new Snapshot(List.copyOf(people), in, out, bl, friends.alerts(actor),
            standing.map(GuildService.Standing::guildId).orElse(null), standing.map(s -> s.capabilities().contains("member.invite")).orElse(false));
    }

    private record Person(UUID id, String name, String world, Instant lastSeen, GuildService.Profile guild, Instant since,
                          Set<FriendService.Share> theyMay, Set<FriendService.Share> iMay, boolean hasPlot) {
        boolean online() { return world != null; }
    }
    private record Snapshot(List<Person> friends, List<Person> incoming, List<Person> outgoing, List<Person> blocked, boolean alerts, UUID myGuild, boolean canInvite) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), List.of(), true, null, false);
    }

    public static final class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("@Add", Codec.STRING), (d, v) -> d.add = v, d -> d.add).add().build();
        public String action, add;
    }
}
