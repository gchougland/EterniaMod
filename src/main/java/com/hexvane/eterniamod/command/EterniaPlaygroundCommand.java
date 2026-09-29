package com.hexvane.eterniamod.command;
import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.localplayground.LocalPlayground;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
public final class EterniaPlaygroundCommand extends AbstractPlayerCommand {
    public EterniaPlaygroundCommand(){super("playground","Open the local Eternia testing village");setPermissionGroups("hytale:WorldEditor");}
    @Override protected void execute(CommandContext c,Store<EntityStore> s,Ref<EntityStore> r,PlayerRef p,World w){LocalPlayground.open(EterniaModPlugin.get(),r,s,p);}
}
