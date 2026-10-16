package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.DomainException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationsTest {
    // Historical V001 hashes pin compatibility to the actual shipped SQL.
    private static final String LF="04c35e34880b450605c2603535f2bedcf24a1ceb8e60f3b86485e1387061eb08";
    private static final String CRLF="317fdb7677e19592665d9f7603f90fbbaa3c9d03c1934a8bd0d6d6ff0ad77653";

    @Test void existingUnixMigrationIsNotReapplied() { assertExistingAccepted(LF); }
    @Test void existingWindowsMigrationIsNotReapplied() { assertExistingAccepted(CRLF); }

    private void assertExistingAccepted(String hash) {
        var database=new MigrationDatabase(hash);
        DatabaseMigrations.migrate(database.source());
        DatabaseMigrations.migrate(database.source());
        assertEquals(hash,database.storedHash,"Existing migration history is preserved");
        assertEquals(2,database.commits);
        assertEquals(0,database.rollbacks);
        assertEquals(0,database.inserts);
        assertTrue(database.schemaStatements.isEmpty(),"Startup must not replay V001");
    }

    @Test void freshDatabaseUsesCanonicalChecksumAndRestarts() {
        var database=new MigrationDatabase(null);
        DatabaseMigrations.migrate(database.source());
        assertEquals(LF,database.storedHash);
        assertEquals(1,database.inserts);
        assertEquals(2,database.schemaStatements.size());
        assertTrue(database.schemaStatements.get(0).startsWith("CREATE TABLE eternia_records"));
        assertTrue(database.schemaStatements.get(1).startsWith("CREATE INDEX eternia_records_updated"));
        DatabaseMigrations.migrate(database.source());
        assertEquals(1,database.inserts);
        assertEquals(2,database.schemaStatements.size());
        assertEquals(2,database.commits);
    }

    @Test void changedMigrationStillRollsBackWithDiagnosticHashes() {
        String changed="0".repeat(64);
        var database=new MigrationDatabase(changed);
        var error=assertThrows(DomainException.class,()->DatabaseMigrations.migrate(database.source()));
        assertEquals(DomainException.Code.CONFLICT,error.code());
        for(String detail:List.of("V001__durable_domain_records.sql",changed,LF,CRLF))
            assertTrue(error.getMessage().contains(detail));
        assertEquals(changed,database.storedHash);
        assertEquals(0,database.commits);
        assertEquals(1,database.rollbacks);
        assertEquals(0,database.inserts);
        assertTrue(database.schemaStatements.isEmpty());
    }

    /** Minimal JDBC double; unexpected calls fail instead of silently succeeding. */
    private static final class MigrationDatabase {
        String storedHash;
        int commits,rollbacks,inserts;
        final List<String> schemaStatements=new ArrayList<>();
        MigrationDatabase(String storedHash) { this.storedHash=storedHash; }
        DataSource source() {
            return proxy(DataSource.class,(p,m,a)->switch(m.getName()) {
                case "getConnection" -> connection();
                default -> throw new AssertionError(m);
            });
        }
        Connection connection() {
            return proxy(Connection.class,(p,m,a)->switch(m.getName()) {
                case "setAutoCommit", "close" -> null;
                case "commit" -> { commits++; yield null; }
                case "rollback" -> { rollbacks++; yield null; }
                case "prepareStatement" -> prepared((String)a[0]);
                case "createStatement" -> proxy(Statement.class,(sp,sm,sa)->switch(sm.getName()) {
                    case "close" -> null;
                    case "execute" -> {
                        String sql=((String)sa[0]).strip();
                        if(!sql.startsWith("CREATE TABLE IF NOT EXISTS eternia_schema_migrations")) schemaStatements.add(sql);
                        yield false;
                    }
                    default -> throw new AssertionError(sm);
                });
                default -> throw new AssertionError(m);
            });
        }
        PreparedStatement prepared(String sql) {
            String[] parameter={null};
            return proxy(PreparedStatement.class,(p,m,a)->switch(m.getName()) {
                case "close", "setLong" -> null;
                case "setString" -> { parameter[0]=(String)a[1]; yield null; }
                case "execute" -> {
                    assertTrue(sql.startsWith("SELECT pg_advisory_xact_lock")); yield false;
                }
                case "executeUpdate" -> {
                    assertTrue(sql.startsWith("INSERT INTO eternia_schema_migrations"));
                    storedHash=parameter[0]; inserts++; yield 1;
                }
                case "executeQuery" -> {
                    assertEquals("SELECT checksum FROM eternia_schema_migrations WHERE version=1",sql);
                    boolean[] read={false};
                    yield proxy(ResultSet.class,(rp,rm,ra)->switch(rm.getName()) {
                        case "close" -> null;
                        case "next" -> { boolean exists=!read[0]&&storedHash!=null; read[0]=true; yield exists; }
                        case "getString" -> storedHash;
                        default -> throw new AssertionError(rm);
                    });
                }
                default -> throw new AssertionError(m);
            });
        }
        private static <T> T proxy(Class<T> type,InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
        }
    }
}
