package dev.supavolt.api.features.auth;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies hashes written by ASP.NET Core Identity's {@code PasswordHasher}, so users registered on
 * the .NET API can still sign in. Never used to create hashes: a successful sign-in rehashes the
 * password with the current encoder (see {@code PasswordConfig}).
 *
 * <p>Format v3 (marker 0x01): PRF as a big-endian uint32 (0 SHA-1, 1 SHA-256, 2 SHA-512), the
 * iteration count, the salt length, the salt, then the PBKDF2 subkey. Format v2 (marker 0x00):
 * PBKDF2-HMAC-SHA1, 1000 iterations, a 16-byte salt and a 32-byte subkey.
 */
public class AspNetIdentityPasswordHasher implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
        throw new UnsupportedOperationException("ASP.NET Identity hashes are only verified, never created");
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isEmpty()) return false;

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedPassword);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (decoded.length == 0) return false;

        return switch (decoded[0]) {
            case 0x00 -> verifyV2(rawPassword, decoded);
            case 0x01 -> verifyV3(rawPassword, decoded);
            default -> false;
        };
    }

    private static boolean verifyV2(CharSequence password, byte[] hash) {
        if (hash.length != 1 + 16 + 32) return false;

        var salt = Arrays.copyOfRange(hash, 1, 17);
        var expected = Arrays.copyOfRange(hash, 17, 49);
        return MessageDigest.isEqual(expected, pbkdf2("PBKDF2WithHmacSHA1", password, salt, 1000, 32));
    }

    private static boolean verifyV3(CharSequence password, byte[] hash) {
        if (hash.length < 13) return false;

        var header = ByteBuffer.wrap(hash, 1, 12);
        var prf = header.getInt();
        var iterations = header.getInt();
        var saltLength = header.getInt();

        if (iterations <= 0 || saltLength < 16 || 13 + saltLength >= hash.length) return false;

        var algorithm = switch (prf) {
            case 0 -> "PBKDF2WithHmacSHA1";
            case 1 -> "PBKDF2WithHmacSHA256";
            case 2 -> "PBKDF2WithHmacSHA512";
            default -> null;
        };
        if (algorithm == null) return false;

        var salt = Arrays.copyOfRange(hash, 13, 13 + saltLength);
        var expected = Arrays.copyOfRange(hash, 13 + saltLength, hash.length);
        if (expected.length < 16) return false;

        return MessageDigest.isEqual(expected, pbkdf2(algorithm, password, salt, iterations, expected.length));
    }

    private static byte[] pbkdf2(String algorithm, CharSequence password, byte[] salt, int iterations, int length) {
        var chars = password.toString().toCharArray();
        try {
            // SunJCE's PBKDF2 encodes the password as UTF-8, as .NET's KeyDerivation.Pbkdf2 does.
            var spec = new PBEKeySpec(chars, salt, iterations, length * 8);
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is unavailable", e);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }
}
