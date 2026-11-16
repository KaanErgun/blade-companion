package com.kaanergun.bladecompanion.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ProtocolTest {
    private static int checks;
    private static final byte[] SESSION = new byte[16];
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
    private ProtocolTest() { }
    private interface Work { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) { throw new AssertionError(label); } }
    private static void rejects(Work action, String label) {
        checks++;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        catch (Exception unexpected) { throw new AssertionError(label, unexpected); }
        throw new AssertionError(label);
    }
    private static byte[] header(int type, long id, int length) {
        return ByteBuffer.allocate(16).putInt(0x424c4431).putShort((short) 1).putShort((short) type)
                .putInt((int) id).putInt(length).array();
    }
    private static CompanionProtocol.Packet read(byte[] bytes) throws IOException {
        return CompanionProtocol.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
    private static CompanionProtocol.Packet frame(long id) { return frame(id, 640, 480); }
    private static CompanionProtocol.Packet frame(long id, int width, int height) {
        byte[] data = ByteBuffer.allocate(40 + JPEG.length).put(SESSION).putLong(id).putLong(100)
                .putShort((short) width).putShort((short) height).putInt(JPEG.length).put(JPEG).array();
        return new CompanionProtocol.Packet(CompanionProtocol.FRAME, 0, data);
    }
    public static int run() {
        checks = 0;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CompanionProtocol.write(new DataOutputStream(bytes), CompanionProtocol.START, 0xffffffffL, CompanionProtocol.start(300, CompanionProtocol.PROFILE_VGA));
            check(Arrays.equals(Arrays.copyOf(bytes.toByteArray(), 16), header(3, 0xffffffffL, 4)), "exact big-endian request header");
            check(ByteBuffer.wrap(bytes.toByteArray()).getInt(16) == 300, "duration encoded big-endian");
            // Shared OS protocol.c/test_protocol.c vector: START, request 0x10203040, length 4.
            bytes.reset();
            CompanionProtocol.write(new DataOutputStream(bytes), CompanionProtocol.START, 0x10203040, CompanionProtocol.start(300, CompanionProtocol.PROFILE_VGA));
            check(CompanionProtocol.hex(Arrays.copyOf(bytes.toByteArray(), 16)).equals("424c4431000100031020304000000004"),
                    "shared OS exact wire vector");
            // Only HD uses the 8-byte START: duration, then profile.
            byte[] hd = CompanionProtocol.start(300, CompanionProtocol.PROFILE_HD);
            check(hd.length == 8 && ByteBuffer.wrap(hd).getInt(0) == 300 && ByteBuffer.wrap(hd).getInt(4) == 1, "HD start form");
            check(CompanionProtocol.start(300, CompanionProtocol.PROFILE_VGA).length == 4, "VGA keeps the original start form");
            rejects(() -> CompanionProtocol.start(300, 2), "unknown profile rejected");
            rejects(() -> CompanionProtocol.start(300, -1), "negative profile rejected");
            rejects(() -> CompanionProtocol.start(0, CompanionProtocol.PROFILE_HD), "HD duration lower bound");
            check(CompanionProtocol.HD_WIDTH * CompanionProtocol.VGA_HEIGHT == CompanionProtocol.VGA_WIDTH * CompanionProtocol.HD_HEIGHT,
                    "both profiles keep 4:3 for preview and annotation");
            rejects(() -> CompanionProtocol.write(new DataOutputStream(new ByteArrayOutputStream()), 3, 0, new byte[4]), "zero request rejected");
            rejects(() -> CompanionProtocol.write(new DataOutputStream(new ByteArrayOutputStream()), 3, 0x100000000L, new byte[4]), "request overflow rejected");
            rejects(() -> CompanionProtocol.start(0, CompanionProtocol.PROFILE_VGA), "zero duration rejected");
            rejects(() -> CompanionProtocol.start(1801, CompanionProtocol.PROFILE_VGA), "duration upper bound");
            for (int length = 0; length < 16; length++) {
                final int size = length;
                rejects(() -> read(Arrays.copyOf(header(0x8001, 1, 4), size)), "truncated header");
            }
            byte[] bad = header(0x8001, 1, 4); bad[0] = 0;
            final byte[] badMagic = bad;
            rejects(() -> read(badMagic), "foreign magic");
            bad = header(0x8001, 1, 4); bad[5] = 2;
            final byte[] badVersion = bad;
            rejects(() -> read(badVersion), "unknown protocol version");
            rejects(() -> read(header(0x8001, 1, -1)), "unsigned huge payload rejected before allocation");
            rejects(() -> read(header(0x8002, 1, 4097)), "control maximum");
            rejects(() -> read(header(0x9001, 0, CompanionProtocol.MAX_JPEG + 65)), "image maximum");
            rejects(() -> read(header(0x9001, 1, 0)), "push request must be zero");
            rejects(() -> read(header(0x8001, 0, 0)), "response request must be positive");
            rejects(() -> read(header(0x9003, 0, 0)), "unknown push rejected");
            rejects(() -> read(header(0x8001, 1, 4)), "truncated payload");
            CompanionProtocol.Packet ok = new CompanionProtocol.Packet(0x8001, 1, new byte[4]);
            check(CompanionProtocol.response(ok, 1, 1).length == 0, "empty auth response");
            rejects(() -> CompanionProtocol.response(ok, 2, 1), "wrong response type");
            rejects(() -> CompanionProtocol.response(ok, 1, 2), "wrong request response");
            rejects(() -> CompanionProtocol.response(new CompanionProtocol.Packet(0x8003, 1, new byte[19]), 3, 1), "short session response");
            rejects(() -> CompanionProtocol.response(new CompanionProtocol.Packet(0x8001, 1, new byte[] {0,0,0,8}), 1, 1), "unknown status");
            rejects(() -> CompanionProtocol.response(new CompanionProtocol.Packet(0x8001, 1, new byte[] {0,0,0,1,0}), 1, 1), "error payload cannot contain extra data");
            try { CompanionProtocol.response(new CompanionProtocol.Packet(0x8001, 1, new byte[] {0,0,0,1}), 1, 1); throw new AssertionError("auth status"); }
            catch (CompanionProtocol.RemoteException error) { check(error.status == 1, "auth rejection preserved"); }
            CompanionProtocol.Frame image = CompanionProtocol.decodeFrame(frame(Long.MIN_VALUE), CompanionProtocol.PROFILE_VGA);
            check(image.id == Long.MIN_VALUE && image.width == 640 && image.height == 480 && Arrays.equals(image.jpeg, JPEG), "frame fields decoded");
            image = CompanionProtocol.decodeFrame(frame(2, 1280, 960), CompanionProtocol.PROFILE_HD);
            check(image.width == 1280 && image.height == 960, "HD frame geometry decoded");
            rejects(() -> CompanionProtocol.decodeFrame(frame(2, 1280, 960), CompanionProtocol.PROFILE_VGA), "HD frame in a VGA session");
            rejects(() -> CompanionProtocol.decodeFrame(frame(2), CompanionProtocol.PROFILE_HD), "VGA frame in an HD session");
            rejects(() -> CompanionProtocol.decodeFrame(frame(2, 1280, 480), CompanionProtocol.PROFILE_HD), "mixed geometry rejected");
            check(CompanionProtocol.newer(Long.MIN_VALUE, Long.MAX_VALUE), "unsigned frame ordering crosses signed boundary");
            check(!CompanionProtocol.newer(1, -1), "wrapped or stale frame is rejected");
            CompanionProtocol.Packet invalid = frame(1); invalid.payload[39]++;
            final CompanionProtocol.Packet invalidLength = invalid;
            rejects(() -> CompanionProtocol.decodeFrame(invalidLength, CompanionProtocol.PROFILE_VGA), "JPEG length mismatch");
            invalid = frame(1); invalid.payload[32] = 0;
            final CompanionProtocol.Packet dimensions = invalid;
            rejects(() -> CompanionProtocol.decodeFrame(dimensions, CompanionProtocol.PROFILE_VGA), "unknown image dimensions");
            byte[] photo = ByteBuffer.allocate(44 + JPEG.length).putLong(7).put(CompanionProtocol.sha256(JPEG)).putInt(JPEG.length).put(JPEG).array();
            check(CompanionProtocol.decodePhoto(photo).frameId == 7, "photo hash and metadata verified");
            photo[8] ^= 1;
            rejects(() -> CompanionProtocol.decodePhoto(photo), "corrupt photo hash rejected");
            byte[] arrow = CompanionProtocol.arrow(SESSION, 4, new FrameCoordinates.Point(0, 0.5), new FrameCoordinates.Point(1, 1));
            ByteBuffer coordinates = ByteBuffer.wrap(arrow);
            check(arrow.length == 32 && (coordinates.getShort(24) & 0xffff) == 0
                    && (coordinates.getShort(26) & 0xffff) == 32768 && (coordinates.getShort(28) & 0xffff) == 65535, "normalized arrow quantization");
            StringBuilder text = new StringBuilder(); for (int i = 0; i < 80; i++) { text.append('ç'); }
            check(CompanionProtocol.text(SESSION, 1, text.toString()).length == 186, "UTF-8 byte limit accepts exactly 160");
            rejects(() -> CompanionProtocol.text(SESSION, 1, text.toString() + 'ç'), "UTF-8 bytes rather than character count");
            rejects(() -> CompanionProtocol.text(SESSION, 1, ""), "empty text rejected");
            rejects(() -> CompanionProtocol.text(SESSION, 1, "a\nb"), "OS ASCII control boundary");
            rejects(() -> CompanionProtocol.frame(SESSION, 0), "zero frozen frame rejected");
            rejects(() -> CompanionProtocol.decodeFrame(frame(0), CompanionProtocol.PROFILE_VGA), "zero incoming frame rejected");
            rejects(() -> CompanionProtocol.text(SESSION, 1, "\ud800"), "unpaired surrogate rejected");
            rejects(() -> CompanionProtocol.utf8Text(new byte[] {(byte) 0xc0, (byte) 0x80}), "malformed UTF-8 rejected");
            rejects(() -> CompanionProtocol.session(new byte[15]), "short session rejected");
            byte[] end = ByteBuffer.allocate(20).put(SESSION).putInt(4).array();
            check(CompanionProtocol.decodeEnd(new CompanionProtocol.Packet(0x9002, 0, end), SESSION) == 4, "revocation end decoded");
            end[0] = 1;
            rejects(() -> CompanionProtocol.decodeEnd(new CompanionProtocol.Packet(0x9002, 0, end), SESSION), "foreign session end rejected");
            byte[] certificate = {1,2,3,4};
            PairingRecord pairing = new PairingRecord("192.168.77.1", 7443,
                    CompanionProtocol.hex(CompanionProtocol.sha256(certificate)), CompanionProtocol.hex(new byte[32]));
            check(pairing.matchesCertificate(certificate), "exact DER pin accepted");
            check(!pairing.matchesCertificate(new byte[] {1,2,3,5}), "changed DER pin rejected before AUTH");
            byte[] exported = pairing.token(); exported[0] = 1;
            check(pairing.token()[0] == 0, "credential arrays cannot mutate stored pairing");
            for (String host : new String[] {"example.com", "https://192.168.77.1", "8.8.8.8", "192.168.077.1", "192.168.0.256", "127.0.0.1"}) {
                check(!PairingRecord.localIpv4(host), "non-local or noncanonical endpoint rejected");
            }
            for (String host : new String[] {"192.168.77.1", "10.0.0.1", "172.16.0.1", "172.31.255.1", "169.254.1.2"}) {
                check(PairingRecord.localIpv4(host), "private/link-local endpoint accepted");
            }
        } catch (IOException error) { throw new AssertionError(error); }
        return checks;
    }
}
