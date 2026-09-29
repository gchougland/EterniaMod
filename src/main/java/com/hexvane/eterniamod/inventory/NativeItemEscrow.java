package com.hexvane.eterniamod.inventory;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.playerdata.DiskPlayerStorageProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/** World-thread native item custody. Database acknowledgements follow the forced inventory+receipt save. */
public final class NativeItemEscrow {
    private final EterniaModPlugin plugin;
    private final Map<UUID,Object> recoveredSessions=new ConcurrentHashMap<>();
    public NativeItemEscrow(EterniaModPlugin plugin){this.plugin=plugin;}
    public String depositHand(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        requireReady(ref,store,player);
        var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());
        if(hotbar==null)throw new IllegalStateException("Hotbar unavailable");
        short slot=hotbar.getActiveSlot();ItemStack item=hotbar.getInventory().getItemStack(slot);
        if(ItemStack.isEmpty(item))throw new IllegalArgumentException("Hold the item stack you want to deposit.");
        if(item.getItemId().startsWith("Eternia_")||item.getMetadata()!=null&&item.getMetadata().containsKey("EterniaBound"))throw new IllegalArgumentException("Account-bound housing and collection tokens cannot be traded.");
        BsonDocument encoded=ItemStack.CODEC.encode(item,new ExtraInfo());
        if(!encoded.equals(ItemStack.CODEC.encode(ItemStack.CODEC.decode(encoded,new ExtraInfo()),new ExtraInfo())))throw new IllegalStateException("This item cannot be transferred without losing metadata.");
        String source=UUID.randomUUID().toString();
        var escrow=plugin.getServices().escrow().prepareDeposit(Owner.player(player.getUuid()),new EscrowService.Item(item.getItemId(),item.getQuantity(),Base64.getEncoder().encodeToString(encoded.toJson().getBytes(StandardCharsets.UTF_8)),true),"native:"+source);
        var receipts=receipts(ref,store);String operation="deposit."+escrow.id();receipts.beginMutation(operation);
        final boolean removed;
        try {removed=hotbar.getInventory().removeItemStackFromSlot(slot,item,item.getQuantity(),true,true).succeeded();}
        catch(RuntimeException failure){quarantine(player,operation,failure);disconnect(player);throw failure;}
        if(!removed){receipts.mutationResolved();throw new IllegalStateException("The held item changed. Nothing was deposited.");}
        try {
            receipts.values().put(operation,new BsonString(source));receipts.mutationResolved();
            save(ref,store,player);
            plugin.getServices().escrow().acknowledgeNativeRemoval(escrow.id(),source);
            receipts.values().remove(operation);return escrow.id();
        }catch(RuntimeException failure){disconnect(player);throw failure;}
    }
    public boolean claim(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player,String deliveryId) {
        requireReady(ref,store,player);Owner owner=Owner.player(player.getUuid());
        var delivery=plugin.getServices().escrow().deliveries(owner).stream().filter(d->d.id().equals(deliveryId)).findFirst().orElseThrow();
        if(delivery.state().equals("DELIVERED"))return true;
        if(!delivery.state().equals("READY"))throw new IllegalStateException("This delivery is being recovered; it cannot be issued twice.");
        var value=BsonDocument.parse(new String(Base64.getDecoder().decode(delivery.item().metadataBase64()),StandardCharsets.UTF_8));
        ItemStack item=ItemStack.CODEC.decode(value,new ExtraInfo()).withQuantity(Math.toIntExact(delivery.item().quantity()));
        if(!item.getItemId().equals(delivery.item().itemId()))throw new IllegalStateException("Delivery metadata does not match its item.");
        var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.EVERYTHING);
        if(inventory==null||!inventory.canAddItemStack(item))return false;
        var receipts=receipts(ref,store);UUID attempt=UUID.randomUUID();plugin.getServices().escrow().beginNativeDelivery(owner,deliveryId,attempt);
        String operation="delivery."+deliveryId;receipts.beginMutation(operation);
        try {
            final boolean inserted;
            try {var result=inventory.addItemStack(item,true,false,true);inserted=result.succeeded();
                if(inserted&&!ItemStack.isEmpty(result.getRemainder()))throw new IllegalStateException("Native inventory returned a partial all-or-nothing insertion");}
            catch(RuntimeException failure){quarantine(player,operation,failure);throw failure;}
            if(!inserted){receipts.mutationResolved();plugin.getServices().escrow().resolveNativeDelivery(deliveryId,attempt,EscrowService.NativeOutcome.NOT_INSERTED);return false;}
            receipts.values().put(operation,new BsonString(attempt.toString()));receipts.mutationResolved();
            save(ref,store,player);
            plugin.getServices().escrow().resolveNativeDelivery(deliveryId,attempt,EscrowService.NativeOutcome.INSERTED);
            receipts.values().remove(operation);return true;
        }catch(RuntimeException failure){disconnect(player);throw failure;}
    }
    /** Only call during a NEW player login, after the native player document has been loaded. */
    public void recoverLogin(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        store.assertThread();recoverReceipts(receipts(ref,store),player);
    }
    /** PlayerConnectEvent runs after storage load and before world addition or player item actions. */
    public void recoverLoadedInventory(Holder<EntityStore> holder,PlayerRef player) {
        var markers=holder.getComponent(InventoryReceipts.type());
        if(markers==null){markers=new InventoryReceipts();holder.addComponent(InventoryReceipts.type(),markers);}
        recoverReceipts(markers,player);
    }
    private void recoverReceipts(InventoryReceipts markers,PlayerRef player) {
        if(recoveredSessions.get(player.getUuid())==player.getPacketHandler())return;
        try {
            requireStorage();Owner owner=Owner.player(player.getUuid());var escrow=plugin.getServices().escrow();
            escrow.verifyNativeInventoryCheckpoint(owner,markers.revision());
            if(markers.values().containsKey("uncertain"))throw new IllegalStateException("An interrupted native mutation needs administrator review");
            // Raise the durable watermark before making any loaded removal marker spendable.
            escrow.acknowledgeNativeInventoryCheckpoint(owner,markers.revision());
            for(var entry:new ArrayList<>(markers.values().entrySet()))if(entry.getKey().startsWith("deposit.")) {
                String id=entry.getKey().substring(8);var deposit=escrow.find(id).orElseThrow();
                if(!deposit.owner().equals(owner))throw new IllegalStateException("Deposit receipt belongs to another inventory");
                escrow.acknowledgeNativeRemoval(id,entry.getValue().asString().getValue());markers.values().remove(entry.getKey());
            }
            for(var delivery:escrow.deliveries(owner)) {
                var marker=markers.values().getString("delivery."+delivery.id(),null);
                if(delivery.state().equals("READY")&&marker!=null)throw new IllegalStateException("A ready delivery has an unexpected native insertion receipt");
                if(delivery.state().equals("HANDOFF")) {
                    if(marker!=null&&!marker.getValue().equals(delivery.attemptId().toString()))throw new IllegalStateException("Native delivery receipt does not match its pending attempt");
                    escrow.resolveNativeDelivery(delivery.id(),delivery.attemptId(),marker==null?EscrowService.NativeOutcome.NOT_INSERTED:EscrowService.NativeOutcome.INSERTED);
                }
                if(delivery.state().equals("DELIVERED")||delivery.state().equals("HANDOFF"))markers.values().remove("delivery."+delivery.id());
            }
            recoveredSessions.put(player.getUuid(),player.getPacketHandler());
        }catch(RuntimeException failure){disconnect(player);throw failure;}
    }
    public void forgetSession(PlayerRef player){recoveredSessions.remove(player.getUuid(),player.getPacketHandler());}
    private void requireReady(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        store.assertThread();requireStorage();
        if(!ref.isValid()||recoveredSessions.get(player.getUuid())!=player.getPacketHandler())throw new IllegalStateException("Your inventory is still being recovered. Reconnect if this persists.");
        var actual=store.getComponent(ref,PlayerRef.getComponentType());
        if(actual==null||!actual.getUuid().equals(player.getUuid()))throw new IllegalStateException("Inventory player identity changed");
        if(!store.getExternalData().getWorld().getWorldConfig().isSavingPlayers())throw new IllegalStateException("Item transfers require a world that saves player inventory");
        if(Universe.get().isSavingLocked())throw new IllegalStateException("A server backup is in progress. Try the transfer again shortly.");
        plugin.getServices().escrow().verifyNativeInventoryCheckpoint(Owner.player(player.getUuid()),receipts(ref,store).revision());
    }
    private static void requireStorage(){if(Universe.get().getPlayerStorage().getClass()!=DiskPlayerStorageProvider.DiskPlayerStorage.class)throw new IllegalStateException("This player storage provider has not been verified for native item custody");}
    private void quarantine(PlayerRef player,String operation,RuntimeException failure) {
        try{plugin.getServices().escrow().quarantineNativeInventory(Owner.player(player.getUuid()),operation);}
        catch(RuntimeException guardFailure){failure.addSuppressed(guardFailure);}
    }
    private static InventoryReceipts receipts(Ref<EntityStore> ref,Store<EntityStore> store) {
        var value=store.getComponent(ref,InventoryReceipts.type());if(value==null){value=new InventoryReceipts();store.putComponent(ref,InventoryReceipts.type(),value);}return value;
    }
    private void save(Ref<EntityStore> ref,Store<EntityStore> store,PlayerRef player) {
        try {
            NativeSaveBarrier.await(store.getComponent(ref,Player.getComponentType()).saveConfig(store.getExternalData().getWorld(),NativeInventorySnapshot.forImmediateSave(ref,store),true),Duration.ofSeconds(15));
        } catch(RuntimeException failure) {
            plugin.getLogger().atSevere().withCause(failure).log("Native inventory checkpoint could not be saved for %s",player.getUuid());
            throw failure;
        }
        plugin.getServices().escrow().acknowledgeNativeInventoryCheckpoint(Owner.player(player.getUuid()),receipts(ref,store).revision());
    }
    private void disconnect(PlayerRef player) {
        forgetSession(player);
        // The queued storage write keeps its own barrier until real completion. Reconnect loads wait for it.
        player.getPacketHandler().disconnect(com.hypixel.hytale.server.core.Message.raw("Eternia could not confirm inventory custody. Reconnect to recover the transfer; contact an administrator if this repeats."));
    }
}
