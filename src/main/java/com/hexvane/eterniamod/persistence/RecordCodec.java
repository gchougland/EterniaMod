package com.hexvane.eterniamod.persistence;

import com.hexvane.eterniamod.domain.DomainException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned, bounded data encoding. Does not use Java object deserialization. */
final class RecordCodec {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    static void validateKey(String namespace, String key) {
        if (namespace == null || !namespace.matches("[a-z][a-z0-9_]{0,63}") || key == null || key.isBlank() || key.length() > 512 || key.indexOf('\0') >= 0)
            throw new DomainException(DomainException.Code.INVALID_INPUT, "Invalid storage key");
    }
    static byte[] encode(Map<String,String> fields) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            if (fields.size() > 10000) throw new IOException("Too many fields");
            out.writeInt(1); out.writeInt(fields.size());
            for (var entry : new TreeMap<>(fields).entrySet()) { write(out, entry.getKey()); write(out, entry.getValue()); }
            if (bytes.size() > MAX_BYTES) throw new IOException("Record too large");
            return bytes.toByteArray();
        } catch (IOException e) { throw new DomainException(DomainException.Code.INVALID_INPUT, "Cannot encode record", e); }
    }
    static Map<String,String> decode(byte[] bytes) {
        try {
            if (bytes.length > MAX_BYTES) throw new IOException("Record too large");
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 1) throw new IOException("Unsupported record codec");
            int count = in.readInt(); if (count < 0 || count > 10000) throw new IOException("Invalid field count");
            var fields = new TreeMap<String,String>();
            for (int i = 0; i < count; i++) if (fields.put(read(in), read(in)) != null) throw new IOException("Duplicate field");
            if (in.available() != 0) throw new IOException("Trailing record data");
            return Map.copyOf(fields);
        } catch (IOException | RuntimeException e) { throw new DomainException(DomainException.Code.STORAGE_UNAVAILABLE, "Invalid durable record", e); }
    }
    private static void write(DataOutputStream out, String s) throws IOException {
        byte[] b = Objects.requireNonNull(s).getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_BYTES) throw new IOException("Field too large");
        out.writeInt(b.length); out.write(b);
    }
    private static String read(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > MAX_BYTES || size > in.available()) throw new IOException("Invalid field size");
        return new String(in.readNBytes(size), StandardCharsets.UTF_8);
    }
}
