package com.hexvane.eterniamod.housing;

import com.hexvane.eterniamod.customization.EterniaHousingTool;
import com.hexvane.eterniamod.world.ChunkSectionBlockUtil;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.*;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Classifies native behavior, not an item-name substring; unknown actions retain storage protection. */
final class HousingUseCapabilities {
    static final String PORTAL_SERVICE="housing.portal.service";
    static Set<String> required(Store<EntityStore> store,UseBlockEvent.Pre event) {
        try {
            var block=event.getBlockType();var p=event.getTargetBlock();
            var holder=ChunkSectionBlockUtil.blockEntityHolderAt(store.getExternalData().getWorld(),p.x,p.y,p.z);
            boolean container=holder!=null&&holder.getComponent(ItemContainerBlock.getComponentType())!=null;
            var prototype=block.getBlockEntity();container|=prototype!=null&&prototype.getComponent(ItemContainerBlock.getComponentType())!=null;
            var root=RootInteraction.getAssetMap().getAsset(block.getInteractions().get(event.getInteractionType()));
            if(root==null)return Set.of("housing.container.open");
            var collector=new Behaviors();InteractionManager.walkChain(collector,event.getInteractionType(),event.getContext(),root);
            return required(container,block.getBench() instanceof com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.ProcessingBench,
                "Eternia_Management_Block".equals(block.getId()),"Eternia_World_Portal".equals(block.getId()),block.isDoor(),block.getBeds()!=null,block.getBench()!=null,collector.types);
        }catch(RuntimeException invalid){return Set.of("housing.container.open");}
    }
    static Set<String> required(boolean container,boolean processing,boolean ledger,boolean door,boolean bed,boolean bench,Set<String> behaviors) {
        return required(container,processing,ledger,false,door,bed,bench,behaviors);
    }
    static Set<String> required(boolean container,boolean processing,boolean ledger,boolean portal,boolean door,boolean bed,boolean bench,Set<String> behaviors) {
        if(processing||behaviors.contains("OpenProcessingBenchInteraction"))return Set.of("housing.bench.use","housing.container.open");
        if(container||behaviors.stream().anyMatch(name->Set.of("OpenContainerInteraction","OpenItemStackContainerInteraction","OpenTreasureContainerInteraction").contains(name)))return Set.of("housing.container.open");
        if(ledger&&behaviors.equals(Set.of(EterniaHousingTool.class.getSimpleName())))return Set.of("housing.visit");
        if(portal&&behaviors.equals(Set.of(EterniaHousingTool.class.getSimpleName())))return Set.of(PORTAL_SERVICE);
        if(door&&behaviors.contains("DoorInteraction"))return Set.of("housing.door.use");
        if(bed&&behaviors.contains("BedInteraction"))return Set.of("housing.bed.use");
        if(bench&&behaviors.contains("OpenBenchPageInteraction"))return Set.of("housing.bench.use");
        return Set.of("housing.container.open");
    }
    private static final class Behaviors implements Collector {
        final Set<String> types=new HashSet<>();int count,depth;
        public void start(){}
        public void into(InteractionContext context,Interaction interaction){if(++depth>32)throw new IllegalStateException("Interaction tree too deep");}
        public boolean collect(CollectorTag tag,InteractionContext context,Interaction interaction){if(++count>128)throw new IllegalStateException("Interaction tree too large");types.add(interaction.getClass().getSimpleName());return false;}
        public void outof(){depth--;}
        public void finished(){}
    }
    private HousingUseCapabilities(){}
}
