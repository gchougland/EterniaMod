package com.hexvane.eterniamod.hub;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Links world entities spawned from a prop or building prefab back to that placement instance. */
public final class EterniaPlacedInstance implements Component<EntityStore> {
    public enum Kind {
        PROP,
        BUILDING
    }

    @Nonnull
    public static final BuilderCodec<EterniaPlacedInstance> CODEC =
        BuilderCodec.builder(EterniaPlacedInstance.class, EterniaPlacedInstance::new)
            .append(new KeyedCodec<>("Kind", Codec.STRING), (s, v) -> s.kind = parseKind(v), s -> s.kind.name())
            .add()
            .append(new KeyedCodec<>("InstanceId", Codec.UUID_BINARY), (s, v) -> s.instanceId = v, s -> s.instanceId)
            .add()
            .append(new KeyedCodec<>("CatalogId", Codec.STRING), (s, v) -> s.catalogId = v != null ? v : "", s -> s.catalogId)
            .add()
            .build();

    @Nullable
    private static volatile ComponentType<EntityStore, EterniaPlacedInstance> componentType;

    public static void register(@Nonnull ComponentRegistryProxy<EntityStore> registry) {
        componentType = registry.registerComponent(EterniaPlacedInstance.class, "EterniaPlacedInstance", CODEC);
    }

    @Nonnull
    public static ComponentType<EntityStore, EterniaPlacedInstance> getComponentType() {
        ComponentType<EntityStore, EterniaPlacedInstance> t = componentType;
        if (t == null) {
            throw new IllegalStateException("EterniaPlacedInstance not registered");
        }
        return t;
    }

    private Kind kind = Kind.PROP;
    private UUID instanceId = new UUID(0L, 0L);
    private String catalogId = "";

    public EterniaPlacedInstance() {}

    public EterniaPlacedInstance(@Nonnull Kind kind, @Nonnull UUID instanceId, @Nonnull String catalogId) {
        this.kind = kind;
        this.instanceId = instanceId;
        this.catalogId = catalogId != null ? catalogId : "";
    }

    @Nonnull
    public Kind getKind() {
        return kind;
    }

    @Nonnull
    public UUID getInstanceId() {
        return instanceId;
    }

    @Nonnull
    public String getCatalogId() {
        return catalogId;
    }

    @Nonnull
    private static Kind parseKind(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Kind.PROP;
        }
        try {
            return Kind.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return Kind.PROP;
        }
    }

    @Nullable
    @Override
    public Component<EntityStore> clone() {
        return new EterniaPlacedInstance(kind, instanceId, catalogId);
    }
}
