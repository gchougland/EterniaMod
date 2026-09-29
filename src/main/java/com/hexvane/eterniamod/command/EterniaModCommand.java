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
        this.addSubCommand(new EterniaPlayCommand("menu"));
        this.addSubCommand(new EterniaPlayCommand("claim"));
        this.addSubCommand(new EterniaPlayCommand("housing"));
        this.addSubCommand(new EterniaPlayCommand("quests"));
        this.addSubCommand(new EterniaPlayCommand("myplots"));
        this.addSubCommand(new EterniaPlayCommand("shopreturn"));
        this.addSubCommand(new EterniaPlayCommand("worlds"));
        this.addSubCommand(new EterniaAdminCommand());
        this.addSubCommand(new EterniaPartyCommand());
        this.addSubCommand(new EterniaGuildChatCommand());
        this.addSubCommand(new EterniaPlaygroundCommand());
    }
}
