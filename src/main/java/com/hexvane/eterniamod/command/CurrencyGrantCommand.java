package com.hexvane.eterniamod.command;

import com.hexvane.eterniamod.EterniaModPlugin;
import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.socialui.SocialUiBootstrap;
import com.hexvane.eterniamod.ui.CurrencyUi;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.*;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class CurrencyGrantCommand extends AbstractAsyncCommand {
    static final String PERMISSION="eternia.admin.currency";
    private final CurrencyGrantService.Currency currency;
    private final RequiredArg<String> target=withRequiredArg("player","Player name, UUID or me",ArgTypes.STRING);
    private final RequiredArg<Integer> amount=withRequiredArg("amount","Positive whole amount to add",ArgTypes.INTEGER);
    private final OptionalArg<String> reference=withOptionalArg("receipt","Reuse this reference when retrying a grant",ArgTypes.STRING);
    CurrencyGrantCommand(CurrencyGrantService.Currency currency) {
        super(currency==CurrencyGrantService.Currency.CROWNS?"crowns":"coins","Add currency to a player account");
        this.currency=currency;
        setPermissionGroups(); // Do not inherit WorldEditor: builder access must not allow minting money.
        requirePermission(PERMISSION);
        if(currency==CurrencyGrantService.Currency.COINS)addAliases("gold");
    }
    @Override protected CompletableFuture<Void> executeAsync(CommandContext context) {
        var plugin=EterniaModPlugin.get();var services=plugin.getServices();
        String input=target.get(context).trim();long quantity=amount.get(context);
        String receipt=reference.provided(context)?reference.get(context).trim():UUID.randomUUID().toString();
        String administrator=String.valueOf(context.sender().getUuid());
        return SocialUiBootstrap.supply(services,()->{
            if(!hasPermission(context.sender()))throw new IllegalArgumentException("You do not have permission to grant currency.");
            AccountService.Account account=services.batch(()->{
                if(input.equalsIgnoreCase("me"))return services.accounts().find(context.sender().getUuid()).orElseThrow(()->new IllegalArgumentException("Use a player name from the server console."));
                UUID id=null;try{id=UUID.fromString(input);}catch(IllegalArgumentException ignored){}
                return (id==null?services.accounts().findByName(input):services.accounts().find(id))
                    .orElseThrow(()->new IllegalArgumentException("That player must join Eternia before receiving currency."));
            });
            var result=services.currencyGrants().grant(administrator,account.id(),currency,quantity,receipt);
            String label=currency==CurrencyGrantService.Currency.CROWNS?"Crowns":"Coins";
            plugin.getLogger().atInfo().log("Currency grant %s: administrator=%s recipient=%s currency=%s amount=%s added=%s",receipt,administrator,account.id(),currency,quantity,result.added());
            context.sendMessage(Message.raw((result.added()?"Added "+CurrencyUi.amount(quantity)+" "+label+" to "+account.displayName()+".":"This grant was already applied.")
                +" Balance: "+CurrencyUi.amount(result.balance())+" "+label+". Reference: "+receipt));
            return (Void)null;
        }).exceptionally(failure->{
            Throwable cause=failure;while((cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException)&&cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof DomainException domain&&domain.code()!=DomainException.Code.STORAGE_UNAVAILABLE||cause instanceof IllegalArgumentException)
                context.sendMessage(Message.raw("Currency grant rejected: "+cause.getMessage()));
            else {
                plugin.getLogger().atWarning().withCause(failure).log("Currency grant could not be confirmed: %s",receipt);
                context.sendMessage(Message.raw("The grant could not be confirmed. Retry with the same player and amount using --receipt="+receipt+" to avoid adding it twice."));
            }
            return null;
        });
    }
}
