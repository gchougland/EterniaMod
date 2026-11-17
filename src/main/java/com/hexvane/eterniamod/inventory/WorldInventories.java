package com.hexvane.eterniamod.inventory;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.protocol.packets.player.UpdateMemoriesCount;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Separate inventories per world group, saved in the SAME player document as the inventory itself, so the swap and the
 * put away copy are written together and a crash can neither duplicate nor lose a set. Memories carried but not yet
 * recorded travel with the inventory; recorded memories are kept per group by {@code PersonalMemories}.
 *
 * <p>The swap happens as the player is added to a world, while the engine holds the player free of any world: nothing
 * can use or drop an item in between. Creative tools are not swapped.
 */
public final class WorldInventories implements Component<EntityStore> {
    /** Stores a document as it is (the engine's own document codec is deprecated and has no replacement yet). */
    private static final Codec<BsonDocument> DOCUMENT = new Codec<>() {
        @Override public BsonDocument decode(BsonValue value, ExtraInfo extraInfo) { return value.asDocument(); }
        @Override public BsonValue encode(BsonDocument document, ExtraInfo extraInfo) { return document; }
        @Override public Schema toSchema(SchemaContext context) { return new ObjectSchema(); }
    };
    public static final BuilderCodec<WorldInventories> CODEC = BuilderCodec.builder(WorldInventories.class, WorldInventories::new)
        .append(new KeyedCodec<>("Current", Codec.STRING), (w, v) -> w.book = new WorldInventoryBook(v, w.book.stashes()), w -> w.book.current()).add()
        .append(new KeyedCodec<>("Stashes", DOCUMENT), (w, v) -> w.book = new WorldInventoryBook(w.book.current(), v), w -> w.book.stashes().clone()).add()
        .build();

    private static ComponentType<EntityStore, WorldInventories> type;
    private WorldInventoryBook book = new WorldInventoryBook();

    public static void register(ComponentRegistryProxy<EntityStore> registry) {
        type = registry.registerComponent(WorldInventories.class, "EterniaWorldInventories", CODEC);
    }

    public static ComponentType<EntityStore, WorldInventories> type() { return type; }

    @Override public WorldInventories clone() { var copy = new WorldInventories(); copy.book = book.copy(); return copy; }

