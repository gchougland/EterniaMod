package com.hexvane.eterniamod.wildland;

import com.hexvane.eterniamod.domain.GuildService;
import com.hexvane.eterniamod.domain.Owner;
import com.hexvane.eterniamod.domain.WildLandService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Whether someone may do something on a claim. Pure, so the guards and tests share one answer. */
public final class WildAccess {
    private WildAccess() {}

    /**
     * The owner may do anything. On guild land, members whose role grants {@link WildPerm#capability()} may. Anyone the
     * land is shared with (by name, or everyone) may do what it was shared for.
     */
    public static boolean allowed(WildLandIndex.Land land, UUID player, Optional<GuildService.Standing> standing, WildPerm perm) {
        if (land == null) return true;
        if (player == null) return false;
        Owner owner = land.owner();
        if (owner.kind() == Owner.Kind.PLAYER && owner.id().equals(player)) return true;
        if (owner.kind() == Owner.Kind.GUILD && standing.isPresent() && standing.get().guildId().equals(owner.id())
            && standing.get().capabilities().contains(perm.capability())) return true;
        return land.trust().getOrDefault(player.toString(), Set.of()).contains(perm)
            || land.trust().getOrDefault(WildLandService.EVERYONE, Set.of()).contains(perm);
    }

    /** As {@link #allowed}, but reads the guild standing only when the land is a guild's and nothing else allows it. */
    public static boolean allowed(WildLandIndex.Land land, UUID player, java.util.function.Supplier<Optional<GuildService.Standing>> standing, WildPerm perm) {
        if (allowed(land, player, Optional.empty(), perm)) return true;
        return land.owner().kind() == Owner.Kind.GUILD && player != null && allowed(land, player, standing.get(), perm);
    }

    /** Whether {@code player} may claim, grow, give up or share this land. */
    public static boolean manages(WildLandIndex.Land land, UUID player, Optional<GuildService.Standing> standing) {
        if (land == null || player == null) return false;
        Owner owner = land.owner();
        if (owner.kind() == Owner.Kind.PLAYER) return owner.id().equals(player);
        return standing.isPresent() && standing.get().guildId().equals(owner.id()) && standing.get().capabilities().contains(WildLandService.MANAGE);
    }
}
