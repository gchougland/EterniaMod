package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CurrencyGrantServiceTest {
    @Test void grantsBothCurrenciesAndKeepsAnAuditWithoutDuplicatingRetries() {
        var store=new InMemoryStore();var s=new EterniaServices(store);var player=UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(player,"AdminTest");
        assertTrue(s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,250,"crowns-test").added());
        assertTrue(s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.COINS,900,"coins-test").added());
        assertFalse(s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,250,"crowns-test").added());
        assertEquals(250,s.premium().balance(player).available());
        assertEquals(900,s.economy().balance(Owner.player(player)).available());
        var audit=store.transaction(tx->tx.scan("admin_currency_audit"));assertEquals(2,audit.size());
        assertTrue(audit.stream().allMatch(row->row.value("administrator").equals("admin")&&row.value("recipient").equals(player.toString())&&!row.value("at").isBlank()));
        assertThrows(DomainException.class,()->s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,300,"crowns-test"));
        assertThrows(DomainException.class,()->s.currencyGrants().grant("different-admin",player,CurrencyGrantService.Currency.CROWNS,250,"crowns-test"));
        assertThrows(DomainException.class,()->s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.COINS,250,"crowns-test"));
    }
    @Test void invalidAmountsAndUnknownPlayersNeverCreateGrants() {
        var store=new InMemoryStore();var s=new EterniaServices(store);var player=UUID.randomUUID();
        for(long amount:new long[]{0,-1})assertThrows(DomainException.class,()->s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.COINS,amount,"invalid"));
        assertThrows(DomainException.class,()->s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,100,"unknown"));
        assertTrue(store.transaction(tx->tx.scan("admin_currency_audit")).isEmpty());
        assertTrue(store.transaction(tx->tx.scan("admin_currency_receipt")).isEmpty());
    }
    @Test void overflowRollsBackAuditAndAllowsSafeRetry() {
        var store=new InMemoryStore();var s=new EterniaServices(store);var player=UUID.randomUUID();
        s.accounts().recordAuthenticatedLogin(player,"OverflowTest");
        s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.COINS,Long.MAX_VALUE,"full");
        assertThrows(ArithmeticException.class,()->s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.COINS,1,"overflow"));
        assertEquals(1,store.transaction(tx->tx.scan("admin_currency_audit")).size());
        assertEquals(1,store.transaction(tx->tx.scan("admin_currency_receipt")).size());
        assertEquals(Long.MAX_VALUE,s.economy().balance(Owner.player(player)).available());
    }
    @Test void newPlayersReceiveCoinsOnlyAndRenamingOrReconnectingDoesNotRepeatGift() {
        var s=new EterniaServices(new InMemoryStore());var player=UUID.randomUUID();
        assertTrue(s.welcomePlayer(player,"NewPlayer"));
        assertFalse(s.welcomePlayer(player,"NewName"));
        assertEquals(500,s.economy().balance(Owner.player(player)).available());
        assertEquals(0,s.premium().balance(player).available());
        var existing=UUID.randomUUID();s.accounts().recordAuthenticatedLogin(existing,"ExistingPlayer");
        assertFalse(s.welcomePlayer(existing,"ExistingPlayer"));
        assertEquals(0,s.economy().balance(Owner.player(existing)).available());
    }
    @Test void failedFirstLoginRollsBackAccountAndGiftTogether() {
        var s=new EterniaServices(new InMemoryStore());var player=UUID.randomUUID();
        assertThrows(IllegalStateException.class,()->s.batch(()->{s.welcomePlayer(player,"NewPlayer");throw new IllegalStateException("Interrupted login");}));
        assertTrue(s.accounts().find(player).isEmpty());
        assertEquals(0,s.economy().balance(Owner.player(player)).available());
        assertTrue(s.welcomePlayer(player,"NewPlayer"));
        assertEquals(500,s.economy().balance(Owner.player(player)).available());
    }
    @Test void restartRetainsGiftsAndGrantReceipts(@TempDir Path data) {
        UUID player=UUID.randomUUID();
        try(var store=new LocalFileStore(data.resolve("test.bin"))) {
            var s=new EterniaServices(store);s.welcomePlayer(player,"SavedPlayer");
            s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,100,"saved");
        }
        try(var store=new LocalFileStore(data.resolve("test.bin"))) {
            var s=new EterniaServices(store);assertFalse(s.welcomePlayer(player,"SavedPlayer"));
            assertFalse(s.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,100,"saved").added());
            assertEquals(500,s.economy().balance(Owner.player(player)).available());
            assertEquals(100,s.premium().balance(player).available());
        }
    }
}
