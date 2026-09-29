package com.hexvane.eterniamod.inventory;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.*;

/** Saved in the SAME player document as the inventory mutation it proves. */
public final class InventoryReceipts implements Component<EntityStore> {
    public static final BuilderCodec<InventoryReceipts> CODEC=BuilderCodec.builder(InventoryReceipts.class,InventoryReceipts::new)
        .append(new KeyedCodec<>("CustodyRevision",Codec.LONG),(r,v)->r.custodyRevision=v,r->r.custodyRevision).add()
        .append(new KeyedCodec<>("Receipts",Codec.BSON_DOCUMENT),(r,v)->r.receipts=v==null?new BsonDocument():v,r->r.receipts.clone()).add().build();
    private static ComponentType<EntityStore,InventoryReceipts> type;
    private BsonDocument receipts=new BsonDocument();
    private long custodyRevision;
    public static void register(ComponentRegistryProxy<EntityStore> registry){type=registry.registerComponent(InventoryReceipts.class,"EterniaInventoryReceipts",CODEC);}
    public static ComponentType<EntityStore,InventoryReceipts> type(){return type;}
    public BsonDocument values(){return receipts;}
    public long revision(){return custodyRevision;}
    public void beginMutation(String operation){custodyRevision=Math.incrementExact(custodyRevision);receipts.put("uncertain",new BsonString(operation));}
    public void mutationResolved(){receipts.remove("uncertain");}
    @Override public InventoryReceipts clone(){var r=new InventoryReceipts();r.receipts=receipts.clone();r.custodyRevision=custodyRevision;return r;}
}
