package com.hexvane.eterniamod.inventory;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.*;
import org.bson.BsonString;

/** Real native inventory + receipt disk round-trip in the isolated smoke universe. */
public final class NativeInventorySmoke {
    private static ComponentType<EntityStore,LiveOnly> type;
    public static final class LiveOnly implements Component<EntityStore>{public LiveOnly clone(){throw new UnsupportedOperationException("Live component cannot be cloned");}}
    public static void register(EterniaModPlugin plugin){type=plugin.getEntityStoreRegistry().registerComponent(LiveOnly.class,LiveOnly::new);}
    public static void run(World world) throws Exception {
        // Player still inherits the legacy Entity clone path, while modern Player
        // is registered directly as a component. Capture the actual failure too.
        try {new com.hypixel.hytale.server.core.entity.entities.Player().clone();}
        catch(RuntimeException failure){EterniaModPlugin.get().getLogger().atInfo().log("ETERNIA_NATIVE_PLAYER_CLONE_REPRODUCED: %s",failure.toString());}
        UUID id=UUID.randomUUID();var done=new CompletableFuture<Void>();
        world.execute(()->{
            try {
                var store=world.getEntityStore().getStore();var holder=EntityStore.REGISTRY.newHolder();
                var inventory=new InventoryComponent.Storage((short)8);inventory.getInventory().addItemStack(new ItemStack("Soil_Grass",3));
                var receipts=new InventoryReceipts();receipts.beginMutation("delivery.fixture");receipts.values().put("delivery.fixture",new BsonString("confirmed"));receipts.mutationResolved();
                holder.addComponent(InventoryComponent.Storage.getComponentType(),inventory);holder.addComponent(InventoryReceipts.type(),receipts);holder.addComponent(type,new LiveOnly());
                var ref=store.addEntity(holder,AddReason.SPAWN);
                boolean blocked=false;try{store.copyEntity(ref);}catch(UnsupportedOperationException expected){blocked=true;}
                if(!blocked)throw new IllegalStateException("Fixture must reproduce the whole-entity clone failure");
                var write=Universe.get().getPlayerStorage().save(id,NativeInventorySnapshot.forImmediateSave(ref,store),true);
                // Disk serialization must already have detached the document.
                inventory.getInventory().clear();receipts.values().clear();
                store.removeEntity(ref,RemoveReason.REMOVE);
                write.thenCompose(v->Universe.get().getPlayerStorage().load(id)).whenComplete((loaded,error)->{
                    if(error!=null){done.completeExceptionally(error);return;}
                    try{var saved=loaded.getComponent(InventoryComponent.Storage.getComponentType());var proof=loaded.getComponent(InventoryReceipts.type());
                        if(saved.getInventory().getItemStack((short)0).getQuantity()!=3||proof.revision()!=1||!proof.values().containsKey("delivery.fixture"))throw new IllegalStateException("Inventory and custody proof must survive in the same immutable disk snapshot");
                        done.complete(null);
                    }catch(Throwable failure){done.completeExceptionally(failure);}
                });
            }catch(Throwable failure){done.completeExceptionally(failure);}
        });
        done.get(20,TimeUnit.SECONDS);
    }
}
