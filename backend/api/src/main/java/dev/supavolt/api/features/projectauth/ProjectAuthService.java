package dev.supavolt.api.features.projectauth;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Tokens;
import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.features.auth.Jwts;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.infrastructure.mail.EmailSender;
import dev.supavolt.api.infrastructure.persistence.Project;
import dev.supavolt.api.infrastructure.persistence.ProjectAuthToken;
import dev.supavolt.api.infrastructure.persistence.ProjectAuthTokenRepository;
import dev.supavolt.api.infrastructure.persistence.ProjectRepository;
import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.contracts.Contracts.AuthProvider;
import dev.supavolt.contracts.Contracts.ProjectAuthResponse;
import dev.supavolt.contracts.Contracts.ProjectAuthUserDto;
import dev.supavolt.contracts.Contracts.ProjectOAuthSettingsDto;
import dev.supavolt.contracts.Contracts.SignInRequest;
import dev.supavolt.contracts.Contracts.SignUpRequest;
import dev.supavolt.contracts.Contracts.UpdateOAuthSettingsRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class ProjectAuthService {

    private static final Duration TOKEN_LIFETIME = Duration.ofDays(7);
    private static final Duration MAGIC_LINK_LIFETIME = Duration.ofMinutes(15);
    private static final Duration EXCHANGE_CODE_LIFETIME = Duration.ofMinutes(2);

    private static final DataClassRowMapper<AuthUserRow> USER_ROW = new DataClassRowMapper<>(AuthUserRow.class);

    private final ProjectRepository projectRepository;
    private final ProjectAuthTokenRepository authTokens;
    private final TenantConnections connections;
    private final ProjectResolver projects;
    private final PasswordEncoder passwords;
    private final EmailSender mail;
    private final SupavoltProperties properties;
    private final Clock clock;

    public ProjectAuthService(
            ProjectRepository projectRepository,
            ProjectAuthTokenRepository authTokens,
            TenantConnections connections,
            ProjectResolver projects,
            PasswordEncoder passwords,
            EmailSender mail,
            SupavoltProperties properties,
            Clock clock) {
        this.projectRepository = projectRepository;
        this.authTokens = authTokens;
        this.connections = connections;
        this.projects = projects;
        this.passwords = passwords;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    // ── End-user records live in the project's own schema ────────────────────

    public record AuthUserRow(
            UUID id, String email, String passwordHash, boolean emailVerified, String provider, OffsetDateTime createdAt) {
    }

    private static final String USER_COLUMNS = "id, email, password_hash, email_verified, provider, created_at";

    private AuthUserRow findUser(SqlIdentifier schema, String email) {
        return connections.admin()
                .sql("SELECT " + USER_COLUMNS + " FROM " + schema + ".\"auth_users\" WHERE lower(email) = lower(:email)")
                .param("email", email)
                .query(USER_ROW).optional().orElse(null);
    }

    private AuthUserRow insertUser(
            SqlIdentifier schema, String email, String passwordHash, AuthProvider provider, boolean emailVerified) {
        return connections.admin()
                .sql("INSERT INTO " + schema + ".\"auth_users\" (email, password_hash, provider, email_verified)"
                        + " VALUES (:email, :passwordHash, :provider, :emailVerified) RETURNING " + USER_COLUMNS)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("provider", provider.name().toLowerCase(Locale.ROOT))
                .param("emailVerified", emailVerified)
                .query(USER_ROW).single();
    }

    // ── Tokens ──────────────────────────────────────────────────────────────

    /**
     * The project's end-user signing secret. One that was regenerated on load (see Project) is
     * saved before it signs anything, or the next load would generate yet another.
     */
    private String jwtSecret(Project project) {
        if (project.secretReplaced()) projectRepository.save(project);
        return project.getAuthJwtSecret();
    }

    private String issueToken(Project project, AuthUserRow user) {
        var now = clock.instant();

        return Jwts.sign(jwtSecret(project), JwtClaimsSet.builder()
                .issuer(project.getProjectUrl())
                .audience(List.of(project.getSlug()))
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_LIFETIME))
                .subject(user.id().toString())
                .claim("email", user.email())
                .claim("project_id", project.getId().toString())
                .build());
    }

    /**
     * Verifies an end-user token for a project. The original never built this, which is why its
     * data API could not enforce per-user rules. This is the hook row-level rules hang from.
     */
    public Jwt validateEndUserToken(UUID projectId, String token) {
        var project = projectRepository.findById(projectId).orElseThrow(() -> AppException.notFound("Project"));

        try {
            return Jwts.decoder(jwtSecret(project), project.getProjectUrl(), project.getSlug()).decode(token);
        } catch (JwtException e) {
            throw AppException.unauthorized("Invalid end-user token");
        }
    }

    // ── Email and password ──────────────────────────────────────────────────

    public ProjectAuthResponse signUp(String projectSlug, SignUpRequest req) {
        var project = project(projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var email = req.email().trim();

        if (findUser(schema, email) != null)
            throw AppException.conflict("Email already registered");

        var user = insertUser(schema, email, passwords.encode(req.password()), AuthProvider.EMAIL, false);
        return new ProjectAuthResponse(toDto(user), issueToken(project, user));
    }

    public ProjectAuthResponse signIn(String projectSlug, SignInRequest req) {
        var project = project(projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());

        var user = findUser(schema, req.email().trim());
        if (user == null || user.passwordHash() == null) throw AppException.unauthorized();

        if (!passwords.matches(req.password(), user.passwordHash()))
            throw AppException.unauthorized();

        return new ProjectAuthResponse(toDto(user), issueToken(project, user));
    }

    // ── Magic links: single use, stored as a hash ───────────────────────────

    public void sendMagicLink(String projectSlug, String email) {
        var project = project(projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var normalized = email.trim();

        if (findUser(schema, normalized) == null)
            insertUser(schema, normalized, null, AuthProvider.EMAIL, true);

        var raw = Tokens.randomHex(48);
        saveToken(project, normalized, "magic_link", raw, MAGIC_LINK_LIFETIME);

        var link = properties.api().baseUrl() + "/projects/" + projectSlug + "/auth/magic-link/verify?token=" + raw;
        mail.send(normalized, "Your sign-in link",
                "<p>Click to sign in. The link expires in 15 minutes and works once.</p><p><a href=\"" + link + "\">Sign in</a></p>");
    }

    public ProjectAuthResponse verifyMagicLink(String projectSlug, String rawToken) {
        var project = project(projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var email = consumeToken(project.getId(), rawToken, "magic_link");

        var user = findUser(schema, email);
        if (user == null) throw AppException.notFound("User");
        return new ProjectAuthResponse(toDto(user), issueToken(project, user));
    }

    /**
     * OAuth callbacks hand the browser a short-lived code instead of the access token itself.
     * The original redirected to {siteUrl}/?access_token=..., putting the token in history,
     * referrer headers and any log along the way.
     */
    public String createExchangeCode(Project project, String email) {
        var raw = Tokens.randomHex(48);
        saveToken(project, email, "oauth_exchange", raw, EXCHANGE_CODE_LIFETIME);
        return raw;
    }

    public ProjectAuthResponse exchangeCode(String projectSlug, String code) {
        var project = project(projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var email = consumeToken(project.getId(), code, "oauth_exchange");

        var user = findUser(schema, email);
        if (user == null) throw AppException.notFound("User");
        return new ProjectAuthResponse(toDto(user), issueToken(project, user));
    }

    private void saveToken(Project project, String email, String purpose, String raw, Duration lifetime) {
        var token = new ProjectAuthToken();
        token.setProjectId(project.getId());
        token.setEmail(email);
        token.setPurpose(purpose);
        token.setTokenHash(Tokens.sha256(raw));
        token.setExpiresAt(OffsetDateTime.now(clock).plus(lifetime));
        authTokens.save(token);
    }

    private String consumeToken(UUID projectId, String rawToken, String purpose) {
        var now = OffsetDateTime.now(clock);

        var token = authTokens.findByTokenHashAndPurposeAndProjectId(Tokens.sha256(rawToken), purpose, projectId)
                .orElseThrow(() -> AppException.badRequest("Invalid or expired link"));

        if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now))
            throw AppException.badRequest("Invalid or expired link");

        token.setConsumedAt(now);
        authTokens.save(token);

        return token.getEmail();
    }

    public AuthUserRow upsertOAuthUser(Project project, String email, AuthProvider provider) {
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var existing = findUser(schema, email);
        return existing != null ? existing : insertUser(schema, email, null, provider, true);
    }

    // ── Dashboard-facing management ─────────────────────────────────────────

    public List<ProjectAuthUserDto> listUsers(String orgSlug, String projectSlug) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());

        return connections.admin()
                .sql("SELECT " + USER_COLUMNS + " FROM " + schema + ".\"auth_users\" ORDER BY created_at DESC LIMIT 500")
                .query(USER_ROW).list().stream()
                .map(ProjectAuthService::toDto)
                .toList();
    }

    /** Client secrets are never returned — only whether they are set. */
    public ProjectOAuthSettingsDto getSettings(String orgSlug, String projectSlug) {
        return toSettings(projects.byDashboardRoute(orgSlug, projectSlug));
    }

    public ProjectOAuthSettingsDto updateSettings(String orgSlug, String projectSlug, UpdateOAuthSettingsRequest req) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);

        if (req.siteUrl() != null && !req.siteUrl().isEmpty()) {
            var uri = absolute(req.siteUrl());
            if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())))
                throw AppException.badRequest("Site URL must be an absolute http(s) URL");

            project.setSiteUrl(trimSlash(req.siteUrl()));
        }

        if (req.redirectUrls() != null) {
            for (var url : req.redirectUrls())
                if (absolute(url) == null) throw AppException.badRequest("Invalid redirect URL: " + url);

            project.setRedirectUrls(req.redirectUrls().stream().map(ProjectAuthService::trimSlash).toList());
        }

        if (req.googleClientId() != null) project.setGoogleClientId(nullIfEmpty(req.googleClientId()));
        if (req.googleClientSecret() != null) project.setGoogleClientSecret(nullIfEmpty(req.googleClientSecret()));
        if (req.githubClientId() != null) project.setGithubClientId(nullIfEmpty(req.githubClientId()));
        if (req.githubClientSecret() != null) project.setGithubClientSecret(nullIfEmpty(req.githubClientSecret()));

        projectRepository.save(project);
        return toSettings(project);
    }

    /** A redirect target must be on the project's allow-list, or be its site URL. */
    public String resolveRedirect(Project project, String requested) {
        var fallback = project.getSiteUrl() != null ? project.getSiteUrl() : properties.web().url();

        if (requested == null || requested.isEmpty()) return fallback;

        var normalized = trimSlash(requested);
        if (project.getRedirectUrls().contains(normalized) || normalized.equals(fallback)) return normalized;

        throw AppException.badRequest("Redirect URL is not registered for this project");
    }

    public Project project(String projectSlug) {
        return projectRepository.findBySlug(projectSlug).orElseThrow(() -> AppException.notFound("Project"));
    }

    private ProjectOAuthSettingsDto toSettings(Project project) {
        return new ProjectOAuthSettingsDto(
                project.getSiteUrl() != null ? project.getSiteUrl() : properties.web().url(),
                project.getGoogleClientId(),
                project.getGoogleClientSecret() != null && !project.getGoogleClientSecret().isEmpty(),
                project.getGithubClientId(),
                project.getGithubClientSecret() != null && !project.getGithubClientSecret().isEmpty(),
                project.getRedirectUrls());
    }

    private static URI absolute(String value) {
        try {
            var uri = new URI(value);
            return uri.isAbsolute() ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String trimSlash(String value) {
        var end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }

    private static ProjectAuthUserDto toDto(AuthUserRow row) {
        AuthProvider provider;
        try {
            provider = AuthProvider.valueOf(row.provider().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            provider = AuthProvider.EMAIL;
        }
        return new ProjectAuthUserDto(row.id(), row.email(), row.emailVerified(), provider, row.createdAt());
    }
}
