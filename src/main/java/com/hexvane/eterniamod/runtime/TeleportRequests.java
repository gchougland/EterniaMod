package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.DomainException;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;

/**
 * Teleport requests between players: {@code /tpa} asks to go to someone, {@code /tpahere} asks them to come to you, and
 * the other player answers with {@code /tpaccept} or {@code /tpdeny}. Requests live two minutes and are not saved. The
 * asker's 30 minute cooldown starts only when a teleport goes through.
 */
public final class TeleportRequests {
    public static final Duration LIFE = Duration.ofMinutes(2);
    private record Request(UUID from, String fromName, UUID to, boolean here, Instant at) {}
    /** Open requests by the player who must answer, newest last. */
    private static final Map<UUID, List<Request>> OPEN = new ConcurrentHashMap<>();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Eternia-teleport-requests"); t.setDaemon(true); return t; });
    private TeleportRequests() {}

    /** Sends a request. {@code here}: they come to you; otherwise you go to them. */
    public static void ask(EterniaModPlugin plugin, PlayerRef from, String targetName, boolean here) {
        var target = online(targetName);
        if (target == null) { from.sendMessage(Message.raw("Nobody called " + targetName + " is online.")); return; }
        if (target.getUuid().equals(from.getUuid())) { from.sendMessage(Message.raw("You cannot send a teleport request to yourself.")); return; }
        WORK.execute(() -> {
            try {
                var services = plugin.getServices();
                if (services.friends().isBlocked(target.getUuid(), from.getUuid())) { from.sendMessage(Message.raw(target.getUsername() + " is not taking teleport requests.")); return; }
                Duration left = services.travel().requestCooldown(from.getUuid());
                if (!left.isZero()) { from.sendMessage(Message.raw("You can send another teleport request in " + minutes(left) + ".")); return; }
                var list = OPEN.computeIfAbsent(target.getUuid(), k -> new CopyOnWriteArrayList<>());
                list.removeIf(r -> r.from.equals(from.getUuid()));
                list.add(new Request(from.getUuid(), from.getUsername(), target.getUuid(), here, Instant.now()));
                from.sendMessage(Message.raw("Request sent to " + target.getUsername() + ". It lasts two minutes."));
                target.sendMessage(Message.raw(here
                    ? from.getUsername() + " asks you to teleport to them. Type /tpaccept to go, or /tpdeny."
                    : from.getUsername() + " asks to teleport to you. Type /tpaccept to let them, or /tpdeny."));
            } catch (RuntimeException failure) { from.sendMessage(Message.raw("Teleport requests are not available right now.")); }
        });
    }

    /** Answers the newest open request, or the one from {@code fromName} when given. */
    public static void answer(EterniaModPlugin plugin, PlayerRef me, String fromName, boolean accept) {
        var request = take(me.getUuid(), fromName);
        if (request == null) { me.sendMessage(Message.raw(fromName == null || fromName.isBlank() ? "You have no teleport requests waiting." : "No teleport request from " + fromName + " is waiting.")); return; }
        var asker = Universe.get().getPlayer(request.from);
        if (!accept) {
            me.sendMessage(Message.raw("Request from " + request.fromName + " declined."));
            if (asker != null) asker.sendMessage(Message.raw(me.getUsername() + " declined your teleport request."));
            return;
        }
        if (asker == null) { me.sendMessage(Message.raw(request.fromName + " is no longer online.")); return; }
        // Who moves, and to whom: /tpa moves the asker to me, /tpahere moves me to the asker.
        PlayerRef mover = request.here ? me : asker, anchor = request.here ? asker : me;
        WORK.execute(() -> {
            try { plugin.getServices().travel().useRequest(request.from); }
            catch (DomainException cooling) { me.sendMessage(Message.raw(request.fromName + " has to wait before teleporting again.")); return; }
            catch (RuntimeException failure) { me.sendMessage(Message.raw("Teleport requests are not available right now.")); return; }
            moveTo(plugin, mover, anchor).whenComplete((ok, error) -> {
                if (error != null || !Boolean.TRUE.equals(ok)) {
                    WORK.execute(() -> { try { plugin.getServices().travel().clearRequest(request.from); } catch (RuntimeException ignored) {} });
                    me.sendMessage(Message.raw("The teleport did not work. Try again."));
                    asker.sendMessage(Message.raw("The teleport did not work. Your request is ready to use again."));
                    return;
                }
                mover.sendMessage(Message.raw("Teleported to " + anchor.getUsername() + "."));
                anchor.sendMessage(Message.raw(mover.getUsername() + " teleported to you."));
            });
        });
    }

    /** Reads where the anchor stands on their world's thread, then places the mover there. */
    private static CompletableFuture<Boolean> moveTo(EterniaModPlugin plugin, PlayerRef mover, PlayerRef anchor) {
        var ref = anchor.getReference();
        if (ref == null || !ref.isValid()) return CompletableFuture.completedFuture(false);
        var world = ref.getStore().getExternalData().getWorld();
        var spot = new CompletableFuture<Vector3d>();
        world.execute(() -> {
            if (!ref.isValid()) { spot.complete(null); return; }
            var transform = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
            spot.complete(transform == null ? null : new Vector3d(transform.getPosition()));
        });
        return spot.thenCompose(at -> at == null ? CompletableFuture.completedFuture(false) : plugin.getTravel().place(mover, world, at));
    }

    private static Request take(UUID me, String fromName) {
        var list = OPEN.get(me); if (list == null) return null;
        Instant cutoff = Instant.now().minus(LIFE);
        list.removeIf(r -> r.at.isBefore(cutoff));
        Request found = null;
        for (var r : list) if (fromName == null || fromName.isBlank() || r.fromName.equalsIgnoreCase(fromName.strip())) found = r;
        if (found != null) list.remove(found);
        return found;
    }

    private static PlayerRef online(String name) {
        if (name == null) return null;
        for (PlayerRef p : Universe.get().getPlayers()) if (p.getUsername().equalsIgnoreCase(name.strip())) return p;
        return null;
    }

    static String minutes(Duration d) {
        long m = Math.max(1, (d.toSeconds() + 59) / 60);
        return m == 1 ? "1 minute" : m + " minutes";
    }

    public static void forget(UUID player) { OPEN.remove(player); OPEN.values().forEach(l -> l.removeIf(r -> r.from.equals(player))); }
}
