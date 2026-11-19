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
    /** Another owner of land (claims in adventure worlds) that may refuse a bucket on a block. */
    public interface BlockGuard { boolean refuses(World world,com.hypixel.hytale.component.Ref<EntityStore> actor,CommandBuffer<EntityStore> commands,int x,int y,int z); }
    private static volatile BlockGuard extra;
    public static void guard(BlockGuard guard) { extra=guard; }
    private static boolean refusedByLand(World world,InteractionContext context,CommandBuffer<EntityStore> commands,int x,int y,int z) {
        var g=extra;
        if(g==null)return false;
        try{return g.refuses(world,context.getOwningEntity(),commands,x,y,z);}catch(RuntimeException failure){return true;}
    }
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
            if (blocked(world)||target!=null&&refusedByLand(world,context,commands,target.x,target.y,target.z)) { context.getState().state=InteractionState.Failed;return; }
            super.interactWithBlock(world,commands,type,context,item,target,cooldown);
        }
    }
    public static final class RefillContainer extends RefillContainerInteraction {
        public static final BuilderCodec<RefillContainer> CODEC=BuilderCodec.builder(RefillContainer.class,RefillContainer::new,RefillContainerInteraction.CODEC).build();
        public RefillContainer() {}
        @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown) {
            var world=context.getCommandBuffer().getExternalData().getWorld();
            var at=context.getTargetBlock();
            if (blocked(world)||at!=null&&refusedByLand(world,context,context.getCommandBuffer(),at.x,at.y,at.z)) {
                context.getState().state=InteractionState.Failed;return;
            }
            super.firstRun(type,context,cooldown);
        }
    }
}
