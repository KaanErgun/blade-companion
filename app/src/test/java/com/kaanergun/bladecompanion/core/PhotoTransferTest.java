package com.kaanergun.bladecompanion.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

public final class PhotoTransferTest {
    private PhotoTransferTest() { }
    private interface Work { void run() throws IOException; }
    private static void check(boolean value) { if (!value) { throw new AssertionError("Photo transfer"); } }
    private static void rejects(Work work) {
        try { work.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Invalid photo transfer accepted");
    }
    private static final PhotoTransfer.Progress PROGRESS = bytes -> { };
    public static int run() throws Exception {
        int checks = 0;
        byte[] source = new byte[16389]; for (int i = 0; i < source.length; i++) { source[i] = (byte)i; }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PhotoTransfer.Result expected = PhotoTransfer.copy(new ByteArrayInputStream(source), output, source.length, hash, PROGRESS);
        check(expected.bytes == source.length && Arrays.equals(source, output.toByteArray())); checks++;
        check(Arrays.equals(hash, expected.sha256())); checks++;
        byte[] detached = expected.sha256(); detached[0] ^= 1;
        check(Arrays.equals(hash, expected.sha256())); checks++;
        PhotoTransfer.verify(new ByteArrayInputStream(output.toByteArray()), expected, PROGRESS); checks++;
        for (long size : new long[] {0, -1, PhotoTransfer.MAX_BYTES + 1L, Long.MAX_VALUE}) {
            rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(source), output, size, hash, PROGRESS)); checks++;
        }
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(source), output, source.length, new byte[31], PROGRESS)); checks++;
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(source), output, source.length, detached, PROGRESS)); checks++;
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(Arrays.copyOf(source, source.length - 1)), output, source.length, hash, PROGRESS)); checks++;
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(Arrays.copyOf(source, source.length + 1)), output, source.length, hash, PROGRESS)); checks++;
        rejects(() -> PhotoTransfer.verify(new ByteArrayInputStream(Arrays.copyOf(source, source.length - 1)), expected, PROGRESS)); checks++;
        rejects(() -> PhotoTransfer.verify(new ByteArrayInputStream(Arrays.copyOf(source, source.length + 1)), expected, PROGRESS)); checks++;
        byte[] changed = source.clone(); changed[changed.length - 1] ^= 1;
        rejects(() -> PhotoTransfer.verify(new ByteArrayInputStream(changed), expected, PROGRESS)); checks++;
        InputStream stalled = new ByteArrayInputStream(source) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { return 0; }
        };
        rejects(() -> PhotoTransfer.copy(stalled, output, source.length, hash, PROGRESS)); checks++;
        OutputStream failed = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("Synthetic write failure"); } };
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(source), failed, source.length, hash, PROGRESS)); checks++;
        OutputStream flushFailed = new ByteArrayOutputStream() { @Override public void flush() throws IOException { throw new IOException("Synthetic flush failure"); } };
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(source), flushFailed, source.length, hash, PROGRESS)); checks++;
        PhotoTransfer.Progress cancelled = bytes -> { if (bytes >= 8192) { throw new IOException("Cancelled"); } };
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        rejects(() -> PhotoTransfer.copy(new ByteArrayInputStream(source), partial, source.length, hash, cancelled)); checks++;
        check(partial.size() == 8192); checks++;
        rejects(() -> PhotoTransfer.verify(new ByteArrayInputStream(source), expected, cancelled)); checks++;
        final boolean[] closed = {false, false};
        InputStream retainedInput = new ByteArrayInputStream(source) { @Override public void close() { closed[0] = true; } };
        OutputStream retainedOutput = new ByteArrayOutputStream() { @Override public void close() { closed[1] = true; } };
        PhotoTransfer.copy(retainedInput, retainedOutput, source.length, hash, PROGRESS);
        check(!closed[0] && !closed[1]); checks++;
        byte[] maximum = new byte[PhotoTransfer.MAX_BYTES];
        PhotoTransfer.Result large = PhotoTransfer.copy(new ByteArrayInputStream(maximum), new ByteArrayOutputStream(),
                maximum.length, MessageDigest.getInstance("SHA-256").digest(maximum), PROGRESS);
        check(large.bytes == PhotoTransfer.MAX_BYTES); checks++;
        return checks;
    }
}
