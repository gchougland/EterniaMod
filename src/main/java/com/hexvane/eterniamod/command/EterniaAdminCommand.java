package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.*;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Explicit administrator tools. Reusing a grant receipt with changed input is rejected by the authority. */
public final class EterniaAdminCommand extends AbstractCommandCollection {
    public EterniaAdminCommand(){
        super("admin","Eternia administration");setPermissionGroups("hytale:WorldEditor");
        addSubCommand(new Status());addSubCommand(new Reload());addSubCommand(new Inspect());addSubCommand(new Recover());addSubCommand(new Grant());addSubCommand(new EterniaSetupCommand());addSubCommand(new RoadTool());
    }
    private static final class RoadTool extends AbstractPlayerCommand {
        RoadTool(){super("roadtool","Get the held spline Road Designer");setPermissionGroups("hytale:WorldEditor");}
        @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){com.hexvane.eterniamod.pathtool.SplineRoadTool.give(ref,store,player);}
    }
    private static final class Status extends CommandBase {
        Status(){super("status","Show configured systems and pending recovery");}
        protected void executeSync(CommandContext context){var p=EterniaModPlugin.get();
            context.sendMessage(Message.raw("Eternia · "+(p.getRuntimeConfig().local()?"local":"production")+" · "+p.getInfrastructure().worlds().size()+" configured worlds · "+p.getBuildingCatalog().asMap().size()+" houses · "+p.getPropCatalog().asMap().size()+" props"));
            for(var op:p.getServices().journal().unfinished())context.sendMessage(Message.raw(op.id()+" · "+op.kind()+" · "+op.state()+" · "+op.owner().key()));
        }
    }
    private static final class Reload extends CommandBase {
        Reload(){super("reload-infrastructure","Reload authored road, portal and world roles");}
        protected void executeSync(CommandContext context){try{
            EterniaModPlugin.get().getInfrastructure().load();
            com.hexvane.eterniamod.customization.HousingWorldPolicy.refreshAll().whenComplete((unused,failure)->{
                if(failure==null)context.sendMessage(Message.raw("World infrastructure and housing policies reloaded. Content and payment revisions require a restart."));
                else context.sendMessage(Message.raw("Infrastructure reloaded, but a housing world policy needs administrator attention: "+failure.getMessage()));
            });
        }catch(Exception failure){context.sendMessage(Message.raw("Infrastructure was not replaced: "+failure.getMessage()));}}
    }
    private static final class Inspect extends CommandBase {
        final RequiredArg<String> operation=withRequiredArg("operation","Housing operation UUID",ArgTypes.STRING);
        Inspect(){super("inspect-recovery","Verify and inspect a housing recovery snapshot");}
        protected void executeSync(CommandContext context){try{context.sendMessage(Message.raw(EterniaModPlugin.get().getRelocation().inspect(UUID.fromString(operation.get(context))).toString()));}catch(RuntimeException failure){context.sendMessage(Message.raw("Inspection failed: "+failure.getMessage()));}}
    }
    private static final class Recover extends AbstractPlayerCommand {
        final RequiredArg<String> operation=withRequiredArg("operation","Inspected housing operation UUID",ArgTypes.STRING);
        Recover(){super("restore-source","Restore an interrupted pack to its verified original location in this world");}
        protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,PlayerRef player,World world){
            try{var p=EterniaModPlugin.get();var id=UUID.fromString(operation.get(context));var op=p.getServices().journal().find(id).orElseThrow();
                com.hexvane.eterniamod.ui.ChoicePage.open(ref,store,player,"Recover original plot?","Restore the inspected source snapshot for "+op.owner().key()+". Unexpected world edits will retain the recovery lock.",List.of(
                    new com.hexvane.eterniamod.ui.ChoicePage.Choice(id.toString(),"Confirm",(r,s)->{
                        if(op.kind().startsWith("CUSTOMIZE_")){
                            try{com.hexvane.eterniamod.customization.CustomizationBootstrap.service().rollback(s.getExternalData().getWorld(),id);player.sendMessage(Message.raw("Customization restored to its verified previous state."));}
                            catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
                        }else{p.getRelocation().recoverPackToSource(s.getExternalData().getWorld(),op.owner(),id);player.sendMessage(Message.raw("Original plot restored and its move credit released."));}
                    })));
            }catch(RuntimeException failure){context.sendMessage(Message.raw("Recovery could not start: "+failure.getMessage()));}
        }
    }
    private static final class Grant extends CommandBase {
        final RequiredArg<String> owner=withRequiredArg("owner","PLAYER:uuid or GUILD:uuid",ArgTypes.STRING);
        final RequiredArg<String> content=withRequiredArg("content","Namespaced content ID",ArgTypes.STRING);
        final RequiredArg<String> kind=withRequiredArg("kind","UNLOCK, CAPABILITY or QUANTITY",ArgTypes.STRING);
        final RequiredArg<Integer> quantity=withRequiredArg("quantity","Quantity",ArgTypes.INTEGER);
        final RequiredArg<String> receipt=withRequiredArg("receipt","Unique support or testing receipt",ArgTypes.STRING);
        Grant(){super("grant","Record an audited content grant");}
        protected void executeSync(CommandContext context){try{var p=EterniaModPlugin.get();var target=Owner.parse(owner.get(context));
            if(target.kind()==Owner.Kind.PLAYER&&p.getServices().accounts().find(target.id()).isEmpty()||target.kind()==Owner.Kind.GUILD&&p.getServices().guilds().find(target.id()).isEmpty()||target.kind()==Owner.Kind.SERVER)throw new IllegalArgumentException("Recipient must be an existing player or guild");
            p.getServices().ownership().grant(new OwnershipService.GrantRequest("admin:"+receipt.get(context),target,content.get(context),OwnershipService.Kind.valueOf(kind.get(context)),quantity.get(context),null));
            p.getLogger().atInfo().log("Administrator %s recorded content grant receipt %s for %s",context.sender().getUsername(),receipt.get(context),target.key());context.sendMessage(Message.raw("Content grant recorded."));
        }catch(RuntimeException failure){context.sendMessage(Message.raw("Grant rejected: "+failure.getMessage()));}}
    }
}
