package com.hexvane.eterniamod.socialui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.ActionBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;

final class ActionOpenEterniaServices extends ActionBase {
    private final EterniaServicesPage.Section section;
    ActionOpenEterniaServices(BuilderActionOpenEterniaServices builder) { super(builder); section = EterniaServicesPage.Section.valueOf(builder.section); }
    @Override public boolean canExecute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport role, InfoProvider info, double dt, @Nonnull Store<EntityStore> store) {
        return super.canExecute(ref, role, info, dt, store) && role.getStateSupport().getInteractionIterationTarget() != null;
    }
    @Override public boolean execute(@Nonnull Ref<EntityStore> ref, @Nonnull ExecutionSupport role, InfoProvider info, double dt, @Nonnull Store<EntityStore> store) {
        super.execute(ref, role, info, dt, store);
        Ref<EntityStore> target = role.getStateSupport().getInteractionIterationTarget();
        if (target == null || !target.isValid()) return false;
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid() || !target.isValid()) return;
            Player player = store.getComponent(target, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(target, PlayerRef.getComponentType());
            if (player == null || playerRef == null || player.getPageManager().getCustomPage() instanceof EterniaServicesPage) return;
            SocialUiBootstrap.openNpc(target, store, playerRef, section);
        });
        return true;
    }
}
