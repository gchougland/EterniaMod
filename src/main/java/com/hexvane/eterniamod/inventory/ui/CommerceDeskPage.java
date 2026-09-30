package com.hexvane.eterniamod.inventory.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.socialui.*;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hexvane.eterniamod.ui.UiAnchors;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.function.Supplier;

/** Item-transfer desk. Native inventory mutations run on the world thread; domain snapshots use the shared menu executor. */
public final class CommerceDeskPage extends EterniaInteractiveCustomUIPage<CommerceDeskPage.Data> {
    public enum Mode { ITEMS, DELIVERIES, MY_SHOP, NEW_LISTING, BUY, MAIL, TRADES, TRADE }
    private final EterniaModPlugin plugin;private final EterniaServices services;private final UUID actor;
    private final Map<String,String> routes=new HashMap<>();private final Set<String> selected=new LinkedHashSet<>();
    private Mode mode;private String focus="",one="",two="",three="";private int page;private long generation;
    private boolean busy,initial=true;private String status="Loading saved items…";private View view=View.loading();private Pending pending;
    private String receiptSignature="",receipt="";
    private CommerceDeskPage(EterniaModPlugin plugin,PlayerRef player,Mode mode,String focus,String one,String two,String three){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,Data.CODEC);this.plugin=plugin;services=plugin.getServices();actor=player.getUuid();this.mode=mode;this.focus=focus;this.one=one;this.two=two;this.three=three;}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Mode mode){open(plugin,ref,store,player,mode,"","","","");}
    public static void open(EterniaModPlugin plugin,Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,Mode mode,String focus,String one,String two,String three){
        if(!SocialUiBootstrap.isOpen(plugin.getServices())||!ref.isValid())return;var component=store.getComponent(ref,Player.getComponentType());if(component!=null)component.getPageManager().openCustomPage(ref,store,new CommerceDeskPage(plugin,player,mode,focus,one,two,three));
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store){
        commands.append("EterniaMod/ServicesPage.ui");bindHome(events);commands.append("#ServicesBody","EterniaMod/"+(mode==Mode.MAIL?"ServicesMail":mode==Mode.TRADE?"ServicesTrade":"ServicesDirectory")+".ui");commands.set("#ServicesTitle.TextSpans",Message.raw((mode==Mode.MAIL?"Post office":mode==Mode.TRADE||mode==Mode.TRADES?"Trading table":"Market vault")));routes.clear();
        var navigation=List.of(new Button("Main menu","main_menu"),new Button("Return from visit","return_visit"),new Button("Stored items","mode:ITEMS"),new Button("Deliveries","mode:DELIVERIES"),new Button("My shop","mode:MY_SHOP"),new Button("Send a parcel","mode:MAIL"),new Button("Trades","mode:TRADES"),new Button("Shop directory","directory"),new Button("Refresh","refresh"));
        for(int i=0;i<navigation.size();i++){var b=navigation.get(i);commands.append("#Navigation","EterniaMod/ServiceNav.ui");String selector="#Navigation["+i+"]";commands.set(selector+" #Selected.Visible",b.route.equals("mode:"+mode.name()));commands.set(selector+" #NavButton.TextSpans",Message.raw(b.text));commands.set(selector+" #NavButton.Disabled",busy&&!b.route.equals("close"));bind(events,selector+" #NavButton",b.route);}
        commands.set("#PageControls.Visible",pending == null && view.entries.size() > 6);com.hexvane.eterniamod.ui.MenuPagination.show(commands, pending == null ? view.entries.size() : 0, 6);commands.set("#Tabs.Visible",false);commands.set("#Pagination.Text","Page "+(page+1)+" / "+Math.max(1,(view.entries.size()+5)/6));commands.set("#Previous.Disabled",busy||page==0);commands.set("#Next.Disabled",busy||(page+1)*6>=view.entries.size());bind(events,"#Previous","previous");bind(events,"#Next","next");bind(events,"#Close","close");
        commands.set("#SectionTitle.TextSpans",Message.raw(pending==null?view.title:"Confirm transfer"));commands.set("#Description.TextSpans",Message.raw(pending==null?view.description:pending.description));commands.setObject("#Description.Anchor",UiAnchors.heightWithBottom(Math.max(50,UiPresentation.wrappedHeight(pending==null?view.description:pending.description,786,24)),8));commands.set("#Status.TextSpans",Message.raw(busy?"Working…":status));
        List<Entry> rows=pending==null?view.entries:pending.entries;int start=Math.min(page*6,rows.size());
        for(int i=start;i<Math.min(start+6,rows.size());i++){var entry=rows.get(i);commands.append("#Rows",mode==Mode.MAIL?"EterniaMod/MailRow.ui":"EterniaMod/ServiceRow.ui");String selector="#Rows["+(i-start)+"]";commands.set(selector+" #RowText.TextSpans",Message.raw(entry.text));commands.set(selector+" #RowAction.TextSpans",Message.raw(entry.button));commands.set(selector+" #RowAction.Visible",!entry.button.isEmpty());commands.set(selector+" #RowAction.Disabled",busy||entry.route.isEmpty());if(mode!=Mode.MAIL){com.hexvane.eterniamod.ui.ContentImages.row(commands,selector,entry.itemId);int width=UiPresentation.buttonWidth(entry.button);commands.setObject(selector+" #RowAction.Anchor",UiAnchors.serviceAction(width));commands.setObject(selector+".Anchor",UiAnchors.heightWithBottom(Math.max(76,UiPresentation.wrappedHeight(entry.text,756-width-(entry.itemId.isEmpty()?0:78),23)+24),8));}if(!entry.route.isEmpty())bind(events,selector+" #RowAction",entry.route);}
        String[] ids={"One","Two","Three"},values={one,two,three};
        boolean hasFields=pending==null&&Arrays.stream(view.fields).anyMatch(label->!label.isEmpty());commands.set("#Fields.Visible",hasFields);
        if(mode!=Mode.MAIL)commands.setObject("#Fields.Anchor",UiAnchors.heightWithBottom(hasFields?(int)Arrays.stream(view.fields).filter(label->!label.isEmpty()).count()*50:0,10));
        else{commands.set("#Reader.Visible",!hasFields);commands.set("#Document.Text",pending==null?"Select saved items to attach to your parcel.":pending.description);}
        if(mode==Mode.TRADE){var trade=view.trade;String mine="Choose your offer below.",theirs="Waiting for the other player.";if(trade!=null){boolean first=trade.first().equals(actor);var a=first?trade.firstOffer():trade.secondOffer();var b=first?trade.secondOffer():trade.firstOffer();mine=a.coins()+" coins\n"+a.escrowIds().size()+" item stacks\n"+((first?trade.firstConfirmed():trade.secondConfirmed())?"Confirmed":"Awaiting confirmation");theirs=b.coins()+" coins\n"+b.escrowIds().size()+" item stacks\n"+((first?trade.secondConfirmed():trade.firstConfirmed())?"Confirmed":"Awaiting confirmation");}commands.set("#YourOffer.Text",mine);commands.set("#TheirOffer.Text",theirs);}
        for(int i=0;i<3;i++){String label=pending==null?view.fields[i]:"";commands.set("#Field"+ids[i]+"Group.Visible",!label.isEmpty());commands.set("#Field"+ids[i]+"Label.TextSpans",Message.raw(label));commands.set("#Field"+ids[i]+".Value",values[i]);events.addEventBinding(CustomUIEventBindingType.ValueChanged,"#Field"+ids[i],EventData.of("@"+ids[i],"#Field"+ids[i]+".Value"),false);}
        List<Button> buttons=pending==null?view.buttons:List.of(new Button("Confirm","confirm"),new Button("Cancel","cancel_confirmation"));String[] selectors={"#Primary","#Secondary","#Tertiary"};
        for(int i=0;i<3;i++){Button button=i<buttons.size()?buttons.get(i):new Button("","");commands.set(selectors[i]+".TextSpans",Message.raw(button.text));commands.set(selectors[i]+".Visible",!button.text.isEmpty());commands.set(selectors[i]+".Disabled",busy||button.route.isEmpty());if(!button.route.isEmpty())bind(events,selectors[i],button.route);}
        if(initial){initial=false;store.getExternalData().getWorld().execute(()->refresh(ref,store,""));}
    }
    private void bind(UIEventBuilder events,String selector,String route){String token=generation+":"+routes.size();routes.put(token,route);events.addEventBinding(CustomUIEventBindingType.Activating,selector,new EventData().append("Action",token).append("@One","#FieldOne.Value").append("@Two","#FieldTwo.Value").append("@Three","#FieldThree.Value"),false);}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,Data data){store.getExternalData().getWorld().execute(()->handle(ref,store,data));}
    private void handle(Ref<EntityStore> ref,Store<EntityStore> store,Data data){
        if(!current(ref,store))return;String route=routes.get(data.action);if(route==null){if(!busy&&pending==null)capture(data);return;}
        if(route.equals("close")){close();return;}if(busy)return;if(pending==null)capture(data);
        try{
            if(route.equals("cancel_confirmation")){pending=null;page=0;generation++;rebuild();return;}
            if(route.equals("confirm")){Pending action=pending;if(action==null)return;pending=null;perform(ref,store,action.route,action.draft,action.quote,action.trade);return;}
            if(route.startsWith("mode:")){mode=Mode.valueOf(route.substring(5));focus="";one=two=three="";selected.clear();pending=null;page=0;refresh(ref,store,"");return;}
            if(route.equals("return_visit")){NativeShopTravel.returnFromVisit(plugin,playerRef);close();return;}
            if(route.equals("main_menu")){SocialUiBootstrap.open(ref,store,playerRef,services,plugin.getMenuActions(),EterniaServicesPage.Section.GREETER);return;}
            if(route.equals("directory")){plugin.getMenuActions().perform(SocialUiActions.Action.SHOP_BROWSE,ref,store,playerRef);return;}
            if(route.equals("previous")||route.equals("next")){int size=pending==null?view.entries.size():pending.entries.size();page=Math.max(0,Math.min(Math.max(0,(size-1)/6),page+(route.equals("next")?1:-1)));generation++;rebuild();return;}
            if(route.equals("refresh")){refresh(ref,store,"");return;}
            if(route.startsWith("select:")){String id=route.substring(7);if(!selected.remove(id)){if(selected.size()>=(mode==Mode.MAIL?5:12))throw new IllegalArgumentException("The attachment limit has been reached.");selected.add(id);}refresh(ref,store,"Selected "+selected.size()+" stack(s).");return;}
            if(route.startsWith("listing_new:")){mode=Mode.NEW_LISTING;focus=route.substring(12);one="";page=0;refresh(ref,store,"");return;}
            if(route.startsWith("trade_view:")){mode=Mode.TRADE;focus=route.substring(11);one="";selected.clear();page=0;refresh(ref,store,"");return;}
            Draft draft=draft();
            if(Set.of("mail_send","listing_publish","buy","trade_confirm","trade_offer","trade_cancel").contains(route)||route.startsWith("listing_cancel:")){
                if(route.equals("buy"))NativeShopTravel.requireAtShop(plugin,ref,store,playerRef,focus);
                var evidence=new ArrayList<Entry>();for(var entry:view.entries)if(entry.route.isEmpty()||Set.of("mail_send","trade_offer").contains(route)&&entry.route.startsWith("select:")&&draft.selected.contains(entry.route.substring(7))||entry.route.equals(route)&&route.startsWith("listing_cancel:"))evidence.add(Entry.info(entry.text).item(entry.itemId));
                pending=new Pending(route,draft,confirmation(route,draft),List.copyOf(evidence),view.quote,view.trade);page=0;generation++;rebuild();return;
            }
            perform(ref,store,route,draft,view.quote,view.trade);
        }catch(RuntimeException failure){notice(failure);}
    }
    private void capture(Data data){if(data.one!=null)one=bounded(data.one,2000);if(data.two!=null)two=bounded(data.two,2000);if(data.three!=null)three=bounded(data.three,2000);}
    private Draft draft(){return new Draft(mode,focus,one.trim(),two,three,List.copyOf(selected));}
    private void perform(Ref<EntityStore> ref,Store<EntityStore> store,String route,Draft draft,MarketService.Listing quote,TradeService.Trade trade){
        String id=receipt(route,draft);
        if(route.equals("deposit")||route.startsWith("delivery:")||route.equals("buy")){
            busy=true;generation++;rebuild();
            try{
                String notice;
                if(route.equals("deposit")){plugin.getItemEscrow().depositHand(ref,store,playerRef);notice="Held stack saved. It can now be listed, mailed, traded, or withdrawn.";}
                else if(route.startsWith("delivery:")){boolean delivered=plugin.getItemEscrow().claim(ref,store,playerRef,route.substring(9));notice=delivered?"Items delivered to your inventory.":"Make room in your inventory; the delivery remains saved.";}
                else{
                    if(quote==null)throw new IllegalStateException("Refresh the shop quote first.");NativeShopTravel.requireAtShop(plugin,ref,store,playerRef,quote.id());
                    var purchase=services.market().buy(actor,quote.id(),positive(draft.one),quote.revision(),id);mode=Mode.DELIVERIES;focus="";selected.clear();
                    boolean delivered=plugin.getItemEscrow().claim(ref,store,playerRef,purchase.deliveryId());notice=delivered?"Purchase complete. Items are in your inventory.":"Purchase complete. Make room, then collect it from Deliveries.";
                }
                receiptSignature="";busy=false;refresh(ref,store,notice);
            }catch(RuntimeException failure){busy=false;notice(failure);}return;
        }
        work(ref,store,()->{
            String message;
            if(route.startsWith("withdraw:")){services.escrow().withdraw(Owner.player(actor),route.substring(9),id);message="Stack moved to Deliveries. Collect it when your inventory has space.";}
            else if(route.startsWith("listing_cancel:")){services.market().cancel(actor,route.substring(15));message="Listing closed. Unsold stock is waiting in Deliveries.";}
            else switch(route){
                case "listing_publish"->{services.market().list(actor,draft.focus,positive(draft.one),id);message="Listing published. Buyers can visit while you are offline.";}
                case "mail_send"->{services.mail().send(actor,account(draft.one),draft.two,draft.three,draft.selected,id);message="Mail and selected attachments sent.";}
                case "trade_open"->{var opened=services.trades().open(actor,account(draft.one));return new Loaded(load(new Draft(Mode.TRADE,opened.id().toString(),"","","",List.of())),"Trade opened. Both players must confirm the same offer.",Mode.TRADE,opened.id().toString());}
                case "trade_offer"->{if(trade==null)throw new IllegalStateException("Refresh the trade first.");services.trades().offer(actor,trade.id(),trade.offerRevision(),new TradeService.Offer(draft.selected,nonnegative(draft.one)));message="Offer saved. Both confirmations were reset.";}
                case "trade_confirm"->{if(trade==null)throw new IllegalStateException("Refresh the trade first.");var result=services.trades().confirm(actor,trade.id(),trade.offerRevision());message=result.state().equals("COMPLETED")?"Trade complete. Items are waiting in Deliveries.":"Offer confirmed. Waiting for the other player.";}
                case "trade_cancel"->{if(trade==null)throw new IllegalStateException("Refresh the trade first.");services.trades().cancel(actor,trade.id());message="Trade cancelled. Your offered items and coins are available again.";}
                default->throw new IllegalArgumentException("Unknown desk action");
            }
            Draft next=draft;if(route.equals("listing_publish"))next=new Draft(Mode.MY_SHOP,"","","","",List.of());if(route.equals("mail_send"))next=new Draft(Mode.MAIL,"","","","",List.of());
            return new Loaded(load(next),message,next.mode,next.focus);
        },true);
    }
    private String confirmation(String route,Draft draft){return switch(route){
        case "buy"->{if(view.quote==null)throw new IllegalStateException("No listing quote");long quantity=positive(draft.one);yield "Buy "+quantity+" × "+UiPresentation.itemName(view.quote.itemId())+" for "+Math.multiplyExact(quantity,view.quote.unitPrice())+" coins?";}
        case "mail_send"->"Send “"+draft.two+"” to "+draft.one+" with "+draft.selected.size()+" selected stack(s)?";
        case "listing_publish"->"List "+view.description+" Price: "+positive(draft.one)+" coins per item.";
        case "trade_offer"->"Replace your offer with "+draft.selected.size()+" selected stack(s) and "+nonnegative(draft.one)+" coins? Both players will need to confirm again.";
        case "trade_confirm"->"Confirm the exact trade shown below? When both players confirm this offer, the trade completes.";
        case "trade_cancel"->"Cancel this trade and release both players' offers?";
        default->"Close this listing? Unsold stock will return to your saved delivery inbox.";
    };}
    private void refresh(Ref<EntityStore> ref,Store<EntityStore> store,String notice){Draft snapshot=draft();work(ref,store,()->new Loaded(load(snapshot),notice,snapshot.mode,snapshot.focus),false);}
    private void work(Ref<EntityStore> ref,Store<EntityStore> store,Supplier<Loaded> task,boolean mutation){
        if(busy||!current(ref,store))return;busy=true;long expected=++generation;rebuild();var world=store.getExternalData().getWorld();
        SocialUiBootstrap.supply(services,task).whenComplete((loaded,failure)->{
            try{world.execute(()->{if(!current(ref,store)||generation!=expected)return;busy=false;
                if(failure!=null){notice(failure);return;}
                boolean switched=mode!=loaded.mode||!focus.equals(loaded.focus);mode=loaded.mode;focus=loaded.focus;view=loaded.view;status=loaded.notice.isEmpty()?"Your saved items and current offers.":loaded.notice;
                if(mutation){receiptSignature="";if(switched||mode==Mode.MAIL){selected.clear();one=two=three="";}}
                if(mode==Mode.TRADE&&view.trade!=null&&one.isEmpty()){var offer=view.trade.first().equals(actor)?view.trade.firstOffer():view.trade.secondOffer();one=Long.toString(offer.coins());selected.clear();selected.addAll(offer.escrowIds());}
                page=Math.min(page,Math.max(0,(view.entries.size()-1)/6));generation++;rebuild();
            });}catch(RuntimeException ignored){/* The old world stopped; the durable transaction remains available after login. */}
        });
    }
    private View load(Draft draft){
        var rows=new ArrayList<Entry>();Owner owner=Owner.player(actor);String[] fields=none();List<Button> buttons=List.of(new Button("Refresh","refresh"));MarketService.Listing quote=null;TradeService.Trade trade=null;String title,description;
        switch(draft.mode){
            case ITEMS->{title="Stored items";description="Deposit the stack in your hand, then choose how to use it. Deposited items are safely removed from your carried inventory.";for(var item:services.escrow().available(owner))rows.add(new Entry(UiPresentation.itemName(item.item().itemId())+" × "+item.remaining(),"Withdraw","withdraw:"+item.id()).item(item.item().itemId()));buttons=List.of(new Button("Store held stack","deposit"),new Button("Deliveries","mode:DELIVERIES"),new Button("Refresh","refresh"));}
            case DELIVERIES->{title="Deliveries";description="Mail attachments, purchases, cancelled listings and completed trades wait here until collected.";for(var delivery:services.escrow().deliveries(owner))if(!delivery.state().equals("DELIVERED"))rows.add(new Entry(UiPresentation.itemName(delivery.item().itemId())+" × "+delivery.item().quantity()+" · "+(delivery.state().equals("READY")?"Ready":"Recovering"),delivery.state().equals("READY")?"Collect":"",delivery.state().equals("READY")?"delivery:"+delivery.id():"").item(delivery.item().itemId()));}
            case MY_SHOP->{title="My shop";description="An active furnished house is required. Listing prices use earned coins; you can sell while offline.";
                for(var listing:services.market().search(""))if(listing.seller().equals(actor))rows.add(new Entry(UiPresentation.itemName(listing.itemId())+" × "+listing.stock()+" · "+listing.unitPrice()+" coins each","Close listing","listing_cancel:"+listing.id()).item(listing.itemId()));
                for(var item:services.escrow().available(owner))rows.add(new Entry("Saved: "+UiPresentation.itemName(item.item().itemId())+" × "+item.remaining(),"Set price","listing_new:"+item.id()).item(item.item().itemId()));buttons=List.of(new Button("Store held stack","deposit"),new Button("Refresh","refresh"),new Button("Deliveries","mode:DELIVERIES"));}
            case NEW_LISTING->{title="Price this stack";var item=services.escrow().find(draft.focus).orElseThrow();if(!item.owner().equals(owner)||!item.state().equals("AVAILABLE"))throw new IllegalStateException("This stack is no longer available.");description=UiPresentation.itemName(item.item().itemId())+" × "+item.remaining()+". Buyers may purchase part of the stack.";rows.add(Entry.info("The price is charged for each individual item."));fields=new String[]{"Coins per item","",""};buttons=List.of(new Button("Review listing","listing_publish"),new Button("My shop","mode:MY_SHOP"));}
            case BUY->{quote=services.market().search("").stream().filter(l->l.id().equals(draft.focus)).findFirst().orElseThrow(()->new IllegalStateException("This listing is no longer available."));title=name(quote.seller())+"'s shop";description="Stand near this house's entrance to purchase. Your coin balance is "+services.economy().balance(owner).available()+".";rows.add(Entry.info(UiPresentation.itemName(quote.itemId())+" · "+quote.unitPrice()+" coins each · "+quote.stock()+" available").item(quote.itemId()));fields=new String[]{"Quantity","",""};buttons=List.of(new Button("Review purchase",quote.seller().equals(actor)?"":"buy"),new Button("Refresh","refresh"),new Button("Deliveries","mode:DELIVERIES"));}
            case MAIL->{title="Write a parcel";description="Choose up to five saved stacks to attach. A full recipient mailbox leaves your items in storage.";for(var item:services.escrow().available(owner))rows.add(new Entry((draft.selected.contains(item.id())?"✓ ":"")+UiPresentation.itemName(item.item().itemId())+" × "+item.remaining(),draft.selected.contains(item.id())?"Remove":"Attach","select:"+item.id()).item(item.item().itemId()));fields=new String[]{"Player name","Subject","Message"};buttons=List.of(new Button("Review mail","mail_send"),new Button("Store held stack","deposit"),new Button("Refresh","refresh"));}
            case TRADES->{services.trades().expire();title="Player trades";description="Trade with another player, including guild members. Offers expire after five minutes; each player confirms the same offer.";for(var candidate:services.trades().forPlayer(actor))if(candidate.state().equals("OPEN"))rows.add(new Entry("Trade with "+name(candidate.first().equals(actor)?candidate.second():candidate.first()),"Review","trade_view:"+candidate.id()));
                services.guilds().membership(actor).ifPresent(member->{for(var mate:services.guilds().roster(actor))if(!mate.playerId().equals(actor))rows.add(Entry.info("Guild member: "+name(mate.playerId())));});fields=new String[]{"Player name","",""};buttons=List.of(new Button("Start trade","trade_open"),new Button("Refresh","refresh"),new Button("Deliveries","mode:DELIVERIES"));}
            case TRADE->{services.trades().expire();trade=services.trades().find(actor,UUID.fromString(draft.focus)).orElseThrow();title="Trade with "+name(trade.first().equals(actor)?trade.second():trade.first());description=UiPresentation.friendlyId(trade.state())+" · Changing an offer clears both confirmations.";
                var mine=trade.first().equals(actor)?trade.firstOffer():trade.secondOffer();var theirs=trade.first().equals(actor)?trade.secondOffer():trade.firstOffer();boolean mineConfirmed=trade.first().equals(actor)?trade.firstConfirmed():trade.secondConfirmed(),theirsConfirmed=trade.first().equals(actor)?trade.secondConfirmed():trade.firstConfirmed();
                rows.add(Entry.info("You offer "+mine.coins()+" coins"+(mineConfirmed?" · Confirmed":"")));for(String id:mine.escrowIds())rows.add(Entry.info("You: "+stack(id)));rows.add(Entry.info("They offer "+theirs.coins()+" coins"+(theirsConfirmed?" · Confirmed":"")));for(String id:theirs.escrowIds())rows.add(Entry.info("They: "+stack(id)));
                if(trade.state().equals("OPEN")){var chosen=draft.one.isEmpty()?mine.escrowIds():draft.selected;var choices=new LinkedHashMap<String,EscrowService.Escrow>();for(var item:services.escrow().available(owner))choices.put(item.id(),item);for(String id:mine.escrowIds())services.escrow().find(id).ifPresent(item->choices.put(id,item));for(var item:choices.values())rows.add(new Entry((chosen.contains(item.id())?"✓ ":"")+UiPresentation.itemName(item.item().itemId())+" × "+item.remaining(),chosen.contains(item.id())?"Remove":"Offer","select:"+item.id()).item(item.item().itemId()));fields=new String[]{"Your offered coins","",""};buttons=List.of(new Button("Save offer","trade_offer"),new Button(mineConfirmed?"Confirmed":"Confirm trade",mineConfirmed?"":"trade_confirm"),new Button("Cancel trade","trade_cancel"));}
                else buttons=List.of(new Button("Deliveries","mode:DELIVERIES"),new Button("Other trades","mode:TRADES"));
            }
            default->throw new IllegalStateException("Unknown desk view");
        }
        if(rows.isEmpty())rows.add(Entry.info("No entries are available yet."));return new View(title,description,List.copyOf(rows),fields,buttons,quote,trade);
    }
    private UUID account(String name){return services.accounts().findByName(name.trim()).orElseThrow(()->new IllegalArgumentException("That player has not joined Eternia yet.")).id();}
    private String name(UUID id){return services.accounts().find(id).map(AccountService.Account::displayName).orElse("Unknown player");}
    private String stack(String id){var item=services.escrow().find(id).orElseThrow();return UiPresentation.itemName(item.item().itemId())+" × "+item.remaining();}
    private String receipt(String route,Draft draft){String signature=route+draft.toString();if(!signature.equals(receiptSignature)){receiptSignature=signature;receipt="native-desk:"+UUID.randomUUID();}return receipt;}
    private boolean current(Ref<EntityStore> ref,Store<EntityStore> store){if(isDismissed()||!ref.isValid()||!SocialUiBootstrap.isOpen(services))return false;var player=store.getComponent(ref,Player.getComponentType());return player!=null&&player.getPageManager().getCustomPage()==this;}
    private void notice(Throwable failure){while(failure.getCause()!=null)failure=failure.getCause();status=failure instanceof DomainException||failure instanceof IllegalArgumentException||failure instanceof IllegalStateException?failure.getMessage():"This transfer could not complete. Your saved items remain available for recovery.";generation++;rebuild();}
    private static long positive(String value){long result=nonnegative(value);if(result==0)throw new IllegalArgumentException("Enter a positive whole number.");return result;}
    private static long nonnegative(String value){if(!value.matches("[0-9]{1,18}"))throw new IllegalArgumentException("Enter a whole number from 0 to 999999999999999999.");return Long.parseLong(value);}
    private static String bounded(String value,int limit){return value.length()>limit?value.substring(0,limit):value;}
    private static String[] none(){return new String[]{"","",""};}
    private record Draft(Mode mode,String focus,String one,String two,String three,List<String> selected){}
    private record Button(String text,String route){}
    private record Entry(String text,String button,String route,String itemId){Entry(String text,String button,String route){this(text,button,route,"");} Entry item(String id){return new Entry(text,button,route,id);} static Entry info(String text){return new Entry(text,"","");}}
    private record View(String title,String description,List<Entry> entries,String[] fields,List<Button> buttons,MarketService.Listing quote,TradeService.Trade trade){static View loading(){return new View("Trading desk","Loading account records…",List.of(),none(),List.of(),null,null);}}
    private record Loaded(View view,String notice,Mode mode,String focus){}
    private record Pending(String route,Draft draft,String description,List<Entry> entries,MarketService.Listing quote,TradeService.Trade trade){}
    public static final class Data{
        public String action,one,two,three;public static final BuilderCodec<Data> CODEC=BuilderCodec.builder(Data.class,Data::new).append(new KeyedCodec<>("Action",Codec.STRING),(d,v)->d.action=v,d->d.action).add().append(new KeyedCodec<>("@One",Codec.STRING),(d,v)->d.one=v,d->d.one).add().append(new KeyedCodec<>("@Two",Codec.STRING),(d,v)->d.two=v,d->d.two).add().append(new KeyedCodec<>("@Three",Codec.STRING),(d,v)->d.three=v,d->d.three).add().build();
    }
}
