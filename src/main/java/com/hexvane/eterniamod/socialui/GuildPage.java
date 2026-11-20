package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.runtime.WorldDisplayNames;
import com.hexvane.eterniamod.ui.ChoicePage;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
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
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * The guild screen: browse every guild with a detail panel, start a guild with a banner maker, and manage your own
 * guild from a sidebar (overview, members and requests, settings; the notice board, treasury, estate and roles open
 * their own screens). Domain work runs on the menu executor; drawing runs on the world thread.
 */
public final class GuildPage extends EterniaInteractiveCustomUIPage<GuildPage.Data> {
    private static final int ROWS = 6, MEMBERS = 10, ONLINE_SHOWN = 8;
    private enum View { BROWSE, DESIGN, GUILD }
    private enum Tab { OVERVIEW, MEMBERS }
    private enum Filter { ALL("All"), OPEN("Open to join"), ONLINE("Most online"), ESTATE("Has an estate"); final String label; Filter(String l){label=l;} }
    private enum MemberFilter { ALL, ONLINE, REQUESTS }
    private enum Layer { FIELD("Background"), PATTERN("Pattern"), PATTERN_COLOR("Pattern colour"), EMBLEM("Emblem"), EMBLEM_COLOR("Emblem colour"); final String label; Layer(String l){label=l;} }
    private static final String[] WORLD_COLOURS = {"#85c5b3", "#e8743b", "#7fb6e0", "#c98ad6", "#9fd08a", "#e3a83a"};

    private final SocialUiBootstrap.Registration registration;
    private final EterniaServices services;
    private final UUID actor;
    private final Map<String, String> routes = new HashMap<>();
    private final Random dice = new Random();
    private Snapshot snapshot = Snapshot.EMPTY;
    private View view = View.BROWSE;
    private Tab tab = Tab.OVERVIEW;
    private Filter filter = Filter.ALL;
    private MemberFilter memberFilter = MemberFilter.ALL;
    private Layer layer = Layer.FIELD;
    private boolean settings, busy, initial = true, loaded;
    private int listPage, memberPage;
    private UUID selectedGuild, selectedMember;
    private String search = "", name = "", tag = "", motto = "", invite = "", confirm = "", status = "Opening the guild hall…", receipt = "";
    private GuildService.JoinRule rule = GuildService.JoinRule.OPEN;
    private GuildBanner banner = GuildBanner.random(new Random());
    private long generation;

