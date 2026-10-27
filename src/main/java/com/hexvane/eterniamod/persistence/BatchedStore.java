package com.hexvane.eterniamod.persistence;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.time.Duration;
import java.util.concurrent.*;

/** Short domain-only transactions with memoized reads, plus explicit bounded display projections. */
public final class BatchedStore implements TransactionalStore {
    private final TransactionalStore delegate;
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    private record Cached(Object value,long expires,Map<String,Long> versions){}
    private static final class ReadCapture { final Set<String> namespaces=new HashSet<>(); }
    private final ThreadLocal<ReadCapture> reading=new ThreadLocal<>();
    private final Map<String,Long> versions=new HashMap<>();
    private final LinkedHashMap<Object,Cached> views=new LinkedHashMap<>(16,.75f,true);
    private final ConcurrentHashMap<Object,CompletableFuture<Object>> pending=new ConcurrentHashMap<>();
    private long generation;
    private final Map<String,CommittedRows> committed = new ConcurrentHashMap<>();

    /** Single-authority world protection index. Seed at startup, then publish only committed writes.
     * Unlike display read views, this has no TTL, background refresh, or database access on a hit.
     * External writers to eternia_records are unsupported by the store's single-server contract. */
    public synchronized Supplier<List<Row>> committedRows(String namespace) {
        if(current.get()!=null)throw new IllegalStateException("Protection indexes must initialize outside a transaction");
        var existing=committed.get(namespace);if(existing!=null)return existing::snapshot;
        var index=new CommittedRows();committed.put(namespace,index);
        try { index.merge(transaction(tx->tx.scan(namespace)));return index::snapshot; }
        catch(RuntimeException|Error failure){committed.remove(namespace,index);throw failure;}
    }
    private static final class CommittedRows {
        private final Map<String,Row> byKey=new TreeMap<>();
        private volatile List<Row> rows=List.of();
        synchronized void merge(Collection<Row> updates) {
            for(var row:updates)byKey.merge(row.key(),row,(before,next)->before.revision()>next.revision()?before:next);
            rows=List.copyOf(byKey.values());
        }
        List<Row> snapshot(){return rows;}
    }

    /** Display projections only. Writes are forbidden; authoritative actions use batch/transaction.
     * Relevant local writes invalidate immediately; bounded TTL also refreshes external changes and expiry. */
    @SuppressWarnings("unchecked")
    public <T>T readView(Object key,Duration ttl,Supplier<T> work){
        Objects.requireNonNull(key);long duration=ttl.toNanos();if(duration<=0||duration>Duration.ofSeconds(30).toNanos())throw new IllegalArgumentException("Read view lifetime must be within 30 seconds");
        if(current.get()!=null)return work.get();
        if(reading.get()!=null)throw new IllegalStateException("Nested cached projections are unsupported");
        synchronized(views){var hit=views.get(key);if(hit!=null&&System.nanoTime()<hit.expires()&&hit.versions().entrySet().stream().allMatch(e->Objects.equals(versions.getOrDefault(e.getKey(),0L),e.getValue())))return (T)hit.value();views.remove(key);}
        var future=new CompletableFuture<Object>();var existing=pending.putIfAbsent(key,future);
        if(existing!=null){try{return (T)existing.join();}catch(CompletionException e){if(e.getCause() instanceof RuntimeException failure)throw failure;if(e.getCause() instanceof Error failure)throw failure;throw e;}}
        try{
            long before;synchronized(views){before=generation;}
            var capture=new ReadCapture();reading.set(capture);T value;
            try{value=batch(work);}finally{reading.remove();}
            synchronized(views){if(before==generation){var dependencies=new HashMap<String,Long>();capture.namespaces.forEach(ns->dependencies.put(ns,versions.getOrDefault(ns,0L)));views.put(key,new Cached(value,System.nanoTime()+duration,Map.copyOf(dependencies)));while(views.size()>512)views.remove(views.keySet().iterator().next());}}
            future.complete(value);return value;
        }catch(RuntimeException|Error e){future.completeExceptionally(e);throw e;}finally{pending.remove(key,future);}
    }

    public BatchedStore(TransactionalStore delegate) { this.delegate = Objects.requireNonNull(delegate); }

