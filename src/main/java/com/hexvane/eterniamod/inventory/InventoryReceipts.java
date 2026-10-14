package com.hexvane.eterniamod.inventory;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.*;

/** Saved in the SAME player document as the inventory mutation it proves. */
public final class InventoryReceipts implements Component<EntityStore> {
    /** Stores a document as it is, in the same saved form as the engine's deprecated document codec, which has no replacement yet. */
    private static final Codec<BsonDocument> DOCUMENT=new Codec<>(){
        @Override public BsonDocument decode(BsonValue value,ExtraInfo extraInfo){return value.asDocument();}
        @Override public BsonValue encode(BsonDocument document,ExtraInfo extraInfo){return document;}
        @Override public Schema toSchema(SchemaContext context){return new ObjectSchema();}
    };
    public static final BuilderCodec<InventoryReceipts> CODEC=BuilderCodec.builder(InventoryReceipts.class,InventoryReceipts::new)
        .append(new KeyedCodec<>("CustodyRevision",Codec.LONG),(r,v)->r.custodyRevision=v,r->r.custodyRevision).add()
        .append(new KeyedCodec<>("Receipts",DOCUMENT),(r,v)->r.receipts=v==null?new BsonDocument():v,r->r.receipts.clone()).add().build();
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
