package com.kaanergun.bladecompanion.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stream into an app-private snapshot before hashing/uploading; never buffer a package in RAM. */
public final class PackageTransfer {
    private PackageTransfer() { }
    public interface Progress { void update(long bytes) throws IOException; }
    public static final class Result {
        public final long bytes;
        public final byte[] sha256;
        Result(long bytes, byte[] sha256) { this.bytes=bytes; this.sha256=sha256; }
    }
    public static Result copy(InputStream input, OutputStream output, Progress progress) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        byte[] buffer = new byte[MarketProtocol.MAX_CHUNK]; long total=0;
        for (;;) {
            progress.update(total);
            int count = input.read(buffer);
            if (count == -1) { break; }
            if (count <= 0 || total > MarketProtocol.MAX_PACKAGE - count) { throw new IOException("Invalid or oversized package stream"); }
            output.write(buffer, 0, count); digest.update(buffer, 0, count); total += count;
        }
        if (total < 22) { throw new IOException("Package too short"); }
        output.flush(); return new Result(total, digest.digest());
    }
}
