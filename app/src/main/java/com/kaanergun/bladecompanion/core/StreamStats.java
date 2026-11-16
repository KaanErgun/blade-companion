package com.kaanergun.bladecompanion.core;

import java.util.Locale;

/** Aggregate-only measurements. No frame identity, image, session, or peer is exported. */
public final class StreamStats {
    public enum End { STOPPED, BACKGROUND, DISCONNECTED, REMOTE_END, ERROR }
    private final int requestedSeconds;
    private long received, decoded, displayed, gaps, decodeErrors, frozenSkipped, jpegBytes;
    private long firstCapture, lastCapture, firstArrival, lastArrival, lastId;
    private long firstDecoded, lastDecoded, firstDisplayed, lastDisplayed;
    private End ended;

    public StreamStats(int seconds) {
        if (seconds != 300 && seconds != 1800) { throw new IllegalArgumentException("Unsupported measurement duration"); }
        requestedSeconds = seconds;
    }

    public synchronized void received(long frameId, long captureNs, long arrivalNs, int bytes) {
        if (ended != null) { return; }
        if (captureNs <= 0 || arrivalNs <= 0 || bytes < 1 || bytes > 1048576) {
            throw new IllegalArgumentException("Invalid frame measurement");
        }
        long missed = 0;
        if (received != 0) {
            // Match BLD1's unsigned ordering, including the signed-long boundary.
            // A full unsigned wrap is not a newer BLD1 frame identifier.
            long delta = frameId - lastId;
            if (Long.compare(frameId ^ Long.MIN_VALUE, lastId ^ Long.MIN_VALUE) <= 0
                    || delta <= 0 || captureNs <= lastCapture || arrivalNs <= lastArrival) {
                throw new IllegalArgumentException("Nonmonotonic frame measurement");
            }
            missed = delta - 1;
        }
        if (received == Long.MAX_VALUE || missed > Long.MAX_VALUE - gaps
                || bytes > Long.MAX_VALUE - jpegBytes) {
            throw new IllegalArgumentException("Measurement overflow");
        }
        if (received == 0) { firstCapture = captureNs; firstArrival = arrivalNs; }
        received++; gaps += missed; jpegBytes += bytes;
        lastId = frameId; lastCapture = captureNs; lastArrival = arrivalNs;
    }

    public synchronized void decoded(long eventNs) {
        if (ended != null) { return; }
        checkEvent(decoded, lastDecoded, eventNs);
        if (decoded == 0) { firstDecoded = eventNs; }
        decoded++; lastDecoded = eventNs;
    }
    public synchronized void displayed(long eventNs) {
        if (ended != null) { return; }
        checkEvent(displayed, lastDisplayed, eventNs);
        if (displayed == 0) { firstDisplayed = eventNs; }
        displayed++; lastDisplayed = eventNs;
    }
    private static void checkEvent(long count, long previous, long now) {
        if (now <= 0 || count == Long.MAX_VALUE || count != 0 && now <= previous) {
            throw new IllegalArgumentException("Nonmonotonic phase measurement");
        }
    }
    public synchronized void decodeFailed() { if (ended == null && decodeErrors < Long.MAX_VALUE) { decodeErrors++; } }
    public synchronized void frozenSkipped() { if (ended == null && frozenSkipped < Long.MAX_VALUE) { frozenSkipped++; } }
    public synchronized void end(End reason) {
        if (ended != null) { return; }
        if (reason == null) { throw new IllegalArgumentException("Missing end reason"); }
        ended = reason;
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }

    public static final class Snapshot {
        public final int requestedSeconds;
        public final long received, decoded, displayed, gaps, decodeErrors, frozenSkipped, jpegBytes;
        public final long captureSpanNs, arrivalSpanNs, decodedSpanNs, displayedSpanNs;
        public final double captureFps, arrivalFps, decodedFps, displayedFps;
        public final String endReason;

        private Snapshot(StreamStats stats) {
            requestedSeconds = stats.requestedSeconds;
            received = stats.received; decoded = stats.decoded; displayed = stats.displayed;
            gaps = stats.gaps; decodeErrors = stats.decodeErrors; frozenSkipped = stats.frozenSkipped;
            jpegBytes = stats.jpegBytes;
            captureSpanNs = received < 2 ? 0 : stats.lastCapture - stats.firstCapture;
            arrivalSpanNs = received < 2 ? 0 : stats.lastArrival - stats.firstArrival;
            captureFps = captureSpanNs == 0 ? 0 : (received - 1) * 1e9 / captureSpanNs;
            arrivalFps = arrivalSpanNs == 0 ? 0 : (received - 1) * 1e9 / arrivalSpanNs;
            decodedSpanNs = decoded < 2 ? 0 : stats.lastDecoded - stats.firstDecoded;
            displayedSpanNs = displayed < 2 ? 0 : stats.lastDisplayed - stats.firstDisplayed;
            decodedFps = decodedSpanNs == 0 ? 0 : (decoded - 1) * 1e9 / decodedSpanNs;
            displayedFps = displayedSpanNs == 0 ? 0 : (displayed - 1) * 1e9 / displayedSpanNs;
            endReason = stats.ended == null ? "ACTIVE" : stats.ended.name();
        }

        public String json() {
            return String.format(Locale.ROOT,
                    "{\"requestedSeconds\":%d,\"received\":%d,\"decoded\":%d,\"displayed\":%d,"
                    + "\"gaps\":%d,\"decodeErrors\":%d,\"frozenSkipped\":%d,\"jpegBytes\":%d,"
                    + "\"captureSpanNs\":%d,\"arrivalSpanNs\":%d,\"captureFps\":%.6f,\"arrivalFps\":%.6f,"
                    + "\"decodedSpanNs\":%d,\"displayedSpanNs\":%d,\"decodedFps\":%.6f,\"displayedFps\":%.6f,\"endReason\":\"%s\"}",
                    requestedSeconds, received, decoded, displayed, gaps, decodeErrors, frozenSkipped,
                    jpegBytes, captureSpanNs, arrivalSpanNs, captureFps, arrivalFps,
                    decodedSpanNs, displayedSpanNs, decodedFps, displayedFps, endReason);
        }
    }
}
