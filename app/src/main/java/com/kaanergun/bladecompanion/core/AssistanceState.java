package com.kaanergun.bladecompanion.core;

/** Local request ownership. A stopped/revoked session cannot be revived by a late callback. */
public final class AssistanceState {
    public enum State { DISCONNECTED, CONNECTING, READY, STARTING, ACTIVE }
    private State state = State.DISCONNECTED;
    private long generation;
    private String session;
    private String frame;

    public synchronized State state() { return state; }
    public synchronized String session() { return session; }
    public synchronized String frame() { return frame; }

    public synchronized long beginConnection() {
        if (state != State.DISCONNECTED) { throw new IllegalStateException("Close the previous connection first"); }
        reset(State.CONNECTING);
        return generation;
    }

    public synchronized boolean connected(long request) {
        if (request != generation || state != State.CONNECTING) { return false; }
        state = State.READY;
        return true;
    }

    public synchronized long beginStart() {
        if (state != State.READY) { throw new IllegalStateException("Authenticated connection required"); }
        reset(State.STARTING);
        return generation;
    }

    public synchronized boolean started(long request, String sessionId) {
        requireId(sessionId);
        if (request != generation || state != State.STARTING) { return false; }
        session = sessionId;
        state = State.ACTIVE;
        return true;
    }

    public synchronized boolean frameReceived(long request, String sessionId, String frameId) {
        requireId(sessionId);
        requireId(frameId);
        if (request != generation || state != State.ACTIVE || !sessionId.equals(session)) { return false; }
        frame = frameId;
        return true;
    }

    /** Both local state and the server's shared protocol must accept an annotation. */
    public synchronized boolean annotationMatches(String sessionId, String frameId) {
        return state == State.ACTIVE && session != null && frame != null
                && session.equals(sessionId) && frame.equals(frameId);
    }

    /** Returns the old server session for one explicit stop attempt, never for automatic restart. */
    public synchronized String stop() {
        String previous = session;
        reset(state == State.ACTIVE || state == State.STARTING || state == State.READY
                ? State.READY : State.DISCONNECTED);
        return previous;
    }

    /** Network loss, backgrounding, and revoked authorization invalidate every pending request. */
    public synchronized String disconnect() {
        String previous = session;
        reset(State.DISCONNECTED);
        return previous;
    }

    public synchronized boolean current(long request) { return request == generation; }

    private void reset(State next) {
        if (generation == Long.MAX_VALUE) { throw new IllegalStateException("Request generation exhausted"); }
        generation++;
        state = next;
        session = null;
        frame = null;
    }

    private static void requireId(String value) {
        if (value == null || value.isEmpty()) { throw new IllegalArgumentException("Missing session or frame identity"); }
    }
}
