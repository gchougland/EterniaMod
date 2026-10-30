package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hexvane.eterniamod.housing.HousingInfrastructure;
import com.hexvane.eterniamod.runtime.WorldDisplayNames;
import com.hexvane.eterniamod.ui.EterniaInteractiveCustomUIPage;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;

final class WorldDisplayNamePage extends EterniaInteractiveCustomUIPage<WorldDisplayNamePage.Data> {
    private final EterniaModPlugin plugin;
    private final World world;
    private final String original;
    private final HousingInfrastructure.Snapshot snapshot;
    private String value;
    private String description;
    private String status = "Leave a field empty to use its default.";

    private WorldDisplayNamePage(EterniaModPlugin plugin, PlayerRef player, World world) {
        super(player, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.plugin = plugin;
        this.world = world;
        original = world.getWorldConfig().getDisplayName();
        value = original == null ? "" : original;
        snapshot = plugin.getInfrastructure().snapshot();
        var plan = snapshot.worlds().get(world.getName());
        description = plan == null ? "" : plan.description();
    }

    static void open(EterniaModPlugin plugin, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef player) {
        SetupAccess.require(player.getUuid());
        var component = store.getComponent(ref, Player.getComponentType());
        if (component != null) component.getPageManager().openCustomPage(ref, store,
            new WorldDisplayNamePage(plugin, player, store.getExternalData().getWorld()));
    }

    @Override public void build(Ref<EntityStore> ref, UICommandBuilder c, UIEventBuilder e, Store<EntityStore> store) {
        c.append("EterniaMod/WorldDisplayName.ui");
        c.set("#World.Text", "Current world: " + WorldDisplayNames.name(world.getName()));
        c.set("#DisplayName.Value", value);
        c.set("#Description.Value", description);
        c.set("#Status.Text", status);
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Save", new EventData()
            .append("Action", "Save").append("@Name", "#DisplayName.Value").append("@Description", "#Description.Value"), false);
        e.addEventBinding(CustomUIEventBindingType.Activating, "#Back", new EventData().append("Action", "Back"), false);
    }

    @Override public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        if (data.action == null || isDismissed()) return;
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid() || isDismissed()) return;
            var component = store.getComponent(ref, Player.getComponentType());
            if (component == null || component.getPageManager().getCustomPage() != this) return;
            try {
                SetupAccess.require(playerRef.getUuid());
                if (store.getExternalData().getWorld() != world) throw new IllegalArgumentException("Open World setup again in this world.");
                if (data.action.equals("Back")) { InfrastructureSetup.open(plugin, ref, store, playerRef); return; }
                if (!data.action.equals("Save")) return;
                if (!Objects.equals(original, world.getWorldConfig().getDisplayName()))
                    throw new IllegalArgumentException("The name changed. Go back and open World details again.");
                if (data.name != null) value = data.name;
                if (data.description != null) description = data.description;
                WorldDisplayNames.normalize(data.name);
                if (data.description == null) throw new IllegalArgumentException("Enter a description or leave it empty.");
                String nextDescription = HousingInfrastructure.WorldPlan.normalizeDescription(data.description);
                var plan = snapshot.worlds().get(world.getName());
                if (plan == null && !nextDescription.isEmpty()) throw new IllegalArgumentException("Choose this world's role in World setup first.");
                if (plan != null && !plan.description().equals(nextDescription)) {
                    try { plugin.getInfrastructure().saveWorld(snapshot.revision(), world.getName(), plan.withDescription(nextDescription)); }
                    catch (java.io.IOException failure) { throw new IllegalArgumentException("World details could not be saved. Go back and open World details again.", failure); }
                }
                WorldDisplayNames.set(world.getWorldConfig(), data.name);
                playerRef.sendMessage(Message.raw("World details saved for " + WorldDisplayNames.name(world.getName()) + "."));
                InfrastructureSetup.open(plugin, ref, store, playerRef);
            } catch (IllegalArgumentException | DomainException error) {
                status = error.getMessage();
                rebuild();
            }
        });
    }

    public static final class Data {
        public String action, name, description;
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), (d,v) -> d.action=v, d -> d.action).add()
            .append(new KeyedCodec<>("@Name", Codec.STRING), (d,v) -> d.name=v, d -> d.name).add()
            .append(new KeyedCodec<>("@Description", Codec.STRING), (d,v) -> d.description=v, d -> d.description).add().build();
    }
}
