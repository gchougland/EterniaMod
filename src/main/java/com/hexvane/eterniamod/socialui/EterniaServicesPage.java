package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.collections.CollectionBootstrap;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Native service UI. View state is server-owned and all domain I/O runs away from the world tick. */
public final class EterniaServicesPage extends EterniaInteractiveCustomUIPage<EterniaServicesPage.Data> {
    public enum Section {
        GREETER("Welcome"), HOUSING("Housing"), GUILD("Guild"), MAIL("Mailbox"), SHOP("Player shops"),
        QUESTS("Quests"), SEASON("Season passes"), COLLECTION("Collection"), STORE("Crown Store"), WORLDS("Worlds"), COMING_SOON("Minigames");
        final String title;
        Section(String title) { this.title = title; }
    }
    private static final int PAGE_SIZE = 6;
    private static final List<String> EDITABLE_CAPABILITIES = List.of(
        "guild.view", "guild.chat", "board.read", "board.post", "board.moderate", "party.create", "member.invite", "member.remove", "member.role.assign",
        "housing.structure", "housing.palette", "housing.addition", "housing.prop.place", "housing.prop.move", "housing.prop.pack",
        "housing.block.build", "housing.block.break", "housing.road.manage", "inventory.deposit", "inventory.reserve_for_build", "inventory.withdraw",
        "shop.manage", "mail.attachments.claim", "treasury.deposit", "treasury.withdraw", "housing.door.use", "housing.bed.use", "housing.bench.use",
        "housing.container.open", "convenience.use", "housing.visit", "audit.view");
    private final SocialUiBootstrap.Registration registration;
    private final EterniaServices services;
    private final UUID actor;
    private final Map<String, String> allowedActions = new HashMap<>();
    private final String pageReceipt = UUID.randomUUID().toString();
    private long operationNumber;
    private Section section;
    private String mode = "";
    private String focus = "";
    private String one = "", two = "", three = "";
    private Set<String> draftCapabilities;
    private int page;
    private long generation;
    private boolean busy;
    private boolean initialLoad = true;
    private String status = "Loading your account…";
    private View view = View.empty("Loading", "Retrieving current account records.");
    private Pending pending;

