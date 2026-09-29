package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.*;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/** Opt-in against an isolated PostgreSQL test database. Never silently substitutes an in-memory DB. */
class JdbcStoreIntegrationTest {
    private DataSource source;

    @BeforeEach void isolatedDatabaseOnly() {
        String url=System.getenv("ETERNIA_TEST_DATABASE_URL");
        assumeTrue(url!=null&&!url.isBlank(),"No isolated PostgreSQL test database configured");
        assumeTrue("true".equals(System.getenv("ETERNIA_TEST_DATABASE_ALLOW_WRITES")),
            "Explicit ETERNIA_TEST_DATABASE_ALLOW_WRITES=true required for isolated test database");
        source=new DriverDataSource(url,System.getenv("ETERNIA_TEST_DATABASE_USER"),System.getenv("ETERNIA_TEST_DATABASE_PASSWORD"));
        new JdbcStore(source).migrate();
    }

    @Test void migrationRestartAndRollbackUseRealPostgres() {
        var store=new JdbcStore(source);store.migrate();store.migrate();
        UUID player=UUID.randomUUID();var first=new EterniaServices(store);
        first.accounts().recordAuthenticatedLogin(player,"DatabaseTest");
        first.economy().credit(Owner.player(player),20,"test:"+player);
        assertThrows(IllegalArgumentException.class,()->store.transaction(tx->{
            tx.save("test_rollback",player.toString(),0,Map.of("test","value"));
            throw new IllegalArgumentException("rollback");
        }));
        var reconnected=new JdbcStore(source);
        boolean rolledBack=reconnected.transaction(tx->tx.find("test_rollback",player.toString()).isEmpty());
        assertTrue(rolledBack);
        assertEquals(20,new EterniaServices(reconnected).economy().balance(Owner.player(player)).available());
    }

    @Test void independentConnectionsSerializeConcurrentCreditsAndDeduplicateReceipts() throws Exception {
        Owner player=Owner.player(UUID.randomUUID());
        var services=List.of(new EterniaServices(new JdbcStore(source)),new EterniaServices(new JdbcStore(source)));
        String shared="pg-concurrent:"+UUID.randomUUID();
        var ready=new CountDownLatch(24);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<24;i++) {
                final int index=i;
                futures.add(executor.submit(()->{
                    ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));
                    String receipt=index<12?shared:shared+":"+index;
                    return services.get(index%2).economy().credit(player,7,receipt);
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            int credited=0;
            for(var result:futures)if(result.get(30,TimeUnit.SECONDS))credited++;
            assertEquals(13,credited,"One shared receipt and twelve distinct receipts may mint coins");
        }
        var restarted=new EterniaServices(new JdbcStore(source));
        assertEquals(91,restarted.economy().balance(player).available());
        assertFalse(restarted.economy().credit(player,7,shared),"Receipt deduplication survives reconnect");
    }

    @Test void rejectedTransferRollsBackReceiptAndBothWallets() {
        var services=new EterniaServices(new JdbcStore(source));
        Owner sender=Owner.player(UUID.randomUUID()),recipient=Owner.player(UUID.randomUUID());
        String transfer="pg-transfer:"+UUID.randomUUID();
        services.economy().credit(sender,4,transfer+":initial");
        var failure=assertThrows(DomainException.class,()->services.economy().transfer(sender,recipient,9,transfer));
        assertEquals(DomainException.Code.INSUFFICIENT_BALANCE,failure.code());
        assertEquals(4,services.economy().balance(sender).available());
        assertEquals(0,services.economy().balance(recipient).available());
        services.economy().credit(sender,5,transfer+":topup");
        assertTrue(services.economy().transfer(sender,recipient,9,transfer),"The failed transaction must not consume its receipt");
        assertFalse(services.economy().transfer(sender,recipient,9,transfer));
        var restarted=new EterniaServices(new JdbcStore(source));
        assertEquals(0,restarted.economy().balance(sender).available());
        assertEquals(9,restarted.economy().balance(recipient).available());
    }

    @Test void staleRevisionRollsBackEarlierWritesAndTransactionCannotEscape() {
        var store=new JdbcStore(source);String key=UUID.randomUUID().toString();
        var original=store.transaction(tx->tx.save("test_revision",key,0,Map.of("value","original")));
        store.transaction(tx->tx.save("test_revision",key,original.revision(),Map.of("value","current")));
        var failure=assertThrows(DomainException.class,()->store.transaction(tx->{
            tx.save("test_revision",key+":rollback",0,Map.of("value","must disappear"));
            return tx.save("test_revision",key,original.revision(),Map.of("value","stale"));
        }));
        assertEquals(DomainException.Code.CONFLICT,failure.code());
        store.transaction(tx->{
            assertTrue(tx.find("test_revision",key+":rollback").isEmpty());
            assertEquals("current",tx.find("test_revision",key).orElseThrow().value("value"));
            return null;
        });
        var escaped=store.transaction(tx->tx);
        assertThrows(IllegalStateException.class,()->escaped.find("test_revision",key));
    }
    @Test void crownPurchaseAndRefundSurviveReconnect() {
        UUID player=UUID.randomUUID();String suffix=player.toString();var first=new EterniaServices(new JdbcStore(source));first.accounts().recordAuthenticatedLogin(player,"CrownDatabaseTest");
        first.commerce().register(new CommerceService.Product("pg-"+suffix,1,"500 Crowns",List.of(new CommerceService.Benefit(PremiumService.CROWNS,OwnershipService.Kind.QUANTITY,500,false)),false));
        var payment=first.commerce().fulfill(new CommerceService.VerifiedPurchase("tebex",suffix,"crowns","pg-"+suffix,1,Owner.player(player),1,"",null,null));
        String item="eternia:store/test_"+suffix;first.premium().register(new PremiumService.Item(item,1,"Test lantern","A durable test purchase.","Furnishings",300,List.of(new CommerceService.Benefit("eternia:test/lantern",OwnershipService.Kind.QUANTITY,1,false))));
        first.premium().purchase(player,item,1,300,"buy");var restarted=new EterniaServices(new JdbcStore(source));restarted.premium().purchase(player,item,1,300,"buy");assertEquals(200,restarted.premium().balance(player).available());assertEquals(1,restarted.ownership().available(Owner.player(player),"eternia:test/lantern"));
        restarted.commerce().reverse(payment.id(),"refund:"+suffix,"Test refund");var again=new EterniaServices(new JdbcStore(source));assertEquals(new PremiumService.Balance(0,300),again.premium().balance(player));
    }
    private record DriverDataSource(String url,String user,String password)implements DataSource{
        public Connection getConnection()throws SQLException{return user==null?DriverManager.getConnection(url):DriverManager.getConnection(url,user,password);}
        public Connection getConnection(String u,String p)throws SQLException{return DriverManager.getConnection(url,u,p);}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter out){}public void setLoginTimeout(int seconds)throws SQLException{DriverManager.setLoginTimeout(seconds);}public int getLoginTimeout(){return DriverManager.getLoginTimeout();}public Logger getParentLogger(){return Logger.getLogger("eternia-test");}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLException("Not a wrapper");}public boolean isWrapperFor(Class<?> type){return false;}
    }
}
