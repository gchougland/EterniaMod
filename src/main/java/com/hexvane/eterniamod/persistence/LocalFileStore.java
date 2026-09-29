package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.DomainException;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Explicit local-development persistence, never a fallback from failed PostgreSQL.
 * Holds an exclusive process lock until closed. Each commit fsyncs a complete versioned snapshot,
 * then atomically replaces the previous file. A failed callback/write leaves committed memory unchanged.
 * Native inventory/world data are still separate and require the durable operation journal.
 */
public final class LocalFileStore implements TransactionalStore,AutoCloseable {
    private static final int MAGIC=0x45544e31, MAX_RECORDS=1_000_000,MAX_RECORD_BYTES=8*1024*1024;
    private final Path file;private final FileChannel lockChannel;private final FileLock lock;
    private Map<String,Row> records;private boolean closed;private boolean active;
    @FunctionalInterface interface CommitHook { void beforeReplace() throws IOException; }
    private final CommitHook hook;
    public LocalFileStore(Path file) { this(file,()->{}); }
    LocalFileStore(Path file,CommitHook hook) {
        this.file=Objects.requireNonNull(file).toAbsolutePath().normalize();this.hook=Objects.requireNonNull(hook);
        FileChannel channel=null;FileLock acquired=null;
        try {
            Files.createDirectories(this.file.getParent());
            channel=FileChannel.open(this.file.resolveSibling(this.file.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            acquired=channel.tryLock();if(acquired==null)throw new IOException("Local data file is already open by another process");
            records=load();lockChannel=channel;lock=acquired;
        } catch(IOException|RuntimeException e) {
            if(acquired!=null)try{acquired.release();}catch(IOException ignored){}
            if(channel!=null)try{channel.close();}catch(IOException ignored){}
            throw new DomainException(DomainException.Code.STORAGE_UNAVAILABLE,"Cannot open local authoritative store",e);
        }
    }
    @Override public synchronized <T>T transaction(Function<Transaction,T> work) {
        if(closed)throw new IllegalStateException("Store is closed");
        if(active)throw new IllegalStateException("Nested transactions are unsupported");active=true;
        var copy=new TreeMap<>(records);
        class Tx implements Transaction {
            boolean open=true;boolean dirty;
            void check(){if(!open)throw new IllegalStateException("Transaction has ended");}
            public Optional<Row> find(String ns,String key){check();RecordCodec.validateKey(ns,key);return Optional.ofNullable(copy.get(ns+"\0"+key));}
            public List<Row> scan(String ns){check();RecordCodec.validateKey(ns,"scan");return copy.values().stream().filter(r->r.namespace().equals(ns)).toList();}
            public Row save(String ns,String key,long revision,Map<String,String> fields){
                check();RecordCodec.validateKey(ns,key);RecordCodec.encode(fields);var old=copy.get(ns+"\0"+key);
                if((old==null?0:old.revision())!=revision)throw new DomainException(DomainException.Code.CONFLICT,"Record revision changed");
                var row=new Row(ns,key,Math.addExact(revision,1),fields);copy.put(ns+"\0"+key,row);dirty=true;return row;
            }
        }
        var tx=new Tx();
        try{T result=work.apply(tx);if(tx.dirty)persist(copy);records=copy;return result;}
        finally{tx.open=false;active=false;}
    }
    private Map<String,Row> load()throws IOException {
        var loaded=new TreeMap<String,Row>();if(!Files.exists(file))return loaded;
        if(Files.size(file)>512L*1024*1024)throw new IOException("Local data snapshot exceeds limit");
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if(in.readInt()!=MAGIC||in.readInt()!=1)throw new IOException("Unsupported local data version");
            int count=in.readInt();if(count<0||count>MAX_RECORDS)throw new IOException("Invalid record count");
            for(int i=0;i<count;i++){
                String ns=in.readUTF(),key=in.readUTF();RecordCodec.validateKey(ns,key);long revision=in.readLong();if(revision<1)throw new IOException("Invalid revision");
                int size=in.readInt();if(size<0||size>MAX_RECORD_BYTES)throw new IOException("Invalid record size");
                byte[] data=in.readNBytes(size);if(data.length!=size)throw new EOFException("Truncated snapshot");
                if(loaded.put(ns+"\0"+key,new Row(ns,key,revision,RecordCodec.decode(data)))!=null)throw new IOException("Duplicate record");
            }
            if(in.read()!=-1)throw new IOException("Unexpected trailing data");
        }return loaded;
    }
    private void persist(Map<String,Row> snapshot) {
        Path temp=null;
        try{
            temp=Files.createTempFile(file.getParent(),file.getFileName()+".",".pending");
            try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                if(snapshot.size()>MAX_RECORDS)throw new IOException("Too many local records");
                out.writeInt(MAGIC);out.writeInt(1);out.writeInt(snapshot.size());
                for(var r:snapshot.values()){out.writeUTF(r.namespace());out.writeUTF(r.key());out.writeLong(r.revision());byte[] data=RecordCodec.encode(r.fields());out.writeInt(data.length);out.write(data);}
            }
            if(Files.size(temp)>512L*1024*1024)throw new IOException("Local snapshot exceeds limit");
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
            hook.beforeReplace();
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            // Atomic replacement is required; no unsafe truncate/copy fallback if this filesystem lacks it.
        }catch(IOException e){throw new DomainException(DomainException.Code.STORAGE_UNAVAILABLE,"Local transaction was not committed",e);}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;try{lock.release();lockChannel.close();}catch(IOException e){throw new DomainException(DomainException.Code.STORAGE_UNAVAILABLE,"Cannot release local data lock",e);}
    }
}
