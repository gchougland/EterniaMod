package com.hexvane.eterniamod.chat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY probe, {@code /e chat probe keys}: for 30 seconds, writes down everything one player's client tells the
 * server that could give a key press away. Every on or off switch inside a movement packet is reported when it flips,
 * every other field when it appears or disappears, and every other kind of packet by name. Used to find out what, if
 * anything, the client sends when the walk key goes down in flight, where it stops reporting "walking".
 * Read by reflection, so it shows whatever the installed game build really carries.
 */
final class ChatKeyWatch extends AbstractPlayerCommand {
    private static final long MILLIS = 30_000;
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Map<UUID, Watch> WATCHING = new ConcurrentHashMap<>();
    private static volatile PacketFilter filter;

    /** One player's recording. Touched on the network thread only, after it is published. */
    private static final class Watch {
        final long until = System.currentTimeMillis() + MILLIS;
        final Map<String, String> last = new TreeMap<>();
        final Map<String, Integer> packets = new TreeMap<>();
        boolean first = true;
    }

    ChatKeyWatch() { super("keys", "eternia_chat.command.help"); }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
        synchronized (ChatKeyWatch.class) {
            if (filter == null) filter = PacketAdapters.registerInbound((PlayerPacketFilter) ChatKeyWatch::see);
        }
        WATCHING.put(player.getUuid(), new Watch());
        say(player, "Recording for 30 seconds. Press Alt standing still, then flying still, flying forward, falling and swimming. Leave a second between presses.");
    }

    /** Network thread. Never drops a packet. */
    private static boolean see(PlayerRef player, Packet packet) {
        Watch watch = WATCHING.get(player.getUuid());
        if (watch == null) return false;
        try {
            if (System.currentTimeMillis() > watch.until) {
                WATCHING.remove(player.getUuid());
                say(player, "Recording done. Other packets seen: " + watch.packets);
                return false;
            }
            if (packet instanceof ClientMovement movement) {
                var now = new TreeMap<String, String>();
                read("", movement, now, 0);
                var changes = new ArrayList<String>();
                for (var entry : now.entrySet()) {
                    String before = watch.last.put(entry.getKey(), entry.getValue());
                    if (before != null && !before.equals(entry.getValue())) changes.add(entry.getKey() + " " + before + " to " + entry.getValue());
                }
                if (watch.first) { watch.first = false; say(player, "Start: " + on(now)); }
                else if (!changes.isEmpty()) say(player, String.join(", ", changes));
            } else {
                String name = packet.getClass().getSimpleName();
                if (watch.packets.merge(name, 1, Integer::sum) <= 3) say(player, "Packet: " + name);
            }
        } catch (RuntimeException | ReflectiveOperationException failure) {
            WATCHING.remove(player.getUuid());
            LOGGER.atWarning().withCause(failure).log("Key watch failed");
        }
        return false;
    }

    /**
     * Flattens a packet into name and value pairs. Switches keep their value; numbers and text are reduced to "set",
     * so a position that changes every tick is not reported; nested objects are walked two levels deep.
     */
    private static void read(String prefix, Object object, Map<String, String> out, int depth) throws ReflectiveOperationException {
        for (Field field : object.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object value = field.get(object);
            String name = prefix + field.getName();
            if (value == null) out.put(name, "absent");
            else if (value instanceof Boolean flag) out.put(name, flag ? "on" : "off");
            else if (value instanceof Enum<?> choice) out.put(name, choice.name());
            else if (value instanceof Number || value instanceof CharSequence || value.getClass().isArray() || depth >= 2) out.put(name, "set");
            else { out.put(name, "set"); read(name + ".", value, out, depth + 1); }
        }
    }

    private static List<String> on(Map<String, String> now) {
        var out = new ArrayList<String>();
        for (var entry : now.entrySet()) if (entry.getValue().equals("on")) out.add(entry.getKey());
        return out;
    }

    private static void say(PlayerRef player, String text) {
        LOGGER.atInfo().log("[key watch] %s: %s", player.getUsername(), text);
        player.sendMessage(Message.raw(text));
    }
}
