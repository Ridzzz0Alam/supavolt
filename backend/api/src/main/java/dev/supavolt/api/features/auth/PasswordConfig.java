package dev.supavolt.api.features.auth;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class PasswordConfig {

    /**
     * New hashes are Argon2, stored with an {@code {argon2}} prefix. Hashes with no prefix are the
     * .NET API's ASP.NET Identity format: they verify, and {@code upgradeEncoding} reports them so
     * sign-in can rehash them.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        var encoder = new DelegatingPasswordEncoder("argon2",
                Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        encoder.setDefaultPasswordEncoderForMatches(new AspNetIdentityPasswordHasher());
        return encoder;
    }
}
