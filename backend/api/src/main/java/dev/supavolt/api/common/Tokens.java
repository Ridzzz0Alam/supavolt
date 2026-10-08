package dev.supavolt.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/** Random tokens, their hashes, and time-ordered ids. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private Tokens() {
    }

    /** {@code length} lowercase hex characters from a CSPRNG. */
    public static String randomHex(int length) {
        var bytes = new byte[(length + 1) / 2];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes).substring(0, length);
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes. Stored tokens are only ever kept as this. */
    public static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A version 7 UUID: time-ordered, so object keys sort by upload time. */
    public static UUID uuidV7() {
        var bytes = new byte[16];
        RANDOM.nextBytes(bytes);

        var millis = System.currentTimeMillis();
        for (var i = 0; i < 6; i++) bytes[i] = (byte) (millis >>> (40 - 8 * i));

        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x70);   // version 7
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);   // IETF variant

        long msb = 0;
        long lsb = 0;
        for (var i = 0; i < 8; i++) msb = (msb << 8) | (bytes[i] & 0xff);
        for (var i = 8; i < 16; i++) lsb = (lsb << 8) | (bytes[i] & 0xff);
        return new UUID(msb, lsb);
    }
}
