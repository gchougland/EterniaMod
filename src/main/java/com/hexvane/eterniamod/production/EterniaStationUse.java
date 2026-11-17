package com.hexvane.eterniamod.production;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/** Using one of a station's three blocks: WORK the ore face, COLLECT from the stockpile, OPEN the board. */
public final class EterniaStationUse extends SimpleInstantInteraction {
    public static final BuilderCodec<EterniaStationUse> CODEC = BuilderCodec.builder(EterniaStationUse.class, EterniaStationUse::new, SimpleInstantInteraction.CODEC)
        .append(new KeyedCodec<>("Route", Codec.STRING), (use, value) -> use.route = value, use -> use.route).add().build();
    private String route = "OPEN";

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldown) {
        var commands = context.getCommandBuffer();
        var target = context.getTargetBlock();
        if (commands == null || target == null || type != InteractionType.Use) return;
        var ref = context.getEntity();
        var player = commands.getComponent(ref, PlayerRef.getComponentType());
        if (player == null) return;
        var store = commands.getStore();
        var world = store.getExternalData().getWorld();
        String action = route;
        int x = target.x, y = target.y, z = target.z;
        world.execute(() -> {
            ProductionRuntime runtime = ProductionRuntime.get();
            if (runtime == null || !ref.isValid()) return;
            var station = runtime.at(world, x, y, z);
            if (station == null) return;
            ProductionActions.use(runtime, world, ref, store, player, station, action);
        });
    }
}
