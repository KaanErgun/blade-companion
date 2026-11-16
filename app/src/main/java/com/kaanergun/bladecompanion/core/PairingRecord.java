package com.kaanergun.bladecompanion.core;

public final class PairingRecord {
    public final String host;
    public final int port;
    private final byte[] certificate;
    private final byte[] token;

    public PairingRecord(String host, int port, String certificateHex, String tokenHex) {
        if (!localIpv4(host) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("A private or link-local IPv4 endpoint is required");
        }
        this.host = host; this.port = port;
        certificate = CompanionProtocol.unhex(certificateHex, 32);
        token = CompanionProtocol.unhex(tokenHex, 32);
    }

    public static boolean localIpv4(String value) {
        if (value == null || !value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) { return false; }
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].length() > 3) { return false; }
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255 || !parts[i].equals(Integer.toString(octets[i]))) { return false; }
        }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 169 && octets[1] == 254);
    }

    public boolean matchesCertificate(byte[] der) {
        return der != null && java.security.MessageDigest.isEqual(certificate, CompanionProtocol.sha256(der));
    }

    public byte[] certificate() { return certificate.clone(); }
    public byte[] token() { return token.clone(); }
    public PairingRecord withHost(String value) {
        return new PairingRecord(value, port, CompanionProtocol.hex(certificate), CompanionProtocol.hex(token));
    }
}
