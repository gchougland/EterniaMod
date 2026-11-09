package com.hexvane.eterniamod.tutorial;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.TutorialService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.function.Function;

/** {@code /e tutorial status|show|hide|skip|restart}: control the Hub walkthrough panel. */
public final class TutorialCommand extends AbstractCommandCollection {
    public TutorialCommand() {
        super("tutorial", "eternia_tutorial.command.desc");
        setPermissionGroups("hytale:Adventurer");
        addSubCommand(new Change("status", null, null));
        addSubCommand(new Change("show", "command.shown", (service) -> id -> service.hide(id, false)));
        addSubCommand(new Change("hide", "command.hidden", (service) -> id -> service.hide(id, true)));
        addSubCommand(new Change("skip", "command.skipped", (service) -> service::skip));
        addSubCommand(new Change("restart", "command.restarted", (service) -> service::restart));
    }

    private static final class Change extends AbstractPlayerCommand {
        private final String done;
        private final Function<TutorialService, Function<UUID, TutorialService.State>> change;

        Change(String name, String done, Function<TutorialService, Function<UUID, TutorialService.State>> change) {
            super(name, "eternia_tutorial.command.desc");
            setPermissionGroups("hytale:Adventurer");
            this.done = done;
            this.change = change;
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef player, World world) {
            EterniaModPlugin plugin = EterniaModPlugin.get();
            if (plugin == null) { player.sendMessage(Message.translation(HubDialogue.text("command.failed"))); return; }
            var service = plugin.getServices().tutorial();
            UUID id = player.getUuid();
            TutorialRuntime.async(store, () -> change == null ? service.state(id) : change.apply(service).apply(id), (state, failure) -> {
                if (failure != null || state == null) { player.sendMessage(Message.translation(HubDialogue.text("command.failed"))); return; }
                if (!ref.isValid()) return;
                TutorialRuntime.changed(ref, store, player, state);
                if (done != null) player.sendMessage(Message.translation(HubDialogue.text(done)));
                else if (state.finished()) player.sendMessage(Message.translation(HubDialogue.text("command.done")));
                else {
                    var people = TutorialRuntime.townspeople(world);
                    player.sendMessage(Message.translation(HubDialogue.text("command.status"))
                        .param("count", String.valueOf(state.townspeopleMet(people))).param("total", String.valueOf(people.size())));
                }
            });
        }
    }
}