    /** Swaps inventories whenever a player enters a world of another group, including at login. */
    public static void install(EterniaModPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, event -> {
            var world = event.getWorld();
            var holder = event.getHolder();
            var player = holder.getComponent(PlayerRef.getComponentType());
            var group = WorldInventoryBook.groupFor(world.getName(), plugin.getInfrastructure().world(world.getName()).map(plan -> plan.role()));
            try {
                if (group.isPresent() && enter(holder, group.get()) && player != null) {
                    var memories = holder.getComponent(PlayerMemories.getComponentType());
                    player.getPacketHandler().writeNoCache(new UpdateMemoriesCount(memories == null ? 0 : memories.getRecordedMemories().size()));
                }
            } catch (RuntimeException failure) {
                // Nothing was changed: the player keeps what they carry and the swap is tried again on their next entry.
                plugin.getLogger().atSevere().withCause(failure).log("Could not swap world inventories for %s entering %s",
                    player == null ? "a player" : player.getUuid(), world.getName());
            }
            if (player != null) {
                var stored = holder.getComponent(type);
                CURRENT.put(player.getUuid(), stored == null || stored.book.current() == null ? WorldInventoryBook.SHARED : stored.book.current());
            }
        });
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> CURRENT.remove(event.getPlayerRef().getUuid()));
    }

    /** Each online player's current group, kept as they enter worlds. */
    private static final Map<UUID, String> CURRENT = new ConcurrentHashMap<>();

    /** The world group whose inventory and memories an online player has now: {@link WorldInventoryBook#SHARED} until known. */
    public static String currentGroup(UUID player) { return CURRENT.getOrDefault(player, WorldInventoryBook.SHARED); }

    /**
     * Makes the player carry {@code group}'s inventory and memories. Changes nothing unless every part encodes and
     * decodes. Returns true when a swap happened.
     */
    static boolean enter(Holder<EntityStore> holder, String group) {
        var stored = holder.getComponent(type);
        var book = stored == null ? new WorldInventoryBook() : stored.book.copy();
        if (!book.needsSwitch(group)) {
            if (book.current() == null) { book.switchTo(group, new BsonDocument()); save(holder, book); }
            return false;
        }
        var carried = new BsonDocument();
        for (var part : parts()) part.encodeInto(holder, carried);
        Optional<BsonDocument> saved = book.switchTo(group, carried);
        // Decode everything before touching the player, so a bad saved set leaves them exactly as they were.
        var incoming = new ArrayList<Runnable>();
        for (var part : parts()) incoming.add(part.prepare(holder, saved.orElse(null)));
        incoming.forEach(Runnable::run);
        save(holder, book);
        return true;
    }

    private static void save(Holder<EntityStore> holder, WorldInventoryBook book) {
        var value = new WorldInventories();
        value.book = book;
        holder.putComponent(type, value);
    }

    /**
     * One swapped part: its saved name, engine type and codec, how to empty it when the group has none saved, and what
     * to carry over from the live part onto a saved one as it comes back.
     */
    private record Part<T extends Component<EntityStore>>(String key, ComponentType<EntityStore, T> componentType, BuilderCodec<T> codec,
                                                          Consumer<T> empty, BiConsumer<T, T> arriving) {
        void encodeInto(Holder<EntityStore> holder, BsonDocument out) {
            T live = holder.getComponent(componentType);
            if (live != null) out.put(key, codec.encode(live, new ExtraInfo()));
        }

        /** Builds the change that puts the saved part on the player, or empties it when there is none. */
        Runnable prepare(Holder<EntityStore> holder, BsonDocument saved) {
            T live = holder.getComponent(componentType);
            if (saved != null && saved.containsKey(key)) {
                T loaded = codec.decode(saved.get(key), new ExtraInfo());
                return () -> { if (live != null) arriving.accept(live, loaded); holder.putComponent(componentType, loaded); };
            }
            if (live == null) return () -> {};
            return () -> empty.accept(live);
        }
    }

    private static <T extends InventoryComponent> Part<T> inventory(String key, ComponentType<EntityStore, T> type, BuilderCodec<T> codec) {
        return new Part<>(key, type, codec, live -> { live.getInventory().clear(); live.markDirty(); }, (live, loaded) -> loaded.markDirty());
    }

    private static volatile List<Part<?>> parts;

    /** Read on first use: the engine's component types exist only once its modules are set up. */
    private static List<Part<?>> parts() {
        var known = parts;
        if (known == null) parts = known = createParts();
        return known;
    }

    private static List<Part<?>> createParts() {
        var all = new ArrayList<Part<?>>(List.of(
            inventory("Hotbar", InventoryComponent.Hotbar.getComponentType(), InventoryComponent.Hotbar.CODEC),
            inventory("Storage", InventoryComponent.Storage.getComponentType(), InventoryComponent.Storage.CODEC),
            inventory("Armor", InventoryComponent.Armor.getComponentType(), InventoryComponent.Armor.CODEC),
            inventory("Utility", InventoryComponent.Utility.getComponentType(), InventoryComponent.Utility.CODEC),
            inventory("Backpack", InventoryComponent.Backpack.getComponentType(), InventoryComponent.Backpack.CODEC),
            inventory("RuneBag", InventoryComponent.RuneBag.getComponentType(), InventoryComponent.RuneBag.CODEC)));
        // Memories collected but not yet recorded at a bench belong to the world they were found in. How many the player
        // can carry is not part of the set, so it stays as it is. Left out if the memories module is not running.
        var memories = PlayerMemories.getComponentType();
        if (memories != null) all.add(new Part<>("Memories", memories, PlayerMemories.CODEC,
            live -> live.takeMemories(new HashSet<>()), (live, loaded) -> loaded.setMemoriesCapacity(live.getMemoriesCapacity())));
        return List.copyOf(all);
    }
}
