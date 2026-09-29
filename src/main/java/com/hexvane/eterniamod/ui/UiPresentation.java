package com.hexvane.eterniamod.ui;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.SeasonService;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import java.util.*;

/** Human labels and conservative typography budgets shared by the Citadel interfaces. */
public final class UiPresentation {
    private UiPresentation(){}
    public static String friendlyId(String id){
        if(id==null||id.isBlank())return "Unknown item";
        String value=id.substring(Math.max(id.lastIndexOf(':'),id.lastIndexOf('/'))+1).replaceFirst("^Eternia_","");
        value=value.replaceAll("([a-z])([A-Z])","$1 $2").replace('_',' ').replace('-',' ').replace('.',' ').trim();
        if(value.isBlank())return "Unknown item";
        var out=new StringBuilder();for(String word:value.split("\\s+")){if(!out.isEmpty())out.append(' ');out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));}return out.toString();
    }
    public static String itemName(String id){
        try{var item=Item.getAssetMap().getAsset(id);if(item!=null&&item.getTranslationProperties()!=null){String key=item.getTranslationProperties().getName();String text=I18nModule.get().getMessage("en-US",key);if(text!=null&&!text.isBlank()&&!text.equals(key))return text;}}catch(RuntimeException ignored){}
        return friendlyId(id);
    }
    public static String contentName(String id){
        var plugin=EterniaModPlugin.get();String key=id==null?"":id.substring(id.lastIndexOf('/')+1);
        if(plugin!=null){var building=plugin.getBuildingCatalog()==null?null:plugin.getBuildingCatalog().get(key);if(building!=null&&building.getDisplayName()!=null)return building.getDisplayName();var prop=plugin.getPropCatalog()==null?null:plugin.getPropCatalog().get(key);if(prop!=null&&prop.getDisplayName()!=null)return prop.getDisplayName();}
        return friendlyId(id);
    }
    /** Approximate native 16px Latin UI glyph advances with margin; never shrink type to fit an action. */
    public static int textWidth(String text){double width=0;for(char c:text.toCharArray())width+=Character.isWhitespace(c)?4.5:"ilI.,'!:;|".indexOf(c)>=0?4.5:"MW@%&".indexOf(c)>=0?13:Character.isUpperCase(c)?10:8.5;return (int)Math.ceil(width);}
    public static int buttonWidth(String text){return Math.max(168,Math.min(320,textWidth(text)+32));}
    public static int wrappedHeight(String text,int width,int lineHeight){int chars=Math.max(12,width/9);return Arrays.stream(text.split("\\R",-1)).mapToInt(line->Math.max(1,(line.length()+chars-1)/chars)).sum()*lineHeight;}
    public static String questTitle(SeasonService.Quest quest){return switch(quest.activity()){case KILL->"On patrol";case MINE->"A miner's work";case HARVEST->"A fruitful harvest";case ACQUIRE->"Gathering expedition";case MINIGAME->"A friendly challenge";case INTEGRATION->"An adventurer's task";};}
    public static String questGoal(SeasonService.Quest quest){
        String verb=switch(quest.activity()){case KILL->"Defeat";case MINE->"Mine";case HARVEST->"Harvest";case ACQUIRE->"Gather";case MINIGAME->"Complete";case INTEGRATION->"Finish";};
        String noun=switch(quest.activity()){case KILL->"creatures";case MINE,ACQUIRE->"resources";case HARVEST->"crops";case MINIGAME->"games";case INTEGRATION->"activities";};
        String goal=verb+" "+quest.required()+(quest.kind()==SeasonService.ObjectiveKind.DISTINCT?" different types of ":" ")+noun;
        if(quest.targets().size()==1)goal=verb+" "+quest.required()+(quest.kind()==SeasonService.ObjectiveKind.DISTINCT?" types of ":" ")+itemName(quest.targets().iterator().next());
        return goal;
    }
    public static String capability(String id){return switch(id){
        case "guild.view"->"View guild members";case "guild.chat"->"Use guild chat";case "board.read"->"Read notices";case "board.post"->"Write notices";case "board.moderate"->"Pin and remove notices";
        case "party.create"->"Create parties";case "member.invite"->"Invite members";case "member.remove"->"Remove members";case "member.role.assign"->"Assign member roles";
        case "housing.structure"->"Place and move houses";case "housing.palette"->"Change house materials";case "housing.addition"->"Build house additions";case "housing.prop.place"->"Place decorations";case "housing.prop.move"->"Move decorations";case "housing.prop.pack"->"Pack decorations";
        case "housing.block.build"->"Build on the estate";case "housing.block.break"->"Remove estate blocks";case "housing.road.manage"->"Build community roads";case "inventory.deposit"->"Donate building supplies";case "inventory.reserve_for_build"->"Use guild building supplies";case "inventory.withdraw"->"Withdraw guild supplies";
        case "shop.manage"->"Manage guild shop";case "mail.attachments.claim"->"Collect guild deliveries";case "treasury.deposit"->"Deposit guild coins";case "treasury.withdraw"->"Withdraw guild coins";
        case "housing.door.use"->"Open doors";case "housing.bed.use"->"Use beds";case "housing.bench.use"->"Use crafting benches";case "housing.container.open"->"Open storage";case "convenience.use"->"Use guild conveniences";case "housing.visit"->"Visit the estate";case "audit.view"->"Read guild activity history";default->friendlyId(id);};}
}
