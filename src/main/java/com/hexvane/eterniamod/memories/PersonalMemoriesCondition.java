package com.hexvane.eterniamod.memories;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.Int2ObjectMapCodec;
import com.hypixel.hytale.codec.validation.Validators;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.Collector;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.CollectorTag;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.StringTag;
import com.hypixel.hytale.server.core.modules.interaction.interaction.operation.Label;
import com.hypixel.hytale.server.core.modules.interaction.interaction.operation.OperationsBuilder;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Vanilla's memories level branch, using the acting player's level. Vanilla's teleporter limit is driven by this. */
public final class PersonalMemoriesCondition extends Interaction {
    public static final BuilderCodec<PersonalMemoriesCondition> CODEC=BuilderCodec.builder(PersonalMemoriesCondition.class,PersonalMemoriesCondition::new,ABSTRACT_CODEC)
        .appendInherited(new KeyedCodec<>("Next",new Int2ObjectMapCodec<>(Interaction.CHILD_ASSET_CODEC,Int2ObjectOpenHashMap::new)),(o,v)->o.next=v,o->o.next,(o,p)->o.next=p.next)
        .addValidator(Validators.nonNull()).add()
        .appendInherited(new KeyedCodec<>("Failed",Interaction.CHILD_ASSET_CODEC),(o,v)->o.failed=v,o->o.failed,(o,p)->o.failed=p.failed).add()
        .afterDecode(o->{
            o.labels.defaultReturnValue(-1);o.sortedKeys=o.next.keySet().toIntArray();Arrays.sort(o.sortedKeys);o.labels.clear();
            for(int i=0;i<o.sortedKeys.length;i++)o.labels.put(o.sortedKeys[i],i);
        }).build();
    private static final StringTag TAG_FAILED=StringTag.of("Failed");
    private Int2ObjectMap<String> next=Int2ObjectMaps.emptyMap();
    private transient int[] sortedKeys;
    private final Int2IntOpenHashMap labels=new Int2IntOpenHashMap();
    @Nullable private String failed;

    private void jump(int level,InteractionContext context) {
        int label=labels.get(level);
        context.getState().state=label==-1?InteractionState.Failed:InteractionState.Finished;
        context.jump(context.getLabel(label==-1?sortedKeys.length:label));
    }
    @Override protected void tick0(boolean firstRun,float time,@Nonnull InteractionType type,@Nonnull InteractionContext context,@Nonnull CooldownHandler cooldownHandler) {
        var buffer=context.getCommandBuffer();
        var player=buffer==null?null:buffer.getComponent(context.getEntity(),PlayerRef.getComponentType());
        var plugin=com.hexvane.eterniamod.EterniaModPlugin.get();
        int level=player==null||plugin==null?1:plugin.getMemories().level(player.getUuid(),buffer.getExternalData().getWorld().getGameplayConfig());
        context.getState().chainingIndex=level;
        jump(level,context);
    }
    @Override protected void simulateTick0(boolean firstRun,float time,@Nonnull InteractionType type,@Nonnull InteractionContext context,@Nonnull CooldownHandler cooldownHandler) {
        jump(context.getServerState().chainingIndex,context);
    }
    @Override public void compile(@Nonnull OperationsBuilder builder) {
        var end=builder.createUnresolvedLabel();var targets=new Label[next.size()+1];
        for(int i=0;i<targets.length;i++)targets[i]=builder.createUnresolvedLabel();
        builder.addOperation(this,targets);builder.jump(end);
        for(int i=0;i<sortedKeys.length;i++) {
            builder.resolveLabel(targets[i]);
            compileChild(next.get(sortedKeys[i]),builder);
            builder.jump(end);
        }
        builder.resolveLabel(targets[sortedKeys.length]);
        compileChild(failed,builder);
        builder.resolveLabel(end);
    }
    private static void compileChild(@Nullable String id,OperationsBuilder builder) {
        var child=id==null?null:Interaction.getAssetMap().getAsset(Interaction.getAssetMap().getIndex(id));
        if(child!=null)child.compile(builder);
    }
    @Nonnull @Override protected com.hypixel.hytale.protocol.Interaction generatePacket(){return new com.hypixel.hytale.protocol.MemoriesConditionInteraction();}
    @Override protected void configurePacket(@Nonnull com.hypixel.hytale.protocol.Interaction packet) {
        super.configurePacket(packet);
        var condition=(com.hypixel.hytale.protocol.MemoriesConditionInteraction)packet;
        condition.memoriesNext=new Int2IntOpenHashMap(next.size());
        for(var entry:next.int2ObjectEntrySet())condition.memoriesNext.put(entry.getIntKey(),Interaction.getAssetMap().getIndex(entry.getValue()));
        condition.failed=failed==null?AssetMapWithIndexes.NOT_FOUND:Interaction.getAssetMap().getIndex(failed);
    }
    @Override public boolean walk(@Nonnull Collector collector,@Nonnull InteractionContext context) {
        for(var entry:next.int2ObjectEntrySet())if(InteractionManager.walkInteraction(collector,context,new LevelTag(entry.getIntKey()),entry.getValue()))return true;
        return failed!=null&&InteractionManager.walkInteraction(collector,context,TAG_FAILED,failed);
    }
    @Override public boolean needsRemoteSync(){return false;}
    @Nonnull @Override public WaitForDataFrom getWaitForDataFrom(){return WaitForDataFrom.Server;}
    private record LevelTag(int level) implements CollectorTag {}
}
