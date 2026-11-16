package com.kaanergun.bladecompanion.net;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import com.kaanergun.bladecompanion.core.AssistanceState;
import com.kaanergun.bladecompanion.core.CompanionProtocol;
import com.kaanergun.bladecompanion.core.FrameCoordinates;
import com.kaanergun.bladecompanion.core.PairingRecord;
import com.kaanergun.bladecompanion.core.StreamStats;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Two bounded pinned TLS channels; no shell, proxy, automatic reconnect, or automatic START. */
public final class BladeConnection implements AutoCloseable {
    public interface Listener {
        void changed();
        void frame(CompanionProtocol.Frame frame, Bitmap image);
        void photo(CompanionProtocol.Photo photo);
        void failed(int status);
    }
    private final Context context;
    private final PairingRecord pairing;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final AssistanceState state = new AssistanceState();
    private volatile Socket controlSocket, subscriberSocket;
    private volatile boolean closed;
    private volatile boolean frozen, freezing, cameraAvailable, hdAvailable, commanding;
    private final java.util.concurrent.atomic.AtomicReference<FrameImage> pendingFrame = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean framePosted = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile CompanionProtocol.Frame displayed;
    private volatile Bitmap displayedBitmap;
    private volatile byte[] session;
    private volatile StreamStats statistics;
    private CompanionProtocol.Frame countedDisplay;
    private Channel control;
    private long operation;

    public BladeConnection(Context context, PairingRecord pairing, Listener listener) {
        this.context = context.getApplicationContext(); this.pairing = pairing; this.listener = listener;
    }
    public AssistanceState.State state() { return state.state(); }
    public boolean frozen() { return frozen; }
    public boolean busy() { return freezing || commanding; }
    public boolean cameraAvailable() { return cameraAvailable; }
    /** True only when STATUS advertises the exact HD profile; older OS versions lack it. */
    public boolean hdAvailable() { return hdAvailable; }
    public CompanionProtocol.Frame displayed() { return displayed; }
    public Bitmap displayedBitmap() { return displayedBitmap; }
    public StreamStats.Snapshot statistics() { StreamStats value=statistics; return value==null?null:value.snapshot(); }
    public void frameDisplayed(CompanionProtocol.Frame frame) {
        StreamStats value=statistics;
        if (frame!=null && displayed==frame && countedDisplay!=frame && value!=null) {
            countedDisplay=frame;value.displayed(android.os.SystemClock.elapsedRealtimeNanos());
        }
    }

