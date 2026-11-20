package com.hexvane.eterniamod.socialui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.chat.ChatEmotes;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.inventory.NativeItemEscrow;
import com.hexvane.eterniamod.mail.MailText;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hexvane.eterniamod.ui.UiPresentation;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * The mailbox on a player's plot: letters as envelope cards, an opened letter with its parcel, and a writing desk that
 * packs items straight from the player's bag (or market storage). Items leave the bag only when the letter is sent.
 * Domain work runs on the menu executor; inventory moves run on the world thread.
 */
public final class MailboxPage extends EterniaInteractiveCustomUIPage<MailboxPage.Data> {
    private static final int CARDS_PER_PAGE = 6, BAG_PER_PAGE = 40, MAX_STACKS = 5;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneOffset.UTC);
    private enum View { LETTERS, LETTER, WRITE }
    private enum Kind { BAG, STORE }

    private final SocialUiBootstrap.Registration registration;
    private final EterniaServices services;
    private final UUID actor;
    private final Map<String, String> routes = new HashMap<>();
    private Snapshot snapshot = new Snapshot(List.of(), List.of(), List.of());
    private View view = View.LETTERS;
    private String focus = "", to = "", subject = "", body = "", status = "Opening your mailbox…";
    private final List<Pick> picks = new ArrayList<>();
    private int selected = -1, cardPage, bagPage;
    private boolean fromStorage, busy, initial = true;
    private long generation;

    MailboxPage(PlayerRef player, SocialUiBootstrap.Registration registration) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.registration = registration; services = registration.services; actor = player.getUuid();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder ui, @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        ui.append("EterniaMod/MailboxPage.ui"); bindHome(events); routes.clear();
        ui.set("#LettersView.Visible", view == View.LETTERS);
        ui.set("#LetterView.Visible", view == View.LETTER);
        ui.set("#WriteView.Visible", view == View.WRITE);
        ui.set("#MailTitle.TextSpans", Message.raw(view == View.WRITE ? "Write a letter" : "Mailbox"));
        ui.set("#MailKicker.TextSpans", Message.raw(view == View.WRITE ? "Pack a parcel from your bag" : "Your letters and parcels"));
        button(ui, events, "#WriteNew", "Write a letter", "write", view != View.WRITE);
        ui.set("#To.Value", to); ui.set("#Subject.Value", subject); ui.set("#Body.Value", body);
        switch (view) {
            case LETTERS -> drawLetters(ui, events);
            case LETTER -> drawLetter(ui, events);
            case WRITE -> drawWrite(ref, store, ui, events);
        }
        if (initial) { initial = false; refresh(""); }
    }

    private void drawLetters(UICommandBuilder ui, UIEventBuilder events) {
        var letters = snapshot.letters;
        int pages = Math.max(1, (letters.size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE);
        cardPage = Math.min(cardPage, pages - 1);
        int start = cardPage * CARDS_PER_PAGE;
        for (int i = start; i < Math.min(start + CARDS_PER_PAGE, letters.size()); i++) {
            var letter = letters.get(i);
            ui.append("#Cards", letter.read ? "EterniaMod/MailEnvelopeRead.ui" : "EterniaMod/MailEnvelope.ui");
            String card = "#Cards[" + (i - start) + "]";
            ui.set(card + " #EnvTitle.TextSpans", Message.raw(letter.subject));
            String state = letter.read ? (letter.attachments.isEmpty() || !letter.claimed ? "Read" : "Items taken") : "New";
            ui.set(card + " #EnvFrom.TextSpans", Message.raw("From " + letter.from + " · " + state + " · " + WHEN.format(letter.at)));
            boolean parcel = !letter.attachments.isEmpty() && !letter.claimed;
            ui.set(card + " #EnvTag.Visible", parcel);
            ui.set(card + " #EnvTagText.TextSpans", Message.raw("Parcel · " + letter.attachments.size()));
            ui.set(card + " #EnvSeal.Visible", !letter.read);
            ui.set(card + ".Disabled", busy);
            bind(events, card, "open:" + letter.id);
        }
        long unread = letters.stream().filter(l -> !l.read).count();
        String summary = letters.isEmpty() ? "Your mailbox is empty. Write to a friend to get things started."
            : letters.size() + " of 20 letters · " + unread + " new · page " + (cardPage + 1) + " of " + pages + ". Letters with items wait here until you take them.";
        ui.set("#LettersStatus.TextSpans", Message.raw(busy ? "Working…" : status.isEmpty() ? summary : status));
        button(ui, events, "#Earlier", "Earlier", "cards_prev", cardPage > 0);
        button(ui, events, "#More", "More", "cards_next", cardPage + 1 < pages);
    }

    private void drawLetter(UICommandBuilder ui, UIEventBuilder events) {
        var letter = current();
        if (letter == null) { view = View.LETTERS; drawLetters(ui, events); ui.set("#LettersView.Visible", true); ui.set("#LetterView.Visible", false); return; }
        ui.set("#LetterTitle.TextSpans", Message.raw(letter.subject));
        ui.set("#LetterFrom.TextSpans", Message.raw("From " + letter.from + " · " + WHEN.format(letter.at)));
        var lines = MailText.lines(letter.body);
        for (int i = 0; i < lines.size(); i++) {
            ui.append("#LetterLines", "EterniaMod/MailLine.ui");
            String line = "#LetterLines[" + i + "]";
            var pieces = lines.get(i);
            if (pieces.isEmpty()) { ui.append(line, "EterniaMod/MailWord.ui"); ui.set(line + "[0].TextSpans", Message.raw(" ")); continue; }
            for (int k = 0; k < pieces.size(); k++) {
                var piece = pieces.get(k);
                if (piece.isEmote()) {
                    ui.append(line, "EterniaMod/MailPic.ui");
                    ui.setObject(line + "[" + k + "].Background", new PatchStyle(Value.of("EterniaMod/Chat/Emote_" + piece.emote() + ".png")));
                } else {
                    ui.append(line, "EterniaMod/MailWord.ui");
                    ui.set(line + "[" + k + "].TextSpans", Message.raw(piece.text()));
                }
            }
        }
        boolean items = !letter.attachments.isEmpty();
        ui.set("#ParcelHeading.Visible", items); ui.set("#ParcelRow.Visible", items);
        ui.set("#ParcelHeading.TextSpans", Message.raw(letter.claimed ? "The parcel held" : "Inside the parcel"));
        for (int i = 0; i < letter.attachments.size(); i++) {
            var item = letter.attachments.get(i);
            ui.append("#Tiles", "EterniaMod/MailTile.ui");
            String tile = "#Tiles[" + i + "]";
            ui.set(tile + " #TileItem.ItemId", item.itemId);
            ui.set(tile + " #TileName.TextSpans", Message.raw(UiPresentation.itemName(item.itemId)));
            ui.set(tile + " #TileCount.TextSpans", Message.raw("x" + item.quantity));
        }
        button(ui, events, "#TakeAll", "Take all to my bag", "take", items && !letter.claimed);
        ui.set("#TakeNote.TextSpans", Message.raw(letter.claimed ? "Already taken. Anything that did not fit is in Deliveries at the market." : "Make sure your bag has room."));
        button(ui, events, "#Reply", "Reply", "reply", letter.senderId != null && !letter.senderId.equals(actor));
        button(ui, events, "#Archive", "Archive", "archive", true);
        ui.set("#Archive.Disabled", busy || items && !letter.claimed);
        ui.set("#LetterStatus.TextSpans", Message.raw(busy ? "Working…" : items && !letter.claimed && status.isEmpty() ? "Take the items before archiving this letter." : status));
        button(ui, events, "#BackLetters", "Back to letters", "letters", true);
    }

    private void drawWrite(Ref<EntityStore> ref, Store<EntityStore> store, UICommandBuilder ui, UIEventBuilder events) {
        ui.set("#ToFriendsRow.Visible", !snapshot.friends.isEmpty());
        for (int i = 0; i < snapshot.friends.size(); i++) {
            ui.append("#ToFriends", "EterniaMod/MailFriend.ui");
            ui.set("#ToFriends[" + i + "].TextSpans", Message.raw(snapshot.friends.get(i)));
            bind(events, "#ToFriends[" + i + "]", "to:" + snapshot.friends.get(i));
        }
        for (int i = 0; i < ChatEmotes.NAMES.size(); i++) {
            String name = ChatEmotes.NAMES.get(i);
            ui.append("#Emotes", "EterniaMod/MailEmote.ui");
            ui.setObject("#Emotes[" + i + "] #EmoPic.Background", new PatchStyle(Value.of("EterniaMod/Chat/Emote_" + name + ".png")));
            ui.set("#Emotes[" + i + "].TooltipText", ":" + name + ":");
            bind(events, "#Emotes[" + i + "]", "emote:" + name);
        }
        ui.set("#EmoteHint.TextSpans", Message.raw("Click an emote to add it at the end, or type its name like in chat, such as :smile:. " + body.length() + " / 2000"));
        // Parcel slots.
        for (int i = 0; i < MAX_STACKS; i++) {
            if (i < picks.size()) {
                var pick = picks.get(i);
                ui.append("#Parcel", i == selected ? "EterniaMod/MailSlotSelected.ui" : "EterniaMod/MailSlot.ui");
                String slot = "#Parcel[" + i + "]";
                ui.set(slot + " #SlotItem.ItemId", pick.itemId);
                ui.set(slot + " #SlotCount.TextSpans", Message.raw(String.valueOf(pick.quantity)));
                ui.set(slot + ".TooltipText", UiPresentation.itemName(pick.itemId));
                bind(events, slot, "pick:" + i);
            } else ui.append("#Parcel", "EterniaMod/MailSlotEmpty.ui");
        }
        ui.set("#ParcelCount.TextSpans", Message.raw(picks.size() + " of " + MAX_STACKS + " slots"));
        var pick = selected >= 0 && selected < picks.size() ? picks.get(selected) : null;
        ui.set("#Pick.Visible", pick != null);
        if (pick != null) {
            ui.set("#PickItem.ItemId", pick.itemId);
            ui.set("#PickName.TextSpans", Message.raw(UiPresentation.itemName(pick.itemId) + (pick.kind == Kind.STORE ? " (market storage)" : "")));
            ui.set("#PickCount.TextSpans", Message.raw(String.valueOf(pick.quantity)));
        }
        button(ui, events, "#Less", "-", "less", pick != null && pick.kind == Kind.BAG);
        button(ui, events, "#MoreQty", "+", "more", pick != null && pick.kind == Kind.BAG);
        ui.set("#Less.Disabled", busy || pick == null || pick.quantity <= 1);
        ui.set("#MoreQty.Disabled", busy || pick == null || pick.quantity >= pick.max);
        button(ui, events, "#TakeOut", "Take out", "takeout", pick != null);
        ui.set("#ParcelHint.TextSpans", Message.raw("Click an item below to put it in the parcel. Click it here to change how many or take it out. Nothing leaves your bag until you send."));
        // Items to choose from.
        button(ui, events, "#FromBag", "Your bag", "from_bag", true);
        button(ui, events, "#FromStorage", "Market storage", "from_storage", true);
        ui.set("#FromBag.Disabled", busy || !fromStorage);
        ui.set("#FromStorage.Disabled", busy || fromStorage);
        int shown = 0, total;
        if (!fromStorage) {
            var bag = NativeItemEscrow.bag(ref, store).stream().filter(s -> picks.stream().noneMatch(p -> p.kind == Kind.BAG && p.section == s.section() && p.slot == s.slot())).toList();
            total = bag.size(); bagPage = Math.min(bagPage, Math.max(0, (total - 1) / BAG_PER_PAGE));
            for (int i = bagPage * BAG_PER_PAGE; i < Math.min(total, (bagPage + 1) * BAG_PER_PAGE); i++, shown++) {
                var stack = bag.get(i); String slot = "#Bag[" + shown + "]";
                if (stack.sendable()) {
                    ui.append("#Bag", "EterniaMod/MailSlot.ui");
                    ui.set(slot + " #SlotItem.ItemId", stack.itemId());
                    ui.set(slot + " #SlotCount.TextSpans", Message.raw(stack.quantity() > 1 ? String.valueOf(stack.quantity()) : ""));
                    ui.set(slot + ".TooltipText", UiPresentation.itemName(stack.itemId()));
                    bind(events, slot, "bag:" + stack.section() + ":" + stack.slot());
                } else {
                    ui.append("#Bag", "EterniaMod/MailSlotDim.ui");
                    ui.set(slot + " #DimItem.ItemId", stack.itemId());
                }
            }
            ui.set("#BagNote.TextSpans", Message.raw(total == 0 ? "Your bag is empty." : "Darker items belong to your account and cannot be sent."));
        } else {
            var storage = snapshot.storage.stream().filter(e -> picks.stream().noneMatch(p -> p.kind == Kind.STORE && p.escrowId.equals(e.id()))).toList();
            total = storage.size(); bagPage = Math.min(bagPage, Math.max(0, (total - 1) / BAG_PER_PAGE));
            for (int i = bagPage * BAG_PER_PAGE; i < Math.min(total, (bagPage + 1) * BAG_PER_PAGE); i++, shown++) {
                var escrow = storage.get(i); String slot = "#Bag[" + shown + "]";
                ui.append("#Bag", "EterniaMod/MailSlot.ui");
                ui.set(slot + " #SlotItem.ItemId", escrow.item().itemId());
                ui.set(slot + " #SlotCount.TextSpans", Message.raw(escrow.remaining() > 1 ? String.valueOf(escrow.remaining()) : ""));
                ui.set(slot + ".TooltipText", UiPresentation.itemName(escrow.item().itemId()));
                bind(events, slot, "store:" + escrow.id());
            }
            ui.set("#BagNote.TextSpans", Message.raw(total == 0 ? "Nothing is waiting in your market storage." : "Stacks in market storage are sent whole."));
        }
        button(ui, events, "#BagPrev", "<", "bag_prev", total > BAG_PER_PAGE);
        button(ui, events, "#BagNext", ">", "bag_next", total > BAG_PER_PAGE);
        ui.set("#BagPrev.Disabled", busy || bagPage == 0);
        ui.set("#BagNext.Disabled", busy || (bagPage + 1) * BAG_PER_PAGE >= total);
        ui.set("#WriteStatus.TextSpans", Message.raw(busy ? "Sending…" : status));
        button(ui, events, "#CancelWrite", "Cancel", "letters", true);
        button(ui, events, "#Send", picks.isEmpty() ? "Send letter" : "Send letter and parcel", "send", true);
    }

    private void button(UICommandBuilder ui, UIEventBuilder events, String selector, String label, String route, boolean visible) {
        ui.set(selector + ".TextSpans", Message.raw(label)); ui.set(selector + ".Visible", visible); ui.set(selector + ".Disabled", busy);
        if (visible) bind(events, selector, route);
    }

    private void bind(UIEventBuilder events, String selector, String route) {
        String key = generation + ":" + routes.size(); routes.put(key, route);
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, new EventData().append("Action", key)
            .append("@To", "#To.Value").append("@Subject", "#Subject.Value").append("@Body", "#Body.Value"), false);
    }

    // ------------------------------------------------------------------------------------------------ events

    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (isDismissed() || registration.closed || busy) return;
        String route = routes.get(data.action); if (route == null) return;
        if (view == View.WRITE) { if (data.to != null) to = cut(data.to, 32); if (data.subject != null) subject = cut(data.subject, 100); if (data.body != null) body = cut(data.body, 2000); }
        if (route.startsWith("open:")) { openLetter(route.substring(5)); return; }
        if (route.startsWith("to:")) { to = cut(route.substring(3), 32); redraw(); return; }
        if (route.startsWith("emote:")) { body = cut(ChatEmotes.add(body, route.substring(6)), 2000); redraw(); return; }
        if (route.startsWith("bag:")) { pickFromBag(ref, store, route.substring(4)); return; }
        if (route.startsWith("store:")) { pickFromStorage(route.substring(6)); return; }
        if (route.startsWith("pick:")) { selected = Integer.parseInt(route.substring(5)); redraw(); return; }
        switch (route) {
            case "write" -> { view = View.WRITE; status = ""; redraw(); }
            case "letters" -> { view = View.LETTERS; status = ""; redraw(); }
            case "cards_prev" -> { cardPage = Math.max(0, cardPage - 1); redraw(); }
            case "cards_next" -> { cardPage++; redraw(); }
            case "reply" -> { var l = current(); if (l == null) return; view = View.WRITE; to = l.from; subject = cut(l.subject.startsWith("Re: ") ? l.subject : "Re: " + l.subject, 100); status = ""; redraw(); }
            case "archive" -> archive();
            case "take" -> take(ref, store);
            case "less" -> adjust(-1);
            case "more" -> adjust(1);
            case "takeout" -> { if (selected >= 0 && selected < picks.size()) picks.remove(selected); selected = -1; redraw(); }
            case "from_bag" -> { fromStorage = false; bagPage = 0; redraw(); }
            case "from_storage" -> { fromStorage = true; bagPage = 0; redraw(); }
            case "bag_prev" -> { bagPage = Math.max(0, bagPage - 1); redraw(); }
            case "bag_next" -> { bagPage++; redraw(); }
            case "send" -> send(ref, store);
            default -> { }
        }
    }

    private void openLetter(String id) {
        view = View.LETTER; focus = id; status = "";
        var letter = current();
        if (letter != null && !letter.read) work(() -> { services.mail().markOpened(actor, id); return load(); }, loaded -> snapshot = loaded, "");
        else redraw();
    }

    private void pickFromBag(Ref<EntityStore> ref, Store<EntityStore> store, String where) {
        if (picks.size() >= MAX_STACKS) { status = "A parcel holds up to five stacks."; redraw(); return; }
        String[] parts = where.split(":");
        var section = NativeItemEscrow.Section.valueOf(parts[0]); short slot = Short.parseShort(parts[1]);
        var stack = NativeItemEscrow.bag(ref, store).stream().filter(s -> s.section() == section && s.slot() == slot).findFirst().orElse(null);
        if (stack == null || !stack.sendable()) { status = "That item cannot be sent."; redraw(); return; }
        picks.add(new Pick(Kind.BAG, section, slot, "", stack.itemId(), stack.quantity(), stack.quantity()));
        selected = picks.size() - 1; status = ""; redraw();
    }

    private void pickFromStorage(String escrowId) {
        if (picks.size() >= MAX_STACKS) { status = "A parcel holds up to five stacks."; redraw(); return; }
        var escrow = snapshot.storage.stream().filter(e -> e.id().equals(escrowId)).findFirst().orElse(null);
        if (escrow == null) return;
        int quantity = (int) Math.min(Integer.MAX_VALUE, escrow.remaining());
        picks.add(new Pick(Kind.STORE, null, (short) -1, escrowId, escrow.item().itemId(), quantity, quantity));
        selected = picks.size() - 1; status = ""; redraw();
    }

    private void adjust(int delta) {
        if (selected < 0 || selected >= picks.size()) return;
        var pick = picks.get(selected);
        if (pick.kind != Kind.BAG) return;
        picks.set(selected, pick.withQuantity(Math.max(1, Math.min(pick.max, pick.quantity + delta))));
        redraw();
    }

    private void archive() {
        var letter = current(); if (letter == null) return;
        work(() -> { services.mail().archive(actor, letter.id); return load(); }, loaded -> { snapshot = loaded; view = View.LETTERS; }, "Letter archived.");
    }

    /** Takes every item of the open letter into the bag, if it all fits. */
    private void take(Ref<EntityStore> ref, Store<EntityStore> store) {
        var letter = current(); if (letter == null || letter.claimed || letter.attachments.isEmpty()) return;
        var world = store.getExternalData().getWorld();
        busy = true; long version = ++generation; rebuild();
        CompletableFuture.supplyAsync(() -> letter.attachments.stream().map(a -> services.escrow().find(a.escrowId).orElseThrow()).toList(), registration.executor)
            .whenComplete((escrows, error) -> world.execute(() -> {
                if (stale(ref, version)) return;
                if (error != null) { finish(error, null); return; }
                var stacks = escrows.stream().map(NativeItemEscrow::escrowStack).toList();
                if (!NativeItemEscrow.hasRoom(ref, store, stacks)) { busy = false; status = "Your bag is too full. Make room for " + stacks.size() + " stacks and try again."; redraw(); return; }
                long next = ++generation;
                CompletableFuture.supplyAsync(() -> services.mail().claimDeliveries(actor, letter.id), registration.executor)
                    .whenComplete((deliveries, claimError) -> world.execute(() -> {
                        if (stale(ref, next)) return;
                        if (claimError != null) { finish(claimError, null); return; }
                        int left = 0;
                        for (var delivery : deliveries) {
                            try { if (!EterniaModPlugin.get().getItemEscrow().claim(ref, store, playerRef, delivery.id())) left++; }
                            catch (RuntimeException failure) { left++; }
                        }
                        String notice = left == 0 ? "Everything is in your bag." : left + " stack(s) did not fit and are waiting in Deliveries at the market.";
                        work(this::load, loaded -> snapshot = loaded, notice);
                    }));
            }));
    }

    /** Sends the letter: checks first, then moves the picked bag items, then posts the letter. */
    private void send(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (to.isBlank() || subject.isBlank() || body.isBlank()) { status = "Fill in who it is for, a subject and a message."; redraw(); return; }
        var world = store.getExternalData().getWorld();
        var parcel = List.copyOf(picks);
        String name = to.trim(), title = subject.trim(), text = body;
        busy = true; long version = ++generation; rebuild();
        CompletableFuture.supplyAsync(() -> {
            UUID recipient = services.accounts().findByName(name).orElseThrow(() -> new IllegalArgumentException("No player named " + name + " has joined Eternia yet.")).id();
            services.mail().checkSend(actor, recipient, title, text, parcel.size());
            return recipient;
        }, registration.executor).whenComplete((recipient, error) -> world.execute(() -> {
            if (stale(ref, version)) return;
            if (error != null) { finish(error, null); return; }
            var ids = new ArrayList<String>();
            for (var pick : parcel) {
                if (pick.kind == Kind.STORE) { ids.add(pick.escrowId); continue; }
                try { ids.add(EterniaModPlugin.get().getItemEscrow().depositSlot(ref, store, playerRef, pick.section, pick.slot, pick.itemId, pick.quantity)); }
                catch (RuntimeException failure) {
                    boolean moved = ids.size() > parcel.stream().filter(p -> p.kind == Kind.STORE).count();
                    busy = false; picks.clear(); selected = -1;
                    status = message(failure) + (moved ? " Items already taken from your bag are safe in market storage." : "");
                    work(this::load, loaded -> snapshot = loaded, status); return;
                }
            }
            long next = ++generation; String receipt = "native:mail:" + UUID.randomUUID();
            CompletableFuture.runAsync(() -> services.mail().send(actor, recipient, title, text, ids, receipt), registration.executor)
                .whenComplete((ignored, sendError) -> world.execute(() -> {
                    if (stale(ref, next)) return;
                    picks.clear(); selected = -1;
                    if (sendError != null) {
                        boolean fromBag = parcel.stream().anyMatch(p -> p.kind == Kind.BAG);
                        busy = false; status = "The letter could not be sent: " + message(sendError) + (fromBag ? " The items from your bag are safe in market storage." : "");
                        work(this::load, loaded -> snapshot = loaded, status); return;
                    }
                    to = subject = body = ""; view = View.LETTERS;
                    work(this::load, loaded -> snapshot = loaded, "Your letter to " + name + " is on its way.");
                }));
        }));
    }

    // ------------------------------------------------------------------------------------------------ loading

    private void refresh(String notice) { work(this::load, loaded -> snapshot = loaded, notice); }

    private <T> void work(Supplier<T> task, Consumer<T> apply, String notice) {
        busy = true; long version = ++generation;
        var ref = playerRef.getReference(); if (ref == null || !ref.isValid()) return;
        var world = ref.getStore().getExternalData().getWorld();
        CompletableFuture.supplyAsync(task, registration.executor).whenComplete((result, error) -> world.execute(() -> {
            if (stale(ref, version)) return;
            if (error != null) { finish(error, null); return; }
            apply.accept(result); busy = false; status = notice; redraw();
        }));
    }

    private boolean stale(Ref<EntityStore> ref, long version) { return isDismissed() || registration.closed || !ref.isValid() || version != generation; }

    private void finish(Throwable error, String fallback) { busy = false; status = fallback != null ? fallback : message(error); redraw(); }

    private static String message(Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        return cause instanceof DomainException || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
            ? cause.getMessage() : "The mailbox could not finish that. Try again in a moment.";
    }

    private void redraw() { generation++; rebuild(); }

    private Snapshot load() {
        // Live presence first, then every database read in one transaction instead of a round trip per call.
        var online = FriendNotices.onlineWorlds().keySet();
        return services.batch(() -> loadRecords(online));
    }

    private Snapshot loadRecords(Set<UUID> online) {
        var inbox = services.mail().inbox(actor);
        services.accounts().findAll(inbox.stream().map(MailService.Message::sender).toList());
        var letters = new ArrayList<Letter>();
        for (var mail : inbox.stream().sorted(Comparator.comparing(MailService.Message::sentAt).reversed()).toList()) {
            var items = new ArrayList<Attach>();
            for (String id : mail.attachments()) services.escrow().find(id).ifPresent(e -> items.add(new Attach(id, e.item().itemId(), e.item().quantity())));
            String from = services.accounts().find(mail.sender()).map(AccountService.Account::displayName).orElse("Eternia");
            letters.add(new Letter(mail.id(), mail.sender(), from, mail.subject(), mail.body(), mail.read(), mail.claimed(), mail.sentAt(), List.copyOf(items)));
        }
        var mine = services.friends().friends(actor);
        var names = services.accounts().findAll(mine.stream().map(FriendService.Friend::id).toList());
        var friendNames = mine.stream().sorted(Comparator.comparing((FriendService.Friend f) -> !online.contains(f.id())))
            .map(f -> names.containsKey(f.id()) ? names.get(f.id()).displayName() : null).filter(Objects::nonNull).limit(8).toList();
        return new Snapshot(List.copyOf(letters), services.escrow().available(Owner.player(actor)), friendNames);
    }

    private Letter current() { return snapshot.letters.stream().filter(l -> l.id.equals(focus)).findFirst().orElse(null); }
    private static String cut(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }

    private record Snapshot(List<Letter> letters, List<EscrowService.Escrow> storage, List<String> friends) {}
    private record Letter(String id, UUID senderId, String from, String subject, String body, boolean read, boolean claimed, Instant at, List<Attach> attachments) {}
    private record Attach(String escrowId, String itemId, long quantity) {}
    private record Pick(Kind kind, NativeItemEscrow.Section section, short slot, String escrowId, String itemId, int max, int quantity) {
        Pick withQuantity(int value) { return new Pick(kind, section, slot, escrowId, itemId, max, value); }
    }

    public static final class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add()
            .append(new KeyedCodec<>("@To", Codec.STRING), (d, v) -> d.to = v, d -> d.to).add()
            .append(new KeyedCodec<>("@Subject", Codec.STRING), (d, v) -> d.subject = v, d -> d.subject).add()
            .append(new KeyedCodec<>("@Body", Codec.STRING), (d, v) -> d.body = v, d -> d.body).add().build();
        public String action, to, subject, body;
    }
}
