package com.kaanergun.bladecompanion.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Fixed owner-management wire contract; camera types and authority remain separate. */
public final class MarketProtocol {
    public static final int AUTH = 20, LIST = 32, BEGIN = 33, CHUNK = 34, FINISH = 35,
            ABORT = 36, INSTALL = 37, ACTION = 38, STATUS = 39;
    public static final int HUD = 4, NETWORK = 8, STORAGE = 16, MAX_PACKAGE = 64 * 1024 * 1024, MAX_CHUNK = 65536;
    public static final int UPLOAD_LIFE_MS = 300000, UPLOAD_IDLE_MS = 15000, UPLOAD_REVIEW_MS = 120000;
    public static final String EMPTY_REVISION = new String(new char[64]).replace('\0', '0');
    private MarketProtocol() { }

    public static long previewDeadline(long uploadDeadline, long verifiedAt) {
        // Leave one second for the confirmation to reach the OS before expiry.
        return Math.min(uploadDeadline, verifiedAt + UPLOAD_REVIEW_MS - 1000);
    }

    public static boolean validId(String value) {
        return value != null && value.length() <= 96 && value.matches("[a-z0-9]+(?:[.-][a-z0-9]+)+");
    }
    public static boolean supported(int permissions, String runAs) {
        return "app".equals(runAs) && permissions >= 0 && (permissions & ~(HUD | NETWORK | STORAGE)) == 0;
    }
    public static byte[] revision(String value) { return CompanionProtocol.unhex(value, 32); }
    private static ByteBuffer buffer(int bytes) { return ByteBuffer.allocate(bytes); }
    private static boolean version(String value) { return value != null && value.length() <= 64 && value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.+-]+)?"); }
    private static void id(String value) { if (!validId(value)) { throw new IllegalArgumentException("Invalid application ID"); } }
    private static void upload(byte[] value) { if (value == null || value.length != 16) { throw new IllegalArgumentException("Invalid upload ID"); } }
    public static byte[] list(long offset) {
        if (offset < 0 || offset > 0xffffffffL) { throw new IllegalArgumentException("Invalid page offset"); }
        return buffer(4).putInt((int) offset).array();
    }
    public static byte[] begin(long bytes, byte[] sha256) {
        if (bytes < 22 || bytes > MAX_PACKAGE || sha256 == null || sha256.length != 32) { throw new IllegalArgumentException("Invalid package size or hash"); }
        return buffer(40).putLong(bytes).put(sha256).array();
    }
    public static byte[] chunk(byte[] upload, long offset, byte[] data, int count) {
        upload(upload);
        if (offset < 0 || count < 1 || count > MAX_CHUNK || data == null || count > data.length || offset > MAX_PACKAGE - count) {
            throw new IllegalArgumentException("Invalid upload range");
        }
        return buffer(24 + count).put(upload).putLong(offset).put(data, 0, count).array();
    }
    public static byte[] finish(byte[] upload) { upload(upload); return upload.clone(); }
    public static byte[] install(byte[] upload, int grants, String expected) {
        upload(upload);
        if (!supported(grants, "app")) { throw new IllegalArgumentException("Unsupported grants"); }
        return buffer(52).put(upload).putInt(grants).put(revision(expected)).array();
    }
    public static byte[] action(int action, String id, String expected) {
        id(id);
        if (action < 1 || action > 5) { throw new IllegalArgumentException("Invalid action"); }
        byte[] value = id.getBytes(StandardCharsets.US_ASCII);
        return buffer(34 + value.length).put((byte) action).put((byte) value.length).put(value).put(revision(expected)).array();
    }
    public static byte[] status(String id) {
        id(id); byte[] value = id.getBytes(StandardCharsets.US_ASCII);
        return buffer(1 + value.length).put((byte) value.length).put(value).array();
    }
    public static void write(DataOutputStream output, int type, long request, byte[] payload) throws IOException {
        if (!(type == AUTH || type >= LIST && type <= STATUS) || request < 1 || request > 0xffffffffL
                || payload == null || payload.length > (type == CHUNK ? 24 + MAX_CHUNK : 4096)) {
            throw new IllegalArgumentException("Invalid management request");
        }
        output.writeInt(0x424c4431); output.writeShort(1); output.writeShort(type);
        output.writeInt((int) request); output.writeInt(payload.length); output.write(payload); output.flush();
    }
    public static byte[] read(DataInputStream input, int type, long request) throws IOException {
        if (!(type == AUTH || type >= LIST && type <= STATUS) || request < 1 || request > 0xffffffffL) { throw new IllegalArgumentException("Management authority/type"); }
        if (input.readInt() != 0x424c4431 || input.readUnsignedShort() != 1 || input.readUnsignedShort() != (type | 0x8000)
                || (input.readInt() & 0xffffffffL) != request) { throw new IOException("Management response correlation"); }
        long size = input.readInt() & 0xffffffffL;
        if (size < 4 || size > 4096) { throw new IOException("Management response length"); }
        byte[] data = new byte[(int) size]; input.readFully(data);
        int code = ByteBuffer.wrap(data).getInt();
        if (code < 0 || code > 7 || code != 0 && size != 4) { throw new IOException("Management status"); }
        if (code != 0) { throw new CompanionProtocol.RemoteException(code); }
        byte[] body = Arrays.copyOfRange(data, 4, data.length);
        int exact = type == AUTH || type == ABORT ? 0 : type == BEGIN ? 16 : type == CHUNK ? 8 : -1;
        if (exact >= 0 && body.length != exact || exact < 0 && body.length == 0) { throw new IOException("Management response body"); }
        return body;
    }
    public static void nextOffset(byte[] body, long expected) throws IOException {
        if (body.length != 8 || ByteBuffer.wrap(body).getLong() != expected) { throw new IOException("Upload acknowledgement mismatch"); }
    }

    public static final class App {
        public final String id, version, revision, signer, runAs;
        public final int uid, permissions;
        public final boolean installed, enabled, running;
        public App(String id, int uid, String version, String revision, String signer, int permissions,
                   String runAs, boolean installed, boolean enabled, boolean running) {
            id(id); revision(revision); revision(signer);
            if (uid < 0 || permissions < 0 || permissions > 63 || !("app".equals(runAs) || "root".equals(runAs))
                    || installed && (!version(version) || EMPTY_REVISION.equals(revision))
                    || !installed && (version != null || !EMPTY_REVISION.equals(revision) || enabled || running)
                    || running && !enabled) { throw new IllegalArgumentException("Inconsistent application state"); }
            this.id=id; this.uid=uid; this.version=version; this.revision=revision; this.signer=signer;
            this.permissions=permissions; this.runAs=runAs; this.installed=installed; this.enabled=enabled; this.running=running;
        }
        public boolean manageable() { return supported(permissions, runAs); }
    }
    public static final class Preview {
        public final String id, version, revision, signer, runAs;
        public final int permissions, files;
        public final long bytes;
        public final boolean installable;
        public Preview(String id, String version, String revision, String signer, int permissions,
                       String runAs, int files, long bytes, boolean installable) {
            id(id); revision(revision); revision(signer);
            if (!version(version) || EMPTY_REVISION.equals(revision)
                    || permissions < 0 || permissions > 63 || !("app".equals(runAs) || "root".equals(runAs))
                    || files < 1 || files > 512 || bytes < 22 || bytes > MAX_PACKAGE
                    || installable && !supported(permissions, runAs)) { throw new IllegalArgumentException("Invalid verified preview"); }
            this.id=id; this.version=version; this.revision=revision; this.signer=signer; this.permissions=permissions;
            this.runAs=runAs; this.files=files; this.bytes=bytes; this.installable=installable;
        }
    }
}
