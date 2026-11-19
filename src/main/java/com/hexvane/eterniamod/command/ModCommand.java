package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.moderation.ModerationCommands;
import com.hexvane.eterniamod.ranks.RankPermissions;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/** {@code /eternia mod}: ban, unban, ban records, kick, mute and unmute, for the Mod rank (and admins). */
final class ModCommand extends AbstractCommandCollection {
    ModCommand() {
        super("mod", "Moderation: ban, kick and mute players");
        setPermissionGroups(RankPermissions.MOD_GROUP);
        ModerationCommands.addTo(this);
    }
}
