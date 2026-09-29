package com.hexvane.eterniamod.activities;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.farming.FarmingStageData;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

/** Append after a mature BlockState with a finite duration. Native farming cannot
 * advance into this guard, so it retains the mature entity and harvest generation. */
public final class EterniaAwaitHarvestStage extends FarmingStageData {
    public static final BuilderCodec<EterniaAwaitHarvestStage> CODEC=BuilderCodec.builder(EterniaAwaitHarvestStage.class,EterniaAwaitHarvestStage::new,FarmingStageData.BASE_CODEC).build();
    public EterniaAwaitHarvestStage(){}
    @Override public boolean canApply(ComponentAccessor<ChunkStore> accessor,Ref<ChunkStore> section,Ref<ChunkStore> block,int x,int y,int z){return false;}
}
