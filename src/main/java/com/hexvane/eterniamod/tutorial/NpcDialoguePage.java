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
import java.util.List;
import java.util.function.BiConsumer;
import javax.annotation.Nonnull;

/** A Hub NPC speaking: one or more pages of their lines, then Continue opens what they look after. */
public final class NpcDialoguePage extends EterniaInteractiveCustomUIPage<NpcDialoguePage.Data> {
    private final String npc;
    private final List<String> pages;
    private final BiConsumer<Ref<EntityStore>, Store<EntityStore>> then;
    private int page;

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
        c.set("#Line.TextSpans", Message.translation(pages.get(page)).param("player", playerRef.getUsername()));
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
            if (page < pages.size() - 1) { page++; rebuild(); return; }
            then.accept(ref, store);
        });
    }

    public static final class Data {
        public String action;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d, v) -> d.action = v, d -> d.action).add().build();
    }
}
