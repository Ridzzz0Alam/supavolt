package dev.supavolt.api.features.auth;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Tokens;
import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.infrastructure.persistence.RefreshToken;
import dev.supavolt.api.infrastructure.persistence.RefreshTokenRepository;
import dev.supavolt.api.infrastructure.persistence.User;
import dev.supavolt.api.infrastructure.persistence.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    /** The user-id claim the .NET API wrote. Read as a fallback so its sessions survive the switch. */
    private static final String LEGACY_ID_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier";
    private static final String LEGACY_EMAIL_CLAIM = "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress";

    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final SupavoltProperties.Jwt jwt;
    private final Clock clock;
    private final JwtDecoder decoder;

    public TokenService(
            RefreshTokenRepository refreshTokens, UserRepository users, SupavoltProperties properties, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.jwt = properties.jwt();
        this.clock = clock;
        this.decoder = Jwts.decoder(jwt.accessSecret(), jwt.issuer(), jwt.audience());
    }

    public String createAccessToken(User user) {
        var now = clock.instant();

        return Jwts.sign(jwt.accessSecret(), JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .audience(List.of(jwt.audience()))
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(jwt.accessLifetime()))
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getName() == null ? "" : user.getName())
                .build());
    }

    /** Null when the cookie is missing, expired, forged, or for another issuer or audience. */
    public DashboardUser readAccessToken(String token) {
        try {
            var claims = decoder.decode(token);
            var id = claims.getSubject() != null ? claims.getSubject() : claims.getClaimAsString(LEGACY_ID_CLAIM);
            var email = claims.hasClaim("email") ? claims.getClaimAsString("email") : claims.getClaimAsString(LEGACY_EMAIL_CLAIM);
            return id == null ? null : new DashboardUser(UUID.fromString(id), email, claims.getClaimAsString("name"));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Issues an opaque refresh token and stores only its hash. Rotation with reuse detection:
     * presenting an already-rotated token revokes the whole chain for that user.
     */
    public IssuedTokens issue(User user) {
        return store(user).tokens();
    }

    private Stored store(User user) {
        var raw = Tokens.randomHex(64);
        var expires = OffsetDateTime.now(clock).plus(jwt.refreshLifetime());

        var stored = new RefreshToken();
        stored.setUserId(user.getId());
        stored.setTokenHash(Tokens.sha256(raw));
        stored.setExpiresAt(expires);
        refreshTokens.save(stored);

        return new Stored(new IssuedTokens(createAccessToken(user), raw, expires), stored.getId());
    }

    private record Stored(IssuedTokens tokens, UUID id) {
    }

    // Not @Transactional on purpose: the chain revocation must commit even though the call then fails.
    public IssuedTokens rotate(String presented) {
        if (presented == null || presented.isBlank())
            throw AppException.unauthorized("No refresh token");

        var stored = refreshTokens.findByTokenHash(Tokens.sha256(presented))
                .orElseThrow(() -> AppException.unauthorized("Invalid refresh token"));

        var now = OffsetDateTime.now(clock);

        if (stored.getRevokedAt() != null) {
            // Reuse of a rotated token means it leaked. Kill every live token for this user.
            refreshTokens.revokeAllForUser(stored.getUserId(), now);
            throw AppException.unauthorized("Refresh token reuse detected; please sign in again");
        }

        if (!stored.getExpiresAt().isAfter(now))
            throw AppException.unauthorized("Refresh token expired");

        var user = users.findById(stored.getUserId())
                .orElseThrow(() -> AppException.unauthorized("Invalid refresh token"));
        var next = store(user);

        stored.setRevokedAt(now);
        stored.setReplacedByTokenId(next.id());
        refreshTokens.save(stored);

        return next.tokens();
    }

    public void revokeAll(UUID userId) {
        refreshTokens.revokeAllForUser(userId, OffsetDateTime.now(clock));
    }
}
