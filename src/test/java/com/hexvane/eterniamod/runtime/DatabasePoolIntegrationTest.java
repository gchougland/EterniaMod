package com.hexvane.eterniamod.runtime;

import com.hexvane.eterniamod.domain.*;
import com.hexvane.eterniamod.persistence.JdbcStore;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class DatabasePoolIntegrationTest {
    private RuntimeConfig config() {
        String url=System.getenv("ETERNIA_TEST_DATABASE_URL");
        assumeTrue(url!=null&&!url.isBlank()&&"true".equals(System.getenv("ETERNIA_TEST_DATABASE_ALLOW_WRITES")),"Isolated PostgreSQL required");
        return RuntimeConfig.from(Map.of("ETERNIA_DATABASE_URL",url,
            "ETERNIA_DATABASE_USER",System.getenv("ETERNIA_TEST_DATABASE_USER"),
            "ETERNIA_DATABASE_PASSWORD",System.getenv("ETERNIA_TEST_DATABASE_PASSWORD")));
    }
    private static int backend(Connection connection)throws Exception {
        try(var stmt=connection.createStatement();var rows=stmt.executeQuery("SELECT pg_backend_pid()")) {rows.next();return rows.getInt(1);}
    }
    @Test void simultaneousProductionLoginsAndRetriedGrantsOnlyPayOnce()throws Exception {
        try(var pool=DatabasePool.open(new DriverDataSource(config()));var store=new JdbcStore(pool)) {
            store.migrate();var services=new EterniaServices(store);var player=UUID.randomUUID();
            try(var workers=Executors.newFixedThreadPool(4)) {
                var logins=new ArrayList<Future<Boolean>>();
                for(int i=0;i<8;i++)logins.add(workers.submit(()->services.welcomePlayer(player,"NewDatabasePlayer")));
                int first=0;for(var login:logins)if(login.get(30,TimeUnit.SECONDS))first++;
                assertEquals(1,first);
                assertEquals(500,services.economy().balance(Owner.player(player)).available());
                assertEquals(0,services.premium().balance(player).available());
                var grants=new ArrayList<Future<CurrencyGrantService.Result>>();
                for(int i=0;i<8;i++)grants.add(workers.submit(()->services.currencyGrants().grant("admin",player,CurrencyGrantService.Currency.CROWNS,200,"test:"+player)));
                int paid=0;for(var grant:grants)if(grant.get(30,TimeUnit.SECONDS).added())paid++;
                assertEquals(1,paid);
            }
            var restarted=new EterniaServices(store);assertFalse(restarted.welcomePlayer(player,"RenamedDatabasePlayer"));
            assertEquals(500,restarted.economy().balance(Owner.player(player)).available());
            assertEquals(200,restarted.premium().balance(player).available());
        }
    }
    @Test void reusesPhysicalConnectionResetsStateAndClosesWithStore()throws Exception {
        var pool=DatabasePool.open(new DriverDataSource(config()));
        try(var store=new JdbcStore(pool)) {
            int first;
            try(var connection=pool.getConnection()) {
                first=backend(connection);connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try(var stmt=connection.createStatement()){stmt.execute("CREATE TEMP TABLE eternia_pool_rollback (id int)");}
            }
            try(var connection=pool.getConnection()) {
                assertEquals(first,backend(connection),"Closing a lease should reuse its PostgreSQL session");
                assertTrue(connection.getAutoCommit());assertEquals(Connection.TRANSACTION_READ_COMMITTED,connection.getTransactionIsolation());
                try(var stmt=connection.createStatement();var rows=stmt.executeQuery("SELECT to_regclass('pg_temp.eternia_pool_rollback')")) {rows.next();assertNull(rows.getString(1),"Uncommitted work must roll back before reuse");}
            }
        }
        assertTrue(pool.isClosed());assertThrows(java.sql.SQLException.class,pool::getConnection);
    }
    @Test void concurrentPooledBatchesKeepCoinsAtomicAndRecoverAfterRollback()throws Exception {
        try(var pool=DatabasePool.open(new DriverDataSource(config()));var store=new JdbcStore(pool)) {
            store.migrate();var services=new EterniaServices(store);var actor=UUID.randomUUID();var owner=Owner.player(actor);
            services.accounts().recordAuthenticatedLogin(actor,"PoolTest");
            store.transaction(tx->{
                String ns="pool_bulk_test";String key=actor.toString();
                tx.save(ns,key,0,Map.of("value","present"));
                assertEquals(Set.of(key),tx.findAll(ns,List.of(key,"missing:"+key,key)).keySet());
                assertTrue(tx.findAll(ns,List.of()).isEmpty());
                return null;
            });
            try(var workers=Executors.newFixedThreadPool(4)) {
                var jobs=new ArrayList<Future<?>>();
                for(int i=0;i<12;i++){int id=i;jobs.add(workers.submit(()->services.batch(()->{
                    services.economy().credit(owner,10,"pool:"+actor+":"+id);
                    return services.economy().balance(owner);
                })));}
                for(var job:jobs)job.get(30,TimeUnit.SECONDS);
            }
            assertEquals(120,services.economy().balance(owner).available());
            assertThrows(IllegalArgumentException.class,()->services.batch(()->{
                services.economy().credit(owner,100,"pool:rollback:"+actor);throw new IllegalArgumentException("Rollback");
            }));
            assertEquals(120,services.batch(()->services.economy().balance(owner)).available());
        }
    }
}
