package dev.supavolt.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Every setting under {@code supavolt.*}. All of it is validated at boot, so a missing secret fails
 * the start instead of the first request. Secrets come from the secrets file or environment
 * variables, never from application.yml; see README "Run it locally".
 */
@Validated
@ConfigurationProperties("supavolt")
public record SupavoltProperties(
        @Valid @DefaultValue @NotNull Database database,
        @Valid @DefaultValue @NotNull Jwt jwt,
        @Valid @DefaultValue @NotNull ProjectKeys projectKeys,
        @Valid @DefaultValue @NotNull Invites invites,
        @Valid @DefaultValue @NotNull Api api,
        @Valid @DefaultValue @NotNull Web web,
        @Valid @DefaultValue @NotNull Mail mail,
        @Valid @DefaultValue @NotNull Storage storage,
        @Valid @DefaultValue @NotNull Secrets secrets,
        @Valid @DefaultValue @NotNull RateLimits rateLimits,
        @Valid @DefaultValue @NotNull Cookies cookies,
        @Valid @DefaultValue @NotNull OAuth oauth) {

    /**
     * The admin connection is {@code spring.datasource.*}: EF's role in the original, now JPA,
     * Flyway and DDL. These are the other two roles.
     */
    public record Database(
            /*
             * Least-privilege role for server-built tenant SQL: the data API and table editor
             * reads. Cannot read the control-plane tables. See README "Database roles".
             */
            @NotBlank String tenantUsername,
            @NotBlank String tenantPassword,
            /*
             * Direct (non-pooled, non-pgbouncer) JDBC URL for the LISTEN connection. LISTEN/NOTIFY
             * needs a session that is never handed to another client. Blank means the admin URL.
             */
            @DefaultValue("") String directUrl) {
    }

    public record Jwt(
            @NotBlank @Size(min = 32) String accessSecret,
            @NotBlank @Size(min = 32) String refreshSecret,
            @DefaultValue("supavolt") @NotBlank String issuer,
            @DefaultValue("supavolt-dashboard") @NotBlank String audience,
            @DefaultValue("15m") Duration accessLifetime,
            @DefaultValue("7d") Duration refreshLifetime) {
    }

    /** Signs anon and service-role keys. Separate from the dashboard secrets on purpose. */
    public record ProjectKeys(
            @NotBlank @Size(min = 32) String secret,
            @DefaultValue("supavolt-projects") @NotBlank String issuer) {
    }

    public record Invites(
            @NotBlank @Size(min = 32) String secret,
            @DefaultValue("24h") Duration lifetime) {
    }

    /** Public base URL of this API, including the /api prefix. Used to build project URLs. */
    public record Api(@DefaultValue("http://localhost:5000/api") @NotBlank @URL String baseUrl) {
    }

    /** Dashboard origin. Must be exact: CORS with credentials forbids wildcards. */
    public record Web(@DefaultValue("http://localhost:3001") @NotBlank @URL String url) {
    }

    public record Mail(
            @DefaultValue("onboarding@example.com") @NotBlank @Email String fromAddress,
            @DefaultValue("Supavolt") String fromName,
            /* Leave empty in development to log mail to the console instead of sending it. */
            @DefaultValue("") String apiKey,
            @DefaultValue("https://api.resend.com/emails") String apiUrl) {
    }

    public record Storage(
            @NotBlank String bucket,
            @NotBlank String accessKeyId,
            @NotBlank String secretAccessKey,
            /* S3 endpoint. For Cloudflare R2: https://<account-id>.r2.cloudflarestorage.com */
            @DefaultValue("") String serviceUrl,
            @DefaultValue("auto") String region,
            /* Public base URL for objects in public buckets (an R2 custom domain or CloudFront). */
            @NotBlank @URL String publicBaseUrl,
            @DefaultValue("5m") Duration uploadUrlLifetime,
            @DefaultValue("1h") Duration downloadUrlLifetime,
            @DefaultValue("536870912") long maxUploadBytes,
            /* Create the bucket at startup if it is missing. For local development only. */
            @DefaultValue("false") boolean createBucket) {
    }

    /**
     * Encrypts per-project secrets at rest (OAuth client secrets, end-user JWT secrets, project
     * role passwords). Replaces ASP.NET Data Protection; values encrypted by the .NET API cannot be
     * read with this key. See DECISIONS.md.
     */
    public record Secrets(@NotBlank @Size(min = 32) String encryptionKey) {
    }

    /** Configurable so integration tests, which all share one client address, can raise it. */
    public record RateLimits(
            @DefaultValue("10") int authPermitsPerMinute,
            @DefaultValue("20") int projectAuthPermitsPerMinute) {
    }

    /** Secure everywhere except plain-http local development. */
    public record Cookies(@DefaultValue("true") boolean secure) {
    }

    /** Dashboard sign-in providers. A provider is registered only when its client id is set. */
    public record OAuth(@Valid @DefaultValue @NotNull Provider google, @Valid @DefaultValue @NotNull Provider github) {
    }

    public record Provider(@DefaultValue("") String clientId, @DefaultValue("") String clientSecret) {

        public boolean configured() {
            return !clientId.isBlank();
        }
    }
}
