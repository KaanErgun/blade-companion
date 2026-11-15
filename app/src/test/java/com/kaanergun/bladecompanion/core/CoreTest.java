package com.kaanergun.bladecompanion.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class CoreTest {
    private static int checks;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    private CoreTest() { }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { throw new AssertionError(label); }
    }

    private static void rejects(Runnable task, String label) {
        checks++;
        try { task.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(label);
    }

    private static CdcAcmProfile.Interface control(int id, int endpoint) {
        return new CdcAcmProfile.Interface(id, 0, 2, 2, 1,
                Collections.singletonList(new CdcAcmProfile.Endpoint(endpoint, 3)));
    }

    private static CdcAcmProfile.Interface data(int id, int input, int output) {
        return new CdcAcmProfile.Interface(id, 0, 10, 0, 0, Arrays.asList(
                new CdcAcmProfile.Endpoint(input, 2), new CdcAcmProfile.Endpoint(output, 2)));
    }

    private static CdcAcmProfile match(CdcAcmProfile.Interface... interfaces) {
        return CdcAcmProfile.find(0x0525, 0xa4a7, 2, 0, 0, 1, Arrays.asList(interfaces));
    }

    public static void main(String[] args) {
        List<CdcAcmProfile.Interface> valid = Arrays.asList(control(0, 0x81), data(1, 0x82, 3));
        CdcAcmProfile profile = CdcAcmProfile.find(0x0525, 0xa4a7, 2, 0, 0, 1, valid);
        check(profile != null && profile.controlId == 0 && profile.dataId == 1, "known diagnostic gadget");
        check(profile.inputAddress == 0x82 && profile.outputAddress == 3, "endpoint directions");
        check(match(data(4, 0x83, 2), control(3, 0x81)).controlId == 3, "interface order and IDs");
        check(CdcAcmProfile.find(1, 0xa4a7, 2, 0, 0, 1, valid) == null, "unknown VID rejected");
        check(CdcAcmProfile.find(0x0525, 1, 2, 0, 0, 1, valid) == null, "unknown PID rejected");
        check(CdcAcmProfile.find(0x0525, 0xa4a7, 2, 0, 0, 1, null) == null, "absent interfaces");
        check(match(control(0, 0x81)) == null, "missing data interface");
        check(match(control(0, 0x81), data(1, 0x82, 3), data(2, 0x84, 4)) == null, "ambiguous composite gadget");
        check(match(control(0, 0x81), data(0, 0x82, 3)) == null, "duplicate interface IDs");
        check(match(control(0, 1), data(1, 0x82, 3)) == null, "notification must be IN");
        check(match(control(0, 0x81), data(1, 0x82, 0x83)) == null, "two IN endpoints rejected");
        check(match(control(0, 0x81), data(1, 2, 3)) == null, "two OUT endpoints rejected");
        check(match(control(0, 0x81), data(1, 0x81, 3)) == null, "duplicate endpoint address");
        check(match(control(0, 0x81), data(1, 0x80, 3)) == null, "endpoint zero rejected");
        check(match(control(0, 0x81), data(1, 0x92, 3)) == null, "reserved address bits rejected");
        check(match(control(-1, 0x81), data(1, 0x82, 3)) == null, "invalid interface ID");
        check(match(control(0, 0x81), null) == null, "null interface");
        check(match(control(0, 0x81), new CdcAcmProfile.Interface(1, 0, 10, 0, 0,
                Arrays.asList(new CdcAcmProfile.Endpoint(0x82, 3), new CdcAcmProfile.Endpoint(3, 2)))) == null,
                "data transfer type must be bulk");
        checks += UsbProfileTest.run();
        KernelProbe probe = new KernelProbe(TOKEN);
        String begin = "BLADE_INFO_" + TOKEN;
        String end = "BLADE_DONE_" + TOKEN;
        String frame = begin + "\nLinux 6.18.63-blade-bringup aarch64\n" + end + "\n";
        KernelProbe.Identity identity = probe.parse(frame);
        check(identity != null && identity.release.equals("6.18.63-blade-bringup"), "framed kernel release");
        check(identity.architecture.equals("aarch64"), "architecture parsed");
        check(probe.parse(probe.command() + frame) != null, "echoed shell command is not a reply");
        check(probe.parse(frame.replace("\n", "\r\n")) != null, "terminal CRLF");
        check(probe.parse(frame.substring(0, frame.indexOf(end))) == null, "partial reply not success");
        check(probe.parse("Linux 6.18.63 aarch64\n") == null, "unframed data not success");
        check(probe.parse(frame.replace(TOKEN, "ffffffffffffffffffffffffffffffff")) == null, "stale request token");
        check(probe.parse(probe.command()) == null, "echo only not success");
        rejects(() -> probe.parse(frame.replace("Linux", "Darwin")), "unexpected system");
        rejects(() -> probe.parse(frame.replace("aarch64", "armv7l")), "unexpected architecture");
        rejects(() -> probe.parse(frame.replace("6.18.63-blade-bringup", "6.18;reboot")), "untrusted reply token");
        rejects(() -> probe.parse(new String(new char[8193])), "oversized reply");
        rejects(() -> probe.parse(null), "missing reply");
        rejects(() -> new KernelProbe("; reboot"), "command injection in token");
        rejects(() -> new KernelProbe(null), "missing token");
        check(probe.command().equals("printf '\\n" + begin + "\\n'; uname -srm; printf '" + end + "\\n'\n"),
                "exact fixed read-only command");
        if (checks < 30) { throw new AssertionError("Incomplete harness"); }
        System.out.println("Core checks passed: " + checks);
    }
}
