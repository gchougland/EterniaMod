package com.hexvane.eterniamod.setup;

import com.hexvane.eterniamod.domain.DomainException;
import com.hypixel.hytale.server.core.permissions.HytalePermissions;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import java.util.UUID;

/** Current native builder authorization, checked again by each setup mutation. */
public final class SetupAccess {
    private SetupAccess() {}
    public static boolean allowed(UUID player) { return PermissionsModule.get().hasPermission(player,HytalePermissions.BUILDER_TOOLS_EDITOR); }
    public static void require(UUID player) {
        if(!allowed(player))throw new DomainException(DomainException.Code.FORBIDDEN,"WorldEditor permission is required for Eternia setup.");
    }
}
