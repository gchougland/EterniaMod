package com.hexvane.eterniamod.ranks;

import com.hexvane.eterniamod.domain.EterniaServices;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Staff ranks are Hytale permission groups, saved in the server's {@code permissions.json}: Admin is the game's own
 * Admin group (everything), Mod and Builder are Eternia groups given only what they need. The titles that come with
 * them follow the groups ({@link #syncTitles}).
 */
public final class RankPermissions {
    public static final String MOD_GROUP = "eternia:Moderator";
    public static final String BUILDER_GROUP = "eternia:Builder";
    /** Ban, unban, kick, mute and look up records ({@code /e mod}). */
    public static final String MODERATION = "eternia.moderation";
    /** Give and take the Streamer rank. */
    public static final String GRANT_STREAMER = "eternia.ranks.streamer";
    /** Give and take any rank. Admins have it through the Admin group. */
    public static final String GRANT_ANY = "eternia.ranks.manage";
    /** Turn building on in the Hub and housing worlds' public ground ({@code /e build}). */
    public static final String BUILD = "eternia.build";

    private RankPermissions() {}

    /**
     * Gives the Eternia groups their permissions, in memory only. Never saved to {@code permissions.json}: the server
     * reads that file only when its permissions module starts, after every plugin's setup, so writing it during setup
     * replaced the file with one holding no players and admins lost their rank on restart (2026-11-18).
     */
    public static void install() {
        PermissionsModule.registerPermission(MODERATION, MOD_GROUP);
        PermissionsModule.registerPermission(GRANT_STREAMER, MOD_GROUP);
        PermissionsModule.registerPermission(BUILD, BUILDER_GROUP);
    }

    /** Rebuilds the in memory groups once the server has started, in case permissions started before {@link #install}. */
    public static void refresh() {
        var permissions = PermissionsModule.get();
        if (permissions != null) permissions.refreshVirtualGroups();
    }

    /** The permission group behind a staff rank, or null for a cosmetic rank. */
    @Nullable
    public static String group(Rank rank) {
        return switch (rank) {
            case ADMIN -> HytalePermissionsProvider.GROUP_ADMIN;
            case MOD -> MOD_GROUP;
            case BUILDER -> BUILDER_GROUP;
            default -> null;
        };
    }

    /** The staff ranks a player holds, from their permission groups. */
    public static Set<Rank> staffRanks(UUID player) {
        var groups = PermissionsModule.get().getGroupsForUser(player);
        var held = EnumSet.noneOf(Rank.class);
        for (Rank rank : Rank.values()) if (rank.staff() && groups.contains(group(rank))) held.add(rank);
        return held;
    }

    public static boolean has(UUID player, String permission) { return PermissionsModule.get().hasPermission(player, permission); }

    /** Puts a player in or out of a staff rank's group. */
    public static void setStaff(UUID player, Rank rank, boolean held) {
        String group = group(rank);
        if (group == null) throw new IllegalArgumentException(rank.displayName() + " is not a staff rank");
        var permissions = PermissionsModule.get();
        if (held) {
            permissions.addUserToGroup(player, group);
            keepPlayerCommands(player);
        } else permissions.removeUserFromGroup(player, group);
    }

    /**
     * The Mod and Builder groups do not inherit from the game's Adventurer group the way its own groups do, so a player
     * in only those groups would lose every player command ({@code /hub}, {@code /e menu} and the rest). Staff always
     * keep the Adventurer group. Admins inherit it already.
     */
    public static void keepPlayerCommands(UUID player) {
        var permissions = PermissionsModule.get();
        var groups = permissions.getGroupsForUser(player);
        boolean custom = groups.contains(MOD_GROUP) || groups.contains(BUILDER_GROUP);
        if (custom && !groups.contains(HytalePermissionsProvider.GROUP_ADVENTURER) && !groups.contains(HytalePermissionsProvider.GROUP_ADMIN))
            permissions.addUserToGroup(player, HytalePermissionsProvider.GROUP_ADVENTURER);
    }

    /** Gives or revokes staff rank titles so they match the player's groups. Returns true when anything changed. */
    public static boolean syncTitles(EterniaServices services, UUID player) {
        keepPlayerCommands(player);
        Map<String, String> staff = new HashMap<>();
        for (Rank rank : Rank.values()) if (rank.staff()) staff.put(rank.id(), rank.titleId());
        var held = new HashSet<String>();
        for (Rank rank : staffRanks(player)) held.add(rank.id());
        return services.ranks().syncStaff(player, staff, held);
    }
}
