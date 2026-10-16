package com.hexvane.eterniamod.setup;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.UUID;

/** Inventory-free block metadata; it never authorizes travel or owns player items. */
public final class ManagedPortalTag implements Component<ChunkStore>{
    static ComponentType<ChunkStore,ManagedPortalTag> TYPE;
    public static final BuilderCodec<ManagedPortalTag> CODEC=BuilderCodec.builder(ManagedPortalTag.class,ManagedPortalTag::new)
        .append(new KeyedCodec<>("ManagedId",Codec.UUID_BINARY),(v,id)->v.id=id,v->v.id).add()
        .append(new KeyedCodec<>("Rotation",Codec.INTEGER),(v,rotation)->v.rotation=rotation,v->v.rotation).add().build();
    private UUID id;
    private int rotation; // Absent on legacy portals, which were always placed at rotation zero.
    public ManagedPortalTag(){}
    ManagedPortalTag(UUID id){this.id=id;}
    ManagedPortalTag(UUID id,int rotation){this.id=id;this.rotation=rotation;}
    int rotation(){return rotation;}
    boolean matches(UUID expected){return expected.equals(id);}
    @Override public Component<ChunkStore> clone(){return new ManagedPortalTag(id,rotation);}
}
