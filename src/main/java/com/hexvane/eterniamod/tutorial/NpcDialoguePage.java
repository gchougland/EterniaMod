package com.hexvane.eterniamod.tutorial;

import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;

/**
 * A Hub NPC speaking: one or more pages of their lines, typed out a few letters at a time, then Continue opens what
 * they look after. Continue while a line is still typing shows the whole line first.
 */
public final class NpcDialoguePage extends EterniaInteractiveCustomUIPage<NpcDialoguePage.Data> {
    private static final ScheduledExecutorService TYPEWRITER = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("eternia-dialogue").factory());
    private static final int LETTERS_PER_STEP = 2;
    private static final long STEP_MILLIS = 25;
    private final String npc;
    private final List<String> pages;
    private final BiConsumer<Ref<EntityStore>, Store<EntityStore>> then;
    private int page;
    /** The current page's line with the player's name in, or null when it could not be looked up (shown whole). */
    private String line;
    private int shown;
    private ScheduledFuture<?> typing;
    private final AtomicBoolean queued = new AtomicBoolean();

    private NpcDialoguePage(PlayerRef player, String npc, List<String> pages, BiConsumer<Ref<EntityStore>, Store<EntityStore>> then) {
        super(player, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Data.CODEC);
        this.npc = npc;
        this.pages = List.copyOf(pages);
        this.then = then;
    }

    /** Shows the pages, or runs {@code then} at once when there is nothing to say. World thread. */
    public static void open(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player, String npc, List<String> pages,
                            BiConsumer<Ref<EntityStore>, Store<EntityStore>> then) {
        Player component = store.getComponent(ref, Player.getComponentType());
        if (component == null) return;
        if (pages.isEmpty()) { then.accept(ref, store); return; }
        component.getPageManager().openCustomPage(ref, store, new NpcDialoguePage(player, npc, pages, then));
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder c, @Nonnull UIEventBuilder e, @Nonnull Store<EntityStore> store) {
        c.append("EterniaMod/NpcDialogue.ui");
        c.set("#Speaker.Text", HubDialogue.name(npc));
        c.set("#Profession.Text", HubDialogue.profession(npc));
        line = resolve(pages.get(page));
        shown = 0;
        if (line == null) c.set("#Line.TextSpans", Message.translation(pages.get(page)).param("player", playerRef.getUsername()));
        else { c.set("#Line.Text", ""); type(ref, store); }
        boolean last = page >= pages.size() - 1;
        c.set("#PageCount.Text", pages.size() > 1 ? (page + 1) + " / " + pages.size() : "");
        c.set("#Continue.TextSpans", Message.translation(HubDialogue.text(last ? "dialogue.continue" : "dialogue.next")));
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Continue", new EventData().append("Action", "Continue"), false);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Data data) {
        if (data.action == null || isDismissed()) return;
        store.getExternalData().getWorld().execute(() -> {
            if (isDismissed() || !ref.isValid()) return;
            if (line != null && shown < line.length()) { shown = line.length(); stopTyping(); show(); return; }
            stopTyping();
            if (page < pages.size() - 1) { page++; rebuild(); return; }
            then.accept(ref, store);
        });
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        stopTyping();
        super.onDismiss(ref, store);
    }

    /** The line in the player's language with their name filled in, or null when the server has no text for it. */
    private String resolve(String key) {
        var i18n = I18nModule.get();
        String text = i18n == null ? null : i18n.getMessage(playerRef.getLanguage(), key);
        return text == null ? null : text.replace("{player}", playerRef.getUsername());
    }

    private void type(Ref<EntityStore> ref, Store<EntityStore> store) {
        stopTyping();
        var world = store.getExternalData().getWorld();
        typing = TYPEWRITER.scheduleAtFixedRate(() -> {
            if (isDismissed()) { stopTyping(); return; }
            if (!queued.compareAndSet(false, true)) return;
            try {
                world.execute(() -> {
                    queued.set(false);
                    if (isDismissed() || !ref.isValid() || line == null) { stopTyping(); return; }
                    shown = Math.min(line.length(), shown + LETTERS_PER_STEP);
                    show();
                    if (shown >= line.length()) stopTyping();
                });
            } catch (RuntimeException closing) { stopTyping(); }
        }, STEP_MILLIS, STEP_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void show() {
        var c = new UICommandBuilder();
        c.set("#Line.Text", line.substring(0, shown));
        sendUpdate(c, null, false);
    }

    private void stopTyping() {
        var running = typing;
        typing = null;
        if (running != null) running.cancel(false);
    }

    public static final class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add().build();
    }
}
