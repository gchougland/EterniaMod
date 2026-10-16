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
        /** Fetch a known set of keys in one database round trip when supported. Missing keys are omitted. */
        default Map<String,Row> findAll(String namespace, java.util.Collection<String> keys) {
            var rows=new java.util.HashMap<String,Row>();
            for(String key:keys) find(namespace,key).ifPresent(row->rows.put(key,row));
            return Map.copyOf(rows);
        }
        List<Row> scan(String namespace);
        /** expectedRevision 0 inserts. Existing records require their exact current revision. */
        Row save(String namespace, String key, long expectedRevision, Map<String, String> fields);
    }
    <T> T transaction(Function<Transaction, T> work);
}
