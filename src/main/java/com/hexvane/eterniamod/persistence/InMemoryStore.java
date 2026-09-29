package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.DomainException;
import java.util.*;
import java.util.function.Function;

/** Deterministic development/test store only. Never a fallback after a production database failure. */
public final class InMemoryStore implements TransactionalStore {
    private Map<String, Row> records = new TreeMap<>();
    private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
    @Override public synchronized <T> T transaction(Function<Transaction, T> work) {
        if (active.get()) throw new IllegalStateException("Nested transactions are unsupported");
        active.set(true);
        var copy = new TreeMap<>(records);
        class Tx implements Transaction {
            boolean open = true;
            void check() { if (!open) throw new IllegalStateException("Transaction has ended"); }
            public Optional<Row> find(String ns, String key) { check(); return Optional.ofNullable(copy.get(ns + "\0" + key)); }
            public List<Row> scan(String ns) { check(); return copy.values().stream().filter(r -> r.namespace().equals(ns)).toList(); }
            public Row save(String ns, String key, long revision, Map<String,String> fields) {
                check(); RecordCodec.validateKey(ns, key); RecordCodec.encode(fields);
                Row old = copy.get(ns + "\0" + key);
                if ((old == null ? 0 : old.revision()) != revision)
                    throw new DomainException(DomainException.Code.CONFLICT, "Record revision changed");
                Row row = new Row(ns, key, Math.addExact(revision, 1), fields);
                copy.put(ns + "\0" + key, row); return row;
            }
        }
        var tx = new Tx();
        try { T result = work.apply(tx); records = copy; return result; }
        finally { tx.open = false; active.remove(); }
    }
}
