package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.DomainException;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;

/** PostgreSQL implementation. All repository transactions take one advisory lock, intentionally
 * favoring correctness over throughput for the initial single-server deployment. SQL outside this
 * adapter must not mutate eternia_records. Replace with scoped locking only with concurrency tests.
 * No driver is bundled here: bootstrap supplies a PostgreSQL DataSource.
 */
public final class JdbcStore implements TransactionalStore {
    static final long LOCK_ID = 0x455445524e49414cL;
    private final DataSource source;
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
    public JdbcStore(DataSource source) { this.source = Objects.requireNonNull(source); }
    public void migrate() { DatabaseMigrations.migrate(source); }
    @Override public <T> T transaction(Function<Transaction,T> work) {
        if (active.get()) throw new IllegalStateException("Nested transactions are unsupported");
        active.set(true);
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, LOCK_ID); lock.execute();
            }
            var tx = new Tx(connection);
            try { T result = work.apply(tx); connection.commit(); return result; }
            catch (RuntimeException | Error e) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            } finally { tx.open = false; }
        } catch (SQLException e) { throw storage(e); }
        finally { active.remove(); }
    }
    static DomainException storage(SQLException e) {
        return new DomainException(DomainException.Code.STORAGE_UNAVAILABLE, "Authoritative database transaction failed", e);
    }
    private static final class Tx implements Transaction {
        private final Connection connection; private boolean open = true;
        Tx(Connection connection) { this.connection = connection; }
        void check() { if (!open) throw new IllegalStateException("Transaction has ended"); }
        public Optional<Row> find(String ns, String key) {
            check(); RecordCodec.validateKey(ns,key);
            try (var q = connection.prepareStatement("SELECT revision, payload FROM eternia_records WHERE namespace=? AND record_key=?")) {
                q.setString(1,ns); q.setString(2,key);
                try (var rs=q.executeQuery()) { return rs.next() ? Optional.of(new Row(ns,key,rs.getLong(1),RecordCodec.decode(rs.getBytes(2)))) : Optional.empty(); }
            } catch (SQLException e) { throw storage(e); }
        }
        public List<Row> scan(String ns) {
            check(); RecordCodec.validateKey(ns,"scan");
            try (var q = connection.prepareStatement("SELECT record_key, revision, payload FROM eternia_records WHERE namespace=? ORDER BY record_key")) {
                q.setString(1,ns); var rows=new ArrayList<Row>();
                try(var rs=q.executeQuery()) { while(rs.next()) rows.add(new Row(ns,rs.getString(1),rs.getLong(2),RecordCodec.decode(rs.getBytes(3)))); }
                return List.copyOf(rows);
            } catch(SQLException e) { throw storage(e); }
        }
        public Row save(String ns,String key,long revision,Map<String,String> fields) {
            check(); RecordCodec.validateKey(ns,key);
            String sql=revision==0
                ? "INSERT INTO eternia_records(namespace,record_key,revision,payload) VALUES(?,?,1,?) ON CONFLICT DO NOTHING"
                : "UPDATE eternia_records SET revision=revision+1,payload=?,updated_at=CURRENT_TIMESTAMP WHERE namespace=? AND record_key=? AND revision=?";
            try(var q=connection.prepareStatement(sql)) {
                byte[] payload=RecordCodec.encode(fields);
                if(revision==0) { q.setString(1,ns);q.setString(2,key);q.setBytes(3,payload); }
                else { q.setBytes(1,payload);q.setString(2,ns);q.setString(3,key);q.setLong(4,revision); }
                if(q.executeUpdate()!=1) throw new DomainException(DomainException.Code.CONFLICT,"Record revision changed");
                return new Row(ns,key,Math.addExact(revision,1),fields);
            } catch(SQLException e) { throw storage(e); }
        }
    }
}
