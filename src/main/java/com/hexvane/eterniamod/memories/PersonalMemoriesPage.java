package com.hexvane.eterniamod.memories;

import com.hexvane.eterniamod.domain.MemoryService;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesGameplayConfig;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.memories.npc.NPCMemory;
import com.hypixel.hytale.builtin.adventure.memories.page.MemoriesPage;
import com.hypixel.hytale.builtin.adventure.memories.page.MemoriesUnlockedPage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.player.UpdateMemoriesCount;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** The vanilla memories page and its layouts, filled from the viewing player's own memories. */
final class PersonalMemoriesPage extends InteractiveCustomUIPage<MemoriesPage.PageEventData> {
    private final PersonalMemories memories;private final BlockPosition block;private final Vector3d effects;
    @Nullable private String category;@Nullable private String selected;

    PersonalMemoriesPage(PlayerRef player,BlockPosition block,PersonalMemories memories) {
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,MemoriesPage.PageEventData.CODEC);
        this.memories=memories;this.block=block;effects=new Vector3d(block.x,block.y,block.z);
    }
    private Map<String,MemoryService.Entry> recorded(){var own=memories.recorded(playerRef.getUuid());return own==null?Map.of():own;}
    private static List<Memory> sorted(String category) {
        var all=MemoriesPlugin.get().getAllMemories().get(category);
        var list=new ArrayList<Memory>(all==null?List.of():all);list.sort(Comparator.comparing(Memory::getTitle));return list;
    }

    @Override public void build(@Nonnull Ref<EntityStore> ref,@Nonnull UICommandBuilder ui,@Nonnull UIEventBuilder events,@Nonnull Store<EntityStore> store) {
        var own=recorded();
        if(category==null)buildCategories(ref,ui,events,store,own);else buildCategory(ui,events,own);
    }
    private void buildCategories(Ref<EntityStore> ref,UICommandBuilder ui,UIEventBuilder events,Store<EntityStore> store,Map<String,MemoryService.Entry> own) {
        ui.append("Pages/Memories/MemoriesCategoryPanel.ui");
        var all=MemoriesPlugin.get().getAllMemories();
        int total=0;for(var set:all.values())total+=set.size();
        float progress=total==0?0:Math.min(1f,own.size()/(float)total);
        ui.set("#MemoriesProgressBar.Value",progress);ui.set("#MemoriesProgressBarTexture.Value",progress);
        ui.set("#TotalCollected.TextSpans",Message.translation("server.memories.general.totalCollected").param("current",own.size()).param("max",total));
        events.addEventBinding(CustomUIEventBindingType.Activating,"#MemoriesInfoButton",new EventData().append(MemoriesPage.PageEventData.KEY_ACTION,MemoriesPage.PageAction.MemoriesInfo));
        var carried=store.getComponent(ref,PlayerMemories.getComponentType());
        int i=0;
        for(var entry:all.entrySet()) {
            String name=entry.getKey();var inCategory=entry.getValue();String row="#IconList["+i++ +"] ";
            int count=0;for(var memory:inCategory)if(own.containsKey(memory.getId()))count++;
            ui.append("#IconList","Pages/Memories/MemoriesCategory.ui");
            ui.set(row+"#Button.Text",Message.translation("server.memories.categories."+name+".title"));
            ui.set(row+"#CurrentMemoryCountNotComplete.Text",String.valueOf(count));ui.set(row+"#CurrentMemoryCountComplete.Text",String.valueOf(count));
            ui.set(row+"#TotalMemoryCountNotComplete.Text",String.valueOf(inCategory.size()));ui.set(row+"#TotalMemoryCountComplete.Text",String.valueOf(inCategory.size()));
            if(count==inCategory.size()) {
                ui.set(row+"#CategoryIcon.AssetPath","UI/Custom/Pages/Memories/categories/"+name+"Complete.png");
                ui.set(row+"#CompleteCategoryBackground.Visible",true);ui.set(row+"#CompleteCategoryCounter.Visible",true);
            } else {
                ui.set(row+"#CategoryIcon.AssetPath","UI/Custom/Pages/Memories/categories/"+name+".png");
                ui.set(row+"#NotCompleteCategoryCounter.Visible",true);
            }
            if(carried!=null)for(var memory:inCategory)if(carried.getRecordedMemories().contains(memory)){ui.set(row+"#NewMemoryIndicator.Visible",true);break;}
            events.addEventBinding(CustomUIEventBindingType.Activating,row+"#Button",new EventData().append(MemoriesPage.PageEventData.KEY_ACTION,MemoriesPage.PageAction.ViewCategory).append(MemoriesPage.PageEventData.KEY_CATEGORY,name));
        }
        boolean canRecord=carried!=null&&carried.hasMemories();
        ui.set("#RecordButton.Visible",true);ui.set("#RecordButton.Disabled",!canRecord);
        chestMarkers(ui,store.getExternalData().getWorld().getGameplayConfig(),total,own.size());
        if(canRecord)ui.set("#RecordButton.Text",Message.translation("server.memories.general.recordNum").param("count",carried.getRecordedMemories().size()));
        events.addEventBinding(CustomUIEventBindingType.Activating,"#RecordButton",new EventData().append(MemoriesPage.PageEventData.KEY_ACTION,MemoriesPage.PageAction.Record));
    }
    private void buildCategory(UICommandBuilder ui,UIEventBuilder events,Map<String,MemoryService.Entry> own) {
        ui.append("Pages/Memories/MemoriesPanel.ui");
        var list=sorted(category);
        long count=list.stream().filter(memory->own.containsKey(memory.getId())).count();
        ui.set("#CategoryTitle.Text",Message.translation("server.memories.categories."+category+".title"));
        ui.set("#CategoryCount.Text",count+"/"+list.size());
        for(int i=0;i<list.size();i++) {
            var memory=list.get(i);String row="#IconList["+i+"] ";
            ui.append("#IconList","Pages/Memories/Memory.ui");
            if(own.containsKey(memory.getId())) {
                String button=row+(memory.getId().equals(selected)?"#ButtonSelected":"#ButtonNotSelected");
                ui.set(button+".Visible",true);ui.set(button+".TooltipText",memory.getTooltipText());ui.setNull(button+".Background");
                String icon=memory.getIconPath();if(icon!=null&&!icon.isEmpty())ui.set(row+"#Icon.AssetPath",icon);
                events.addEventBinding(CustomUIEventBindingType.Activating,button,new EventData().append(MemoriesPage.PageEventData.KEY_ACTION,MemoriesPage.PageAction.SelectMemory).append(MemoriesPage.PageEventData.KEY_MEMORY_ID,memory.getId()));
            } else {
                ui.set(row+"#EmptyBackground.Visible",true);ui.set(row+"#EmptyBackground.TooltipText",memory.getUndiscoveredTooltipText());
            }
        }
        if(selected!=null&&own.containsKey(selected))for(var memory:list)if(memory.getId().equals(selected))details(ui,memory,own.get(selected));
        events.addEventBinding(CustomUIEventBindingType.Activating,"#BackButton",new EventData().append(MemoriesPage.PageEventData.KEY_ACTION,MemoriesPage.PageAction.Back));
    }
    private static void chestMarkers(UICommandBuilder ui,GameplayConfig gameplay,int total,int own) {
        var config=MemoriesGameplayConfig.get(gameplay);if(config==null||total==0)return;
        int[] amounts=config.getMemoriesAmountPerLevel();if(amounts==null||amounts.length<=1)return;
        // Matches the vanilla progress bar's width of 1018 less its padding of 18.
        final int area=1000;
        for(int i=0;i<amounts.length;i++) {
            String marker="#ChestMarkers["+i+"]";var anchor=new Anchor();
            ui.append("#ChestMarkers","Pages/Memories/ChestMarker.ui");
            anchor.setLeft(Value.of(amounts[i]*area/total));ui.setObject(marker+".Anchor",anchor);
            var rewards=Message.translation("server.memories.general.chestActive.level"+(i+1)+".rewards");
            if(own>=amounts[i]) {
                ui.set(marker+" #Arrow.Visible",true);ui.set(marker+" #ChestActive.Visible",true);
                ui.set(marker+" #ChestActive.TooltipTextSpans",Message.translation("server.memories.general.chestActive.tooltipText").param("count",amounts[i]).insert("\n").insert(rewards));
            } else {
                ui.set(marker+" #ChestDisabled.Visible",true);
                ui.set(marker+" #ChestDisabled.TooltipTextSpans",Message.translation("server.memories.general.chestLocked.tooltipText").param("count",amounts[i]).insert("\n").insert(rewards));
            }
        }
    }
    private static void details(UICommandBuilder ui,Memory memory,MemoryService.Entry entry) {
        ui.set("#MemoryName.Text",Message.translation(memory.getTitle()));
        ui.set("#MemoryTimeLocation.Text","");
        if(memory instanceof NPCMemory) {
            Message location=entry.location().isEmpty()||entry.location().equals(NPCMemory.ZONE_NAME_UNKNOWN)?Message.raw(NPCMemory.ZONE_NAME_UNKNOWN):Message.translation(entry.location());
            ui.set("#MemoryTimeLocation.TextSpans",Message.translation("server.memories.general.foundIn").param("location",location).param("dateValue",Instant.ofEpochMilli(entry.capturedAt()).atZone(ZoneOffset.UTC).toString()));
        }
        var description=memory.getDescription();
        if(description!=null)ui.set("#MemoryDescription.TextSpans",description);
        ui.set("#MemoryDescription.Visible",description!=null);
        String icon=memory.getIconPath();
        if(icon!=null&&!icon.isEmpty())ui.set("#MemoryIcon.AssetPath",icon);else ui.setNull("#MemoryIcon.AssetPath");
    }

    @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref,@Nonnull Store<EntityStore> store,@Nonnull MemoriesPage.PageEventData data) {
        switch(data.action) {
            case Record -> record(ref,store);
            case ViewCategory -> {category=data.category;selected=null;rebuild();}
            case Back -> {category=null;selected=null;rebuild();}
            case MemoriesInfo -> {
                var player=store.getComponent(ref,Player.getComponentType());
                if(player!=null)player.getPageManager().openCustomPage(ref,store,new Unlocked(playerRef,block,memories));
            }
            case SelectMemory -> {
                if(data.memoryId==null||category==null||data.memoryId.equals(selected))return;
                var own=recorded();var list=sorted(category);var ui=new UICommandBuilder();Memory choice=null;
                for(int i=0;i<list.size();i++) {
                    var memory=list.get(i);
                    if(memory.getId().equals(selected))selection(ui,i,memory,false);
                    if(memory.getId().equals(data.memoryId)&&own.containsKey(memory.getId())){choice=memory;selection(ui,i,memory,true);}
                }
                if(choice==null){sendUpdate();return;}
                details(ui,choice,own.get(choice.getId()));selected=choice.getId();sendUpdate(ui);
            }
        }
    }
    private void record(Ref<EntityStore> ref,Store<EntityStore> store) {
        var carried=store.getComponent(ref,PlayerMemories.getComponentType());
        if(carried==null||!carried.hasMemories()){rebuild();return;}
        try{memories.recordCarried(playerRef.getUuid(),carried);}
        catch(RuntimeException e){MemoriesPlugin.get().getLogger().atWarning().withCause(e).log("Memories could not be recorded for %s",playerRef.getUuid());rebuild();return;}
        playerRef.getPacketHandler().writeNoCache(new UpdateMemoriesCount(0));
        var config=MemoriesGameplayConfig.get(store.getExternalData().getWorld().getGameplayConfig());
        if(config!=null) {
            var nearby=SpatialResource.<EntityStore>getThreadLocalReferenceList();
            store.getResource(EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure().collect(effects,ParticleUtil.DEFAULT_PARTICLE_DISTANCE,nearby);
            ParticleUtil.spawnParticleEffect(config.getMemoriesRecordParticles(),effects,nearby,store);
            String sound=config.getMemoriesRestoreSoundEventId();
            if(sound!=null){int index=SoundEvent.getAssetMap().getIndex(sound);if(index!=SoundEvent.EMPTY_ID)SoundUtil.playSoundEvent3d(index,SoundCategory.SFX,effects,store);}
        }
        close();
    }
    private static void selection(UICommandBuilder ui,int index,Memory memory,boolean chosen) {
        String row="#IconList["+index+"] ";String on=row+(chosen?"#ButtonSelected":"#ButtonNotSelected");
        ui.set(row+(chosen?"#ButtonNotSelected":"#ButtonSelected")+".Visible",false);
        ui.set(on+".Visible",true);ui.setNull(on+".Background");ui.set(on+".TooltipText",memory.getTooltipText());
    }

    /** The vanilla first-use page, except that its button leads to this page. */
    static final class Unlocked extends MemoriesUnlockedPage {
        private final BlockPosition block;private final PersonalMemories memories;
        Unlocked(PlayerRef player,BlockPosition block,PersonalMemories memories){super(player,block);this.block=block;this.memories=memories;}
        @Override public void handleDataEvent(@Nonnull Ref<EntityStore> ref,@Nonnull Store<EntityStore> store,@Nonnull MemoriesUnlockedPage.PageEventData data) {
            var player=store.getComponent(ref,Player.getComponentType());
            if(player!=null&&data.action==MemoriesUnlockedPage.PageAction.DiscoverMemories)player.getPageManager().openCustomPage(ref,store,new PersonalMemoriesPage(playerRef,block,memories));
        }
    }
}