    EterniaServicesPage(PlayerRef player, SocialUiBootstrap.Registration registration, Section section) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.registration = registration; this.services = registration.services; this.actor = player.getUuid(); this.section = section;
    }

    @Override public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
        @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        commands.append("EterniaMod/ServicesPage.ui");bindHome(events);commands.set("#Return.Visible",hasReturnPage());
        commands.append("#ServicesBody", "EterniaMod/" + layout() + ".ui");
        commands.set("#ServicesTitle.TextSpans", Message.raw(playerRef.getUsername()));
        allowedActions.clear();bind(events,"#Return","return_parent");
        int nav = 0;
        for (Section entry : Section.values()) {
            if (entry == Section.WORLDS) continue;
            appendNav(commands, events, nav++, entry.title, entry==Section.STORE?"external:STORE_OPEN":"nav:" + entry.name(), !busy, entry == section);
        }
        commands.set("#PageControls.Visible",pending == null && view.rows.size() > PAGE_SIZE);com.hexvane.eterniamod.ui.MenuPagination.show(commands, pending == null ? view.rows.size() : 0, PAGE_SIZE);commands.set("#Previous.Disabled", busy || pending!=null || page == 0);commands.set("#Next.Disabled",busy || pending!=null || (page+1)*PAGE_SIZE>=view.rows.size());
        commands.set("#Pagination.Text", "Page " + (page+1) + " / " + Math.max(1,(view.rows.size()+PAGE_SIZE-1)/PAGE_SIZE));
        bind(events,"#Previous","previous");bind(events,"#Next","next");bind(events,"#Close","close");
        var tabs=tabs();commands.set("#Tabs.Visible",pending==null&&!tabs.isEmpty());
        for(int i=0;i<tabs.size();i++){var tab=tabs.get(i);commands.append("#Tabs","EterniaMod/ServiceTab.ui");commands.set("#Tabs["+i+"].Text",tab.label);commands.set("#Tabs["+i+"].Disabled",busy);bind(events,"#Tabs["+i+"]",tab.route);}
        commands.set("#SectionTitle.TextSpans", Message.raw(pending == null ? view.title : "Confirm action"));
        commands.set("#Description.TextSpans", Message.raw(pending == null ? view.description : pending.explanation));
        commands.setObject("#Description.Anchor",UiAnchors.heightWithBottom(Math.max(50,UiPresentation.wrappedHeight(pending==null?view.description:pending.explanation,786,24)),8));
        commands.set("#Status.TextSpans", Message.raw(busy ? "Working…" : status));
        List<Row> rows = pending == null ? view.rows : List.of();
        int start = Math.min(page * PAGE_SIZE, rows.size());
        for (int i = start; i < Math.min(rows.size(), start + PAGE_SIZE); i++) {
            Row row = rows.get(i);
            String selector = "#Rows[" + (i - start) + "]";
            if(!row.cells.isEmpty()) {commands.append("#Rows","EterniaMod/RewardLevel.ui");commands.set(selector+" #Level.Text",row.text);
                for(int index=0;index<2;index++){Row cell=row.cells.get(index);String track=index==0?"Free":"Paid";commands.set(selector+" #"+track+"Name.Text",cell.text);commands.set(selector+" #"+track+"Action.Text",cell.label);commands.set(selector+" #"+track+"Action.Visible",!cell.label.isEmpty());commands.set(selector+" #"+track+"Action.Disabled",busy||cell.route.isEmpty());if(!cell.route.isEmpty())bind(events,selector+" #"+track+"Action",cell.route);}continue;}
            if(row.total>0) {commands.append("#Rows","EterniaMod/QuestRow.ui");commands.set(selector+" #RowText.Text",row.text);commands.set(selector+" #QuestGoal.Text",row.goal);commands.set(selector+" #QuestReward.Text","+"+row.xp+" XP");commands.set(selector+" #QuestCount.Text",Math.min(row.count,row.total)+" / "+row.total+(row.count>=row.total?" · Complete":""));bar(commands,selector+" #QuestFill",selector+" #QuestRest",row.count,row.total);continue;}
            commands.append("#Rows", section==Section.MAIL?"EterniaMod/MailRow.ui":"EterniaMod/ServiceRow.ui");
            if(section!=Section.MAIL)com.hexvane.eterniamod.ui.ContentImages.row(commands,selector,row.contentId);
            commands.set(selector + " #RowText.TextSpans", Message.raw(row.text));
            commands.set(selector + " #RowAction.TextSpans", Message.raw(row.label));
            commands.set(selector + " #RowAction.Visible", !row.label.isEmpty());
            commands.set(selector + " #RowAction.Disabled", busy || row.route.isEmpty());
            if(section!=Section.MAIL){int width=UiPresentation.buttonWidth(row.label);int body=layout().equals("ServicesOverview")?600:layout().equals("ServicesRoster")&&Arrays.stream(view.fields).anyMatch(label->!label.isEmpty())?554:822;commands.setObject(selector+" #RowAction.Anchor",UiAnchors.serviceAction(width));commands.setObject(selector+".Anchor",UiAnchors.heightWithBottom(Math.max(76,UiPresentation.wrappedHeight(row.text,body-40-(row.contentId.isEmpty()?0:78)-(row.label.isEmpty()?0:width),23)+24),8));}
            else{int textHeight=Math.max(56,UiPresentation.wrappedHeight(row.text,294,23));commands.setObject(selector+" #RowText.Anchor",UiAnchors.heightWithBottom(textHeight,8));commands.setObject(selector+".Anchor",UiAnchors.heightWithBottom(textHeight+64,10));}
            if (!row.route.isEmpty()) bind(events, selector + " #RowAction", row.route);
        }
        String[] labels = pending == null ? view.fields : new String[] {"", "", ""};
        boolean hasFields=Arrays.stream(labels).anyMatch(label->!label.isEmpty());commands.set("#Fields.Visible",hasFields);
        if(!Set.of("ServicesMail","ServicesRoster").contains(layout()))commands.setObject("#Fields.Anchor",UiAnchors.heightWithBottom(hasFields?(int)Arrays.stream(labels).filter(label->!label.isEmpty()).count()*50:0,10));
        if(section==Section.MAIL){commands.set("#Reader.Visible",!hasFields);commands.set("#Document.Text",pending==null?view.document:pending.explanation);commands.setObject("#Document.Anchor",UiAnchors.heightWithRight(Math.max(250,UiPresentation.wrappedHeight(view.document,390,25)),12));}
        if(layout().equals("ServicesOverview")){String feature=switch(section){case HOUSING->"Your own corner";case WORLDS->"The road awaits";case COMING_SOON->"More adventures ahead";default->"Welcome, adventurer";};commands.set("#FeatureTitle.Text",feature);commands.set("#FeatureText.Text",switch(section){case HOUSING->"A free plot, a home to personalize, and a place to return to after your adventures.";case WORLDS->"Public portals connect the Hub to the worlds beyond.";case COMING_SOON->"Minigames are coming soon. Your housing and season journeys are ready now.";default->"Prowl\nEternia Guide\n\nSettle in, find your guild, and start exploring.";});commands.setObject("#SectionArt.Background", new com.hypixel.hytale.server.core.ui.PatchStyle(com.hypixel.hytale.server.core.ui.Value.of("EterniaMod/Icons/"+(section==Section.WORLDS?"worlds":"housing")+".png")));}
        if(section==Section.SEASON){commands.set("#SeasonProgress.Visible",!focus.isEmpty()&&pending==null);commands.set("#ProgressLabel.Text",view.progressLabel);bar(commands,"#ProgressFill","#ProgressRest",view.progress,view.progressTotal);}
        if(section==Section.COLLECTION){commands.set("#CollectionSummary.Text",view.document.isEmpty()?"Choose your appearance, meet your pets, or browse everything you own.":view.document);}
        String[] values = {one, two, three};
        String[] ids = {"One", "Two", "Three"};
        for (int i = 0; i < 3; i++) {
            commands.set("#Field" + ids[i] + "Group.Visible", !labels[i].isEmpty());
            commands.set("#Field" + ids[i] + "Label.TextSpans", Message.raw(labels[i]));
            commands.set("#Field" + ids[i] + ".Value", values[i]);
            events.addEventBinding(CustomUIEventBindingType.ValueChanged, "#Field" + ids[i],
                EventData.of("@" + ids[i], "#Field" + ids[i] + ".Value"), false);
        }
        Button[] buttons = pending == null ? view.buttons : new Button[] {new Button("Confirm", "confirm"), new Button("Cancel", "cancel_confirmation"), Button.NONE};
        String[] buttonIds = {"#Primary", "#Secondary", "#Tertiary"};
        for (int i = 0; i < buttons.length; i++) {
            commands.set(buttonIds[i] + ".TextSpans", Message.raw(buttons[i].label));
            commands.set(buttonIds[i] + ".Visible", !buttons[i].label.isEmpty());
            commands.set(buttonIds[i] + ".Disabled", busy || buttons[i].route.isEmpty());
            if (!buttons[i].route.isEmpty()) bind(events, buttonIds[i], buttons[i].route);
        }
        if (initialLoad) { initialLoad = false; refresh(ref, store, ""); }
    }

    private String layout(){return switch(section){case GREETER,HOUSING,WORLDS,COMING_SOON->"ServicesOverview";case GUILD->"ServicesRoster";case MAIL->"ServicesMail";case SEASON->"ServicesSeason";case COLLECTION->"ServicesCollection";default->"ServicesDirectory";};}
    private List<Button> tabs(){return switch(section){
        case GUILD->view.title.equals("Find your guild")||view.title.equals("Loading")?List.of():List.of(new Button("Members","back"),new Button("Roles","guild_roles"),new Button("Notice board","guild_board"),new Button("Guild house","external:GUILD_HOUSING"));
        case MAIL->List.of(new Button("Inbox","back"),new Button("Write a letter","mail_compose"),new Button("Deliveries","external:ITEM_DESK"));
        case SEASON->focus.isEmpty()?List.of():List.of(new Button("Rewards","season_rewards"),new Button("Quests","season_quests"),new Button("All passes","back"));
        case COLLECTION->List.of(new Button("Appearance","back"),new Button("Pets","collection_view:pets"),new Button("Owned items","collection_view:inventory"));
        case SHOP->List.of(new Button("Browse","back"),new Button("My shop","external:SHOP_MANAGE"),new Button("Trade","external:TRADE_DESK"));
        default->List.of();};}
    private static void bar(UICommandBuilder commands,String fill,String rest,long value,long total){int amount=total<=0?0:(int)Math.max(0,Math.min(1000,(double)value/total*1000));commands.set(fill+".Visible",amount>0);commands.set(rest+".Visible",amount<1000);commands.set(fill+".FlexWeight",Math.max(1,amount));commands.set(rest+".FlexWeight",Math.max(1,1000-amount));}

    private void appendNav(UICommandBuilder commands, UIEventBuilder events, int index, String text, String route, boolean enabled, boolean selected) {
        commands.append("#Navigation", "EterniaMod/ServiceNav.ui");
        String selector = "#Navigation[" + index + "]";
        commands.set(selector + " #Selected.Visible", selected);
        commands.set(selector + " #NavButton.TextSpans", Message.raw(text));
        commands.set(selector + " #NavButton.Disabled", !enabled);
        bind(events, selector + " #NavButton", route);
    }

    private void bind(UIEventBuilder events, String selector, String route) {
        String token = generation + ":" + allowedActions.size();
        allowedActions.put(token, route);
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, new EventData().append("Action", token)
            .append("@One", "#FieldOne.Value").append("@Two", "#FieldTwo.Value").append("@Three", "#FieldThree.Value"), false);
    }

    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (isDismissed() || registration.closed) return;
        if (pending == null && !busy) {
            if (data.one != null) one = bounded(data.one, 2000);
            if (data.two != null) two = bounded(data.two, 2000);
            if (data.three != null) three = bounded(data.three, 2000);
        }
        String route = allowedActions.get(data.action);
        if (route == null) return;
        if (route.equals("close")) { close(); return; }
        if (busy) return;
        if(route.equals("return_parent")){store.getExternalData().getWorld().execute(()->{if(ref.isValid()&&!isDismissed())returnOrClose(ref,store);});return;}
        if (route.startsWith("nav:")) {
            if(route.equals("nav:WORLDS")){registration.actions.perform(SocialUiActions.Action.WORLD_SELECT,ref,store,playerRef);return;}
            section = Section.valueOf(route.substring(4)); mode = ""; focus = ""; page = 0; one = two = three = ""; draftCapabilities = null; pending = null;
            refresh(ref, store, ""); return;
        }
        if (route.equals("next") || route.equals("previous")) { page = Math.max(0, page + (route.equals("next") ? 1 : -1)); generation++; rebuild(); return; }
        if (route.equals("cancel_confirmation")) { pending = null; generation++; rebuild(); return; }
        if (route.equals("confirm")) { Pending current = pending; if (current == null) return; pending = null; runDomain(ref, store, current.route, current.input); return; }
        if (route.startsWith("external:")) {
            SocialUiActions.Result result = registration.actions.perform(SocialUiActions.Action.valueOf(route.substring(9)), ref, store, playerRef);
            if (!result.opened()) { status = result.message(); generation++; rebuild(); }
            return;
        }
        if (route.startsWith("shop_visit:")) {
            var result = registration.actions.visitShop(route.substring(11), ref, store, playerRef);
            if (!result.opened()) { status = result.message(); generation++; rebuild(); }
            return;
        }
        if(route.equals("intro_toolkit")){status=com.hexvane.eterniamod.customization.CustomizationTools.reissue(ref,store,playerRef,true);generation++;rebuild();return;}
        if (route.equals("refresh")) { refresh(ref, store, ""); return; }
        if (route.equals("mail_send")) {
            var result=registration.actions.composeMail(one,two,three,ref,store,playerRef);
            if(!result.opened()){status=result.message();generation++;rebuild();}return;
        }
        if (route.equals("back")) { mode = ""; focus = ""; one = two = three = ""; page = 0; draftCapabilities = null; refresh(ref, store, ""); return; }
        if (route.equals("mail_compose")) { mode="compose";focus="";page=0;refresh(ref,store,"");return; }
        if(route.startsWith("guild_assign_select:")){two=route.substring(20);pending=new Pending("guild_assign",input(),"Assign the selected role to "+view.title+"? Your current guild permissions are checked when you confirm.");generation++;rebuild();return;}
        if (route.equals("guild_roles")) { mode = "roles"; page = 0; refresh(ref, store, ""); return; }
        if (route.equals("guild_board")) { registration.openBoard(ref,store,playerRef);return; }
        if (route.startsWith("member_select:")) { one = route.substring(14);mode="member";focus=one;page=0;refresh(ref,store,"");return; }
        if (route.startsWith("role_select:")) { mode = "role_edit"; focus = route.substring(12); draftCapabilities = null; page = 0; refresh(ref, store, ""); return; }
        if (route.equals("new_role")) { mode = "role_edit"; focus = ""; one = "role_"+UUID.randomUUID().toString().replace("-",""); two = "Custom role"; three = "50"; draftCapabilities = new HashSet<>(); page = 0; refresh(ref, store, ""); return; }
        if (route.startsWith("cap:")) { String capability = route.substring(4); if (draftCapabilities == null || !EDITABLE_CAPABILITIES.contains(capability)) return;
            if (!draftCapabilities.add(capability)) draftCapabilities.remove(capability); refresh(ref, store, "Draft updated; save to apply permissions."); return; }
        if (route.startsWith("mail_read:")) { mode = "message"; focus = route.substring(10); page = 0; runDomain(ref, store, "mail_mark", input()); return; }
        if (route.startsWith("season_view:")) { mode = "rewards"; focus = route.substring(12); page = 0; refresh(ref, store, ""); return; }
        if (route.equals("season_quests")) { mode = "quests"; page = 0; refresh(ref, store, ""); return; }
        if (route.equals("season_rewards")) { mode = "rewards"; page = 0; refresh(ref, store, ""); return; }
        if (route.startsWith("collection_view:")) { mode = route.substring(16); focus = ""; page = 0; refresh(ref, store, ""); return; }
        if (route.startsWith("pet_view:")) { mode = "pet"; focus = route.substring(9); page = 0; refresh(ref, store, ""); return; }
        if (Set.of("guild_leave", "guild_assign", "guild_remove", "guild_transfer", "role_save", "mail_archive").contains(route)) {
            String explanation = switch (route) {
                case "guild_leave" -> "Leave this guild? Guild conveniences end immediately. An attached personal plot is returned after the 48-hour grace period.";
                case "guild_assign" -> "Assign role “" + two + "” to “" + (mode.equals("member")?view.title:one) + "”? The service rechecks your current permission before saving.";
                case "guild_remove" -> "Remove “"+view.title+"” from the guild? Their guild conveniences end immediately. An attached personal plot is returned after the 48-hour grace period. Your current role and their rank are checked again.";
                case "guild_transfer" -> "Make “"+view.title+"” the guild leader? They receive all leader powers and you become an officer. Your current leadership and their membership are checked again.";
                case "role_save" -> "Save role “" + two + "” with " + (draftCapabilities == null ? 0 : draftCapabilities.size()) + " allowed capabilities?";
                default -> "Archive this message? Its attachments must already be claimed.";
            };
            pending = new Pending(route, input(), explanation); generation++; rebuild(); return;
        }
        runDomain(ref, store, route, input());
    }

    private Input input() { return new Input(one, two, three, mode, focus, draftCapabilities == null ? Set.of() : Set.copyOf(draftCapabilities)); }
    private Request request() { return new Request(section, input()); }

    private void refresh(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        Request request = request();
        work(ref, store, () -> new Loaded(load(request), notice), false);
    }

    private void runDomain(Ref<EntityStore> ref, Store<EntityStore> store, String route, Input input) {
        Request request = new Request(section, input);
        String receipt = "native:" + pageReceipt + ":" + operationNumber;
        work(ref, store, () -> {
            String notice = mutate(route, input, receipt);
            if(section==Section.COLLECTION)CollectionBootstrap.refresh();
            return new Loaded(load(request), notice);
        }, true);
    }

    private void work(Ref<EntityStore> ref, Store<EntityStore> store, Supplier<Loaded> operation, boolean mutation) {
        if (busy || registration.closed) return;
        busy = true; long expected = ++generation;
        var world = store.getExternalData().getWorld();
        CompletableFuture.supplyAsync(operation, registration.executor).whenComplete((loaded, failure) -> world.execute(() -> {
            if (isDismissed() || registration.closed || !ref.isValid() || expected != generation) return;
            busy = false;
            if (failure == null) {
                if (mutation) operationNumber++;
                if(!Arrays.equals(view.fields,loaded.view.fields))one=two=three="";
                view = loaded.view;
                status = loaded.notice.isEmpty() ? "Your latest account information. Entries " + (view.rows.isEmpty() ? "0" : Math.min(page * PAGE_SIZE + 1, view.rows.size()) + "–" + Math.min((page + 1) * PAGE_SIZE, view.rows.size())) + " of " + view.rows.size() : loaded.notice;
                if (view.role != null && draftCapabilities == null) {
                    GuildService.Role role = view.role;
                    boolean builtIn = Set.of("leader", "officer", "architect", "quartermaster", "member").contains(role.id());
                    one = builtIn ? "role_"+UUID.randomUUID().toString().replace("-","") : role.id(); two = builtIn ? role.name() + " custom" : role.name(); three = Integer.toString(Math.max(1, role.rank()));
                    draftCapabilities = new HashSet<>(role.capabilities()); draftCapabilities.retainAll(EDITABLE_CAPABILITIES);
                }
            } else {
                Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof DomainException || cause instanceof IllegalArgumentException ? cause.getMessage() : "This operation could not be completed. Try again or contact a steward.";
                if (view.title.equals("Loading")) view = View.empty(section.title, "Account information is currently unavailable. Reopen this section to retry.");
            }
            page = Math.min(page, Math.max(0, (view.rows.size() - 1) / PAGE_SIZE));
            generation++; rebuild();
        }));
    }

    private String mutate(String route, Input input, String receipt) {
        switch (route) {
            case "guild_create" -> { services.guilds().create(actor, input.one.trim(), receipt); return "Guild created. Open Guild again to manage members and permissions."; }
            case "guild_invite" -> { String invite = services.guilds().invite(actor, account(input.one)); return "Invitation created: " + invite; }
            case "guild_accept" -> { services.guilds().acceptInvite(actor, input.two.trim()); return "Guild invitation accepted. Open Guild to see your membership."; }
            case "guild_assign" -> { services.guilds().assignRole(actor, input.mode.equals("member")?UUID.fromString(input.focus):account(input.one), input.two.trim()); return "Member role updated."; }
            case "guild_remove" -> { services.guilds().remove(actor,UUID.fromString(input.focus));return "Member removed. Any attached-plot return deadline is now recorded."; }
            case "guild_transfer" -> { services.guilds().transferLeadership(actor,UUID.fromString(input.focus));return "Leadership transferred. Your role is now officer."; }
            case "guild_leave" -> { services.guilds().leave(actor); return "You left the guild. Any attached-plot return deadline is recorded on your account."; }
            case "role_save" -> { services.guilds().defineRole(actor, new GuildService.Role(input.one.trim(), input.two.trim(), Integer.parseInt(input.three.trim()), input.capabilities)); return "Guild role saved. Assign it from the member screen."; }
            case "mail_mark" -> { services.mail().markRead(actor, input.focus); return "Message opened."; }
            case "mail_claim" -> { services.mail().claim(actor, input.focus); return "Attachments are ready to collect in Deliveries."; }
            case "mail_claim_all" -> { services.mail().claimAll(actor); return "Unclaimed attachments moved to your delivery inbox."; }
            case "mail_archive" -> { services.mail().archive(actor, input.focus); return "Message archived. Return to Mailbox to continue."; }
            case "season_activate" -> { services.seasons().select(actor, input.focus); return "Active season changed. Every season keeps its existing progress."; }
            case "pet_follow" -> { services.collection().follow(actor, UUID.fromString(input.focus)); return "Your pet will join you shortly."; }
            case "pet_home" -> { var property = services.housing().find(Owner.player(actor)).orElseThrow(() -> new IllegalArgumentException("Claim an active home first.")); services.collection().assignProperty(actor, UUID.fromString(input.focus), property.propertyId()); return "Your pet is settling in at home."; }
            case "pet_unassign" -> { services.collection().unassign(actor, UUID.fromString(input.focus)); return "Pet assignment cleared."; }
            default -> {
                if (route.startsWith("collection_equip:")) {
                    services.collection().equip(actor, route.substring(17));
                    return "Your new look will appear shortly.";
                }
                if (route.startsWith("collection_clear:")) {
                    String[] parts = route.substring(17).split(":", 2);
                    services.collection().unequip(actor, CollectionService.Kind.valueOf(parts[0]), parts.length > 1 ? parts[1] : "");
                    return "Selection cleared.";
                }
                if (route.startsWith("pet_adopt:")) {
                    String[] parts = route.substring(10).split(":", 2);
                    services.collection().materializePet(Owner.player(actor), parts[0], Long.parseLong(parts[1]));
                    return "Pet added to your collection. Open Pets to choose its assignment.";
                }
                if (route.startsWith("reward:")) {
                    String[] reward = route.substring(7).split(":");
                    services.seasons().claimReward(actor, input.focus, SeasonService.Track.valueOf(reward[0]), Integer.parseInt(reward[1]), Integer.parseInt(reward[2]));
                    return "Reward added to your account collection.";
                }
                throw new IllegalArgumentException("This action is no longer available. Refresh this section.");
            }
        }
    }

    private UUID account(String value) {
        try { UUID id = UUID.fromString(value.trim()); if (services.accounts().find(id).isPresent()) return id; } catch (IllegalArgumentException ignored) {}
        return services.accounts().findByName(value.trim()).orElseThrow(() -> new IllegalArgumentException("No known player has that name. They must join Eternia first.")).id();
    }

    private String name(UUID id) { return services.accounts().find(id).map(AccountService.Account::displayName).orElse("Unknown player"); }

    private View load(Request request) {
        Input input = request.input;
        return switch (request.section) {
            case GREETER -> new View("Welcome to Eternia", "I'm Prowl. Build a home, find your people, and explore at your own pace.", List.of(
                Row.info("Your first 24 × 24 plot, starter house, mailbox and one plot move are free."),
                Row.info("Choose a housing plot near a connected public road, an established home, or a hub portal."),
                Row.info("Walk near a plot edge to see its low, glowing boundary. Claim previews show placement reasons."),
                Row.info("Guilds share a house and can grow a neighborhood of member plots."),
                Row.info("Select a season pass and earn progress through normal play. Old seasons never expire."),
                Row.info("The hub's merchants connect you to mail, player shops, collections and optional store items.")),
                noFields(), buttons("Get starter toolkit", "intro_toolkit", "Claim a plot", "external:HOUSING_CLAIM", "Quest journal", "nav:QUESTS"), null);
            case HOUSING -> {
                var overview = services.overview(actor);
                List<Row> rows = new ArrayList<>();
                rows.add(Row.info(overview.housing() == null ? "Your free 24 × 24 plot is waiting for you." : "Your home · " + UiPresentation.friendlyId(overview.housing().state().name())));
                services.housing().location(Owner.player(actor)).ifPresent(location -> rows.add(Row.info(location.width() + " × " + location.depth() + " plot in " + UiPresentation.friendlyId(location.worldId()) + (location.guildId()==null?"":" · Guild neighbourhood"))));
                rows.add(Row.info("Move credits available: " + services.ownership().available(Owner.player(actor), HousingService.MOVE_CREDIT)));
                rows.add(Row.info("Keep structures and additions five blocks inside the plot and five blocks away from roads."));
                yield new View("Housing Ledger", "Build and personalize your home. Purchases and placement tokens stay linked to your account.", rows, noFields(),
                    buttons(overview.housing() == null ? "Claim a plot" : "Manage house", overview.housing() == null ? "external:HOUSING_CLAIM" : "external:HOUSING_MANAGE", "My plots", "external:HOUSING_MANAGE", "Refresh", "refresh"), null);
            }
            case GUILD -> guildView(input);
            case MAIL -> mailView(input);
            case SHOP -> {
                List<Row> rows = services.market().search(input.one).stream().map(listing -> new Row(UiPresentation.itemName(listing.itemId()) + " · " + listing.unitPrice() + " coins each\n" + listing.stock() + " in stock · " + name(listing.seller()), "Visit shop", "shop_visit:" + listing.id()).image(listing.itemId())).toList();
                yield new View("Player shops", "Browse real listings, then visit the seller's house to buy. Sellers can be offline.", rows.isEmpty() ? List.of(Row.info("No active listings match this search.")) : rows,
                    new String[] {"Find an item", "", ""}, buttons("Search", "refresh", "Manage my shop", "external:SHOP_MANAGE", "Item desk", "external:ITEM_DESK"), null);
            }
            case SEASON -> seasonView(input);
            case QUESTS -> questJournal();
            case COLLECTION -> collectionView(input);
            case STORE -> new View("Crown Store", "Discover house styles, decorations, titles and pets. Spend Crowns from your account inside Eternia.",
                List.of(Row.info("Crowns are separate from the earned coins used in player shops.")),
                noFields(), buttons("Browse Crown Store", "external:STORE_OPEN", "", "", "", ""), null);
            case WORLDS -> new View("Choose your destination", "Travel through the hub's world-select portal, or use the destinations made available by the server.",
                List.of(Row.info("The hub connects housing, player services and adventure worlds."), Row.info("After more than 30 minutes offline, your next login returns you to a safe point in your home.")),
                noFields(), buttons("Select a world", "external:WORLD_SELECT", "Return to hub", "external:HUB_TRAVEL", "", ""), null);
            case COMING_SOON -> new View("Minigames — Coming Soon", "Minigames are being prepared for Eternia. There is no queue or entry fee yet.",
                List.of(Row.info("Explore, settle into your home, and earn season progress through normal activities while you wait.")), noFields(), buttons("Quest journal", "nav:QUESTS", "", "", "", ""), null);
        };
    }

    private View collectionView(Input input) {
        Owner owner = Owner.player(actor);
        var definitions = services.collection().definitions();
        Map<String, CollectionService.Definition> catalog = new HashMap<>();
        definitions.forEach(definition -> catalog.put(definition.id(), definition));
        var grants = services.ownership().grants(owner);
        if (input.mode.equals("inventory")) {
            List<Row> rows = new ArrayList<>();
            for (var grant : grants) {
                boolean active = !grant.revoked() && (grant.validUntil() == null || grant.validUntil().isAfter(Instant.now()));
                var definition = catalog.get(grant.contentId());
                rows.add(Row.info((definition == null ? UiPresentation.contentName(grant.contentId()) : definition.name()) + " • "
                    + (active ? grant.kind() == OwnershipService.Kind.QUANTITY ? grant.available() + " available" : "Owned" : "Inactive")
                    + (grant.validUntil() == null ? "" : " • Until " + grant.validUntil())).image(grant.contentId()));
            }
            return new View("Owned items", "Your house styles, decorations, cosmetics and other collected rewards.",
                rows.isEmpty() ? List.of(Row.info("Your collection is waiting for its first treasure.")) : rows, noFields(),
                buttons("Selections", "back", "Pets", "collection_view:pets", "Refresh", "refresh"), null);
        }
        if (input.mode.equals("pet") || input.mode.equals("pets")) {
            var pets = services.collection().pets(owner);
            if (input.mode.equals("pet")) {
                var pet = pets.stream().filter(p -> p.id().toString().equals(input.focus)).findFirst().orElseThrow(() -> new IllegalArgumentException("Pet no longer belongs to your collection."));
                var definition = catalog.get(pet.contentId());
                boolean home = services.housing().find(owner).filter(slot -> slot.state() == HousingService.State.ACTIVE).isPresent();
                return new View(definition == null ? "Your pet" : definition.name(), "Choose one pet to follow you, or let it roam around your home.",
                    List.of(Row.info("Current home: " + UiPresentation.friendlyId(pet.assignment())), Row.info("Only one personal pet can follow you at a time."),
                        new Row("Return to all owned pets.", "All pets", "collection_view:pets")), noFields(),
                    buttons("Assign follower", "pet_follow", "Assign to home", home ? "pet_home" : "", "Clear assignment", "pet_unassign"), null);
            }
            List<Row> rows = new ArrayList<>();
            for (var pet : pets) {
                var definition = catalog.get(pet.contentId());
                rows.add(new Row((definition == null ? UiPresentation.contentName(pet.contentId()) : definition.name()) + " · " + UiPresentation.friendlyId(pet.assignment()), "Manage pet", "pet_view:" + pet.id()).image(pet.contentId()));
            }
            for (var grant : grants) {
                var definition = catalog.get(grant.contentId());
                if (definition == null || definition.kind() != CollectionService.Kind.PET || grant.revoked()
                    || grant.validUntil() != null && !grant.validUntil().isAfter(Instant.now())) continue;
                Set<Long> used = new HashSet<>();
                pets.stream().filter(pet -> pet.sourceGrant().equals(grant.id())).forEach(pet -> used.add(pet.sourceIndex()));
                long next = 0;
                while (used.contains(next)) next++;
                if (next < grant.quantity()) rows.add(new Row(definition.name() + " · Ready to welcome", "Add pet", "pet_adopt:" + grant.id() + ":" + next).image(grant.contentId()));
            }
            return new View("Your pets", "Choose a companion from your pets, or welcome a new pet from your collection.",
                rows.isEmpty() ? List.of(Row.info("You do not own any pets yet.")) : rows, noFields(),
                buttons("Selections", "back", "Owned items", "collection_view:inventory", "Refresh", "refresh"), null);
        }
        var selection = services.collection().selection(actor);
        List<Row> rows = new ArrayList<>();
        for (var definition : definitions) {
            if (definition.kind() == CollectionService.Kind.PET || !services.ownership().owns(owner, definition.id())) continue;
            String selected = switch (definition.kind()) {
                case PREFIX_TITLE -> selection.prefix(); case SUFFIX_TITLE -> selection.suffix(); case OUTFIT -> selection.outfit();
                case WEARABLE -> selection.wearables().getOrDefault(definition.slot(), ""); case PET -> "";
            };
            boolean equipped = definition.id().equals(selected);
            rows.add(new Row(definition.name() + " • " + collectionKind(definition.kind()) + (definition.slot().isEmpty() ? "" : " / " + definition.slot()),
                equipped ? "Clear selection" : "Select", equipped ? "collection_clear:" + definition.kind() + ":" + definition.slot() : "collection_equip:" + definition.id()).image(definition.id()));
        }
        rows.add(Row.info(CollectionBootstrap.status(actor)));
        return new View("Collection selections", "Pair prefix and suffix titles, or choose an outfit and wearables. Supported selections apply in the world within two seconds; Refresh shows the current result.",
            rows.isEmpty() ? List.of(Row.info("No selectable title or appearance content is owned yet. Account inventory includes housing unlocks and other grants.")) : rows,
            noFields(), buttons("Owned items", "collection_view:inventory", "Pets", "collection_view:pets", "Refresh", "refresh"), null).document("YOUR TITLES\n"+selectionName(catalog,selection.prefix(),"No prefix")+"  ·  "+selectionName(catalog,selection.suffix(),"No suffix")+"\nOutfit: "+selectionName(catalog,selection.outfit(),"Your character"));
    }

    private static String selectionName(Map<String,CollectionService.Definition> catalog,String id,String fallback){return id==null||id.isBlank()?fallback:catalog.containsKey(id)?catalog.get(id).name():UiPresentation.contentName(id);}

    private static String collectionKind(CollectionService.Kind kind) {
        return switch (kind) { case PREFIX_TITLE -> "Prefix title"; case SUFFIX_TITLE -> "Suffix title"; case OUTFIT -> "Outfit"; case WEARABLE -> "Wearable"; case PET -> "Pet"; };
    }

    private View guildView(Input input) {
        var membership = services.guilds().membership(actor);
        if (membership.isEmpty()) return new View("Find your guild", "Create a guild or accept an invitation. Five current members are needed to claim a guild house plot.",
            List.of(Row.info("Guilds provide shared housing, roles, trading shortcuts and member conveniences.")), new String[] {"Guild name", "Invitation code", ""},
            buttons("Create guild", "guild_create", "Accept invite", "guild_accept", "Refresh", "refresh"), null);
        UUID guild = membership.get().guildId();
        String guildName = services.guilds().find(guild).map(GuildService.Guild::name).orElse("Your guild");
        boolean edit = services.guilds().can(actor, guild, "role.edit");
        if(input.mode.equals("member")) {
            var selected=services.guilds().roster(actor).stream().filter(member->member.playerId().toString().equals(input.focus)).findFirst().orElse(null);
            if(selected==null)return new View("Member no longer present","This player is no longer in the guild. Refresh the roster to choose another member.",List.of(),noFields(),buttons("Members","back","","","",""),null);
            var roles=services.guilds().roles(actor);var ownRole=roles.stream().filter(role->role.id().equals(membership.get().role())).findFirst().orElseThrow();
            var selectedRole=roles.stream().filter(role->role.id().equals(selected.role())).findFirst().orElseThrow();
            boolean lower=ownRole.rank()<selectedRole.rank();boolean transfer=!selected.playerId().equals(actor)&&services.guilds().can(actor,guild,"leadership.transfer");
            List<Row> rows=new ArrayList<>();rows.add(Row.info(guildName+" • "+selectedRole.name()+" • "+online(selected.playerId())));
            rows.add(new Row("Return to the current member roster.","Members","back"));
            rows.add(new Row("Refresh this member's role and online status.","Refresh","refresh"));
            if(lower&&services.guilds().can(actor,guild,"member.role.assign"))for(var role:roles)if(role.rank()>ownRole.rank())rows.add(new Row(role.name()+" · "+role.capabilities().size()+" permissions",role.id().equals(selected.role())?"Current role":"Assign",role.id().equals(selected.role())?"":"guild_assign_select:"+role.id()));
            return new View(name(selected.playerId()),"Choose a role below. Removing this member or transferring leadership needs your confirmation.",rows,noFields(),
                buttons("Members","back","Remove member",lower&&services.guilds().can(actor,guild,"member.remove")?"guild_remove":"","Transfer leadership",transfer?"guild_transfer":""),null);
        }
        if (input.mode.equals("roles")) {
            List<Row> rows = services.guilds().roles(actor).stream().map(role -> new Row(role.name() + " • " + role.capabilities().size() + " permissions • rank " + role.rank(), edit ? "Edit / copy" : "View", "role_select:" + role.id())).toList();
            return new View(guildName + " / Roles", "Built-in roles are templates. Custom roles grant only explicitly selected capabilities; permissions are checked again when actions are used.", rows, noFields(),
                buttons("New role", edit ? "new_role" : "", "Members", "back", "Guild house", "external:GUILD_HOUSING"), null);
        }
        if (input.mode.equals("role_edit")) {
            GuildService.Role role = input.focus.isEmpty() ? null : services.guilds().roles(actor).stream().filter(r -> r.id().equals(input.focus)).findFirst().orElseThrow(() -> new IllegalArgumentException("Role no longer exists."));
            Set<String> caps = draftCapabilities == null && role != null ? role.capabilities() : input.capabilities;
            List<Row> rows = EDITABLE_CAPABILITIES.stream().map(cap -> new Row(UiPresentation.capability(cap), caps.contains(cap) ? "Allowed" : "Not allowed", edit ? "cap:" + cap : "")).toList();
            return new View(guildName + " / Permission editor", "Use Next to inspect every permission. Saving a built-in template creates a custom copy. Leader-only powers cannot be delegated.", rows,
                edit ? new String[] {"", "Role name", "Role priority"} : noFields(), buttons("Save role", edit ? "role_save" : "", "Role list", "guild_roles", "", ""), role);
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Guild notices and pinned plans. Live chat: /e guildchat <message>","Notice board",services.guilds().can(actor,guild,"board.read")?"guild_board":""));
        var roleNames=new HashMap<String,String>();services.guilds().roles(actor).forEach(role->roleNames.put(role.id(),role.name()));
        for (var member : services.guilds().roster(actor)) rows.add(new Row(name(member.playerId()) + " · " + online(member.playerId())+"\n"+roleNames.getOrDefault(member.role(),UiPresentation.friendlyId(member.role())), "Manage", "member_select:" + member.playerId()));
        rows.add(new Row("Refresh roles and online status.","Refresh roster","refresh"));
        rows.add(membership.get().role().equals("leader")?Row.info("To leave, select another member and transfer leadership first."):new Row("Leave guild; attached plots have a 48-hour return grace period.", "Leave guild", "guild_leave"));
        return new View(guildName, "Your role: " + roleNames.get(membership.get().role()) + ". Invite a player, or select a member to manage their role.", rows,
            new String[] {"Invite player", "", ""}, buttons("Send invitation", services.guilds().can(actor, guild, "member.invite") ? "guild_invite" : "",
                "Notice board", "guild_board", "Guild house", "external:GUILD_HOUSING"), null);
    }

    private static String online(UUID player) {
        var universe=com.hypixel.hytale.server.core.universe.Universe.get();
        return universe!=null&&universe.getPlayer(player)!=null?"Online":"Offline";
    }

    private View mailView(Input input) {
        var inbox = services.mail().inbox(actor);
        List<Row> rows=inbox.stream().sorted(Comparator.comparing(MailService.Message::sentAt).reversed()).map(mail->new Row((mail.read()?"":"NEW · ")+mail.subject()+"\nFrom "+name(mail.sender()),"Read","mail_read:"+mail.id())).toList();
        if(rows.isEmpty())rows=List.of(Row.info("Your mailbox is empty. A new adventure is a good reason to write to someone."));
        if(input.mode.equals("compose"))return new View("Write a letter","Your items are attached in the next step, before the letter is sent.",rows,new String[]{"To player","Subject","Your message"},buttons("Add attachments","mail_send","Inbox","back","",""),null);
        if (input.mode.equals("message")) {
            var mail = inbox.stream().filter(m -> m.id().equals(input.focus)).findFirst();
            if (mail.isEmpty()) return new View("Message unavailable", "This message was archived or is no longer in your inbox.", List.of(), noFields(), buttons("Mailbox", "back", "", "", "", ""), null);
            var message = mail.get();
            return new View(message.subject(), "From " + name(message.sender()) + " · " + message.sentAt().toString().substring(0,10) + " · " + message.attachments().size() + " attached stacks", rows, noFields(),
                buttons(message.claimed() ? "Collected" : "Collect attachments", message.claimed() ? "" : "mail_claim", "Archive", message.claimed() ? "mail_archive" : "", "Write a letter", "mail_compose"), null).document(message.body());
        }
        return new View("Your mailbox", "Letters and parcels from your fellow adventurers.", rows,
            noFields(), buttons("Write a letter", "mail_compose", "Collect all", "mail_claim_all", "Deliveries", "external:ITEM_DESK"), null).document("Choose a letter to read it here.\n\nMessages and attached items stay safe while you are offline. Collect an attachment whenever your inventory has room.");
    }

    private View questJournal() {
        var active=services.seasons().progress(actor).stream().filter(SeasonService.Progress::active).findFirst();
        if(active.isEmpty())return new View("Quest journal","Choose an active season pass to begin earning quest XP.",List.of(),noFields(),buttons("Choose pass","nav:SEASON","","","",""),null);
        var pass=active.get();
        var rows=services.seasons().quests(actor,pass.id()).stream().map(Row::quest).toList();
        return new View("Quest journal",pass.name()+" · Progress is recorded while you play. Completed goals award XP automatically.",rows.isEmpty()?List.of(Row.info("No quests have been published for this pass.")):rows,noFields(),buttons("Refresh","refresh","Season passes","nav:SEASON","",""),null);
    }

    private View seasonView(Input input) {
        var progress = services.seasons().progress(actor);
        if (input.focus.isEmpty()) {
            List<Row> rows = progress.stream().map(p -> new Row(p.name() + " • " + p.xp() + " / " + p.totalXp() + " XP • Level " + p.level() + (p.active() ? " • Active" : ""), "View pass", "season_view:" + p.id())).toList();
            return new View("Permanent season passes", "Choose one active pass. Free and paid tracks share XP; all seasons remain available and switching preserves progress.", rows.isEmpty() ? List.of(Row.info("No season has been published yet.")) : rows,
                noFields(), buttons("Refresh", "refresh", "", "", "", ""), null);
        }
        var current = progress.stream().filter(p -> p.id().equals(input.focus)).findFirst().orElseThrow(() -> new IllegalArgumentException("Season is not available."));
        if (input.mode.equals("quests")) {
            var quests = services.seasons().quests(actor, input.focus).stream().map(Row::quest).toList();
            return new View(current.name() + " · Quests", "Complete these goals while you explore. Quest XP is added to this pass automatically.", quests.isEmpty() ? List.of(Row.info("No quests have been added to this pass yet.")) : quests,
                noFields(), buttons(current.active()?"Active pass":"Activate pass",current.active()?"":"season_activate","Rewards","season_rewards","",""),null).progress(current);
        }
        var definition = services.seasons().definitions().stream().filter(d -> d.id().equals(input.focus)).findFirst().orElseThrow();
        List<Row> rows = new ArrayList<>();
        var tiers=new TreeMap<Integer,TreeMap<Integer,Map<SeasonService.Track,Row>>>();
        var names=new HashMap<String,String>();services.collection().definitions().forEach(item->names.put(item.id(),item.name()));
        for (var reward : definition.rewards()) {
            boolean claimed = services.seasons().isClaimed(actor, input.focus, reward.track(), reward.level(), reward.index());
            boolean ready = !claimed && current.level() >= reward.level() && (reward.track() == SeasonService.Track.FREE || current.paid());
            String name=names.getOrDefault(reward.contentId(),UiPresentation.contentName(reward.contentId()));
            var row=new Row(name+(reward.quantity()>1?" × "+reward.quantity():""),claimed?"Claimed":ready?"Claim":reward.track()==SeasonService.Track.PAID&&!current.paid()?"Paid pass required":"Reach level "+reward.level(),ready?"reward:"+reward.track()+":"+reward.level()+":"+reward.index():"");
            tiers.computeIfAbsent(reward.level(),ignored->new TreeMap<>()).computeIfAbsent(reward.index(),ignored->new EnumMap<>(SeasonService.Track.class)).put(reward.track(),row);
        }
        tiers.forEach((level,indices)->indices.forEach((index,tracks)->rows.add(Row.rewards("Level "+level+(indices.size()>1?" · Reward "+(index+1):""),tracks.getOrDefault(SeasonService.Track.FREE,Row.info("No free reward here")),tracks.getOrDefault(SeasonService.Track.PAID,Row.info("No paid reward here"))))));
        return new View(current.name(),(current.paid()?"Both tracks are yours.":"The free track is yours. A paid pass unlocks the second track.")+" Every pass stays available forever.", rows,
            noFields(), buttons(current.active() ? "Active pass" : "Activate pass", current.active() ? "" : "season_activate", "Quests", "season_quests", "All passes", "back"), null).progress(current);
    }

    private static String bounded(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private static String[] noFields() { return new String[] {"", "", ""}; }
    private static Button[] buttons(String a, String ar, String b, String br, String c, String cr) { return new Button[] {new Button(a, ar), new Button(b, br), new Button(c, cr)}; }
    private record Button(String label, String route) { static final Button NONE = new Button("", ""); }
    private record Row(String text,String label,String route,List<Row> cells,String goal,long count,long total,long xp,String contentId) {
        Row(String text,String label,String route){this(text,label,route,List.of(),"",0,0,0,"");}
        Row image(String id){return new Row(text,label,route,cells,goal,count,total,xp,id);}
        static Row info(String text){return new Row(text,"","");}
        static Row quest(SeasonService.QuestProgress progress){var quest=progress.quest();return new Row(UiPresentation.questTitle(quest),"","",List.of(),UiPresentation.questGoal(quest),progress.count(),quest.required(),quest.bonusXp(),"");}
        static Row rewards(String level,Row free,Row paid){return new Row(level,"","",List.of(free,paid),"",0,0,0,"");}
    }
    private record Input(String one, String two, String three, String mode, String focus, Set<String> capabilities) {}
    private record Request(Section section, Input input) {}
    private record View(String title,String description,List<Row> rows,String[] fields,Button[] buttons,GuildService.Role role,String document,long progress,long progressTotal,String progressLabel) {
        View(String title,String description,List<Row> rows,String[] fields,Button[] buttons,GuildService.Role role){this(title,description,rows,fields,buttons,role,"",0,0,"");}
        View document(String value){return new View(title,description,rows,fields,buttons,role,value,progress,progressTotal,progressLabel);}
        View progress(SeasonService.Progress value){return new View(title,description,rows,fields,buttons,role,document,value.xp(),value.totalXp(),"Level "+value.level()+"  ·  "+value.xp()+" / "+value.totalXp()+" XP"+(value.active()?"  ·  Active pass":""));}
        static View empty(String title, String description) { return new View(title, description, List.of(), noFields(), EterniaServicesPage.buttons("Retry", "refresh", "", "", "", ""), null); }
    }
    private record Loaded(View view, String notice) {}
    private record Pending(String route, Input input, String explanation) {}

    public static final class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
            .append(new KeyedCodec<>("@One", Codec.STRING), (data, value) -> data.one = value, data -> data.one).add()
            .append(new KeyedCodec<>("@Two", Codec.STRING), (data, value) -> data.two = value, data -> data.two).add()
            .append(new KeyedCodec<>("@Three", Codec.STRING), (data, value) -> data.three = value, data -> data.three).add().build();
        public String action, one, two, three;
    }
}
