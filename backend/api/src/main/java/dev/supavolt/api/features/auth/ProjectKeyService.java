package dev.supavolt.api.features.auth;

import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.contracts.Contracts.ProjectKeyRole;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class ProjectKeyService {

    public static final String PROJECT_ID = "project_id";
    public static final String ROLE = "project_role";
    public static final String KEY_VERSION = "key_version";

    private final String issuer;
    private final Clock clock;
    private final String secret;
    private final JwtDecoder decoder;

    public ProjectKeyService(SupavoltProperties properties, Clock clock) {
        var keys = properties.projectKeys();
        this.issuer = keys.issuer();
        this.clock = clock;
        this.secret = keys.secret();
        this.decoder = Jwts.decoder(keys.secret(), keys.issuer(), keys.issuer());
    }

    /**
     * Keys do not expire — they are long-lived credentials — but they carry a version claim, so
     * rotating a project's keys invalidates every key issued before.
     */
    public String sign(UUID projectId, ProjectKeyRole role, int keyVersion) {
        return Jwts.sign(secret, JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(issuer))
                .issuedAt(clock.instant())
                .claim(PROJECT_ID, projectId.toString())
                .claim(ROLE, role == ProjectKeyRole.SERVICE_ROLE ? "service_role" : "anon")
                .claim(KEY_VERSION, Integer.toString(keyVersion))
                .build());
    }

    /** Null for anything that is not a key this API signed. Version is checked by the resolver. */
    public ProjectKey validate(String token) {
        try {
            var jwt = decoder.decode(token);
            var projectId = jwt.getClaimAsString(PROJECT_ID);
            if (projectId == null) return null;

            var version = jwt.getClaimAsString(KEY_VERSION);
            return new ProjectKey(
                    UUID.fromString(projectId),
                    version == null ? 0 : Integer.parseInt(version),
                    "service_role".equals(jwt.getClaimAsString(ROLE)));
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