    GuildPage(PlayerRef player, SocialUiBootstrap.Registration registration) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.registration = registration; services = registration.services; actor = player.getUuid();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("EterniaMod/GuildPage.ui"); bindHome(events); routes.clear(); chipCounts.clear();
        ui.set("#BrowseView.Visible", view == View.BROWSE);
        ui.set("#DesignView.Visible", view == View.DESIGN);
        ui.set("#GuildView.Visible", view == View.GUILD);
        ui.set("#Search.Value", search); ui.set("#Name.Value", name); ui.set("#Tag.Value", tag); ui.set("#Motto.Value", motto); ui.set("#InviteName.Value", invite);
        switch (view) {
            case BROWSE -> drawBrowse(ui, events);
            case DESIGN -> drawDesign(ui, events);
            case GUILD -> drawGuild(ui, events);
        }
        if (initial) { initial = false; refresh(""); }
    }

    private void top(UICommandBuilder ui, UIEventBuilder events, String title, String kicker, String action, String route) {
        ui.set("#GuildTitle.TextSpans", Message.raw(title));
        ui.set("#GuildKicker.TextSpans", Message.raw(kicker));
        button(ui, events, "#TopAction", action, route, !action.isEmpty());
    }

    private void drawBrowse(UICommandBuilder ui, UIEventBuilder events) {
        boolean member = snapshot.mine != null;
        top(ui, events, "Guilds", "Find a guild or start your own", member ? "Back to my guild" : "Start a guild", member ? "mine" : "create");
        button(ui, events, "#SearchGo", "Search", "search", true);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#Search", data("search"), false);
        for (var f : Filter.values()) chip(ui, events, "#Filters", f.label, "filter:" + f.name(), f == filter);

        var shown = shownGuilds();
        int pages = Math.max(1, (shown.size() + ROWS - 1) / ROWS); listPage = Math.min(listPage, pages - 1);
        ui.set("#GuildCount.TextSpans", Message.raw(shown.size() + (shown.size() == 1 ? " guild" : " guilds")));
        if (selectedGuild == null || shown.stream().noneMatch(e -> e.profile.id().equals(selectedGuild))) {
            var invited = member ? null : shown.stream().filter(e -> snapshot.invites.containsKey(e.profile.id())).findFirst().orElse(null);
            selectedGuild = invited != null ? invited.profile.id() : shown.isEmpty() ? null : shown.getFirst().profile.id();
            if (invited != null) {
                int at = shown.indexOf(invited); listPage = at / ROWS;
                if (status.isEmpty()) status = invited.profile.name() + " invited you to join. Press Accept invitation.";
            }
        }
        int i = 0;
        for (var e : shown.subList(listPage * ROWS, Math.min(shown.size(), (listPage + 1) * ROWS))) {
            String row = "#Guilds[" + i + "]";
            ui.append("#Guilds", e.profile.id().equals(selectedGuild) ? "EterniaMod/GuildRowSelected.ui" : "EterniaMod/GuildRow.ui");
            paint(ui, row + " #RField", row + " #RPat", row + " #REmb", e.profile.banner());
            ui.set(row + " #RName.TextSpans", Message.raw(e.profile.name() + (e.profile.tag().isEmpty() ? "" : "  [" + e.profile.tag() + "]")));
            ui.set(row + " #RMotto.TextSpans", Message.raw(e.profile.motto().isEmpty() ? "No motto yet." : e.profile.motto()));
            ui.set(row + " #RRule.TextSpans", Message.raw(e.profile.join().label));
            ui.set(row + " #ROnline.TextSpans", Message.raw(e.online.size() + " online  ·  " + e.members + (e.members == 1 ? " member" : " members")));
            bind(events, row, "pick:" + e.profile.id());
            i++;
        }
        ui.set("#BrowseStatus.TextSpans", Message.raw(busy ? "Loading…" : shown.isEmpty() ? (loaded ? "No guilds match. Start the first one!" : status) : status.isEmpty() ? "Page " + (listPage + 1) + " of " + pages : status));
        button(ui, events, "#ListPrev", "Earlier", "list_prev", true); ui.set("#ListPrev.Disabled", busy || listPage == 0);
        button(ui, events, "#ListNext", "More", "list_next", true); ui.set("#ListNext.Disabled", busy || listPage + 1 >= pages);

        var e = snapshot.guilds.stream().filter(g -> g.profile.id().equals(selectedGuild)).findFirst().orElse(null);
        ui.set("#Detail.Visible", e != null);
        if (e == null) return;
        var p = e.profile;
        paint(ui, "#DField", "#DPat", "#DEmb", p.banner());
        ui.set("#DName.TextSpans", Message.raw(fit(p.name(), 26)));
        ui.set("#DTag.TextSpans", Message.raw((p.tag().isEmpty() ? "" : "[" + p.tag() + "]  ·  ") + "Led by " + e.leaderName));
        ui.set("#DRule.TextSpans", Message.raw(p.join().label));
        ui.set("#DMotto.TextSpans", Message.raw(p.motto().isEmpty() ? "This guild has not written a motto yet." : p.motto()));
        ui.set("#DMembers.TextSpans", Message.raw(Integer.toString(e.members)));
        ui.set("#DOnlineCount.TextSpans", Message.raw(Integer.toString(e.online.size())));
        ui.set("#DEstate.TextSpans", Message.raw(e.estate ? "Yes" : "Not yet"));
        drawOnline(ui, "#DOnline", e.online, 5, false);
        String invitation = snapshot.invites.get(p.id());
        String label, route, note;
        if (member && p.id().equals(snapshot.mine.profile.id())) { label = "Open my guild"; route = "mine"; note = "This is your guild."; }
        else if (member) { label = "You are in a guild"; route = ""; note = "Leave your guild first to join another one."; }
        else if (invitation != null) { label = "Accept invitation"; route = "accept:" + invitation; note = "This guild invited you."; }
        else switch (p.join()) {
            case OPEN -> { label = "Join " + p.name(); route = "join:" + p.id(); note = "Anyone can join. You can leave any time."; }
            case ASK -> {
                boolean asked = snapshot.requested.contains(p.id());
                label = asked ? "Cancel my request" : "Ask to join"; route = (asked ? "unask:" : "ask:") + p.id();
                note = asked ? "You asked to join. An officer will answer soon." : "An officer will look at your request.";
            }
            default -> { label = "Invite only"; route = ""; note = "Ask a member of this guild to invite you."; }
        }
        button(ui, events, "#DJoin", label, route, true); ui.set("#DJoin.Disabled", busy || route.isEmpty());
        ui.set("#DNote.TextSpans", Message.raw(note));
    }

    private void drawOnline(UICommandBuilder ui, String list, List<Person> people, int limit, boolean roles) {
        int i = 0;
        for (var person : people) {
            if (i >= limit) break;
            String row = list + "[" + i + "]";
            ui.append(list, "EterniaMod/GuildOnline.ui");
            ui.set(row + " #OnInitial.TextSpans", Message.raw(initial(person.name)));
            ui.setObject(row + " #OnDot.Background", colour("#6fd08c"));
            ui.set(row + " #OnName.TextSpans", Message.raw(person.name));
            ui.set(row + " #OnRole.TextSpans", Message.raw(roles ? fit(person.roleName, 14) : ""));
            ui.setObject(row + " #OnWorldDot.Background", colour(worldColour(person.world)));
            ui.set(row + " #OnWorld.TextSpans", Message.raw(person.world));
            ui.set(row + " #OnWorld.Style.TextColor", worldColour(person.world));
            i++;
        }
        if (people.isEmpty()) {
            ui.append(list, "EterniaMod/GuildOnline.ui");
            ui.set(list + "[0] #OnTile.Visible", false); ui.set(list + "[0] #OnDot.Visible", false); ui.set(list + "[0] #OnWorldDot.Visible", false);
            ui.set(list + "[0] #OnName.TextSpans", Message.raw("Nobody is online right now."));
            ui.set(list + "[0] #OnName.Style.TextColor", "#b5c3b8");
        }
    }

    private void drawDesign(UICommandBuilder ui, UIEventBuilder events) {
        top(ui, events, settings ? "Guild settings" : "Start a guild", settings ? "Change your banner, motto and who can join" : "Your banner updates as you go", "", "");
        ui.set("#Name.Visible", !settings); ui.set("#NameFixed.Visible", settings);
        if (settings && snapshot.mine != null) ui.set("#NameFixed.TextSpans", Message.raw(snapshot.mine.profile.name()));
        for (var r : GuildService.JoinRule.values()) chip(ui, events, "#Rules", r.label, "rule:" + r.name(), r == rule);
        for (var l : Layer.values()) chip(ui, events, "#Layers", l.label, "layer:" + l.name(), l == layer);
        button(ui, events, "#Dice", "Surprise me", "dice", true);
        int i = 0;
        switch (layer) {
            case FIELD, PATTERN_COLOR, EMBLEM_COLOR -> {
                String current = layer == Layer.FIELD ? banner.field() : layer == Layer.PATTERN_COLOR ? banner.patternColor() : banner.emblemColor();
                for (var c : GuildBanner.COLOURS) {
                    String cell = "#Picker[" + i++ + "]";
                    ui.append("#Picker", c.id().equals(current) ? "EterniaMod/GuildSwatchOn.ui" : "EterniaMod/GuildSwatch.ui");
                    ui.setObject(cell + " #SwColour.Background", colour(c.hex()));
                    ui.set(cell + ".TooltipText", c.name());
                    bind(events, cell, "colour:" + c.id());
                }
                ui.set("#PickerHint.TextSpans", Message.raw(layer.label + ": " + GuildBanner.colourName(current) + (layer == Layer.PATTERN_COLOR && banner.pattern().equals("plain") ? ". Pick a pattern to see this colour." : "")));
            }
            case PATTERN -> {
                for (var p : GuildBanner.PATTERNS) {
                    String cell = "#Picker[" + i++ + "]";
                    ui.append("#Picker", p.id().equals(banner.pattern()) ? "EterniaMod/GuildTileOn.ui" : "EterniaMod/GuildTile.ui");
                    paint(ui, cell + " #TField", cell + " #TPat", cell + " #TEmb", banner.withPattern(p.id()));
                    ui.set(cell + " #TEmb.Visible", false);
                    ui.set(cell + ".TooltipText", p.name());
                    bind(events, cell, "pattern:" + p.id());
                }
                ui.set("#PickerHint.TextSpans", Message.raw("Pattern: " + GuildBanner.name(GuildBanner.PATTERNS, banner.pattern()) + ". " + GuildBanner.PATTERNS.size() + " to choose from."));
            }
            case EMBLEM -> {
                for (var e : GuildBanner.EMBLEMS) {
                    String cell = "#Picker[" + i++ + "]";
                    ui.append("#Picker", e.id().equals(banner.emblem()) ? "EterniaMod/GuildTileOn.ui" : "EterniaMod/GuildTile.ui");
                    paint(ui, cell + " #TField", cell + " #TPat", cell + " #TEmb", banner.withEmblem(e.id()));
                    ui.set(cell + ".TooltipText", e.name());
                    bind(events, cell, "emblem:" + e.id());
                }
                ui.set("#PickerHint.TextSpans", Message.raw("Emblem: " + GuildBanner.name(GuildBanner.EMBLEMS, banner.emblem()) + ". " + GuildBanner.EMBLEMS.size() + " to choose from."));
            }
        }
        paint(ui, "#PField", "#PPat", "#PEmb", banner);
        String shownName = settings && snapshot.mine != null ? snapshot.mine.profile.name() : name.isBlank() ? "Your guild" : name.strip();
        ui.set("#PName.TextSpans", Message.raw(fit(shownName, 26)));
        String shownTag = tag.strip().toUpperCase(Locale.ROOT);
        ui.set("#PLine.TextSpans", Message.raw((shownTag.isEmpty() ? "" : "[" + shownTag + "]  ·  ") + rule.label));
        ui.set("#PMotto.TextSpans", Message.raw(motto.isBlank() ? "" : "“" + motto.strip() + "”"));
        ui.set("#DesignStatus.TextSpans", Message.raw(busy ? "Saving…" : status));
        button(ui, events, "#DesignCancel", "Cancel", settings ? "mine" : "browse", true);
        button(ui, events, "#DesignSave", settings ? "Save changes" : "Start the guild", settings ? "save" : "found", true);
    }

    private void drawGuild(UICommandBuilder ui, UIEventBuilder events) {
        var mine = snapshot.mine;
        if (mine == null) { view = View.BROWSE; drawBrowse(ui, events); ui.set("#BrowseView.Visible", true); ui.set("#GuildView.Visible", false); return; }
        var p = mine.profile;
        boolean canInvite = snapshot.caps.contains("member.invite");
        top(ui, events, p.name(), (p.tag().isEmpty() ? "" : "[" + p.tag() + "]  ·  ") + mine.online().size() + " of " + mine.members.size() + " online",
            canInvite ? "Invite a player" : "", "invite_tab");
        paint(ui, "#SField", "#SPat", "#SEmb", p.banner());
        ui.set("#SName.TextSpans", Message.raw(fit(p.name(), 22)));
        ui.set("#SRole.TextSpans", Message.raw("You are " + article(snapshot.myRoleName) + " " + snapshot.myRoleName));
        int t = 0;
        t = sideTab(ui, events, t, "Overview", "guild", "tab:OVERVIEW", tab == Tab.OVERVIEW);
        int requests = mine.requests.size();
        t = sideTab(ui, events, t, requests > 0 ? "Members (" + requests + " asking)" : "Members", "members", "tab:MEMBERS", tab == Tab.MEMBERS);
        t = sideTab(ui, events, t, "Notice board", "board", "board", false);
        t = sideTab(ui, events, t, "Treasury", "crown", "treasury", false);
        t = sideTab(ui, events, t, "Estate", "charter", "estate", false);
        t = sideTab(ui, events, t, "Roles", "title", "roles", false);
        if (snapshot.caps.contains("role.edit")) t = sideTab(ui, events, t, "Settings", "palette", "settings", false);
        sideTab(ui, events, t, "All guilds", "worlds", "browse", false);
        boolean leader = "leader".equals(snapshot.myRole);
        button(ui, events, "#Leave", confirm.equals("leave") ? "Press again to leave" : "Leave guild", "leave", true);
        ui.set("#Leave.Disabled", busy || leader);
        if (leader) ui.set("#Leave.TooltipText", "Make someone else the leader before you leave.");
        ui.set("#OverviewPane.Visible", tab == Tab.OVERVIEW);
        ui.set("#MembersPane.Visible", tab == Tab.MEMBERS);
        if (tab == Tab.OVERVIEW) drawOverview(ui, events, mine); else drawMembers(ui, events, mine, canInvite);
    }

    private int sideTab(UICommandBuilder ui, UIEventBuilder events, int i, String label, String icon, String route, boolean on) {
        String row = "#Tabs[" + i + "]";
        ui.append("#Tabs", on ? "EterniaMod/SideTabSelected.ui" : "EterniaMod/SideTab.ui");
        ui.set(row + " #SideTabLabel.TextSpans", Message.raw(label));
        ui.setObject(row + " #SideTabIcon.Background", new PatchStyle(Value.of("EterniaMod/Icons/" + icon + ".png")));
        bind(events, row, route);
        return i + 1;
    }

    private void drawOverview(UICommandBuilder ui, UIEventBuilder events, Mine mine) {
        var p = mine.profile;
        ui.set("#OMotto.TextSpans", Message.raw(p.motto().isEmpty() ? "Welcome to " + p.name() + "." : p.motto()));
        ui.set("#ORule.TextSpans", Message.raw(p.join().label + (mine.requests.isEmpty() ? "" : "  ·  " + mine.requests.size() + " asking to join")));
        ui.set("#OMembers.TextSpans", Message.raw(Integer.toString(mine.members.size())));
        ui.set("#OOnline.TextSpans", Message.raw(Integer.toString(mine.online().size())));
        ui.set("#OCrowns.TextSpans", Message.raw(String.format(Locale.ROOT, "%,d", mine.crowns)));
        ui.set("#OEstate.TextSpans", Message.raw(mine.estate ? "Yes" : "Not yet"));
        var online = mine.online();
        ui.set("#OOnlineNote.TextSpans", Message.raw(online.size() + " of " + mine.members.size()));
        drawOnline(ui, "#OOnlineList", online, ONLINE_SHOWN * 3, true);
        int i = 0;
        for (var post : mine.notices) {
            if (i >= 4) break;
            String row = "#ONotices[" + i++ + "]";
            ui.append("#ONotices", "EterniaMod/GuildNotice.ui");
            ui.set(row + " #NTitle.TextSpans", Message.raw((post.pinned() ? "Pinned: " : "") + post.title()));
            ui.set(row + " #NBy.TextSpans", Message.raw("By " + post.authorName()));
        }
        if (mine.notices.isEmpty()) {
            ui.append("#ONotices", "EterniaMod/GuildNotice.ui");
            ui.set("#ONotices[0] #NTitle.TextSpans", Message.raw("No notices yet."));
            ui.set("#ONotices[0] #NBy.TextSpans", Message.raw("Pin plans and news on the notice board."));
        }
        button(ui, events, "#OBoard", "Open board", "board", true);
        ui.set("#OverviewStatus.TextSpans", Message.raw(busy ? "Loading…" : status));
    }

    private void drawMembers(UICommandBuilder ui, UIEventBuilder events, Mine mine, boolean canInvite) {
        chip(ui, events, "#MemberFilters", "Everyone (" + mine.members.size() + ")", "mf:ALL", memberFilter == MemberFilter.ALL);
        chip(ui, events, "#MemberFilters", "Online (" + mine.online().size() + ")", "mf:ONLINE", memberFilter == MemberFilter.ONLINE);
        if (canInvite) chip(ui, events, "#MemberFilters", "Requests (" + mine.requests.size() + ")", "mf:REQUESTS", memberFilter == MemberFilter.REQUESTS);
        ui.set("#InviteName.Visible", canInvite);
        button(ui, events, "#InviteSend", "Send invite", "invite", canInvite);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#InviteName", data("invite"), false);
        ui.set("#FriendInvitesRow.Visible", canInvite && !snapshot.friendsToInvite.isEmpty());
        for (var f : snapshot.friendsToInvite.entrySet()) chip(ui, events, "#FriendInvites", fit(f.getValue(), 16), "finvite:" + f.getKey(), false);
        ui.set("#MemberHead.Visible", memberFilter != MemberFilter.REQUESTS);
        int i = 0;
        if (memberFilter == MemberFilter.REQUESTS) {
            for (var r : mine.requests) {
                if (i >= MEMBERS) break;
                String row = "#Members[" + i++ + "]";
                ui.append("#Members", "EterniaMod/GuildRequest.ui");
                ui.set(row + " #QInitial.TextSpans", Message.raw(initial(r.name)));
                ui.set(row + " #QName.TextSpans", Message.raw(r.name));
                ui.set(row + " #QWhen.TextSpans", Message.raw("Asked " + ago(r.at)));
                button(ui, events, row + " #QAccept", "Accept", "req_yes:" + r.player, true);
                button(ui, events, row + " #QDecline", "Decline", "req_no:" + r.player, true);
            }
            ui.set("#MembersStatus.TextSpans", Message.raw(busy ? "Saving…" : !status.isEmpty() ? status : mine.requests.isEmpty() ? "Nobody is asking to join right now." : "Accepted players join as members."));
            ui.set("#MemPrev.Visible", false); ui.set("#MemNext.Visible", false); ui.set("#MemberActions.Visible", false);
            return;
        }
        var list = memberFilter == MemberFilter.ONLINE ? mine.online() : mine.members;
        int pages = Math.max(1, (list.size() + MEMBERS - 1) / MEMBERS); memberPage = Math.min(memberPage, pages - 1);
        for (var m : list.subList(memberPage * MEMBERS, Math.min(list.size(), (memberPage + 1) * MEMBERS))) {
            String row = "#Members[" + i++ + "]";
            ui.append("#Members", m.id.equals(selectedMember) ? "EterniaMod/GuildMemberSelected.ui" : "EterniaMod/GuildMember.ui");
            ui.set(row + " #MInitial.TextSpans", Message.raw(initial(m.name)));
            ui.setObject(row + " #MDot.Background", colour(m.online() ? "#6fd08c" : "#5a6b68"));
            ui.set(row + " #MName.TextSpans", Message.raw(m.name + (m.id.equals(actor) ? " (you)" : "")));
            ui.set(row + " #MRole.TextSpans", Message.raw(fit(m.roleName, 22)));
            ui.set(row + " #MRole.Style.TextColor", m.roleId.equals("leader") ? "#d8b66b" : m.rank <= 10 ? "#85c5b3" : "#b5c3b8");
            if (m.online()) {
                ui.setObject(row + " #MWorldDot.Background", colour(worldColour(m.world)));
                ui.set(row + " #MWhere.TextSpans", Message.raw(m.world));
                ui.set(row + " #MWhere.Style.TextColor", worldColour(m.world));
            } else {
                ui.set(row + " #MWorldDot.Visible", false);
                ui.set(row + " #MWhere.TextSpans", Message.raw(m.lastSeen == null ? "Offline" : "Last seen " + ago(m.lastSeen)));
                ui.set(row + " #MWhere.Style.TextColor", "#8a9a94");
            }
            bind(events, row, "member:" + m.id);
        }
        ui.set("#MembersStatus.TextSpans", Message.raw(busy ? "Saving…" : !status.isEmpty() ? status : "Click a member to change their role."));
        button(ui, events, "#MemPrev", "Earlier", "mem_prev", pages > 1); ui.set("#MemPrev.Disabled", busy || memberPage == 0);
        button(ui, events, "#MemNext", "More", "mem_next", pages > 1); ui.set("#MemNext.Disabled", busy || memberPage + 1 >= pages);

        var chosen = mine.members.stream().filter(m -> m.id.equals(selectedMember)).findFirst().orElse(null);
        ui.set("#MemberActions.Visible", chosen != null);
        if (chosen == null) return;
        ui.set("#ActName.TextSpans", Message.raw(chosen.name));
        ui.set("#ActRole.TextSpans", Message.raw(chosen.roleName + "  ·  " + (chosen.online() ? "In " + chosen.world : chosen.lastSeen == null ? "Offline" : "Last seen " + ago(chosen.lastSeen))));
        int myRank = mine.members.stream().filter(m -> m.id.equals(actor)).mapToInt(m -> m.rank).findFirst().orElse(Integer.MAX_VALUE);
        boolean below = !chosen.id.equals(actor) && myRank < chosen.rank;
        int r = 0;
        if (below && snapshot.caps.contains("member.role.assign")) {
            for (var role : mine.roles) {
                if (role.rank() <= myRank || r >= 6) continue;
                chip(ui, events, "#RoleButtons", fit(role.name(), 16), role.id().equals(chosen.roleId) ? "" : "assign:" + role.id(), role.id().equals(chosen.roleId));
                r++;
            }
        }
        if (r == 0) {
            ui.appendInline("#RoleButtons", "Label { Anchor: (Height: 40); Style: (FontSize: 14, TextColor: #b5c3b8, VerticalAlignment: Center); }");
            ui.set("#RoleButtons[0].TextSpans", Message.raw(chosen.id.equals(actor) ? "This is you." : "Only higher roles can change this member."));
        }
        boolean transfer = !chosen.id.equals(actor) && snapshot.caps.contains("leadership.transfer");
        button(ui, events, "#MakeLeader", confirm.equals("lead:" + chosen.id) ? "Press again" : "Make leader", "lead:" + chosen.id, transfer);
        boolean kick = below && snapshot.caps.contains("member.remove");
        button(ui, events, "#Kick", confirm.equals("kick:" + chosen.id) ? "Press again" : "Remove", "kick:" + chosen.id, kick);
    }

    // ------------------------------------------------------------------------------------------------ small drawing helpers

    private static void paint(UICommandBuilder ui, String field, String pattern, String emblem, GuildBanner b) {
        ui.setObject(field + ".Background", tinted(GuildBanner.ART + "Field.png", GuildBanner.hex(b.field())));
        boolean plain = b.pattern().equals("plain");
        ui.set(pattern + ".Visible", !plain);
        if (!plain) ui.setObject(pattern + ".Background", tinted(GuildBanner.ART + "Pattern_" + b.pattern() + ".png", GuildBanner.hex(b.patternColor())));
        ui.setObject(emblem + ".Background", tinted(GuildBanner.ART + "Emblem_" + b.emblem() + ".png", GuildBanner.hex(b.emblemColor())));
    }
    private static PatchStyle tinted(String path, String hex) { return new PatchStyle(Value.of(path)).setColor(Value.of(hex)); }
    private static PatchStyle colour(String hex) { return new PatchStyle().setColor(Value.of(hex)); }

    private void chip(UICommandBuilder ui, UIEventBuilder events, String list, String label, String route, boolean on) {
        int index = chipCounts.merge(list, 1, Integer::sum) - 1;
        String cell = list + "[" + index + "]";
        ui.append(list, on ? "EterniaMod/GuildChipOn.ui" : "EterniaMod/GuildChip.ui");
        ui.set(cell + ".TextSpans", Message.raw(label));
        ui.set(cell + ".Disabled", busy);
        if (!route.isEmpty()) bind(events, cell, route);
    }
    private final Map<String, Integer> chipCounts = new HashMap<>();

    private void button(UICommandBuilder ui, UIEventBuilder events, String selector, String label, String route, boolean visible) {
        ui.set(selector + ".TextSpans", Message.raw(label)); ui.set(selector + ".Visible", visible); ui.set(selector + ".Disabled", busy || route.isEmpty());
        if (visible && !route.isEmpty()) bind(events, selector, route);
    }

    private void bind(UIEventBuilder events, String selector, String route) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, data(route), false);
    }

    private EventData data(String route) {
        String key = generation + ":" + routes.size(); routes.put(key, route);
        var d = new EventData().append("Action", key).append("@Search", "#Search.Value");
        if (view == View.DESIGN) d = d.append("@Name", "#Name.Value").append("@Tag", "#Tag.Value").append("@Motto", "#Motto.Value");
        if (view == View.GUILD && tab == Tab.MEMBERS) d = d.append("@Invite", "#InviteName.Value");
        return d;
    }

    /** Shortens text for narrow spots, ending in an ellipsis. */
    private static String fit(String text, int max) { return text.length() <= max ? text : text.substring(0, max - 1).stripTrailing() + "…"; }
    private static String initial(String name) { return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT); }
    private static String article(String word) { return !word.isEmpty() && "AEIOUaeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a"; }
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
        return d.toDays() + " days ago";
    }

    private List<Entry> shownGuilds() {
        String q = search.strip().toLowerCase(Locale.ROOT);
        var out = new ArrayList<Entry>();
        for (var e : snapshot.guilds) {
            if (!q.isEmpty() && !e.profile.name().toLowerCase(Locale.ROOT).contains(q) && !e.profile.tag().toLowerCase(Locale.ROOT).contains(q)) continue;
            if (filter == Filter.OPEN && e.profile.join() == GuildService.JoinRule.INVITE) continue;
            if (filter == Filter.ESTATE && !e.estate) continue;
            out.add(e);
        }
        Comparator<Entry> order = filter == Filter.ONLINE ? Comparator.comparingInt((Entry e) -> e.online.size()).reversed().thenComparing(e -> -e.members)
            : Comparator.comparingInt((Entry e) -> e.online.size()).reversed().thenComparing(e -> e.profile.name().toLowerCase(Locale.ROOT));
        out.sort(order);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ events

    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (isDismissed() || registration.closed || busy) return;
        String route = routes.get(data.action); if (route == null) return;
        if (data.search != null) search = cut(data.search, 48);
        if (view == View.DESIGN) { if (data.name != null) name = cut(data.name, 48); if (data.tag != null) tag = cut(data.tag, 4); if (data.motto != null) motto = cut(data.motto, GuildService.MOTTO_MAX); }
        if (data.invite != null) invite = cut(data.invite, 32);
        String wasConfirm = confirm; confirm = "";
        int colon = route.indexOf(':');
        String verb = colon < 0 ? route : route.substring(0, colon), arg = colon < 0 ? "" : route.substring(colon + 1);
        switch (verb) {
            case "search" -> { listPage = 0; status = ""; redraw(); }
            case "filter" -> { filter = Filter.valueOf(arg); listPage = 0; status = ""; redraw(); }
            case "pick" -> { selectedGuild = UUID.fromString(arg); status = ""; redraw(); }
            case "list_prev" -> { listPage = Math.max(0, listPage - 1); redraw(); }
            case "list_next" -> { listPage++; redraw(); }
            case "browse" -> { view = View.BROWSE; status = ""; refresh(""); }
            case "mine" -> { view = snapshot.mine == null ? View.BROWSE : View.GUILD; tab = Tab.OVERVIEW; status = ""; refresh(""); }
            case "create" -> { view = View.DESIGN; settings = false; status = ""; receipt = "guild-create:" + UUID.randomUUID(); redraw(); }
            case "settings" -> {
                var mine = snapshot.mine; if (mine == null) return;
                view = View.DESIGN; settings = true; status = "";
                tag = mine.profile.tag(); motto = mine.profile.motto(); rule = mine.profile.join(); banner = mine.profile.banner(); redraw();
            }
            case "rule" -> { rule = GuildService.JoinRule.valueOf(arg); redraw(); }
            case "layer" -> { layer = Layer.valueOf(arg); redraw(); }
            case "dice" -> { banner = GuildBanner.random(dice); redraw(); }
            case "colour" -> {
                banner = switch (layer) { case FIELD -> banner.withField(arg); case PATTERN_COLOR -> banner.withPatternColor(arg); case EMBLEM_COLOR -> banner.withEmblemColor(arg); default -> banner; };
                redraw();
            }
            case "pattern" -> { banner = banner.withPattern(arg); redraw(); }
            case "emblem" -> { banner = banner.withEmblem(arg); redraw(); }
            case "found" -> act(() -> { services.guilds().create(actor, name, tag, motto, banner, rule, receipt); return null; }, v -> { view = View.GUILD; tab = Tab.OVERVIEW; }, "Your guild is ready. Invite your friends!");
            case "save" -> act(() -> services.guilds().updateProfile(actor, tag, motto, banner, rule), v -> { view = View.GUILD; tab = Tab.OVERVIEW; }, "Guild settings saved.");
            case "join" -> act(() -> services.guilds().join(actor, UUID.fromString(arg)), v -> { view = View.GUILD; tab = Tab.OVERVIEW; }, "Welcome to your new guild!");
            case "ask" -> act(() -> { services.guilds().requestJoin(actor, UUID.fromString(arg)); return null; }, v -> {}, "Request sent. An officer will answer soon.");
            case "unask" -> act(() -> { services.guilds().cancelRequest(actor, UUID.fromString(arg)); return null; }, v -> {}, "Request cancelled.");
            case "accept" -> act(() -> services.guilds().acceptInvite(actor, arg), v -> { view = View.GUILD; tab = Tab.OVERVIEW; }, "Welcome to your new guild!");
            case "tab" -> { tab = Tab.valueOf(arg); selectedMember = null; status = ""; refresh(""); }
            case "invite_tab" -> { tab = Tab.MEMBERS; memberFilter = MemberFilter.ALL; status = "Type a player's name, then press Send invite."; redraw(); }
            case "mf" -> { memberFilter = MemberFilter.valueOf(arg); memberPage = 0; selectedMember = null; status = ""; redraw(); }
            case "mem_prev" -> { memberPage = Math.max(0, memberPage - 1); redraw(); }
            case "mem_next" -> { memberPage++; redraw(); }
            case "member" -> { UUID id = UUID.fromString(arg); selectedMember = id.equals(selectedMember) ? null : id; status = ""; redraw(); }
            case "invite" -> {
                String who = invite.strip();
                if (who.isEmpty()) { status = "Type a player's name first."; redraw(); return; }
                act(() -> {
                    var account = services.accounts().findByName(who).orElseThrow(() -> new IllegalArgumentException("Nobody called " + who + " has played here yet."));
                    services.guilds().invite(actor, account.id()); return account.displayName();
                }, shown -> invite = "", "Invitation sent to " + who + ". They can accept it from Guilds in their Journal.");
            }
            case "finvite" -> {
                UUID target = UUID.fromString(arg); String who = snapshot.friendsToInvite.getOrDefault(target, "your friend");
                String me = playerRef.getUsername();
                act(() -> services.guilds().invite(actor, target), v -> FriendNotices.tell(target, me + " invited you to their guild. Open Guild in your Journal to accept."), "Invitation sent to " + who + ".");
            }
            case "req_yes" -> act(() -> services.guilds().answerRequest(actor, UUID.fromString(arg), true), ok -> { if (!ok) status = "They already joined another guild."; }, "They joined the guild.");
            case "req_no" -> act(() -> services.guilds().answerRequest(actor, UUID.fromString(arg), false), ok -> {}, "Request declined.");
            case "assign" -> { UUID target = selectedMember; if (target == null) return; act(() -> { services.guilds().assignRole(actor, target, arg); return null; }, v -> {}, "Role changed."); }
            case "kick", "lead", "leave" -> {
                if (!wasConfirm.equals(route)) { confirm = route; status = verb.equals("leave") ? "Press Leave again to leave the guild." : "Press again to confirm."; redraw(); return; }
                if (verb.equals("leave")) act(() -> { services.guilds().leave(actor); return null; }, v -> { view = View.BROWSE; }, "You left the guild.");
                else if (verb.equals("kick")) act(() -> { services.guilds().remove(actor, UUID.fromString(arg)); return null; }, v -> selectedMember = null, "Member removed.");
                else act(() -> { services.guilds().transferLeadership(actor, UUID.fromString(arg)); return null; }, v -> {}, "Leadership handed over.");
            }
            case "board" -> registration.openBoard(ref, store, playerRef);
            case "estate" -> {
                var result = registration.actions.perform(SocialUiActions.Action.GUILD_HOUSING, ref, store, playerRef);
                if (!result.opened()) { status = result.message(); redraw(); }
            }
            case "treasury" -> openTreasury(ref, store);
            case "roles" -> registration.openGuildRoles(ref, store, playerRef);
            default -> { }
        }
    }

    private void openTreasury(Ref<EntityStore> ref, Store<EntityStore> store) {
        var plugin = EterniaModPlugin.get(); if (plugin == null) return;
        ChoicePage.open(ref, store, playerRef, "Guild Treasury", "Give your own Coins or Crowns to your guild. Gifts stay with the guild.",
            List.of(new ChoicePage.Choice("See and give the guild's Crowns, spent on more guild plots and hexagons", "Guild Crowns",
                    (r, s) -> com.hexvane.eterniamod.runtime.GuildCrownsMenu.open(plugin, r, s, playerRef)),
                new ChoicePage.Choice("See and give the guild's Coins, spent on guild land in adventure worlds", "Guild Coins",
                    (r, s) -> com.hexvane.eterniamod.wildland.GuildCoinsMenu.open(plugin, r, s, playerRef))));
    }

    // ------------------------------------------------------------------------------------------------ loading

    private void refresh(String notice) { work(this::load, notice); }

    /** Runs one guild action, reloads, then applies {@code after} to the action's result on the world thread. */
    private <T> void act(Supplier<T> action, java.util.function.Consumer<T> after, String notice) {
        busy = true; long version = ++generation; rebuild();
        var ref = playerRef.getReference(); if (ref == null || !ref.isValid()) return;
        var world = ref.getStore().getExternalData().getWorld();
        CompletableFuture.supplyAsync(() -> { T result = action.get(); return new Object[] {result, load()}; }, registration.executor)
            .whenComplete((pair, error) -> world.execute(() -> {
                if (stale(ref, version)) return;
                busy = false;
                if (error != null) { status = message(error); redraw(); return; }
                snapshot = (Snapshot) pair[1]; loaded = true; status = notice;
                @SuppressWarnings("unchecked") T result = (T) pair[0];
                after.accept(result);
                if (view == View.GUILD && snapshot.mine == null) view = View.BROWSE;
                redraw();
            }));
    }

    private void work(Supplier<Snapshot> task, String notice) {
        busy = true; long version = ++generation;
        var ref = playerRef.getReference(); if (ref == null || !ref.isValid()) return;
        var world = ref.getStore().getExternalData().getWorld();
        CompletableFuture.supplyAsync(task, registration.executor).whenComplete((result, error) -> world.execute(() -> {
            if (stale(ref, version)) return;
            busy = false;
            if (error != null) { status = message(error); redraw(); return; }
            boolean first = !loaded;
            snapshot = result; loaded = true; status = notice;
            if (first && snapshot.mine != null) view = View.GUILD;
            if (view == View.GUILD && snapshot.mine == null) view = View.BROWSE;
            redraw();
        }));
    }

    private boolean stale(Ref<EntityStore> ref, long version) { return isDismissed() || registration.closed || !ref.isValid() || version != generation; }

    private static String message(Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        return cause instanceof DomainException || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
            ? cause.getMessage() : "The guild hall could not finish that. Try again in a moment.";
    }

    private void redraw() { generation++; rebuild(); }

    /** Everything the screen shows, read on the menu executor. Online players and their worlds come from the live server. */
    private Snapshot load() {
        var guilds = services.guilds();
        Map<UUID, String> worlds = new HashMap<>(), names = new HashMap<>();
        var universe = Universe.get();
        if (universe != null) for (PlayerRef p : List.copyOf(universe.getPlayers())) { worlds.put(p.getUuid(), worldName(universe, p)); names.put(p.getUuid(), p.getUsername()); }
        var onlineByGuild = new HashMap<UUID, List<Person>>();
        for (var id : worlds.keySet()) guilds.membership(id).ifPresent(m -> onlineByGuild.computeIfAbsent(m.guildId(), k -> new ArrayList<>())
            .add(new Person(id, names.get(id), m.role(), "", 100, worlds.get(id), null)));

        var listing = guilds.directory();
        var leaders = services.accounts().findAll(listing.stream().map(l -> l.profile().leader()).toList());
        var entries = new ArrayList<Entry>();
        for (var l : listing) {
            var online = new ArrayList<>(onlineByGuild.getOrDefault(l.profile().id(), List.of()));
            online.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
            boolean estate = services.housing().homeLocation(Owner.guild(l.profile().id())).isPresent();
            String leader = Optional.ofNullable(leaders.get(l.profile().leader())).map(AccountService.Account::displayName).orElse("someone");
            entries.add(new Entry(l.profile(), l.members(), estate, List.copyOf(online), leader));
        }
        var invites = new HashMap<UUID, String>();
        for (var inv : guilds.invitations(actor)) invites.put(inv.guild(), inv.id());
        var requested = guilds.pendingRequests(actor);

        var membership = guilds.membership(actor);
        if (membership.isEmpty()) return new Snapshot(List.copyOf(entries), Map.copyOf(invites), requested, null, "", "", Set.of(), Map.of());
        UUID guildId = membership.get().guildId();
        var profile = guilds.profile(guildId).orElseThrow();
        var roles = guilds.roles(actor);
        var roleById = new HashMap<String, GuildService.Role>(); roles.forEach(r -> roleById.put(r.id(), r));
        var roster = guilds.roster(actor);
        var requests = guilds.requests(actor);
        var ids = new ArrayList<UUID>(); roster.forEach(m -> ids.add(m.playerId())); requests.forEach(r -> ids.add(r.player()));
        var accounts = services.accounts().findAll(ids);
        var members = new ArrayList<Person>();
        for (var m : roster) {
            var role = roleById.get(m.role());
            var account = accounts.get(m.playerId());
            String shown = account != null ? account.displayName() : names.getOrDefault(m.playerId(), "Unknown");
            Instant seen = account == null ? null : account.lastDisconnect() != null ? account.lastDisconnect() : account.lastSeen();
            members.add(new Person(m.playerId(), shown, m.role(), role == null ? UiPresentation.friendlyId(m.role()) : role.name(), role == null ? 1000 : role.rank(), worlds.get(m.playerId()), seen));
        }
        members.sort(Comparator.comparing((Person p) -> !p.online()).thenComparingInt(p -> p.rank).thenComparing(p -> p.name.toLowerCase(Locale.ROOT)));
        var asking = new ArrayList<Request>();
        for (var r : requests) asking.add(new Request(r.player(), Optional.ofNullable(accounts.get(r.player())).map(AccountService.Account::displayName).orElse("Unknown"), r.at()));
        long crowns = services.guildCrowns().balance(guildId);
        boolean estate = services.housing().homeLocation(Owner.guild(guildId)).isPresent();
        var notices = new ArrayList<Notice>();
        try {
            var posts = services.guildCommunications().board(actor);
            var authors = services.accounts().findAll(posts.stream().map(GuildCommunicationService.Post::author).toList());
            for (var post : posts) notices.add(new Notice(post.title(), Optional.ofNullable(authors.get(post.author())).map(AccountService.Account::displayName).orElse("a member"), post.pinned()));
        } catch (DomainException noBoardAccess) { /* their role may not read the board */ }
        var mine = new Mine(profile, List.copyOf(members), roles, List.copyOf(asking), crowns, estate, List.copyOf(notices));
        var myRole = roleById.get(membership.get().role());
        var toInvite = new LinkedHashMap<UUID, String>();
        if (myRole != null && myRole.capabilities().contains("member.invite")) {
            var friends = services.friends().friends(actor);
            var friendNames = services.accounts().findAll(friends.stream().map(FriendService.Friend::id).toList());
            for (var f : friends) {
                if (toInvite.size() >= 6 || guilds.membership(f.id()).isPresent() || !friendNames.containsKey(f.id())) continue;
                toInvite.put(f.id(), friendNames.get(f.id()).displayName());
            }
        }
        return new Snapshot(List.copyOf(entries), Map.copyOf(invites), requested, mine, membership.get().role(),
            myRole == null ? "Member" : myRole.name(), myRole == null ? Set.of() : myRole.capabilities(), toInvite);
    }

    private static String worldName(Universe universe, PlayerRef player) {
        UUID id = player.getWorldUuid();
        if (id == null) return "Travelling";
        var world = universe.getWorld(id);
        if (world == null) return "Travelling";
        return WorldDisplayNames.label(world.getName(), world.getWorldConfig().getDisplayName());
    }

    private static String cut(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }

    private record Person(UUID id, String name, String roleId, String roleName, int rank, String world, Instant lastSeen) { boolean online() { return world != null; } }
    private record Entry(GuildService.Profile profile, int members, boolean estate, List<Person> online, String leaderName) {}
    private record Request(UUID player, String name, Instant at) {}
    private record Notice(String title, String authorName, boolean pinned) {}
    private record Mine(GuildService.Profile profile, List<Person> members, List<GuildService.Role> roles, List<Request> requests, long crowns, boolean estate, List<Notice> notices) {
        List<Person> online() { return members.stream().filter(Person::online).toList(); }
    }
    private record Snapshot(List<Entry> guilds, Map<UUID, String> invites, Set<UUID> requested, Mine mine, String myRole, String myRoleName, Set<String> caps, Map<UUID, String> friendsToInvite) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), Set.of(), null, "", "", Set.of(), Map.of());
    }

    public static final class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("@Search", Codec.STRING), (d, v) -> d.search = v, d -> d.search).add()
            .append(new KeyedCodec<>("@Name", Codec.STRING), (d, v) -> d.name = v, d -> d.name).add()
            .append(new KeyedCodec<>("@Tag", Codec.STRING), (d, v) -> d.tag = v, d -> d.tag).add()
            .append(new KeyedCodec<>("@Motto", Codec.STRING), (d, v) -> d.motto = v, d -> d.motto).add()
            .append(new KeyedCodec<>("@Invite", Codec.STRING), (d, v) -> d.invite = v, d -> d.invite).add().build();
        public String action, search, name, tag, motto, invite;
    }
}