    /** Related service calls share one commit and memoize repeated reads, including empty results.
     * Callers must not do world, network or other external work inside this callback.
     * Any failed service call aborts the batch, even when its exception is caught by the caller.
     */
    public <T> T batch(Supplier<T> work) {
        Scope existing = current.get();
        if (existing != null) {
            if (existing.inCallback) throw new IllegalStateException("Nested transactions are unsupported");
            try { return work.get(); }
            catch (RuntimeException | Error failure) { existing.failed = true; throw failure; }
        }
        Scope[] completed=new Scope[1];
        T result=delegate.transaction(tx -> {
            Scope scope = new Scope(tx,reading.get()!=null);completed[0]=scope;
            current.set(scope);
            try {
                T value = work.get();
                if (scope.failed) throw new IllegalStateException("A failed service call aborted this batch");
                return value;
            } finally { current.remove(); }
        });
        var scope=completed[0];var capture=reading.get();if(capture!=null)capture.namespaces.addAll(scope.reads);
        if(!scope.writes.isEmpty())synchronized(views){generation++;scope.writes.forEach(ns->versions.merge(ns,1L,Long::sum));}
        scope.savedRows.forEach((namespace,rows)->{var index=committed.get(namespace);if(index!=null)index.merge(rows.values());});
        return result;
    }

    @Override public <T> T transaction(Function<Transaction,T> work) {
        Scope scope = current.get();
        if (scope == null) return batch(() -> transaction(work));
        if (scope.inCallback) throw new IllegalStateException("Nested transactions are unsupported");
        if (scope.failed) throw new IllegalStateException("This batch has failed");
        scope.inCallback = true;
        // Each service callback still gets a transaction that cannot escape its lifetime.
        class View implements Transaction {
            boolean open = true;
            void check() { if (!open) throw new IllegalStateException("Transaction has ended"); }
            public Optional<Row> find(String ns, String key) { check(); return scope.find(ns,key); }
            public List<Row> scan(String ns) { check(); return scope.scan(ns); }
            public Map<String,Row> findAll(String ns,Collection<String> keys) { check();return scope.findAll(ns,keys); }
            public Row save(String ns,String key,long revision,Map<String,String> fields) {
                check(); return scope.save(ns,key,revision,fields);
            }
        }
        var view = new View();
        try { return work.apply(view); }
        catch (RuntimeException | Error failure) { scope.failed = true; throw failure; }
        finally { view.open = false; scope.inCallback = false; }
    }

    private static final class Scope {
        private final Transaction tx;
        private final Map<String,Map<String,Optional<Row>>> finds = new HashMap<>();
        private final Map<String,SortedMap<String,Row>> scans = new HashMap<>();
        final Set<String> reads=new HashSet<>(),writes=new HashSet<>();final boolean readOnly;
        final Map<String,Map<String,Row>> savedRows=new HashMap<>();
        boolean inCallback, failed;
        Scope(Transaction tx,boolean readOnly) { this.tx = tx;this.readOnly=readOnly; }
        Optional<Row> find(String ns,String key) {
            RecordCodec.validateKey(ns,key);reads.add(ns);
            if (scans.containsKey(ns)) return Optional.ofNullable(scans.get(ns).get(key));
            return finds.computeIfAbsent(ns,n -> new HashMap<>()).computeIfAbsent(key,k -> tx.find(ns,k));
        }
        List<Row> scan(String ns) {
            RecordCodec.validateKey(ns,"scan");reads.add(ns);
            var rows = scans.computeIfAbsent(ns,n -> {
                var result = new TreeMap<String,Row>();
                tx.scan(n).forEach(row -> result.put(row.key(),row));
                return result;
            });
            return List.copyOf(rows.values());
        }
        Map<String,Row> findAll(String ns,Collection<String> keys) {
            RecordCodec.validateKey(ns,"findAll");reads.add(ns);keys.forEach(key->RecordCodec.validateKey(ns,key));
            var known=finds.computeIfAbsent(ns,n->new HashMap<>());
            if(!scans.containsKey(ns)) {
                var missing=keys.stream().distinct().filter(key->!known.containsKey(key)).toList();
                if(!missing.isEmpty()) {
                    var fetched=tx.findAll(ns,missing);
                    missing.forEach(key->known.put(key,Optional.ofNullable(fetched.get(key))));
                }
            }
            var result=new HashMap<String,Row>();
            keys.forEach(key->find(ns,key).ifPresent(row->result.put(key,row)));
            return Map.copyOf(result);
        }
        Row save(String ns,String key,long revision,Map<String,String> fields) {
            if(readOnly)throw new IllegalStateException("Cached read views cannot write records");
            Row saved = tx.save(ns,key,revision,fields);
            savedRows.computeIfAbsent(ns,ignored->new HashMap<>()).put(key,saved);
            writes.add(ns);finds.computeIfAbsent(ns,n -> new HashMap<>()).put(key,Optional.of(saved));
            if (scans.containsKey(ns)) scans.get(ns).put(key,saved);
            return saved;
        }
    }
}
