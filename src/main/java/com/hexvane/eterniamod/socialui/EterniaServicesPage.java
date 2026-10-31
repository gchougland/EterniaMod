package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.collections.CollectionBootstrap;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hexvane.eterniamod.ui.ContentImages;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hexvane.eterniamod.ui.JournalChrome;
import com.hexvane.eterniamod.setup.HubNpcIdentity;
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
    /** Where the page was opened: the Journal (personal sections, anywhere) or one Hub NPC's own desk. */
    public enum Desk {
        JOURNAL(null, null, List.of(Section.COLLECTION, Section.QUESTS, Section.SEASON, Section.MAIL, Section.GUILD, Section.HOUSING),
            Set.of(SocialUiActions.Action.HOUSING_INVENTORY, SocialUiActions.Action.ITEM_DESK)),
        GUIDE(HubNpcIdentity.GREETER, "worlds", List.of(Section.GREETER), Set.of(SocialUiActions.Action.WORLD_SELECT, SocialUiActions.Action.HUB_TRAVEL)),
        STEWARD(HubNpcIdentity.HOUSING, "housing", List.of(Section.HOUSING), Set.of(SocialUiActions.Action.HOUSING_CLAIM, SocialUiActions.Action.HOUSING_MANAGE,
            SocialUiActions.Action.HOUSING_INVENTORY, SocialUiActions.Action.ARCHITECT_LEDGER)),
        MARSHAL(HubNpcIdentity.GUILD, "guild", List.of(Section.GUILD), Set.of(SocialUiActions.Action.GUILD_HOUSING)),
        BROKER(HubNpcIdentity.SHOP, "market", List.of(Section.SHOP), Set.of(SocialUiActions.Action.SHOP_MANAGE, SocialUiActions.Action.ITEM_DESK, SocialUiActions.Action.TRADE_DESK)),
        GAMES(HubNpcIdentity.MINIGAME, "games", List.of(Section.COMING_SOON), Set.of());
        final HubNpcIdentity identity; final String emblem; final List<Section> sections; final Set<SocialUiActions.Action> actions;
        Desk(HubNpcIdentity identity, String emblem, List<Section> sections, Set<SocialUiActions.Action> actions) {
            this.identity = identity; this.emblem = emblem; this.sections = sections; this.actions = actions;
        }
        /** The desk a Hub NPC's role opens; sections without an NPC of their own belong to the Journal. */
        public static Desk forNpc(Section section) {
            return switch (section) { case GREETER, WORLDS -> GUIDE; case HOUSING -> STEWARD; case GUILD -> MARSHAL; case SHOP -> BROKER; case COMING_SOON -> GAMES; default -> JOURNAL; };
        }
        /** The desk a menu shortcut in code reaches: personal sections open the Journal, trades open their NPC's desk. */
        public static Desk forShortcut(Section section) {
            return switch (section) { case SHOP -> BROKER; case COMING_SOON -> GAMES; default -> JOURNAL; };
        }
    }
    private static final int PAGE_SIZE = 6;
    private static final int CARDS_PER_ROW = 6;
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
    private final Desk desk;
    private Section section;
    private boolean deskHome;
    /** Collection content shown in the 3D viewer beside the cards; empty when the viewer is closed. */
    private String previewContent = "";
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

    EterniaServicesPage(PlayerRef player, SocialUiBootstrap.Registration registration, Desk desk, Section section, boolean deskHome) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.registration = registration; this.services = registration.services; this.actor = player.getUuid();
        this.desk = desk; this.section = desk.sections.contains(section) ? section : desk.sections.getFirst(); this.deskHome = deskHome && desk != Desk.JOURNAL;
        this.preview = new com.hexvane.eterniamod.ui.PrefabCardPreview(player);
    }

    boolean isJournal() { return desk == Desk.JOURNAL; }
    void closeJournal() { close(); }

    @Override public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
        @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        commands.append("EterniaMod/ServicesPage.ui");bindHome(events);showWallet(commands,ref,store);
        allowedActions.clear();
        boolean journal = desk == Desk.JOURNAL;
        if (journal) JournalChrome.header(commands, "Journal", "Journal", null);
        else JournalChrome.header(commands, desk.identity.characterName(), desk.identity.profession(), desk.emblem);
        commands.set("#JournalShortcut.Visible", !journal);
        commands.set("#HintJournal.Visible", !journal);
        commands.set("#HintCloseText.TextSpans", Message.raw(journal ? "Close" : "Goodbye"));
        boolean backToDesk = !journal && !deskHome;
        commands.set("#Return.Visible", backToDesk || hasReturnPage());
        bind(events, "#Return", backToDesk ? "desk_home" : "return_parent");
        bind(events, "#Close", "close");
        if (journal) for (int i = 0; i < desk.sections.size(); i++) {
            Section entry = desk.sections.get(i);
            bind(events, JournalChrome.navTab(commands, i, tabLabel(entry), sectionIcon(entry), entry == section, busy), "nav:" + entry.name());
        }
        if (deskHome) { buildDeskHome(commands, events, ref, store); return; }
        commands.append("#ServicesBody", "EterniaMod/" + layout() + ".ui");
        JournalChrome.section(commands, sectionName(section), sectionIcon(section), sectionNote(section));
        com.hexvane.eterniamod.ui.MenuPagination.sidebar(commands, pending == null ? view.rows.size() : 0, visiblePageSize());commands.set("#Previous.Disabled", busy || pending!=null || page == 0);commands.set("#Next.Disabled",busy || pending!=null || (page+1)*PAGE_SIZE>=view.rows.size());
        commands.set("#Pagination.Text", "Page " + (page+1) + " / " + Math.max(1,(view.rows.size()+PAGE_SIZE-1)/PAGE_SIZE));
        bind(events,"#Previous","previous");bind(events,"#Next","next");
        var tabs=tabs().stream().filter(tab->allowed(tab.route)).toList();commands.set("#Tabs.Visible",pending==null&&!tabs.isEmpty());
        for(int i=0;i<tabs.size();i++){var tab=tabs.get(i);bind(events,JournalChrome.sideTab(commands,i,tab.label,tab.icon,tab.selected,busy),tab.route);}
        commands.set("#SectionTitle.TextSpans", Message.raw(pending == null ? view.title : "Confirm action"));
        commands.set("#Description.TextSpans", Message.raw(pending == null ? view.description : pending.explanation));
        commands.setObject("#Description.Anchor",UiAnchors.heightWithBottom(Math.max(50,UiPresentation.wrappedHeight(pending==null?view.description:pending.explanation,1400,24)),8));
        commands.set("#Status.TextSpans", Message.raw(busy ? "Working…" : status));
        List<Row> rows = pending == null ? view.rows : List.of();
        int start = Math.min(page * visiblePageSize(), rows.size());
        for (int i = start; i < Math.min(rows.size(), start + visiblePageSize()); i++) {
            Row row = rows.get(i);
            if(collectionGrid()&&!row.contentId.isEmpty()) {
                int perRow=cardsPerRow();int card=(i-start)%perRow;String grid="#Rows["+((i-start)/perRow)+"]";
                if(card==0){commands.append("#Rows","EterniaMod/CollectionCardRow.ui");for(int slot=0;slot<CARDS_PER_ROW;slot++){commands.set(grid+" #Slot"+slot+".Visible",slot<perRow);commands.set(grid+" #Card"+slot+".Visible",slot<perRow&&i+slot<rows.size());}}
                String tile=grid+" #Card"+card;ContentImages.row(commands,tile,row.contentId);
                commands.set(tile+" #CardName.Text",row.text);commands.set(tile+" #CardDetail.Text",row.goal);
                boolean cardAction=!row.label.isEmpty()&&(row.route.isEmpty()||allowed(row.route));commands.set(tile+" #RowAction.Text",row.label);commands.set(tile+" #RowAction.Visible",cardAction);
                commands.set(tile+" #RowAction.Disabled",busy||row.route.isEmpty());
                if(cardAction&&!row.route.isEmpty())bind(events,tile+" #RowAction",row.route);
                continue;
            }
            String selector = "#Rows[" + (i - start) + "]";
            if(!row.cells.isEmpty()) {commands.append("#Rows","EterniaMod/RewardLevel.ui");commands.set(selector+" #Level.Text",row.text);
                for(int index=0;index<2;index++){Row cell=row.cells.get(index);String track=index==0?"Free":"Paid";ContentImages.row(commands,selector+" #"+track+"Reward",cell.contentId);commands.set(selector+" #"+track+"Name.Text",cell.text);commands.set(selector+" #"+track+"Action.Text",cell.label);commands.set(selector+" #"+track+"Action.Visible",!cell.label.isEmpty());commands.set(selector+" #"+track+"Action.Disabled",busy||cell.route.isEmpty());if(!cell.route.isEmpty())bind(events,selector+" #"+track+"Action",cell.route);}continue;}
            if(row.total>0) {commands.append("#Rows","EterniaMod/QuestRow.ui");commands.set(selector+" #RowText.Text",row.text);commands.set(selector+" #QuestGoal.Text",row.goal);commands.set(selector+" #QuestReward.Text","+"+CurrencyUi.amount(row.xp)+" XP");commands.set(selector+" #QuestCoins.Text",CurrencyUi.amount(row.coins)+" Coins"+(row.count>=row.total?" received":" on completion"));commands.set(selector+" #QuestCount.Text",Math.min(row.count,row.total)+" / "+row.total+(row.count>=row.total?" · Complete":""));bar(commands,selector+" #QuestFill",selector+" #QuestRest",row.count,row.total);continue;}
            commands.append("#Rows", section==Section.MAIL?"EterniaMod/MailRow.ui":"EterniaMod/ServiceRow.ui");
            if(section!=Section.MAIL){ContentImages.row(commands,selector,row.contentId);CurrencyUi.price(commands,selector,row.coins," each");}
            commands.set(selector + " #RowText.TextSpans", Message.raw(row.text));
            commands.set(selector + " #RowAction.TextSpans", Message.raw(row.label));
            boolean rowAction = !row.label.isEmpty() && (row.route.isEmpty() || allowed(row.route));
            commands.set(selector + " #RowAction.Visible", rowAction);
            commands.set(selector + " #RowAction.Disabled", busy || row.route.isEmpty());
            if(section!=Section.MAIL){int width=UiPresentation.buttonWidth(row.label);int body=layout().equals("ServicesOverview")?1160:layout().equals("ServicesRoster")&&Arrays.stream(view.fields).anyMatch(label->!label.isEmpty())?1080:1400;commands.setObject(selector+" #RowAction.Anchor",UiAnchors.serviceAction(width));commands.setObject(selector+".Anchor",UiAnchors.heightWithBottom(Math.max(76,UiPresentation.wrappedHeight(row.text,body-40-(row.coins>=0?176:0)-(row.contentId.isEmpty()?0:78)-(row.label.isEmpty()?0:width),23)+24),8));}
            else{int textHeight=Math.max(56,UiPresentation.wrappedHeight(row.text,436,23));commands.setObject(selector+" #RowText.Anchor",UiAnchors.heightWithBottom(textHeight,8));commands.setObject(selector+".Anchor",UiAnchors.heightWithBottom(textHeight+64,10));}
            if (rowAction && !row.route.isEmpty()) bind(events, selector + " #RowAction", row.route);
        }
        String[] labels = pending == null ? view.fields : new String[] {"", "", ""};
        boolean hasFields=Arrays.stream(labels).anyMatch(label->!label.isEmpty());commands.set("#Fields.Visible",hasFields);
        if(!Set.of("ServicesMail","ServicesRoster").contains(layout()))commands.setObject("#Fields.Anchor",UiAnchors.heightWithBottom(hasFields?(int)Arrays.stream(labels).filter(label->!label.isEmpty()).count()*50:0,10));
        if(section==Section.MAIL){commands.set("#Reader.Visible",!hasFields);commands.set("#Document.Text",pending==null?view.document:pending.explanation);commands.setObject("#Document.Anchor",UiAnchors.heightWithRight(Math.max(250,UiPresentation.wrappedHeight(view.document,820,25)),12));}
        if(layout().equals("ServicesOverview")){String feature=switch(section){case HOUSING->"Your own corner";case WORLDS->"The road awaits";case COMING_SOON->"More adventures ahead";default->"Welcome, adventurer";};commands.set("#FeatureTitle.Text",feature);commands.set("#FeatureText.Text",switch(section){case HOUSING->"A free plot, a home to personalize, and a place to return to after your adventures.";case WORLDS->"Public portals connect the Hub to the worlds beyond.";case COMING_SOON->"Minigames are coming soon. Your housing and season journeys are ready now.";default->"Prowl\nEternia Guide\n\nSettle in, find your guild, and start exploring.";});commands.setObject("#SectionArt.Background", new com.hypixel.hytale.server.core.ui.PatchStyle(com.hypixel.hytale.server.core.ui.Value.of("EterniaMod/Icons/"+(section==Section.WORLDS?"worlds":"housing")+".png")));}
        if(section==Section.SEASON){commands.set("#SeasonProgress.Visible",!focus.isEmpty()&&pending==null);commands.set("#ProgressLabel.Text",view.progressLabel);bar(commands,"#ProgressFill","#ProgressRest",view.progress,view.progressTotal);
            if(pending==null&&view.season!=null)SeasonHall.render(commands,view.season,(selector,route)->bind(events,selector,route),busy);}
        if(section==Section.COLLECTION){commands.set("#CollectionBanner.Visible",false);commands.set("#CollectionSummary.Text",view.document);
            boolean viewer=!previewContent.isEmpty()&&pending==null;commands.set("#CollectionPreview.Visible",viewer);
            if(viewer){commands.set("#PreviewName.TextSpans",Message.raw(view.rows.stream().filter(row->row.contentId.equals(previewContent)).map(Row::text).findFirst().orElse(UiPresentation.contentName(previewContent))));bind(events,"#ClosePreview","collection_preview_close");}}
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
            boolean shown = !buttons[i].label.isEmpty() && (buttons[i].route.isEmpty() || allowed(buttons[i].route));
            commands.set(buttonIds[i] + ".Visible", shown);
            commands.set(buttonIds[i] + ".Disabled", busy || buttons[i].route.isEmpty());
            if (shown && !buttons[i].route.isEmpty()) bind(events, buttonIds[i], buttons[i].route);
        }
        if(section==Section.SEASON)commands.set("#Status.Visible",!status.isEmpty());
        if(section==Section.SEASON)commands.set("#Actions.Visible",Arrays.stream(buttons).anyMatch(button->!button.label.isEmpty()&&(button.route.isEmpty()||allowed(button.route))));
        if (initialLoad) { initialLoad = false; store.getExternalData().getWorld().execute(()->{if(!isDismissed()&&ref.isValid())refresh(ref, store, "");}); }
    }

    private int cardsPerRow(){return previewContent.isEmpty()?CARDS_PER_ROW:4;}
    /** Every rebuild recreates the viewer, so the open prefab is sent again after it. */
    private final com.hexvane.eterniamod.ui.PrefabCardPreview preview;
    @Override protected void rebuild(){super.rebuild();if(isDismissed())return;preview.show(section==Section.COLLECTION&&!deskHome&&!previewContent.isEmpty()?previewContent:null);}
    @Override public void onDismiss(@Nonnull Ref<EntityStore> ref,@Nonnull Store<EntityStore> store){preview.clear();super.onDismiss(ref,store);}
    private boolean collectionGrid(){return section==Section.COLLECTION&&!mode.equals("pet");}
    private int visiblePageSize(){return section==Section.COLLECTION&&(mode.equals("inventory")||collectionGrid())?Math.max(PAGE_SIZE,view.rows.size()):PAGE_SIZE;}

    private String layout(){return switch(section){case GREETER,HOUSING,WORLDS,COMING_SOON->"ServicesOverview";case GUILD->"ServicesRoster";case MAIL->"ServicesMail";case SEASON->"ServicesSeason";case COLLECTION->"ServicesCollection";default->"ServicesDirectory";};}
    private List<Tab> tabs(){return switch(section){
        case GUILD->view.title.equals("Find your guild")||view.title.equals("Loading")?List.of():List.of(new Tab("Members","back","members",mode.isEmpty()||mode.equals("member")),new Tab("Roles","guild_roles","title",mode.startsWith("role")),new Tab("Notice board","guild_board","board",false),new Tab("Guild estate","external:GUILD_HOUSING","charter",false));
        case MAIL->List.of(new Tab("Inbox","back","mail",!mode.equals("compose")),new Tab("Write a letter","mail_compose","quests",mode.equals("compose")),new Tab("Deliveries","external:ITEM_DESK","parcel",false));
        case SEASON->focus.isEmpty()?List.of():List.of(new Tab("Rewards","season_rewards","season",!mode.equals("quests")),new Tab("Quests","season_quests","quests",mode.equals("quests")),new Tab("All passes","back","back",false));
        case COLLECTION->{
            String current=mode.isEmpty()?"houses":mode.equals("pet")?"pets":mode;
            String[][] categories={{"Houses","housing"},{"Decorations","furnishing"},{"Materials","palette"},{"Titles","title"},{"Wardrobe","wardrobe"},{"Pets","pet"},{"Mounts","mount"},{"Upgrades","addition"}};
            List<Tab> list=new ArrayList<>();
            for(String[] category:categories){String id=category[0].toLowerCase(java.util.Locale.ROOT);list.add(new Tab(category[0],"collection_view:"+id,category[1],id.equals(current)));}
            yield list;
        }
        case SHOP->List.of(new Tab("Browse","back","market",true),new Tab("My shop","external:SHOP_MANAGE","storage",false),new Tab("Trade","external:TRADE_DESK","trade",false));
        default->List.of();};}
    private record Tab(String label,String route,String icon,boolean selected) {}

    /** A route may lead only to this desk's own sections and trades; everything else belongs to another NPC or the Journal. */
    private boolean allowed(String route){
        if(route.startsWith("nav:")){try{return desk.sections.contains(Section.valueOf(route.substring(4)));}catch(IllegalArgumentException unknown){return false;}}
        if(route.startsWith("external:")){try{return desk.actions.contains(SocialUiActions.Action.valueOf(route.substring(9)));}catch(IllegalArgumentException unknown){return false;}}
        if(route.startsWith("shop_visit:"))return desk==Desk.BROKER;
        if(route.equals("intro_toolkit"))return desk==Desk.GUIDE;
        return true;
    }

    private void buildDeskHome(UICommandBuilder commands, UIEventBuilder events, Ref<EntityStore> ref, Store<EntityStore> store) {
        commands.set("#SideBar.Visible", false);
        commands.append("#ServicesBody", "EterniaMod/ServicesDesk.ui");
        commands.set("#DeskGreeting.TextSpans", Message.raw(view.description));
        commands.set("#DeskStatus.TextSpans", Message.raw(busy ? "Working…" : status));
        for (int i = 0; i < view.tiles.size(); i++) {
            Tile tile = view.tiles.get(i);
            boolean usable = !tile.route.isEmpty() && allowed(tile.route);
            String selector = JournalChrome.tile(commands, i, tile.title, tile.detail, tile.icon, tile.primary, busy || !usable);
            if (usable) bind(events, selector, tile.route);
        }
        if (initialLoad) { initialLoad = false; store.getExternalData().getWorld().execute(()->{if(!isDismissed()&&ref.isValid())refresh(ref, store, "");}); }
    }

    private String tabLabel(Section entry){return switch(entry){case HOUSING->"Home";case MAIL->"Mail";case SEASON->"Seasons";default->entry.title;};}
    private String sectionName(Section entry){return desk==Desk.JOURNAL?tabLabel(entry):entry.title;}
    private static String sectionIcon(Section entry){return switch(entry){case GREETER,WORLDS->"worlds";case HOUSING->"housing";case GUILD->"guild";case MAIL->"mail";case SHOP->"market";case QUESTS->"quests";case SEASON->"season";case COLLECTION->"collection";case STORE->"crown";case COMING_SOON->"games";};}
    private String sectionNote(Section entry){return switch(entry){
        case GREETER->"How life in Eternia works.";
        case HOUSING->desk==Desk.JOURNAL?"Your plot and home at a glance.":"Your plot, moves and building rules.";
        case GUILD->"Your guild, its members and roles.";
        case MAIL->"Letters and parcels from other players.";
        case SHOP->"Real listings from players across Eternia.";
        case QUESTS->"Goals that earn season points and coins.";
        case SEASON->"Every pass stays yours forever.";
        case COLLECTION->"Everything you have earned, bought or unlocked.";
        case STORE->"Styles, decorations, titles and pets.";
        case WORLDS->"The worlds beyond the Hub.";
        case COMING_SOON->"Something fun is on the way.";
    };}
    private static void bar(UICommandBuilder commands,String fill,String rest,long value,long total){int amount=total<=0?0:(int)Math.max(0,Math.min(1000,(double)value/total*1000));commands.set(fill+".Visible",amount>0);commands.set(rest+".Visible",amount<1000);commands.set(fill+".FlexWeight",Math.max(1,amount));commands.set(rest+".FlexWeight",Math.max(1,1000-amount));}

    private void bind(UIEventBuilder events, String selector, String route) {
        String token = generation + ":" + allowedActions.size();
        allowedActions.put(token, route);
        EventData data = new EventData().append("Action", token);
        // The desk home has no text fields; asking the client for their values disconnects the player.
        if (!deskHome) data.append("@One", "#FieldOne.Value").append("@Two", "#FieldTwo.Value").append("@Three", "#FieldThree.Value");
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, data, false);
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
        if(!allowed(route))return;
        if(route.equals("desk_home")&&desk!=Desk.JOURNAL){deskHome=true;section=desk.sections.getFirst();mode="";focus="";page=0;one=two=three="";draftCapabilities=null;pending=null;refresh(ref,store,"");return;}
        if(route.equals("return_parent")){store.getExternalData().getWorld().execute(()->{if(ref.isValid()&&!isDismissed())returnOrClose(ref,store);});return;}
        if (route.startsWith("nav:")) {
            if(route.equals("nav:WORLDS")){registration.actions.perform(SocialUiActions.Action.WORLD_SELECT,ref,store,playerRef);return;}
            section = Section.valueOf(route.substring(4)); deskHome = false; previewContent = ""; mode = ""; focus = ""; page = 0; one = two = three = ""; draftCapabilities = null; pending = null;
            refresh(ref, store, ""); return;
        }
        if (route.equals("next") || route.equals("previous")) { page = Math.max(0, page + (route.equals("next") ? 1 : -1)); generation++; rebuild(); return; }
        if (route.equals("cancel_confirmation")) { pending = null; generation++; rebuild(); return; }
        if (route.equals("confirm")) { Pending current = pending; if (current == null) return; pending = null; runDomain(ref, store, current.route, current.input); return; }
        if(route.startsWith("mount_summon:")){
            close();com.hexvane.eterniamod.collections.MountRuntime.summon(services,playerRef,ref,store,route.substring(13));return;
        }
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
        if (route.startsWith("collection_view:")) { mode = route.substring(16); focus = ""; previewContent = ""; page = 0; refresh(ref, store, ""); return; }
        if (route.startsWith("collection_preview:")) { previewContent = route.substring(19); generation++; rebuild(); return; }
        if (route.equals("collection_preview_close")) { previewContent = ""; generation++; rebuild(); return; }
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
    private Request request() { return new Request(section, input(), desk, deskHome); }

    private void refresh(Ref<EntityStore> ref, Store<EntityStore> store, String notice) {
        Request request = request();
        view = View.empty(section.title, "Loading your latest information…");
        work(ref, store, () -> new Loaded(loadSnapshot(request), notice), false);
    }

    private void runDomain(Ref<EntityStore> ref, Store<EntityStore> store, String route, Input input) {
        Request request = new Request(section, input, desk, deskHome);
        String receipt = "native:" + pageReceipt + ":" + operationNumber;
        work(ref, store, () -> {
            String notice = mutate(route, input, receipt);
            if(section==Section.COLLECTION)CollectionBootstrap.refresh();
            return new Loaded(loadSnapshot(request), notice);
        }, true);
    }

    private void work(Ref<EntityStore> ref, Store<EntityStore> store, Supplier<Loaded> operation, boolean mutation) {
        if (busy || registration.closed) return;
        busy = true; long expected = ++generation;
        // Show the new section immediately instead of leaving the previous page on screen.
        rebuild();
        var world = store.getExternalData().getWorld();
        CompletableFuture.supplyAsync(operation, registration.executor).whenComplete((loaded, failure) -> world.execute(() -> {
            if (isDismissed() || registration.closed || !ref.isValid() || expected != generation) return;
            busy = false;
            if (failure == null) {
                if (mutation) operationNumber++;
                if(!Arrays.equals(view.fields,loaded.view.fields))one=two=three="";
                view = loaded.view;
                status = loaded.notice.isEmpty() ? deskHome || view.season != null ? "" : "Your latest account information. Entries " + (view.rows.isEmpty() ? "0" : Math.min(page * visiblePageSize() + 1, view.rows.size()) + "–" + Math.min((page + 1) * visiblePageSize(), view.rows.size())) + " of " + view.rows.size() : loaded.notice;
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

    private record ViewKey(UUID actor,Request request) {}
    private View loadSnapshot(Request request){
        return switch(request.section){
            case COLLECTION,SHOP -> services.readView(new ViewKey(actor,request),java.time.Duration.ofSeconds(2),()->load(request));
            case GREETER,STORE,WORLDS,COMING_SOON -> load(request);
            default -> services.batch(()->load(request));
        };
    }
    private View load(Request request) {
        Input input = request.input;
        if (request.home) return deskView(request.desk);
        return switch (request.section) {
            case GREETER -> new View("Welcome to Eternia", "I'm Prowl. Build a home, find your people, and explore at your own pace.", List.of(
                Row.info("Your first 24 × 24 plot, starter house, mailbox and one plot move are free."),
                Row.info("Choose a housing plot within five blocks of any public road's centerline. House paths do not count."),
                Row.info("Walk near a plot edge to see its low, glowing boundary. Claim previews show placement reasons."),
                Row.info("Guilds share a house and can grow a neighborhood of member plots."),
                Row.info("Select a season pass and earn progress through normal play. Old seasons never expire."),
                Row.info("Bramble Hearthleaf helps with plots and homes. Torren Ironbough handles guilds. Nima Quicktail runs the market."),
                Row.info("Press O any time to open your Journal: mail, guild, quests, seasons and your collection.")),
                noFields(), buttons("Starter toolkit", "intro_toolkit", "Travel", "external:WORLD_SELECT", "", ""), null);
            case HOUSING -> {
                var housing = services.housing().find(Owner.player(actor)).orElse(null);
                List<Row> rows = new ArrayList<>();
                rows.add(Row.info(housing == null ? "Your free 24 × 24 plot is waiting for you." : "Your home · " + UiPresentation.friendlyId(housing.state().name())));
                services.housing().location(Owner.player(actor)).ifPresent(location -> rows.add(Row.info(location.width() + " × " + location.depth() + " plot in " + com.hexvane.eterniamod.runtime.WorldDisplayNames.name(location.worldId()) + (location.guildId()==null?"":" · Guild neighbourhood"))));
                rows.add(Row.info("Move credits available: " + services.ownership().available(Owner.player(actor), HousingService.MOVE_CREDIT)));
                rows.add(Row.info("Keep structures and additions five blocks inside the plot and five blocks away from roads."));
                if (request.desk == Desk.JOURNAL && housing == null) rows.add(Row.info("Visit Bramble Hearthleaf in the Hub to claim your free plot."));
                yield new View("Housing Ledger", "Build and personalize your home. Purchases and placement tokens stay linked to your account.", rows, noFields(),
                    request.desk == Desk.JOURNAL
                        ? buttons("Housing inventory", "external:HOUSING_INVENTORY", "Refresh", "refresh", "", "")
                        : buttons(housing == null ? "Claim a plot" : "Furnish my home", housing == null ? "external:HOUSING_CLAIM" : "external:HOUSING_MANAGE", "Architect's Ledger", "external:ARCHITECT_LEDGER", "Refresh", "refresh"), null);
            }
            case GUILD -> guildView(input);
            case MAIL -> mailView(input);
            case SHOP -> {
                var listings=services.market().search(input.one);services.accounts().findAll(listings.stream().map(MarketService.Listing::seller).toList());
                List<Row> rows = listings.stream().map(listing -> new Row(UiPresentation.itemName(listing.itemId()) + "\n" + listing.stock() + " in stock · " + name(listing.seller()), "Visit shop", "shop_visit:" + listing.id()).image(listing.itemId()).price(listing.unitPrice())).toList();
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

    private View deskView(Desk desk) {
        return switch (desk) {
            case GUIDE -> View.desk("Welcome to Eternia! I'm Prowl. Build a home, find your people and explore at your own pace.", List.of(
                new Tile("Getting started", "How plots, guilds and seasons work.", "charter", "nav:GREETER", true),
                new Tile("Starter toolkit", "Your housing deed and packing wand.", "toolkit", "intro_toolkit", false),
                new Tile("Travel", "Visit the worlds beyond the Hub.", "worlds", "external:WORLD_SELECT", false)));
            case STEWARD -> {
                boolean home = services.housing().find(Owner.player(actor)).isPresent();
                yield View.desk(home ? "Welcome back! Want to change something about your home, or find a new view?"
                        : "Welcome! Every adventurer gets a free plot, a starter house and one free move. Shall we find you a spot?", List.of(
                    home ? new Tile("Furnish my home", "Place your house, decorations and additions.", "housing", "external:HOUSING_MANAGE", true)
                        : new Tile("Claim a plot", "Get a Housing Deed, then use it where you want to live.", "plot", "external:HOUSING_CLAIM", true),
                    new Tile("Architect's Ledger", "Your plots, palettes, paths and waygate.", "ledger", "external:ARCHITECT_LEDGER", false),
                    new Tile("Housing storage", "Browse your house styles and decorations.", "storage", "external:HOUSING_INVENTORY", false),
                    new Tile("Home details", "Your plot, move credits and building rules.", "plot", "nav:HOUSING", false)));
            }
            case MARSHAL -> {
                boolean member = services.guilds().membership(actor).isPresent();
                List<Tile> tiles = new ArrayList<>();
                tiles.add(member ? new Tile("Your guild", "Members, roles and invitations.", "guild", "nav:GUILD", true)
                    : new Tile("Find a guild", "Start a guild or accept an invitation.", "guild", "nav:GUILD", true));
                tiles.add(new Tile("Guild estate", "Claim and furnish your guild's estate, roads and waygate.", "charter", "external:GUILD_HOUSING", false));
                if (member) tiles.add(new Tile("Notice board", "Guild notices and pinned plans.", "board", "guild_board", false));
                yield View.desk("Strength in numbers. Five members can claim a guild estate together.", tiles);
            }
            case BROKER -> View.desk("Buying, selling or trading? Everything in the market goes through me.", List.of(
                new Tile("Browse player shops", "Find items listed by players across Eternia.", "market", "nav:SHOP", true),
                new Tile("My shop", "Stock your shelves and set your prices.", "storage", "external:SHOP_MANAGE", false),
                new Tile("Item desk", "Collect deliveries and stored items.", "parcel", "external:ITEM_DESK", false),
                new Tile("Trade with a player", "Swap items and coins safely.", "trade", "external:TRADE_DESK", false)));
            case GAMES -> View.desk("Minigames are on their way! Until then, your home and season pass are ready for you.", List.of(
                new Tile("Minigames", "Coming soon.", "games", "", true)));
            case JOURNAL -> throw new IllegalStateException("The Journal has no desk home.");
        };
    }

    private View collectionView(Input input) {
        Owner owner = Owner.player(actor);
        var definitions = services.collection().definitions();
        Map<String, CollectionService.Definition> catalog = new HashMap<>();
        definitions.forEach(definition -> catalog.put(definition.id(), definition));
        var grants = services.ownership().grants(owner);
        if (Set.of("", "houses", "decorations", "materials", "upgrades").contains(input.mode)) {
            List<Row> rows = new ArrayList<>();
            for (var item : com.hexvane.eterniamod.ui.CollectionInventory.summarize(grants,services.provenance().ownedInstances(owner),Instant.now())) {
                if(!com.hexvane.eterniamod.ui.CollectionCategories.matches(input.mode,item.contentId()))continue;
                var definition = catalog.get(item.contentId());
                String detail=item.contentId().equals("eternia:convenience/teleporter")?"\nUse Home Waygate in the Architect's Ledger while on your plot. Nothing needs placing.":"";
                String name=definition == null ? UiPresentation.contentName(item.contentId()) : definition.name();
                String availability=item.status()+(item.earliestExpiry()==null?"":" • Some expire on "+item.earliestExpiry());
                boolean model=com.hexvane.eterniamod.ui.PrefabCardPreview.supports(item.contentId());
                rows.add(Row.card(name,availability,model?"Preview":"",model?"collection_preview:"+item.contentId():"",item.contentId()));
            }
            String category=input.mode.isEmpty()?"houses":input.mode;
            return new View("Your "+category, switch(category){
                case "houses"->"Homes you can build. Open Housing inventory to choose one for your plot.";
                case "decorations"->"Furniture, garden treasures and additions for your home.";
                case "materials"->"Palettes and paths to make your home your own.";
                default->"Your plot upgrades, waygates and passes.";
            }, rows.isEmpty()?List.of(Row.info("Nothing in this collection yet.")):rows,noFields(),
                buttons("Housing inventory","external:HOUSING_INVENTORY","","","Refresh","refresh"),null);
        }
        if(input.mode.equals("mounts")){
            var rows=definitions.stream().filter(d->d.kind()==CollectionService.Kind.MOUNT&&services.ownership().owns(owner,d.id()))
                .map(d->Row.card(d.name(),"Ready to ride","Summon","mount_summon:"+d.id(),d.id())).toList();
            return new View("Your mounts","Summon a mount to start riding. It leaves when you dismount.",rows.isEmpty()?List.of(Row.info("No mounts owned yet.")):rows,noFields(),buttons("Refresh","refresh","","","",""),null);
        }

        if (input.mode.equals("pet") || input.mode.equals("pets")) {
            var pets = services.collection().pets(owner);
            if (input.mode.equals("pet")) {
                var pet = pets.stream().filter(p -> p.id().toString().equals(input.focus)).findFirst().orElseThrow(() -> new IllegalArgumentException("Pet no longer belongs to your collection."));
                var definition = catalog.get(pet.contentId());
                boolean home = services.housing().find(owner).filter(slot -> slot.state() == HousingService.State.ACTIVE).isPresent();
                return new View(definition == null ? "Your pet" : definition.name(), "Choose one pet to follow you, or let it roam around your home.",
                    List.of(Row.info("Current home: " + UiPresentation.friendlyId(pet.assignment())), Row.info("Only one personal pet can follow you at a time.")), noFields(),
                    buttons("Assign follower", "pet_follow", "Assign to home", home ? "pet_home" : "", "Clear assignment", "pet_unassign"), null);
            }
            List<Row> rows = new ArrayList<>();
            for (var pet : pets) {
                var definition = catalog.get(pet.contentId());
                rows.add(Row.card(definition == null ? UiPresentation.contentName(pet.contentId()) : definition.name(), UiPresentation.friendlyId(pet.assignment()), "Manage pet", "pet_view:" + pet.id(),pet.contentId()));
            }
            for (var grant : grants) {
                var definition = catalog.get(grant.contentId());
                if (definition == null || definition.kind() != CollectionService.Kind.PET || grant.revoked()
                    || grant.validUntil() != null && !grant.validUntil().isAfter(Instant.now())) continue;
                Set<Long> used = new HashSet<>();
                pets.stream().filter(pet -> pet.sourceGrant().equals(grant.id())).forEach(pet -> used.add(pet.sourceIndex()));
                long next = 0;
                while (used.contains(next)) next++;
                if (next < grant.quantity()) rows.add(Row.card(definition.name(),"Ready to welcome", "Add pet", "pet_adopt:" + grant.id() + ":" + next,grant.contentId()));
            }
            return new View("Your pets", "Choose a companion from your pets, or welcome a new pet from your collection.",
                rows.isEmpty() ? List.of(Row.info("You do not own any pets yet.")) : rows, noFields(),
                buttons("Refresh", "refresh", "", "", "", ""), null);
        }
        var selection = services.collection().selection(actor);
        List<Row> rows = new ArrayList<>();
        for (var definition : definitions) {
            boolean title=definition.kind()==CollectionService.Kind.PREFIX_TITLE||definition.kind()==CollectionService.Kind.SUFFIX_TITLE;
            if((input.mode.equals("titles")?!title:definition.kind()!=CollectionService.Kind.OUTFIT&&definition.kind()!=CollectionService.Kind.WEARABLE)||!services.ownership().owns(owner,definition.id()))continue;
            String selected = switch (definition.kind()) {
                case PREFIX_TITLE -> selection.prefix(); case SUFFIX_TITLE -> selection.suffix(); case OUTFIT -> selection.outfit();
                case WEARABLE -> selection.wearables().getOrDefault(definition.slot(), ""); case PET, MOUNT -> "";
            };
            boolean equipped = definition.id().equals(selected);
            rows.add(Row.card(definition.name(),equipped?"Equipped":collectionKind(definition.kind()),equipped?"Unequip":"Equip",equipped?"collection_clear:"+definition.kind()+":"+definition.slot():"collection_equip:"+definition.id(),definition.id()));
        }
        return new View(input.mode.equals("titles")?"Your titles":"Your wardrobe",input.mode.equals("titles")?"Pair a prefix and suffix to make your name your own.":"Choose a look to wear on your adventures.",
            rows.isEmpty()?List.of(Row.info("Nothing in this collection yet.")):rows,noFields(),buttons("Refresh","refresh","","","",""),null);
    }

    private static String selectionName(Map<String,CollectionService.Definition> catalog,String id,String fallback){return id==null||id.isBlank()?fallback:catalog.containsKey(id)?catalog.get(id).name():UiPresentation.contentName(id);}

    private static String collectionKind(CollectionService.Kind kind) {
        return switch (kind) { case PREFIX_TITLE -> "Prefix title"; case SUFFIX_TITLE -> "Suffix title"; case OUTFIT -> "Outfit"; case WEARABLE -> "Wearable"; case PET -> "Pet"; case MOUNT -> "Mount"; };
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
        var roster=services.guilds().roster(actor);services.accounts().findAll(roster.stream().map(GuildService.Membership::playerId).toList());
        for (var member : roster) rows.add(new Row(name(member.playerId()) + " · " + online(member.playerId())+"\n"+roleNames.getOrDefault(member.role(),UiPresentation.friendlyId(member.role())), "Manage", "member_select:" + member.playerId()));
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
        services.accounts().findAll(inbox.stream().map(MailService.Message::sender).toList());
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
        return new View("Quest journal",pass.name()+" · Daily quests reset at 00:00 UTC. Weekly quests reset Monday at 00:00 UTC. Play in Adventure mode. XP and Coins arrive automatically.",rows.isEmpty()?List.of(Row.info("No quests have been published for this pass.")):rows,noFields(),buttons("Refresh","refresh","Season passes","nav:SEASON","",""),null);
    }

    private View seasonView(Input input) {
        var progress = services.seasons().progress(actor);
        var definitions = new HashMap<String,SeasonService.Definition>();
        services.seasons().definitions().forEach(definition -> definitions.put(definition.id(), definition));
        var names = new HashMap<String,String>();
        services.collection().definitions().forEach(item -> names.put(item.id(), item.name()));
        java.util.function.Function<String,String> name = id -> names.getOrDefault(id, UiPresentation.contentName(id));
        if (input.focus.isEmpty()) {
            var banners = progress.stream().filter(p -> definitions.containsKey(p.id())).map(p -> SeasonHall.banner(definitions.get(p.id()), p, services.seasons().claimedRewards(actor, definitions.get(p.id())), name)).toList();
            return new View("Season passes", "Choose a banner to see every reward. Your active pass earns XP while you play, and every pass stays yours forever.", banners.isEmpty() ? List.of(Row.info("No season has been published yet.")) : List.of(),
                noFields(), buttons("", "", "", "", "", ""), null).season(banners.isEmpty() ? null : SeasonHall.Screen.hall(banners));
        }
        var current = progress.stream().filter(p -> p.id().equals(input.focus)).findFirst().orElseThrow(() -> new IllegalArgumentException("Season is not available."));
        if (input.mode.equals("quests")) {
            var quests = services.seasons().quests(actor, input.focus).stream().map(Row::quest).toList();
            return new View(current.name() + " · Quests", "Complete these goals while you explore. Quest XP and Coins are awarded automatically.", quests.isEmpty() ? List.of(Row.info("No quests have been added to this pass yet.")) : quests,
                noFields(), buttons(current.active()?"Active pass":"Activate pass",current.active()?"":"season_activate","","","",""),null).progress(current);
        }
        var definition = definitions.get(input.focus);
        if (definition == null) throw new IllegalArgumentException("Season is not available.");
        var claimed = services.seasons().claimedRewards(actor, definition);
        var quests = services.seasons().quests(actor, input.focus).stream()
            .sorted(Comparator.comparing(SeasonService.QuestProgress::completed))
            .limit(4).map(q -> SeasonHall.quest(q, UiPresentation.questTitle(q.quest()), UiPresentation.questGoal(q.quest()))).toList();
        var screen = new SeasonHall.Screen(List.of(), SeasonHall.banner(definition, current, claimed, name), SeasonHall.tiles(definition, current, claimed, name), quests);
        return new View(current.name(), (current.active() ? "This is your active pass." : "Activate this pass to earn XP for it.") + (current.paid() ? " Both tracks are yours." : " The premium pass unlocks the second track."),
            List.of(), noFields(), buttons(current.active() ? "" : "Activate pass", current.active() ? "" : "season_activate", "", "", "", ""), null).season(screen);
    }

    private static String bounded(String value, int length) { return value.length() <= length ? value : value.substring(0, length); }
    private static String[] noFields() { return new String[] {"", "", ""}; }
    private static Button[] buttons(String a, String ar, String b, String br, String c, String cr) { return new Button[] {new Button(a, ar), new Button(b, br), new Button(c, cr)}; }
    private record Button(String label, String route) { static final Button NONE = new Button("", ""); }
    private record Row(String text,String label,String route,List<Row> cells,String goal,long count,long total,long xp,String contentId,long coins) {
        Row(String text,String label,String route){this(text,label,route,List.of(),"",0,0,0,"",-1);}
        Row image(String id){return new Row(text,label,route,cells,goal,count,total,xp,id,coins);}
        Row price(long amount){return new Row(text,label,route,cells,goal,count,total,xp,contentId,amount);}
        static Row info(String text){return new Row(text,"","");}
        static Row card(String title,String detail,String action,String route,String image){return new Row(title,action,route,List.of(),detail,0,0,0,image,-1);}
        static Row quest(SeasonService.QuestProgress progress){var quest=progress.quest();return new Row(UiPresentation.questTitle(quest),"","",List.of(),UiPresentation.questGoal(quest),progress.count(),quest.required(),quest.bonusXp(),"",quest.coins());}
        static Row rewards(String level,Row free,Row paid){return new Row(level,"","",List.of(free,paid),"",0,0,0,"",-1);}
    }
    private record Input(String one, String two, String three, String mode, String focus, Set<String> capabilities) {}
    private record Request(Section section, Input input, Desk desk, boolean home) {}
    private record Tile(String title,String detail,String icon,String route,boolean primary) {}
    private record View(String title,String description,List<Row> rows,String[] fields,Button[] buttons,GuildService.Role role,String document,long progress,long progressTotal,String progressLabel,List<Tile> tiles,SeasonHall.Screen season) {
        View(String title,String description,List<Row> rows,String[] fields,Button[] buttons,GuildService.Role role){this(title,description,rows,fields,buttons,role,"",0,0,"",List.of(),null);}
        View document(String value){return new View(title,description,rows,fields,buttons,role,value,progress,progressTotal,progressLabel,tiles,season);}
        View season(SeasonHall.Screen value){return new View(title,description,rows,fields,buttons,role,document,progress,progressTotal,progressLabel,tiles,value);}
        static View desk(String greeting,List<Tile> tiles){return new View("",greeting,List.of(),noFields(),EterniaServicesPage.buttons("","","","","",""),null,"",0,0,"",tiles,null);}
        View progress(SeasonService.Progress value){return new View(title,description,rows,fields,buttons,role,document,value.xp(),value.totalXp(),"Level "+value.level()+"  ·  "+value.xp()+" / "+value.totalXp()+" XP"+(value.active()?"  ·  Active pass":""),tiles,season);}
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
