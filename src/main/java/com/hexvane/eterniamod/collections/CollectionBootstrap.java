package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.EterniaServices;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

public final class CollectionBootstrap {
    private static volatile CollectionRuntime active;
    private CollectionBootstrap(){}
    public static void refresh(){var runtime=active;if(runtime!=null)runtime.refresh();}
    public static String status(java.util.UUID player){var runtime=active;return runtime==null?"Collection renderer is unavailable.":runtime.status(player);}
    public static CollectionRuntime register(EterniaModPlugin plugin,EterniaServices services) {
        CosmeticPet.register(plugin.getEntityStoreRegistry().registerComponent(CosmeticPet.class,()->new CosmeticPet(null,null,"")));
        var runtime=new CollectionRuntime(plugin,services);
        active=runtime;
        plugin.getEntityStoreRegistry().registerSystem(new TickingSystem<EntityStore>() {
            @Override public void tick(float dt,int systemIndex,@Nonnull Store<EntityStore> store){runtime.tick(dt,store);}
        });
        plugin.getEventRegistry().registerGlobal(RemovedPlayerFromWorldEvent.class,runtime::departed);
        plugin.getEventRegistry().registerGlobal(RemoveWorldEvent.class,event->{if(!event.isCancelled())runtime.removedWorld(event.getWorld());});
        plugin.getEventRegistry().registerAsyncGlobal(EventPriority.LAST,PlayerChatEvent.class,future->future.thenApply(event->{
            if(event.getFormatter()==PlayerChatEvent.DEFAULT_FORMATTER)event.setFormatter((player,text)->Message.translation("server.chat.playerMessage").param("username",runtime.displayName(player)).param("message",text));
            return event;
        }));
        return runtime;
    }
}
