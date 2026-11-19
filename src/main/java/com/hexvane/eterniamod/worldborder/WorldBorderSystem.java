package com.hexvane.eterniamod.worldborder;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Keeps players inside their world's border and shows them the force field when they come near it. A player who
 * steps out is put back where they last stood inside (or just inside the side they crossed); a rider's mount is moved
 * with them on it. The field is cosmetic
 * and sent only to the player who is near it.
 */
public final class WorldBorderSystem extends EntityTickingSystem<EntityStore> {
    /** How often a player's position is checked, in seconds. */
    static final float CHECK_SECONDS = 0.2f;
    /** How often the field is redrawn, in seconds; each panel lives a little longer, so the wall never flickers off. */
    static final float FIELD_SECONDS = 0.9f;
    static final float PANEL_SECONDS = 1.3f;
    /** A player put back lands this far inside the side they crossed. */
    static final double PUSH_BACK = 1.5;
    static final String FIELD = "Eternia_World_Border_Field";

    private final WorldBorders borders;
    private final Map<UUID, Watch> watches = new ConcurrentHashMap<>();

    /** Per player timers and the last place they stood inside the border. */
    private static final class Watch {
        float check, field, message;
        String world;
        Vector3d inside;
    }

    public WorldBorderSystem(WorldBorders borders) { this.borders = borders; }

    @Nonnull @Override public Query<EntityStore> getQuery() { return Player.getComponentType(); }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        if (!Float.isFinite(dt) || dt <= 0) return;
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (player == null || transform == null) return;
        World world = store.getExternalData().getWorld();
        var border = borders.get(world.getName()).orElse(null);
        if (border == null) { watches.remove(player.getUuid()); return; }
        Watch watch = watches.computeIfAbsent(player.getUuid(), k -> new Watch());
        watch.check += dt; watch.field += dt; watch.message = Math.max(0, watch.message - dt);
        if (watch.check < CHECK_SECONDS) return;
        watch.check = 0;
        if (store.getComponent(ref, DeathComponent.getComponentType()) != null || store.getComponent(ref, Teleport.getComponentType()) != null) return;
        var p = transform.getPosition();
        if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) return;
        if (!world.getName().equals(watch.world)) { watch.world = world.getName(); watch.inside = null; }

        if (!border.contains(p.x, p.z)) {
            putBack(ref, store, commandBuffer, world, border, watch, p, player);
            return;
        }
        // Remember a spot a step inside, so putting someone back never lands them on the line itself.
        if (border.distanceInside(p.x, p.z) >= PUSH_BACK) watch.inside = new Vector3d(p.x, p.y, p.z);

        if (watch.field >= FIELD_SECONDS) {
            watch.field = 0;
            if (border.distanceInside(p.x, p.z) <= WorldBorderField.REACH) showField(ref, commandBuffer, border, p.x, p.y, p.z);
        }
    }

    private void putBack(Ref<EntityStore> ref, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, World world,
                         WorldBorders.Border border, Watch watch, org.joml.Vector3d p, PlayerRef player) {
        Vector3d target;
        if (watch.inside != null && border.contains(watch.inside.x, watch.inside.z)) target = new Vector3d(watch.inside);
        else {
            // No spot inside to go back to (they arrived outside, or the border moved): just inside, on the ground there.
            double[] xz = border.clampInside(p.x, p.z, PUSH_BACK);
            target = new Vector3d(xz[0], groundAbove(world, xz[0], xz[1], p.y), xz[1]);
        }
        // A rider is attached to their mount, so the mount is moved and carries them; on foot, the player is moved.
        var mounted = store.getComponent(ref, com.hypixel.hytale.builtin.mounts.MountedComponent.getComponentType());
        var mount = mounted == null ? null : mounted.getMountedToEntity();
        if (mount != null && mount.isValid() && mount.getStore() == store && store.getComponent(mount, Teleport.getComponentType()) == null) {
            TransformComponent pose = store.getComponent(mount, TransformComponent.getComponentType());
            Rotation3f facing = pose == null ? new Rotation3f() : new Rotation3f(pose.getRotation());
            commandBuffer.putComponent(mount, Teleport.getComponentType(), new Teleport(target, facing));
        } else {
            HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
            Rotation3f look = head == null ? new Rotation3f() : new Rotation3f(head.getRotation());
            commandBuffer.putComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(target, look));
        }
        if (watch.message <= 0) {
            player.sendMessage(Message.raw("You have reached the edge of the world."));
            watch.message = 4;
        }
    }

    /** Feet height on top of the highest block in a loaded column, or {@code fallback} when the column is not loaded. */
    private static double groundAbove(World world, double x, double z, double fallback) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (com.hexvane.eterniamod.world.ChunkSectionBlockUtil.worldChunkIfInMemory(world, bx, bz) == null) return fallback;
        int top = com.hexvane.eterniamod.world.ChunkSectionBlockUtil.columnHeight(world, bx, bz);
        return top < com.hypixel.hytale.math.util.ChunkUtil.MIN_Y ? fallback : top + 1;
    }

    private static void showField(Ref<EntityStore> ref, CommandBuffer<EntityStore> commandBuffer, WorldBorders.Border border, double x, double y, double z) {
        for (var panel : WorldBorderField.panels(border, x, y, z)) {
            String effect = panel.strength() > .6 ? FIELD : panel.strength() > .3 ? FIELD + "_Soft" : FIELD + "_Faint";
            ParticleUtil.spawnParticleEffect(effect, panel.x(), panel.y(), panel.z(), (float) Math.toRadians(panel.yawDegrees()), 0, 0, 1,
                null, null, List.of(ref), commandBuffer, PANEL_SECONDS);
        }
    }

    /** Forgets a player who left. */
    public void forget(UUID player) { watches.remove(player); }
}
