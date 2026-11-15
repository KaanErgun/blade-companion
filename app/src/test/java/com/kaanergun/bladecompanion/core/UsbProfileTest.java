package com.kaanergun.bladecompanion.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class UsbProfileTest {
    private static int checks;
    private UsbProfileTest() { }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { throw new AssertionError(label); }
    }
    private static CdcAcmProfile.Interface intf(int id, int alt, int cls, int sub, int protocol,
            CdcAcmProfile.Endpoint... endpoints) {
        return new CdcAcmProfile.Interface(id, alt, cls, sub, protocol, Arrays.asList(endpoints));
    }
    private static CdcAcmProfile.Endpoint ep(int address, int type) { return new CdcAcmProfile.Endpoint(address, type); }
    private static List<CdcAcmProfile.Interface> composite() {
        return new ArrayList<>(Arrays.asList(
                intf(0, 0, 2, 6, 0, ep(0x81, 3)),
                intf(1, 0, 10, 0, 0),
                intf(1, 1, 10, 0, 0, ep(0x82, 2), ep(3, 2)),
                intf(2, 0, 2, 2, 1, ep(0x84, 3)),
                intf(3, 0, 10, 0, 0, ep(0x85, 2), ep(6, 2))));
    }
    private static CdcAcmProfile match(List<CdcAcmProfile.Interface> interfaces) {
        return CdcAcmProfile.find(0x1d6b, 0x0104, 0xef, 2, 1, 1, interfaces);
    }
    private static byte[] hex(String input) {
        String[] words = input.trim().split("\\s+");
        byte[] bytes = new byte[words.length];
        for (int i = 0; i < words.length; i++) { bytes[i] = (byte) Integer.parseInt(words[i], 16); }
        return bytes;
    }
    private static byte[] descriptors(boolean composite) {
        // USB 2 descriptor fixtures follow Linux f_ecm/f_acm: ECM has two data alternates.
        String device = composite ? "12 01 00 02 ef 02 01 40 6b 1d 04 01 00 01 01 02 03 01 "
                : "12 01 00 02 02 00 00 40 25 05 a7 a4 00 01 01 02 03 01 ";
        String ecm = "08 0b 00 02 02 06 00 00 09 04 00 00 01 02 06 00 00 "
                + "05 24 00 10 01 05 24 06 00 01 0d 24 0f 04 00 00 00 00 ea 05 00 00 00 "
                + "07 05 81 03 10 00 09 09 04 01 00 00 0a 00 00 00 "
                + "09 04 01 01 02 0a 00 00 00 07 05 82 02 00 02 00 07 05 03 02 00 02 00 ";
        String acm = composite
                ? "08 0b 02 02 02 02 01 00 09 04 02 00 01 02 02 01 00 "
                    + "05 24 00 10 01 05 24 01 00 03 04 24 02 02 05 24 06 02 03 "
                    + "07 05 84 03 0a 00 09 09 04 03 00 02 0a 00 00 00 "
                    + "07 05 85 02 00 02 00 07 05 06 02 00 02 00"
                : "08 0b 00 02 02 02 01 00 09 04 00 00 01 02 02 01 00 "
                    + "05 24 00 10 01 05 24 01 00 01 04 24 02 02 05 24 06 00 01 "
                    + "07 05 81 03 0a 00 09 09 04 01 00 02 0a 00 00 00 "
                    + "07 05 82 02 00 02 00 07 05 03 02 00 02 00";
        byte[] bytes = hex(device + "09 02 00 00 " + (composite ? "04 01" : "02 02")
                + " 00 80 fa " + (composite ? ecm : "") + acm);
        int length = bytes.length - 18;
        bytes[20] = (byte) length;
        bytes[21] = (byte) (length >> 8);
        return bytes;
    }
    private static int descriptor(byte[] raw, int type, int subtype, int occurrence) {
        for (int offset = 27; offset < raw.length; offset += raw[offset] & 255) {
            if ((raw[offset + 1] & 255) == type && (subtype < 0 || (raw[offset + 2] & 255) == subtype)
                    && occurrence-- == 0) { return offset; }
        }
        throw new AssertionError("Fixture descriptor missing");
    }
    private static byte[] changed(byte[] input, int offset, int value) {
        byte[] copy = input.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    private static byte[] withoutAssociation(byte[] input, int occurrence) {
        int offset = descriptor(input, 11, -1, occurrence);
        byte[] result = new byte[input.length - 8];
        System.arraycopy(input, 0, result, 0, offset);
        System.arraycopy(input, offset + 8, result, offset, input.length - offset - 8);
        result[20] = (byte) (result.length - 18);
        result[21] = (byte) ((result.length - 18) >> 8);
        return result;
    }

    static int run() {
        List<CdcAcmProfile.Interface> interfaces = composite();
        CdcAcmProfile profile = match(interfaces);
        check(profile != null && profile.controlId == 2 && profile.dataId == 3
                && profile.inputAddress == 0x85 && profile.outputAddress == 6, "ECM+ACM selects only serial pair");
        Collections.reverse(interfaces);
        check(match(interfaces) != null, "composite enumeration order does not matter");
        interfaces = composite(); interfaces.remove(1);
        check(match(interfaces) == null, "ECM idle alternate required");
        interfaces = composite(); interfaces.remove(2);
        check(match(interfaces) == null, "ECM active alternate required");
        interfaces = composite(); interfaces.set(2, intf(1, 0, 10, 0, 0, ep(0x82, 2), ep(3, 2)));
        check(match(interfaces) == null, "duplicate interface and alternate rejected");
        interfaces = composite(); interfaces.set(1, intf(1, 0, 10, 0, 0, ep(7, 2)));
        check(match(interfaces) == null, "idle alternate must have no endpoints");
        interfaces = composite(); interfaces.set(0, intf(0, 0, 2, 13, 0, ep(0x81, 3)));
        check(match(interfaces) == null, "NCM is not the ECM profile");
        interfaces = composite(); interfaces.set(3, intf(2, 0, 2, 2, 0, ep(0x84, 3)));
        check(match(interfaces) == null, "wrong ACM protocol rejected");
        interfaces = composite(); interfaces.set(4, intf(3, 0, 10, 0, 0, ep(0x82, 2), ep(6, 2)));
        check(match(interfaces) == null, "ACM cannot reuse ECM endpoint address");
        interfaces = composite(); interfaces.add(intf(4, 0, 255, 0, 0));
        check(match(interfaces) == null, "extra foreign interface rejected");
        check(CdcAcmProfile.find(0x1d6b, 0x0104, 0, 0, 0, 1, composite()) == null, "composite device tuple exact");
        check(CdcAcmProfile.find(0x1d6b, 0x0104, 0xef, 2, 1, 2, composite()) == null, "multiple configurations rejected");
        check(CdcAcmProfile.find(0x1d6b, 0x0105, 0xef, 2, 1, 1, composite()) == null, "foreign composite PID rejected");
        byte[] raw = descriptors(true);
        check(profile.matchesDescriptors(raw), "valid ECM and ACM unions, five records and four IDs");
        CdcAcmProfile serial = CdcAcmProfile.find(0x0525, 0xa4a7, 2, 0, 0, 1, Arrays.asList(
                intf(0, 0, 2, 2, 1, ep(0x81, 3)), intf(1, 0, 10, 0, 0, ep(0x82, 2), ep(3, 2))));
        check(serial != null && serial.matchesDescriptors(descriptors(false)), "standalone ACM raw descriptor regression");
        check(!serial.matchesDescriptors(raw), "different valid profile cannot replace selected device");
        check(!profile.matchesDescriptors(withoutAssociation(raw, 0)), "ECM association required");
        check(!profile.matchesDescriptors(withoutAssociation(raw, 1)), "composite ACM association required");
        check(!profile.matchesDescriptors(withoutAssociation(withoutAssociation(raw, 1), 0)),
                "composite without either association rejected despite valid unions");
        check(!serial.matchesDescriptors(withoutAssociation(descriptors(false), 0)), "standalone ACM association required");
        check(!profile.matchesDescriptors(changed(raw, 23, 7)), "composite configuration value must be one");
        check(!serial.matchesDescriptors(changed(descriptors(false), 23, 1)), "standalone configuration value must be two");
        int acmUnion = descriptor(raw, 0x24, 6, 1);
        int ecmUnion = descriptor(raw, 0x24, 6, 0);
        check(!profile.matchesDescriptors(changed(raw, acmUnion + 4, 1)), "ACM union cannot target ECM data");
        check(!profile.matchesDescriptors(changed(raw, acmUnion + 3, 0)), "union master must own descriptor");
        check(!profile.matchesDescriptors(changed(raw, ecmUnion + 4, 3)), "ECM union cannot target ACM data");
        check(!profile.matchesDescriptors(changed(raw, acmUnion + 2, 2)), "missing ACM union rejected");
        check(!profile.matchesDescriptors(changed(raw, ecmUnion + 2, 2)), "missing ECM union rejected");
        check(!profile.matchesDescriptors(changed(raw, acmUnion, 4)), "truncated union rejected");
        int acmData = descriptor(raw, 4, 3, 0);
        check(!profile.matchesDescriptors(changed(raw, acmData + 4, 1)), "endpoint count mismatch rejected");
        int acmInput = descriptor(raw, 5, 0x85, 0);
        check(!profile.matchesDescriptors(changed(raw, acmInput + 2, 0x87)), "raw endpoints must match selected profile");
        check(!profile.matchesDescriptors(changed(raw, acmInput + 3, 3)), "raw bulk transfer type required");
        check(!profile.matchesDescriptors(changed(raw, acmInput + 5, 0)), "zero maximum packet size rejected");
        check(!profile.matchesDescriptors(changed(raw, 22, 5)), "bNumInterfaces counts IDs, not alternate records");
        check(!profile.matchesDescriptors(changed(raw, 20, 1)), "configuration total length enforced");
        check(!profile.matchesDescriptors(changed(raw, 8, 0x25)), "foreign raw VID rejected");
        check(!profile.matchesDescriptors(changed(raw, 27, 0)), "zero descriptor length rejected");
        check(!profile.matchesDescriptors(changed(raw, 27, 255)), "overlong descriptor rejected");
        check(!profile.matchesDescriptors(changed(raw, 30, 3)), "ambiguous association rejected");
        byte[] duplicateUnion = Arrays.copyOf(raw, raw.length + 5);
        System.arraycopy(raw, acmUnion, duplicateUnion, raw.length, 5);
        duplicateUnion[20] = (byte) (duplicateUnion.length - 18);
        check(!profile.matchesDescriptors(duplicateUnion), "union under data interface rejected");
        byte[] duplicateControlUnion = new byte[raw.length + 5];
        System.arraycopy(raw, 0, duplicateControlUnion, 0, acmUnion);
        System.arraycopy(raw, acmUnion, duplicateControlUnion, acmUnion, 5);
        System.arraycopy(raw, acmUnion, duplicateControlUnion, acmUnion + 5, raw.length - acmUnion);
        duplicateControlUnion[20] = (byte) (duplicateControlUnion.length - 18);
        check(!profile.matchesDescriptors(duplicateControlUnion), "duplicate union under control interface rejected");
        check(!profile.matchesDescriptors(null), "absent raw descriptors rejected");
        check(!profile.matchesDescriptors(new byte[65554]), "raw descriptor bound enforced");
        boolean allTruncationsRejected = true;
        for (int length = 0; length < raw.length; length++) {
            byte[] truncated = Arrays.copyOf(raw, length);
            if (length >= 27) { truncated[20] = (byte) (length - 18); truncated[21] = 0; }
            if (profile.matchesDescriptors(truncated)) { allTruncationsRejected = false; }
        }
        check(allTruncationsRejected, "all prefix truncations rejected without an exception");
        return checks;
    }
}