    public void connect() {
        operation = state.beginConnection(); changed();
        execute(() -> {
            control = channel(false, 0);
            control.call(CompanionProtocol.AUTH, pairing.token());
            JSONObject status = new JSONObject(CompanionProtocol.utf8Text(control.call(CompanionProtocol.STATUS, new byte[0])));
            // Frame geometry is the live session's profile: another owner may be streaming HD.
            boolean vga = geometry(status, CompanionProtocol.VGA_WIDTH, CompanionProtocol.VGA_HEIGHT);
            boolean hd = geometry(status, CompanionProtocol.HD_WIDTH, CompanionProtocol.HD_HEIGHT);
            if (!(status.get("protocol") instanceof Integer) || status.getInt("protocol") != 1
                    || !(status.get("camera_available") instanceof Boolean)
                    || !(status.get("session_active") instanceof Boolean) || !(status.get("os_version") instanceof String)
                    || !(vga || hd)) {
                throw new IOException("Unexpected status");
            }
            cameraAvailable = status.getBoolean("camera_available");
            JSONArray profiles = status.optJSONArray("profiles");
            JSONArray advertised = profiles == null ? null : profiles.optJSONArray(CompanionProtocol.PROFILE_HD);
            hdAvailable = advertised != null && advertised.length() == 3
                    && advertised.opt(0) instanceof Integer && advertised.optInt(0) == CompanionProtocol.HD_WIDTH
                    && advertised.opt(1) instanceof Integer && advertised.optInt(1) == CompanionProtocol.HD_HEIGHT
                    && advertised.opt(2) instanceof Integer && advertised.optInt(2) == CompanionProtocol.HD_FPS;
            if (state.connected(operation)) { changed(); }
        });
        worker.scheduleWithFixedDelay(() -> {
            if (!closed && control != null && state.state() != AssistanceState.State.CONNECTING) {
                executeDirect(() -> control.call(CompanionProtocol.PING, session == null ? new byte[0] : session));
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    public void start(int durationSeconds, int profile) {
        if (busy()) { return; }
        byte[] request = CompanionProtocol.start(durationSeconds, profile);
        StreamStats measured = new StreamStats(durationSeconds);
        operation = state.beginStart(); statistics=measured; countedDisplay=null; changed();
        final long requested = operation;
        execute(() -> {
            byte[] created = control.call(CompanionProtocol.START, request);
            if (closed || !state.started(requested, CompanionProtocol.hex(created))) { return; }
            session = created; changed();
            Thread reader = new Thread(() -> readFrames(requested, created, measured, profile), "blade-frames");
            reader.start();
        });
    }

    public void stop() {
        endStatistics(StreamStats.End.STOPPED);
        byte[] previous = session;
        state.stop(); session = null; frozen = false; freezing = false; displayed = null; displayedBitmap = null; countedDisplay=null;
        closeSocket(subscriberSocket); subscriberSocket = null; changed();
        // A START reply can race cancellation. Closing control ends even a session whose ID is unknown.
        if (previous == null) { close(); return; }
        execute(() -> {
            try { control.call(CompanionProtocol.STOP, previous); }
            catch (CompanionProtocol.RemoteException error) {
                // Subscriber close may have already ended this exact session; never retry STOP.
                if (error.status != 4) { throw error; }
            }
        });
    }

    public void freeze() {
        CompanionProtocol.Frame selected = displayed;
        if (selected == null || frozen || busy() || session == null) { return; }
        freezing = true; changed();
        execute(() -> {
            control.call(CompanionProtocol.FREEZE, CompanionProtocol.frame(selected.session, selected.id));
            if (closed || !Arrays.equals(session, selected.session)) { return; }
            frozen = true; freezing = false; changed();
        });
    }

    public void resume() {
        if (session == null || !frozen || busy()) { return; }
        byte[] selected = session.clone();
        execute(() -> { control.call(CompanionProtocol.RESUME, selected); frozen = false; changed(); });
    }

    public void text(String text) {
        CompanionProtocol.Frame selected = annotationFrame();
        byte[] payload = CompanionProtocol.text(selected.session, selected.id, text);
        execute(() -> control.call(CompanionProtocol.TEXT, payload));
    }

    public void arrow(FrameCoordinates.Point from, FrameCoordinates.Point to) {
        CompanionProtocol.Frame selected = annotationFrame();
        byte[] payload = CompanionProtocol.arrow(selected.session, selected.id, from, to);
        execute(() -> control.call(CompanionProtocol.ARROW, payload));
    }

    public void photo() {
        if (session == null || busy()) { return; }
        byte[] selected = session.clone();
        execute(() -> {
            CompanionProtocol.Photo photo = CompanionProtocol.decodePhoto(control.call(CompanionProtocol.PHOTO, selected));
            main.post(() -> { if (!closed && Arrays.equals(session, selected)) { listener.photo(photo); } });
        });
    }

    private CompanionProtocol.Frame annotationFrame() {
        CompanionProtocol.Frame selected = displayed;
        if (busy() || !frozen || selected == null || !state.annotationMatches(CompanionProtocol.hex(selected.session), Long.toHexString(selected.id))) {
            throw new IllegalStateException("A current frozen frame is required");
        }
        return selected;
    }

    private static boolean geometry(JSONObject status, int width, int height) {
        return status.opt("frame_width") instanceof Integer && status.optInt("frame_width") == width
                && status.opt("frame_height") instanceof Integer && status.optInt("frame_height") == height;
    }

    private void readFrames(long requested, byte[] selected, StreamStats measured, int profile) {
        Channel stream = null;
        try {
            stream = channel(true, requested);
            if (closed || !state.current(requested)) { throw new IOException("Subscription cancelled"); }
            stream.call(CompanionProtocol.AUTH, pairing.token());
            stream.call(CompanionProtocol.SUBSCRIBE, selected);
            boolean received = false; long lastId = 0;
            while (!closed && state.current(requested) && Arrays.equals(session, selected)) {
                CompanionProtocol.Packet packet = CompanionProtocol.read(stream.input);
                if (packet.type == CompanionProtocol.END) {
                    int reason = CompanionProtocol.decodeEnd(packet, selected);
                    measured.endRemote(reason);
                    main.post(() -> { if (!closed && state.current(requested)) { fail(reason == 4 ? 1 : reason == 3 ? 7 : 4); } });
                    return;
                }
                CompanionProtocol.Frame frame = CompanionProtocol.decodeFrame(packet, profile);
                if (!MessageDigest.isEqual(frame.session, selected) || (received && !CompanionProtocol.newer(frame.id, lastId))) {
                    throw new IOException("Stale or foreign frame");
                }
                lastId = frame.id; received = true;
                measured.received(frame.id, frame.captureMonotonicNs, android.os.SystemClock.elapsedRealtimeNanos(), frame.jpeg.length);
                if (frozen || freezing) { measured.frozenSkipped(); continue; }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length, bounds);
                if (bounds.outWidth != frame.width || bounds.outHeight != frame.height) { measured.decodeFailed(); throw new IOException("JPEG dimensions mismatch"); }
                Bitmap image = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.length);
                if (image == null) { measured.decodeFailed(); throw new IOException("JPEG decode failed"); }
                measured.decoded(android.os.SystemClock.elapsedRealtimeNanos());
                pendingFrame.set(new FrameImage(requested, frame, image));
                deliverFrame();
            }
        } catch (Exception error) {
            if (!closed && state.current(requested)) { main.post(() -> { if (state.current(requested)) { fail(status(error)); } }); }
        } finally { if (stream != null) { closeSocket(stream.socket); } }
    }

    private static final class FrameImage {
        final long request; final CompanionProtocol.Frame frame; final Bitmap image;
        FrameImage(long request, CompanionProtocol.Frame frame, Bitmap image) {
            this.request = request; this.frame = frame; this.image = image;
        }
    }
    private void deliverFrame() {
        if (!framePosted.compareAndSet(false, true)) { return; }
        main.post(() -> {
            FrameImage next = pendingFrame.getAndSet(null);
            if (next != null && !closed && !frozen && !freezing && state.frameReceived(next.request,
                    CompanionProtocol.hex(next.frame.session), Long.toHexString(next.frame.id))) {
                displayed = next.frame; displayedBitmap = next.image; listener.frame(next.frame, next.image);
            }
            framePosted.set(false);
            if (pendingFrame.get() != null) { deliverFrame(); }
        });
    }
    private interface Work { void run() throws Exception; }
    private void execute(Work work) {
        if (closed) { return; }
        commanding = true; changed();
        try {
            worker.execute(() -> {
                executeDirect(work);
                commanding = false; changed();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { commanding = false; }
    }
    private void executeDirect(Work work) {
        try { if (!closed) { work.run(); } }
        catch (Exception error) { main.post(() -> { if (!closed) { fail(status(error)); } }); }
    }
    private static int status(Exception error) {
        return error instanceof CompanionProtocol.RemoteException ? ((CompanionProtocol.RemoteException) error).status : -1;
    }
    private void fail(int status) { endStatistics(StreamStats.End.ERROR); close(); listener.failed(status); }
    private void endStatistics(StreamStats.End reason) { StreamStats value=statistics; if (value!=null) { value.end(reason); } }
    public void close(StreamStats.End reason) { endStatistics(reason); close(); }
    private void changed() { main.post(() -> listener.changed()); }

    @Override public synchronized void close() {
        if (closed) { return; }
        endStatistics(StreamStats.End.DISCONNECTED);
        closed = true; state.disconnect(); session = null; frozen = false; freezing = false;
        displayed = null; displayedBitmap = null; countedDisplay=null; pendingFrame.set(null);
        closeSocket(controlSocket); closeSocket(subscriberSocket);
        // Raw close promptly interrupts TLS I/O without writing close_notify on the UI thread.
        // The existing worker owns TLS teardown, including a connect already in progress.
        worker.execute(() -> { if (control != null) { closeSocket(control.socket); } });
        worker.shutdown(); changed();
    }
    private static void closeSocket(Socket socket) { if (socket != null) { try { socket.close(); } catch (IOException ignored) { } } }

    private Channel channel(boolean subscriber, long requested) throws Exception {
        InetAddress address = InetAddress.getByName(pairing.host);
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network match = null;
        for (Network network : manager.getAllNetworks()) {
            LinkProperties properties = manager.getLinkProperties(network);
            if (properties == null) { continue; }
            for (LinkAddress link : properties.getLinkAddresses()) {
                if (sameSubnet(address.getAddress(), link.getAddress().getAddress(), link.getPrefixLength())) {
                    if (match != null && !match.equals(network)) { throw new IOException("Ambiguous local network"); }
                    match = network;
                }
            }
        }
        Socket plain = match == null ? new Socket() : match.getSocketFactory().createSocket();
        SSLSocket secure = null;
        try {
            synchronized (state) {
                if (closed || subscriber && !state.current(requested)) { throw new IOException("Connection cancelled"); }
                // A stale reader must not replace the next session's cancellation socket.
                if (subscriber) { subscriberSocket = plain; } else { controlSocket = plain; }
            }
            plain.connect(new InetSocketAddress(address, pairing.port), 5000); plain.setSoTimeout(5000);
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[] { new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    throw new CertificateException("Client trust not used");
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    if (chain == null || chain.length == 0 || !pairing.matchesCertificate(chain[0].getEncoded())) { throw new CertificateException("Certificate pin mismatch"); }
                    chain[0].checkValidity();
                }
            } }, null);
            SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(plain, pairing.host, pairing.port, true);
            secure = socket;
            // Keep cancellation fields bound to the underlying TCP socket, never this TLS wrapper.
            java.util.List<String> protocols = new java.util.ArrayList<>();
            for (String protocol : socket.getSupportedProtocols()) { if (protocol.equals("TLSv1.2") || protocol.equals("TLSv1.3")) { protocols.add(protocol); } }
            socket.setEnabledProtocols(protocols.toArray(new String[0]));
            if (closed || subscriber && !state.current(requested)) { throw new IOException("Connection cancelled"); }
            socket.setSoTimeout(5000); socket.startHandshake(); socket.setSoTimeout(subscriber ? 15000 : 5000);
            return new Channel(socket);
        } catch (Exception error) { closeSocket(plain); closeSocket(secure); throw error; }
    }

    private static boolean sameSubnet(byte[] target, byte[] local, int prefix) {
        if (target.length != local.length || prefix < 0 || prefix > local.length * 8) { return false; }
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 1 << (7 - bit % 8);
            if ((target[bit / 8] & mask) != (local[bit / 8] & mask)) { return false; }
        }
        return true;
    }

    private static final class Channel {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        long next = 1;
        Channel(Socket socket) throws IOException { this.socket = socket; input = new DataInputStream(socket.getInputStream()); output = new DataOutputStream(socket.getOutputStream()); }
        byte[] call(int type, byte[] payload) throws IOException {
            long request = next++;
            CompanionProtocol.write(output, type, request, payload);
            return CompanionProtocol.response(CompanionProtocol.read(input), type, request);
        }
    }
}
