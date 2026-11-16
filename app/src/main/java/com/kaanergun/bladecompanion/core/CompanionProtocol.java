package com.kaanergun.bladecompanion.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Blade protocol v1; paired with the OS docs/companion-protocol.md contract. */
public final class CompanionProtocol {
    public static final int AUTH = 1, STATUS = 2, START = 3, STOP = 4, SUBSCRIBE = 5,
            FREEZE = 6, TEXT = 7, ARROW = 8, RESUME = 9, PHOTO = 10, PING = 11;
    public static final int FRAME = 0x9001, END = 0x9002;
    public static final int MAX_JPEG = 1024 * 1024;
    public static final int MAX_CONTROL = 4096;
    private CompanionProtocol() { }

    public static final class Packet {
        public final int type;
        public final long request;
        public final byte[] payload;
        public Packet(int type, long request, byte[] payload) {
            this.type = type; this.request = request; this.payload = payload;
        }
    }

    public static final class Frame {
        public final byte[] session;
        public final long id;
        public final long captureMonotonicNs;
        public final int width;
        public final int height;
        public final byte[] jpeg;
        private Frame(byte[] session, long id, long captureMonotonicNs, int width, int height, byte[] jpeg) {
            this.session = session; this.id = id; this.captureMonotonicNs = captureMonotonicNs;
            this.width = width; this.height = height; this.jpeg = jpeg;
        }
    }

    public static final class Photo {
        public final long frameId;
        public final byte[] jpeg;
        private Photo(long frameId, byte[] jpeg) { this.frameId = frameId; this.jpeg = jpeg; }
    }

