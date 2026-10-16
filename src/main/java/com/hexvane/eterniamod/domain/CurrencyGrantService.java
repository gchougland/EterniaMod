package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Only the permission-checked administrator command may invoke grants. Never expose to the web bridge. */
public final class CurrencyGrantService extends DomainSupport {
    public enum Currency { CROWNS, COINS }
    public record Result(boolean added,long balance) {}
    private final PremiumService premium;
    private final EconomyService economy;
    CurrencyGrantService(TransactionalStore store,Clock clock,Supplier<UUID> ids,PremiumService premium,EconomyService economy) {
        super(store,clock,ids);this.premium=premium;this.economy=economy;
    }
    public Result grant(String administrator,UUID recipient,Currency currency,long amount,String reference) {
        text(administrator,"administrator",100);Objects.requireNonNull(recipient);Objects.requireNonNull(currency);
        positive(amount);text(reference,"grant reference",160);
        return store.transaction(tx->{
            row(tx,"account",recipient.toString());
            var input=fields("administrator",administrator,"recipient",recipient,"currency",currency,"amount",amount);
            boolean fresh=receipt(tx,"admin_currency_receipt",reference,input);
            if(fresh) {
                String source="admin-currency:"+reference;
                if(currency==Currency.CROWNS)premium.creditIn(tx,recipient,amount,source);
                else economy.creditIn(tx,Owner.player(recipient),amount,source);
                input.put("at",clock.instant().toString());
                tx.save("admin_currency_audit",key(reference),0,input);
            }
            long balance=currency==Currency.CROWNS?Math.max(0,tx.find("premium_wallet",recipient.toString()).orElseThrow().number("balance"))
                :economy.balanceIn(tx,Owner.player(recipient)).available();
            return new Result(fresh,balance);
        });
    }
}
