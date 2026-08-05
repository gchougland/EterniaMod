package com.hexvane.eterniamod.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

public final class EterniaModCommand extends AbstractCommandCollection {
    public EterniaModCommand() {
        super("eternia", "eterniamod_commands.commands.eternia.root.desc");
        this.setPermissionGroups("hytale:Adventurer");
        this.addAliases("e");
        this.addSubCommand(new EterniaPlotsCommand());
        this.addSubCommand(new EterniaBuildingCommand());
        this.addSubCommand(new EterniaPropCommand());
        this.addSubCommand(new EterniaPrefabCommand());
    }
}
