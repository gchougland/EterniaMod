package com.hexvane.eterniamod.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** A transaction is serialized with other transactions, rolls back on failure, and must not escape its callback.
 * Callbacks perform durable-domain work only; never call the game, network or another store transaction inside one.
 */
public interface TransactionalStore {
    record Row(String namespace, String key, long revision, Map<String, String> fields) {
        public Row { fields = Map.copyOf(fields); }
        public String value(String name) { return fields.getOrDefault(name, ""); }
        public long number(String name) { return Long.parseLong(value(name)); }
    }
    interface Transaction {
        Optional<Row> find(String namespace, String key);
        List<Row> scan(String namespace);
        /** expectedRevision 0 inserts. Existing records require their exact current revision. */
        Row save(String namespace, String key, long expectedRevision, Map<String, String> fields);
    }
    <T> T transaction(Function<Transaction, T> work);
}
