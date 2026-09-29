package com.hexvane.eterniamod.ui;

import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Native images are shipped in the asset pack; UI pages never fetch remote URLs. */
public final class ContentImages {
    private ContentImages() {}
    public static String path(String content,boolean screenshot) {
        if(content==null||!content.matches("eternia:[a-z_]+/[a-z0-9_-]+"))return "EterniaMod/Icons/collection.png";
        String relative=content.substring(8).replace("addition/","prop/");
        String base="EterniaMod/Catalog/"+relative+"/";
        if(screenshot&&exists(base+"screenshot.png"))return base+"screenshot.png";
        if(exists(base+"icon.png"))return base+"icon.png";
        String kind=relative.substring(0,relative.indexOf('/'));
        return "EterniaMod/Icons/"+switch(kind){case "house","prop","plot","palette","path","move"->"housing";case "season"->"season";case "convenience"->"worlds";default->"collection";}+".png";
    }
    private static boolean exists(String path){return ContentImages.class.getClassLoader().getResource("Common/UI/Custom/"+path)!=null;}
    public static void show(UICommandBuilder c,String selector,String id,boolean screenshot){c.set(selector+".Visible",id!=null&&!id.isBlank());if(id!=null&&!id.isBlank())c.setObject(selector+".Background",new PatchStyle(Value.of(path(id,screenshot))));}
}