    public static final class RemoteException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int status;
        public RemoteException(int status) { super("Device status " + status); this.status = status; }
    }

    public static Packet read(DataInputStream input) throws IOException {
        if (input.readInt() != 0x424c4431 || input.readUnsignedShort() != 1) {
            throw new IOException("Protocol header mismatch");
        }
        int type = input.readUnsignedShort();
        long request = input.readInt() & 0xffffffffL;
        long size = input.readInt() & 0xffffffffL;
        boolean push = type == FRAME || type == END;
        boolean response = type >= 0x8001 && type <= 0x800b;
        int maximum = type == FRAME || type == (PHOTO | 0x8000) ? MAX_JPEG + 64 : MAX_CONTROL;
        if ((!push && !response) || (push ? request != 0 : request == 0) || size > maximum) {
            throw new IOException("Invalid packet type, request, or length");
        }
        byte[] payload = new byte[(int) size];
        input.readFully(payload);
        return new Packet(type, request, payload);
    }

    public static void write(DataOutputStream output, int type, long request, byte[] payload) throws IOException {
        if (type < AUTH || type > PING || request < 1 || request > 0xffffffffL
                || payload == null || payload.length > MAX_CONTROL) {
            throw new IllegalArgumentException("Invalid request");
        }
        output.writeInt(0x424c4431); output.writeShort(1); output.writeShort(type);
        output.writeInt((int) request); output.writeInt(payload.length); output.write(payload); output.flush();
    }

    public static byte[] response(Packet packet, int type, long request) throws IOException {
        if (packet.type != (type | 0x8000) || packet.request != request || packet.payload.length < 4) {
            throw new IOException("Unexpected response");
        }
        int status = bytes(packet.payload).getInt();
        if (status < 0 || status > 7 || (status != 0 && packet.payload.length != 4)) {
            throw new IOException("Malformed status");
        }
        if (status != 0) { throw new RemoteException(status); }
        byte[] body = Arrays.copyOfRange(packet.payload, 4, packet.payload.length);
        if (type == START ? body.length != 16 : type != STATUS && type != PHOTO && body.length != 0) {
            throw new IOException("Unexpected response body");
        }
        return body;
    }

    public static byte[] start(int durationSeconds) {
        if (durationSeconds < 1 || durationSeconds > 1800) { throw new IllegalArgumentException("Invalid duration"); }
        return buffer(4).putInt(durationSeconds).array();
    }

    public static byte[] session(byte[] session) {
        requireSession(session);
        return session.clone();
    }

    public static byte[] frame(byte[] session, long id) {
        requireSession(session);
        if (id == 0) { throw new IllegalArgumentException("Frame ID must be nonzero"); }
        return buffer(24).put(session).putLong(id).array();
    }

    public static byte[] text(byte[] session, long id, String text) {
        requireSession(session);
        if (id == 0) { throw new IllegalArgumentException("Frame ID must be nonzero"); }
        byte[] encoded = utf8(text);
        for (byte value : encoded) {
            if ((value & 0xff) < 0x20 || value == 0x7f) { throw new IllegalArgumentException("Control characters are not allowed"); }
        }
        if (encoded.length < 1 || encoded.length > 160) { throw new IllegalArgumentException("Text must be 1..160 UTF-8 bytes"); }
        return buffer(26 + encoded.length).put(session).putLong(id).putShort((short) encoded.length).put(encoded).array();
    }

    public static byte[] arrow(byte[] session, long id, FrameCoordinates.Point from, FrameCoordinates.Point to) {
        requireSession(session);
        if (id == 0) { throw new IllegalArgumentException("Frame ID must be nonzero"); }
        if (from == null || to == null) { throw new IllegalArgumentException("Missing arrow point"); }
        return buffer(32).put(session).putLong(id).putShort(coordinate(from.x)).putShort(coordinate(from.y))
                .putShort(coordinate(to.x)).putShort(coordinate(to.y)).array();
    }

    public static Frame decodeFrame(Packet packet) throws IOException {
        if (packet.type != FRAME || packet.request != 0 || packet.payload.length < 40) { throw new IOException("Invalid frame"); }
        ByteBuffer value = bytes(packet.payload);
        byte[] session = new byte[16]; value.get(session);
        long id = value.getLong(), captured = value.getLong();
        int width = value.getShort() & 0xffff, height = value.getShort() & 0xffff;
        long size = value.getInt() & 0xffffffffL;
        if (id == 0 || width != 640 || height != 480 || size > MAX_JPEG || size != value.remaining()) {
            throw new IOException("Unsupported frame dimensions or length");
        }
        byte[] jpeg = new byte[(int) size]; value.get(jpeg); requireJpeg(jpeg);
        return new Frame(session, id, captured, width, height, jpeg);
    }

    public static Photo decodePhoto(byte[] body) throws IOException {
        if (body.length < 44) { throw new IOException("Missing photo metadata"); }
        ByteBuffer value = bytes(body);
        long id = value.getLong(); byte[] expected = new byte[32]; value.get(expected);
        long size = value.getInt() & 0xffffffffL;
        if (size > MAX_JPEG || size != value.remaining()) { throw new IOException("Photo length mismatch"); }
        byte[] jpeg = new byte[(int) size]; value.get(jpeg); requireJpeg(jpeg);
        if (!MessageDigest.isEqual(expected, sha256(jpeg))) { throw new IOException("Photo hash mismatch"); }
        return new Photo(id, jpeg);
    }

    public static int decodeEnd(Packet packet, byte[] session) throws IOException {
        requireSession(session);
        if (packet.type != END || packet.request != 0 || packet.payload.length != 20
                || !MessageDigest.isEqual(session, Arrays.copyOf(packet.payload, 16))) { throw new IOException("Invalid end event"); }
        int reason = bytes(packet.payload).getInt(16);
        if (reason < 0 || reason > 4) { throw new IOException("Invalid end reason"); }
        return reason;
    }

    public static byte[] utf8(String value) {
        if (value == null) { throw new IllegalArgumentException("Missing text"); }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("Malformed text", error); }
    }

    public static String utf8Text(byte[] value) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException error) { throw new IOException("Malformed UTF-8", error); }
    }

    public static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) { output.append(Character.forDigit((item & 0xff) >>> 4, 16)).append(Character.forDigit(item & 15, 16)); }
        return output.toString();
    }

    public static byte[] unhex(String value, int size) {
        if (value == null || value.length() != size * 2 || !value.matches("[0-9a-f]+")) { throw new IllegalArgumentException("Invalid hex field"); }
        byte[] output = new byte[size];
        for (int i = 0; i < size; i++) { output[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16); }
        return output;
    }

    public static boolean newer(long candidate, long previous) {
        return Long.compare(candidate ^ Long.MIN_VALUE, previous ^ Long.MIN_VALUE) > 0;
    }

    private static void requireSession(byte[] value) {
        if (value == null || value.length != 16) { throw new IllegalArgumentException("Session must be 16 bytes"); }
    }
    private static void requireJpeg(byte[] value) throws IOException {
        if (value.length < 4 || value[0] != (byte) 0xff || value[1] != (byte) 0xd8
                || value[value.length - 2] != (byte) 0xff || value[value.length - 1] != (byte) 0xd9) {
            throw new IOException("Invalid JPEG envelope");
        }
    }
    private static short coordinate(double value) { return (short) Math.round(value * 65535); }
    private static ByteBuffer buffer(int length) { return ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN); }
    private static ByteBuffer bytes(byte[] value) { return ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN); }
}
