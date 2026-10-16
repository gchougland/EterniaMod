package com.hexvane.eterniamod.domain;

import java.util.*;

/** Shared native target families used by the activity adapter and quest filters. */
public final class QuestTargets {
    private QuestTargets(){}
    public static boolean matches(Set<String> selectors,String id){return selectors.isEmpty()||selectors.stream().anyMatch(s->matches(s,id));}
    public static boolean matches(String selector,String id){
        if(selector.equals(id))return true;
        String target=id.toLowerCase(Locale.ROOT).replaceFirst("^\\*+","");
        if(selector.startsWith("ore:")){String base="ore_"+selector.substring(4);return target.equals(base)||target.startsWith(base+"_");}
        if(selector.startsWith("crop:")){String base="plant_crop_"+selector.substring(5)+"_";return target.startsWith(base)||selector.equals("crop:wheat")&&target.startsWith("eternia_trial_wheat");}
        if(selector.startsWith("npc:")){String base=selector.substring(4).toLowerCase(Locale.ROOT);return target.equals(base)||target.startsWith(base+"_");}
        return switch(selector){
            case "group:trorks" -> target.startsWith("trork_");
            case "group:undead" -> target.equals("skeleton")||target.startsWith("skeleton_")||target.equals("ghoul")||target.startsWith("zombie");
            case "group:enemies" -> matches("group:undead",id)||target.startsWith("trork_")||target.startsWith("goblin_")||target.startsWith("outlander_");
            case "group:ores" -> target.startsWith("ore_");
            case "ore:iron" -> target.startsWith("ore_iron_");
            case "ore:copper" -> target.startsWith("ore_copper_");
            case "crop:wheat" -> target.startsWith("plant_crop_wheat_")||target.startsWith("eternia_trial_wheat");
            case "crop:corn" -> target.startsWith("plant_crop_corn_");
            case "group:crops" -> target.startsWith("plant_crop_")||target.startsWith("eternia_trial_wheat");
            default -> false;
        };
    }
    public static String distinctTarget(SeasonService.ActivityKind kind,String id){
        if(kind!=SeasonService.ActivityKind.HARVEST)return id;
        String normalized=id.toLowerCase(Locale.ROOT).replaceFirst("^\\*+","");
        if(normalized.startsWith("eternia_trial_wheat"))return "plant_crop_wheat_block";
        int block=normalized.indexOf("_block");return block<0?normalized:normalized.substring(0,block+6);
    }
    public static Long defaultXp(SeasonService.ActivityKind kind,String target){return switch(kind){
        case KILL -> matches("group:enemies",target)?100L:null;
        case MINE -> matches("group:ores",target)?40L:null;
        case HARVEST -> matches("group:crops",target)?30L:null;
        default -> null;
    };}
}
