package com.hexvane.eterniamod.collections;

import com.hexvane.eterniamod.domain.Owner;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/** Runtime provenance marker. Housing snapshots must omit this transient presentation entity. */
public final class CosmeticPet implements Component<EntityStore> {
    private static ComponentType<EntityStore,CosmeticPet> type;
    private final UUID petId;
    private final Owner owner;
    private final String sourceGrant;
    CosmeticPet(UUID petId,Owner owner,String sourceGrant){this.petId=petId;this.owner=owner;this.sourceGrant=sourceGrant;}
    static void register(ComponentType<EntityStore,CosmeticPet> registered){type=registered;}
    public static ComponentType<EntityStore,CosmeticPet> getComponentType(){return type;}
    public UUID getPetId(){return petId;}
    public Owner getOwner(){return owner;}
    public String getSourceGrant(){return sourceGrant;}
    @Override public Component<EntityStore> clone(){return this;}
}
