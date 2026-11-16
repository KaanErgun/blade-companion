package com.kaanergun.bladecompanion.core;

import java.util.Locale;

public final class StreamStatsTest {
    private StreamStatsTest() { }
    private static void check(boolean value) { if (!value) { throw new AssertionError("Stream statistics"); } }
    private static void rejects(Runnable work) {
        try { work.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid measurement accepted");
    }
    public static int run() {
        int checks = 0;
        rejects(() -> new StreamStats(10)); checks++;
        check(new StreamStats(1800).snapshot().requestedSeconds == 1800); checks++;
        StreamStats stats = new StreamStats(300);
        StreamStats.Snapshot empty = stats.snapshot();
        check(empty.received == 0 && empty.captureFps == 0 && empty.endReason.equals("ACTIVE")); checks++;
        rejects(() -> stats.received(1, 0, 1, 1)); checks++;
        rejects(() -> stats.received(1, 1, 0, 1)); checks++;
        rejects(() -> stats.received(1, 1, 1, 0)); checks++;
        rejects(() -> stats.received(1, 1, 1, 1048577)); checks++;
        stats.received(10, 1000000000L, 2000000000L, 1048576);
        check(stats.snapshot().received == 1 && stats.snapshot().arrivalFps == 0); checks++;
        rejects(() -> stats.received(10, 2000000000L, 3000000000L, 1)); checks++;
        rejects(() -> stats.received(9, 2000000000L, 3000000000L, 1)); checks++;
        rejects(() -> stats.received(11, 1000000000L, 3000000000L, 1)); checks++;
        rejects(() -> stats.received(11, 2000000000L, 2000000000L, 1)); checks++;
        stats.received(13, 1500000000L, 3000000000L, 24);
        stats.decoded(2000000000L); stats.displayed(3000000000L); stats.decodeFailed(); stats.frozenSkipped();
        StreamStats.Snapshot measured = stats.snapshot();
        check(measured.received == 2 && measured.gaps == 2 && measured.jpegBytes == 1048600); checks++;
        check(measured.decoded == 1 && measured.displayed == 1 && measured.decodeErrors == 1 && measured.frozenSkipped == 1); checks++;
        check(measured.captureSpanNs == 500000000L && measured.arrivalSpanNs == 1000000000L
                && measured.captureFps == 2 && measured.arrivalFps == 1); checks++;
        check(empty.received == 0 && measured.endReason.equals("ACTIVE")
                && measured.decodedFps == 0 && measured.displayedSpanNs == 0); checks++;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            check(measured.json().contains("\"captureFps\":2.000000") && measured.json().equals(stats.snapshot().json())); checks++;
        } finally { Locale.setDefault(previous); }
        stats.end(StreamStats.End.BACKGROUND);
        String ended = stats.snapshot().json();
        stats.end(StreamStats.End.ERROR); stats.received(0, 0, 0, 0);
        stats.decoded(0); stats.displayed(0); stats.decodeFailed(); stats.frozenSkipped();
        check(ended.equals(stats.snapshot().json()) && stats.snapshot().endReason.equals("BACKGROUND")); checks++;
        StreamStats boundary = new StreamStats(300);
        boundary.received(Long.MAX_VALUE, 1, 1, 1);
        boundary.received(Long.MIN_VALUE, 2, 2, 1);
        check(boundary.snapshot().received == 2 && boundary.snapshot().gaps == 0); checks++;
        StreamStats wrap = new StreamStats(300);
        wrap.received(-1L, 1, 1, 1);
        rejects(() -> wrap.received(0, 2, 2, 1)); checks++;
        StreamStats huge = new StreamStats(300);
        huge.received(0, 1, 1, 1);
        rejects(() -> huge.received(Long.MIN_VALUE, 2, 2, 1)); checks++;
        check(huge.snapshot().received == 1 && huge.snapshot().gaps == 0); checks++;
        huge.received(Long.MAX_VALUE, 2, 2, 1);
        rejects(() -> huge.received(-2L, 3, 3, 1)); checks++;
        check(huge.snapshot().received == 2 && huge.snapshot().gaps == Long.MAX_VALUE - 1); checks++;
        check(!ended.contains("frameId") && !ended.contains("session") && !ended.contains("token")
                && !ended.contains("host") && !ended.contains("firstCapture")); checks++;
        StreamStats phases = new StreamStats(300);
        for (int i = 0; i < 3; i++) {
            phases.received(i + 1, 1000000000L + i * 50000000L, 2000000000L + i * 50000000L, 100);
            phases.decoded(3000000000L + i * 100000000L);
            phases.displayed(4000000000L + i * 250000000L);
        }
        StreamStats.Snapshot phase = phases.snapshot();
        check(phase.decoded == 3 && phase.displayed == 3 && phase.decodedSpanNs == 200000000L
                && phase.displayedSpanNs == 500000000L && phase.decodedFps == 10 && phase.displayedFps == 4); checks++;
        rejects(() -> phases.decoded(0)); checks++;
        rejects(() -> phases.decoded(3200000000L)); checks++;
        rejects(() -> phases.displayed(-1)); checks++;
        rejects(() -> phases.displayed(4499999999L)); checks++;
        check(phase.json().equals(phases.snapshot().json())
                && phase.json().contains("\"decodedFps\":10.000000")
                && phase.json().contains("\"displayedFps\":4.000000")); checks++;
        return checks;
    }
}
