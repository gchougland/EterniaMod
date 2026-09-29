package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import java.util.concurrent.*;

public final class CustomizationBootstrap {
    private static volatile CustomizationService service;
    private static ScheduledExecutorService timer;
    private CustomizationBootstrap(){}
    public static CustomizationService register(EterniaModPlugin plugin){
        plugin.getCodecRegistry(Interaction.CODEC).register("EterniaHousingTool",EterniaHousingTool.class,EterniaHousingTool.CODEC);
        HousingEnvironmentalProtection.register(plugin);
        HousingWorldPolicy.register(plugin);
        service=new CustomizationService(plugin);
        timer=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"Eternia-customization-preview");thread.setDaemon(true);return thread;});
        return service;
    }
    public static CustomizationService service(){return service;}
    static ScheduledFuture<?> previewTimer(Runnable task){return timer.scheduleWithFixedDelay(task,0,600,TimeUnit.MILLISECONDS);}
    static void stop(){if(timer!=null)timer.shutdownNow();service=null;HousingWorldPolicy.stop();}
}
