package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.DomainException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.HexFormat;
import javax.sql.DataSource;

public final class DatabaseMigrations {
    private DatabaseMigrations() {}
    public static void migrate(DataSource source) {
        String resource="/Server/EterniaMod/Database/V001__durable_domain_records.sql";
        String sql;
        try(var input=DatabaseMigrations.class.getResourceAsStream(resource)) {
            if(input==null) throw new IOException("Migration resource is missing");
            sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new DomainException(DomainException.Code.STORAGE_UNAVAILABLE,"Cannot read database migration",e); }
        // Git checkouts may use LF or CRLF. New records use LF; accept the old
        // raw-byte CRLF checksum too, without accepting any other SQL edits.
        sql=sql.replace("\r\n","\n");
        String hash=checksum(sql);
        String legacyWindowsHash=checksum(sql.replace("\n","\r\n"));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try(var lock=connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) { lock.setLong(1,JdbcStore.LOCK_ID);lock.execute(); }
                try(var statement=connection.createStatement()) { statement.execute("CREATE TABLE IF NOT EXISTS eternia_schema_migrations(version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)"); }
                boolean applied=false;
                try(var q=connection.prepareStatement("SELECT checksum FROM eternia_schema_migrations WHERE version=1");var rs=q.executeQuery()) {
                    if(rs.next()) {
                        applied=true;
                        String storedHash=rs.getString(1);
                        if(!hash.equals(storedHash)&&!legacyWindowsHash.equals(storedHash))
                            throw new DomainException(DomainException.Code.CONFLICT,
                                "Applied migration checksum changed for "+resource+": stored="+storedHash
                                +", expected LF="+hash+", legacy CRLF="+legacyWindowsHash
                                +". Restore the original migration and put schema changes in a new migration; do not reset migration history.");
                    }
                }
                if(!applied) {
                    // Migration files deliberately contain simple SQL statements, without functions or quoted semicolons.
                    for(String statement:sql.split(";")) if(!statement.isBlank()) try(var q=connection.createStatement()) { q.execute(statement); }
                    try(var q=connection.prepareStatement("INSERT INTO eternia_schema_migrations(version,checksum) VALUES(1,?)")) { q.setString(1,hash);q.executeUpdate(); }
                }
                connection.commit();
            } catch(SQLException | RuntimeException e) { try { connection.rollback(); } catch(SQLException rollback) { e.addSuppressed(rollback); } throw e; }
        } catch(SQLException e) { throw JdbcStore.storage(e); }
    }
    private static String checksum(String sql) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
