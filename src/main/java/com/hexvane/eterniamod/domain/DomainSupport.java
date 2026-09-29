package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.hexvane.eterniamod.domain.DomainException.Code.*;

abstract class DomainSupport {
    final TransactionalStore store; final Clock clock; final Supplier<UUID> ids;
    DomainSupport(TransactionalStore store,Clock clock,Supplier<UUID> ids) {
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);this.ids=Objects.requireNonNull(ids);
    }
    static void require(boolean condition,DomainException.Code code,String message) { if(!condition) throw new DomainException(code,message); }
    static String text(String value,String name,int max) { require(value!=null&&!value.isBlank()&&value.length()<=max,INVALID_INPUT,"Invalid "+name);return value; }
    static String content(String value) { text(value,"content id",160);require(value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"),INVALID_INPUT,"Content must be namespaced");return value; }
    static long positive(long amount) { require(amount>0,INVALID_INPUT,"Quantity must be positive");return amount; }
    static Map<String,String> fields(Object... values) {
        var map=new TreeMap<String,String>(); for(int i=0;i<values.length;i+=2)map.put(values[i].toString(),values[i+1]==null?"":values[i+1].toString());return map;
    }
    static Map<String,String> changed(TransactionalStore.Row row,Object... values) { var map=new TreeMap<>(row.fields());map.putAll(fields(values));return map; }
    static TransactionalStore.Row row(TransactionalStore.Transaction tx,String ns,String key) { return tx.find(ns,key).orElseThrow(()->new DomainException(NOT_FOUND,ns+" record not found")); }
    static TransactionalStore.Row save(TransactionalStore.Transaction tx,TransactionalStore.Row row,Object... values) { return tx.save(row.namespace(),row.key(),row.revision(),changed(row,values)); }
    static String key(String... values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:values) { byte[] b=value.getBytes(StandardCharsets.UTF_8);digest.update(Integer.toString(b.length).getBytes(StandardCharsets.UTF_8));digest.update((byte)':');digest.update(b); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static boolean receipt(TransactionalStore.Transaction tx,String namespace,String receipt,Map<String,String> data) {
        text(receipt,"receipt",240); String k=key(receipt);var previous=tx.find(namespace,k);
        if(previous.isPresent()) { require(previous.get().fields().equals(data),CONFLICT,"Receipt was reused with different input");return false; }
        tx.save(namespace,k,0,data);return true;
    }
    static boolean future(String instant,Instant now) { return instant.isEmpty() || Instant.parse(instant).isAfter(now); }
}
