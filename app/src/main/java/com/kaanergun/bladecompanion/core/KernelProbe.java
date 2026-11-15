package com.kaanergun.bladecompanion.core;

/** Fixed read-only diagnostic request with per-request framing and bounded replies. */
public final class KernelProbe {
    public static final int MAX_REPLY_BYTES = 8192;
    private final String begin;
    private final String end;

    public KernelProbe(String token) {
        if (token == null || !token.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Invalid request token");
        }
        begin = "BLADE_INFO_" + token;
        end = "BLADE_DONE_" + token;
    }

    public String command() {
        return "printf '\\n" + begin + "\\n'; uname -srm; printf '" + end + "\\n'\n";
    }

    public Identity parse(String reply) {
        if (reply == null || reply.length() > MAX_REPLY_BYTES) {
            throw new IllegalArgumentException("Reply exceeds limit");
        }
        String[] lines = reply.replace("\r", "").split("\n", -1);
        for (int i = 0; i + 2 < lines.length; i++) {
            if (!lines[i].equals(begin)) {
                continue;
            }
            if (!lines[i + 2].equals(end)) {
                continue;
            }
            String[] fields = lines[i + 1].split(" ", -1);
            if (fields.length != 3 || !fields[0].equals("Linux")
                    || !fields[1].matches("[A-Za-z0-9._+~-]{1,128}")
                    || !fields[2].equals("aarch64")) {
                throw new IllegalArgumentException("Unexpected kernel reply");
            }
            return new Identity(fields[1], fields[2]);
        }
        return null;
    }

    public static final class Identity {
        public final String release;
        public final String architecture;

        private Identity(String release, String architecture) {
            this.release = release;
            this.architecture = architecture;
        }
    }
}
