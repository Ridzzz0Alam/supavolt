package dev.supavolt.api.features.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * HS256 signing and validation. Keys are the UTF-8 bytes of the configured secret, as in the .NET
 * API and the Next.js middleware, so tokens and project keys stay valid across the two.
 */
public final class Jwts {

    private Jwts() {
    }

    public static SecretKeySpec key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Signs with Nimbus directly rather than NimbusJwtEncoder, which adds a {@code kid} header
     * derived from the key itself: no reason to publish a hash of the signing secret.
     */
    public static String sign(String secret, JwtClaimsSet claims) {
        var builder = new JWTClaimsSet.Builder();
        claims.getClaims().forEach((name, value) -> builder.claim(name,
                value instanceof java.time.Instant instant ? Date.from(instant) : value));

        // A single audience is written as a string, as the .NET API did.
        var audience = claims.getAudience();
        if (audience != null && audience.size() == 1) builder.audience(audience.get(0));

        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), builder.build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a token", e);
        }
    }

    /** Checks signature, issuer, audience, and exp/nbf when present (with 30 seconds of skew). */
    public static JwtDecoder decoder(String secret, String issuer, String audience) {
        var decoder = NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer),
                audience(audience)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audience(String expected) {
        var error = new OAuth2Error("invalid_token", "The token audience is not accepted", null);
        return jwt -> {
            List<String> audiences = jwt.getAudience();
            return audiences != null && audiences.contains(expected)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(error);
        };
    }
}
