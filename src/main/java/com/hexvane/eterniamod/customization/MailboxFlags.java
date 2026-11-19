package com.hexvane.eterniamod.customization;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.housing.HousingAccess;
import com.hexvane.eterniamod.hub.EterniaWorldRegistries;
import com.hexvane.eterniamod.hub.HubPlotManager;
import com.hexvane.eterniamod.hub.HubPlotProp;
import com.hexvane.eterniamod.hub.HubPlotRecord;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raises the flag on a placed mailbox, with a letter in its slot, while its plot's owner has unread mail, and lowers it
 * when they have read everything. The mailbox block has a "Mail" state whose model shows the flag up and the letter.
 * Unread mail is read off the world threads; only loaded mailboxes are touched, on their own world's thread.
 */
public final class MailboxFlags implements AutoCloseable {
    public static final String PROP_ID = "qa_mailbox";
    public static final String BLOCK_ID = "Eternia_QA_Mailbox";
    public static final String MAIL_STATE = "Mail";
    private static final long PERIOD_SECONDS = 5;

    private final EterniaModPlugin plugin;
    private volatile ScheduledFuture<?> task;

    public MailboxFlags(EterniaModPlugin plugin) { this.plugin = plugin; }

    public void start() {
        task = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::pass, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        ScheduledFuture<?> running = task;
        if (running != null) running.cancel(false);
    }

    private void pass() {
        try {
            Set<UUID> unread = null;
            for (World world : Universe.get().getWorlds().values()) {
                if (!world.isAlive()) continue;
                HubPlotManager plots = EterniaWorldRegistries.existing(world);
                if (plots == null) continue;
                List<Mailbox> boxes = new ArrayList<>();
                for (HubPlotRecord plot : plots.listPlots()) {
                    Owner owner = HousingAccess.owner(plot);
                    if (owner == null || owner.kind() != Owner.Kind.PLAYER) continue;
                    for (HubPlotProp prop : plot.getProps())
                        if (PROP_ID.equals(prop.getPropId())) boxes.add(new Mailbox(owner.id(), prop.getAnchorX(), prop.getAnchorY(), prop.getAnchorZ()));
                }
                if (boxes.isEmpty()) continue;
                if (unread == null) unread = plugin.getServices().mail().recipientsWithUnread();
                Set<UUID> waiting = unread;
                world.execute(() -> { for (Mailbox box : boxes) show(world, box, waiting.contains(box.owner())); });
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().atWarning().withCause(failure).log("Mailbox flags could not be updated");
        }
    }

    private record Mailbox(UUID owner, int x, int y, int z) {}

    private static void show(World world, Mailbox box, boolean mail) {
        var chunkStore = world.getChunkStore();
        var sectionRef = chunkStore.getChunkSectionReferenceAtBlock(box.x(), box.y(), box.z());
        if (sectionRef == null || !sectionRef.isValid()) return;
        var section = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
        if (section == null) return;
        BlockType current = BlockType.getAssetMap().getAsset(section.get(box.x(), box.y(), box.z()));
        if (current == null) return;
        String base = Objects.requireNonNullElse(current.getDefaultStateKey(), current.getId());
        if (!BLOCK_ID.equals(base)) return;
        boolean showing = MAIL_STATE.equals(current.getStateForBlock(current));
        if (showing == mail) return;
        BlockOperations.setBlockInteractionState(chunkStore, sectionRef, box.x(), box.y(), box.z(), current, mail ? MAIL_STATE : "default", false);
    }
}
