package dev.supavolt.api.unit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.supavolt.api.features.auth.AspNetIdentityPasswordHasher;
import java.nio.ByteBuffer;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import java.util.Map;

/** Users registered on the .NET API must still be able to sign in. */
class AspNetIdentityPasswordHasherTest {

    private final AspNetIdentityPasswordHasher hasher = new AspNetIdentityPasswordHasher();

    /** A v3 hash laid out exactly as ASP.NET Core Identity writes it: SHA-512, 100,000 iterations. */
    private static String identityV3(String password, int prf, String algorithm, int iterations) throws Exception {
        var salt = new byte[16];
        for (var i = 0; i < salt.length; i++) salt[i] = (byte) (i * 7 + 3);

        var subkey = SecretKeyFactory.getInstance(algorithm)
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256))
                .getEncoded();

        var buffer = ByteBuffer.allocate(13 + salt.length + subkey.length);
        buffer.put((byte) 0x01).putInt(prf).putInt(iterations).putInt(salt.length).put(salt).put(subkey);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    /**
     * Written by .NET 10's {@code PasswordHasher<TUser>} with default options — the hasher the .NET
     * API registered — for "correct-horse-battery": v3 is PBKDF2-HMAC-SHA512 with 100,000
     * iterations, v2 is the IdentityV2 compatibility mode.
     */
    private static final String DOTNET_V3 =
            "AQAAAAIAAYagAAAAEFsEn7liNV3nNdLPXPdz7Js5pOayU4O+bbXzyilTCRM1tdyLDSbSEXtlqWe8Vg2yhQ==";
    private static final String DOTNET_V2 =
            "AGkkQ9xwUnhy54nFzqsQk6IzllGBuvripIpwJUvovwYXe0LZmw86qf39czCzDmnFrA==";

    @Test
    void verifies_hashes_written_by_aspnet_core_identity() {
        assertThat(hasher.matches("correct-horse-battery", DOTNET_V3)).isTrue();
        assertThat(hasher.matches("correct-horse-batterY", DOTNET_V3)).isFalse();
        assertThat(hasher.matches("correct-horse-battery", DOTNET_V2)).isTrue();
        assertThat(hasher.matches("wrong", DOTNET_V2)).isFalse();
    }

    @Test
    void verifies_the_default_v3_format() throws Exception {
        var hash = identityV3("correct-horse-battery", 2, "PBKDF2WithHmacSHA512", 100_000);

        assertThat(hasher.matches("correct-horse-battery", hash)).isTrue();
        assertThat(hasher.matches("wrong-horse-battery", hash)).isFalse();
    }

    @Test
    void verifies_older_v3_parameters() throws Exception {
        assertThat(hasher.matches("pw", identityV3("pw", 1, "PBKDF2WithHmacSHA256", 10_000))).isTrue();
    }

    @Test
    void rejects_malformed_hashes() {
        assertThat(hasher.matches("pw", "")).isFalse();
        assertThat(hasher.matches("pw", "not base64!")).isFalse();
        assertThat(hasher.matches("pw", Base64.getEncoder().encodeToString(new byte[] {0x01, 0, 0}))).isFalse();
    }

    @Test
    void legacy_hashes_verify_and_ask_to_be_upgraded() throws Exception {
        var encoder = new DelegatingPasswordEncoder("argon2", Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        encoder.setDefaultPasswordEncoderForMatches(hasher);
        var legacy = identityV3("pw", 2, "PBKDF2WithHmacSHA512", 1_000);

        assertThat(encoder.matches("pw", legacy)).isTrue();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();

        var fresh = encoder.encode("pw");
        assertThat(fresh).startsWith("{argon2}");
        assertThat(encoder.upgradeEncoding(fresh)).isFalse();
    }
}
