package com.kaanergun.bladecompanion.core;

public final class AssistanceTest {
    private static int checks;
    private AssistanceTest() { }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { throw new AssertionError(label); }
    }

    private static void rejects(Runnable task, String label) {
        checks++;
        try { task.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(label);
    }

    public static int run() {
        checks = 0;
        FrameCoordinates.Viewport portrait = new FrameCoordinates.Viewport(640, 480, 360, 640);
        check(portrait.left == 0 && portrait.top == 185, "landscape frame centered in portrait view");
        check(portrait.width == 360 && portrait.height == 270, "frame preserves aspect ratio");
        check(portrait.fromView(180, 184) == null, "top letterbox is not an image point");
        check(portrait.fromView(180, 456) == null, "bottom letterbox is not an image point");
        FrameCoordinates.Point center = portrait.fromView(180, 320);
        check(center != null && center.x == 0.5 && center.y == 0.5, "center is normalized");
        FrameCoordinates.Point corner = portrait.fromView(360, 455);
        check(corner != null && corner.x == 1 && corner.y == 1, "last image corner is preserved");
        check(portrait.viewX(center) == 180 && portrait.viewY(center) == 320, "normalized roundtrip");
        FrameCoordinates.Viewport landscape = new FrameCoordinates.Viewport(480, 640, 800, 480);
        check(landscape.left == 220 && landscape.top == 0, "portrait image has horizontal letterbox");
        check(landscape.fromView(219, 240) == null, "left letterbox rejected");
        check(landscape.fromView(Double.NaN, 0) == null, "NaN touch rejected");
        check(landscape.fromView(0, Double.POSITIVE_INFINITY) == null, "infinite touch rejected");
        for (double bad : new double[] { -0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY }) {
            rejects(() -> new FrameCoordinates.Point(bad, 0), "invalid normalized x");
            rejects(() -> new FrameCoordinates.Point(0, bad), "invalid normalized y");
        }
        rejects(() -> new FrameCoordinates.Viewport(0, 480, 360, 640), "zero image rejected");
        rejects(() -> new FrameCoordinates.Viewport(640, 480, -1, 640), "negative view rejected");
        FrameCoordinates.Viewport narrow = new FrameCoordinates.Viewport(1, 6, 360, 320);
        check(narrow.fromView(narrow.left + narrow.width, narrow.top).x == 1,
                "floating-point edge rounding stays normalized");
        AssistanceState session = new AssistanceState();
        check(session.state() == AssistanceState.State.DISCONNECTED, "initially disconnected");
        long first = session.beginConnection();
        session.disconnect();
        check(!session.connected(first), "late connect cannot revive backgrounded UI");
        long connection = session.beginConnection();
        check(session.connected(connection), "explicit authenticated connection");
        long start = session.beginStart();
        session.stop();
        check(!session.started(start, "old-session"), "late start cannot revive stopped session");
        long active = session.beginStart();
        check(session.started(active, "session-a"), "current start accepted");
        check(!session.frameReceived(start, "old-session", "frame-0"), "previous operation frame rejected");
        check(session.frameReceived(active, "session-a", "frame-1"), "current frame accepted");
        check(session.annotationMatches("session-a", "frame-1"), "annotation targets displayed frame");
        check(!session.annotationMatches("other-session", "frame-1"), "foreign session annotation rejected");
        check(session.frameReceived(active, "session-a", "frame-2"), "new displayed frame accepted");
        check(!session.annotationMatches("session-a", "frame-1"), "old displayed frame annotation rejected");
        check(session.disconnect().equals("session-a"), "disconnect provides session for explicit stop attempt");
        check(session.session() == null && session.frame() == null, "disconnect clears sensitive view state");
        check(!session.frameReceived(active, "session-a", "frame-3"), "revoked session cannot accept a late frame");
        check(!session.annotationMatches("session-a", "frame-2"), "revocation invalidates annotation");
        check(session.state() == AssistanceState.State.DISCONNECTED, "reconnection never restarts camera");
        rejects(() -> session.started(active, ""), "empty session ID rejected");
        rejects(() -> session.frameReceived(active, "session-a", null), "missing frame ID rejected");
        return checks;
    }
}
