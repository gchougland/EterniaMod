package com.hexvane.eterniamod.housing.relocation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable, forced-to-disk snapshots. A failed or corrupted read never becomes an empty template. */
public final class SnapshotFiles {
    private final Path root;
    public SnapshotFiles(Path root) { this.root=root.toAbsolutePath().normalize(); }
    public record Saved(String reference,String sha256) {}
    public Saved write(String name,byte[] bytes) throws IOException {
        if(!name.matches("[A-Za-z0-9_.-]{1,180}")) throw new IOException("Invalid snapshot name");
        Files.createDirectories(root);
        Path target=resolve(name), temporary=root.resolve(name+".pending");
        if(Files.exists(target)) {
            if(!MessageDigest.isEqual(Files.readAllBytes(target),bytes)) throw new IOException("Immutable snapshot already exists with different contents");
            return new Saved(name,hash(bytes));
        }
        try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true);
        }
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        Saved saved=new Saved(name,hash(bytes));read(saved);return saved;
    }
    public byte[] read(Saved saved) throws IOException {
        byte[] bytes=Files.readAllBytes(resolve(saved.reference()));
        if(!hash(bytes).equals(saved.sha256())) throw new IOException("Snapshot SHA-256 verification failed: "+saved.reference());
        return bytes;
    }
    public Path resolve(String reference) throws IOException {
        Path result=root.resolve(reference).normalize();
        if(!result.getParent().equals(root)||Files.isSymbolicLink(result)||Files.isSymbolicLink(root)) throw new IOException("Snapshot path escapes its directory");
        return result;
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
