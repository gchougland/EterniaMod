package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.PlaceFluidInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.RefillContainerInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;

/** Native bucket actions bypass block events. Keep their housing lock during admin building. */
public final class HousingInteractionGuard {
    private static volatile EterniaModPlugin active;
    private HousingInteractionGuard() {}
    static void register(EterniaModPlugin plugin) {
        if (Interaction.CODEC.getClassFor("PlaceFluid") != PlaceFluidInteraction.class
                || Interaction.CODEC.getClassFor("RefillContainer") != RefillContainerInteraction.class)
            throw new IllegalStateException("Another plugin replaced the native fluid interactions; Hub building cannot safely enable.");
        active=plugin;
        Interaction.CODEC.register("PlaceFluid",PlaceFluid.class,PlaceFluid.CODEC);
        Interaction.CODEC.register("RefillContainer",RefillContainer.class,RefillContainer.CODEC);
    }
    static void stop() {
        active=null;
        if (Interaction.CODEC.getClassFor("PlaceFluid")==PlaceFluid.class) {
            Interaction.CODEC.remove(PlaceFluid.class);
            Interaction.CODEC.register("PlaceFluid",PlaceFluidInteraction.class,PlaceFluidInteraction.CODEC);
        }
        if (Interaction.CODEC.getClassFor("RefillContainer")==RefillContainer.class) {
            Interaction.CODEC.remove(RefillContainer.class);
            Interaction.CODEC.register("RefillContainer",RefillContainerInteraction.class,RefillContainerInteraction.CODEC);
        }
    }
    static boolean blocked(World world) {
        var plugin=active;
        return plugin!=null&&plugin.getInfrastructure().isHousing(world.getName());
    }
    public static final class PlaceFluid extends PlaceFluidInteraction {
        public static final BuilderCodec<PlaceFluid> CODEC=BuilderCodec.builder(PlaceFluid.class,PlaceFluid::new,PlaceFluidInteraction.CODEC).build();
        public PlaceFluid() {}
        @Override protected void interactWithBlock(World world,CommandBuffer<EntityStore> commands,InteractionType type,
                InteractionContext context,ItemStack item,Vector3i target,CooldownHandler cooldown) {
            if (blocked(world)) { context.getState().state=InteractionState.Failed;return; }
            super.interactWithBlock(world,commands,type,context,item,target,cooldown);
        }
    }
    public static final class RefillContainer extends RefillContainerInteraction {
        public static final BuilderCodec<RefillContainer> CODEC=BuilderCodec.builder(RefillContainer.class,RefillContainer::new,RefillContainerInteraction.CODEC).build();
        public RefillContainer() {}
        @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown) {
            if (blocked(context.getCommandBuffer().getExternalData().getWorld())) {
                context.getState().state=InteractionState.Failed;return;
            }
            super.firstRun(type,context,cooldown);
        }
    }
}
