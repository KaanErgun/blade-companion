package com.kaanergun.bladecompanion.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded export of an already verified PHOTO. Callers own stream closure and persistence. */
public final class PhotoTransfer {
    public static final int MAX_BYTES = 1024 * 1024;
    private PhotoTransfer() { }
    public interface Progress { void update(long bytes) throws IOException; }
    public static final class Result {
        public final long bytes;
        private final byte[] digest;
        private Result(long bytes, byte[] hash) { this.bytes = bytes; digest = hash.clone(); }
        public byte[] sha256() { return digest.clone(); }
    }

    public static Result copy(InputStream source, OutputStream destination, long expectedBytes,
                              byte[] expectedSha256, Progress progress) throws IOException {
        if (destination == null) { throw new IOException("Missing photo destination"); }
        Result result = transfer(source, destination, expectedBytes, expectedSha256, progress);
        destination.flush(); progress.update(result.bytes);
        return result;
    }

    public static void verify(InputStream destination, Result expected, Progress progress) throws IOException {
        if (expected == null) { throw new IOException("Missing photo identity"); }
        transfer(destination, null, expected.bytes, expected.digest, progress);
    }

    private static Result transfer(InputStream source, OutputStream output, long expectedBytes,
                                   byte[] expectedSha256, Progress progress) throws IOException {
        if (source == null || progress == null || expectedBytes < 1 || expectedBytes > MAX_BYTES
                || expectedSha256 == null || expectedSha256.length != 32) {
            throw new IOException("Invalid photo identity");
        }
        byte[] pinned = expectedSha256.clone();
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        byte[] buffer = new byte[8192]; long total = 0;
        for (;;) {
            progress.update(total);
            // One extra byte proves EOF without reading an unbounded provider stream.
            int count = source.read(buffer, 0, (int)Math.min(buffer.length, expectedBytes - total + 1));
            if (count == -1) { break; }
            if (count <= 0 || count > expectedBytes - total) { throw new IOException("Photo size mismatch"); }
            if (output != null) { output.write(buffer, 0, count); }
            digest.update(buffer, 0, count); total += count;
        }
        byte[] actual = digest.digest();
        if (total != expectedBytes || !MessageDigest.isEqual(pinned, actual)) {
            throw new IOException("Photo integrity mismatch");
        }
        progress.update(total);
        return new Result(total, actual);
    }
}
