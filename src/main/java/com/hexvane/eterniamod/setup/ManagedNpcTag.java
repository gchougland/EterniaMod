package com.hexvane.eterniamod.setup;

import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/** Serialized before the NPC enters the world, with the same stable UUID as its managed record. */
public final class ManagedNpcTag implements Component<EntityStore>{
    static ComponentType<EntityStore,ManagedNpcTag> TYPE;
    public static final BuilderCodec<ManagedNpcTag> CODEC=BuilderCodec.builder(ManagedNpcTag.class,ManagedNpcTag::new)
        .append(new KeyedCodec<>("ManagedId",Codec.UUID_BINARY),(v,id)->v.id=id,v->v.id).add()
        .append(new KeyedCodec<>("Role",Codec.STRING),(v,role)->v.role=role,v->v.role).add()
        .append(new KeyedCodec<>("IdentityRevision",Codec.INTEGER),(v,revision)->v.identityRevision=revision,v->v.identityRevision).add().build();
    private UUID id;private String role;private int identityRevision;
    public ManagedNpcTag(){}
    ManagedNpcTag(UUID id,String role){this.id=id;this.role=role;}
    boolean matches(ManagedHubRegistry.Entry entry){return entry.id().equals(id)&&entry.role().equals(role);}
    int identityRevision(){return identityRevision;}
    void markIdentityCurrent(){identityRevision=HubNpcIdentity.REVISION;}
    @Override public Component<EntityStore> clone(){var copy=new ManagedNpcTag(id,role);copy.identityRevision=identityRevision;return copy;}
}
