using System.ComponentModel.DataAnnotations;

namespace Supavolt.Api.Infrastructure.Configuration;

public sealed class DatabaseOptions
{
    public const string Section = "Database";

    /// <summary>Pooled connection used by EF Core and the request-path Dapper queries.</summary>
    [Required] public string ConnectionString { get; init; } = default!;

    /// <summary>
    /// Direct (non-pooled, non-pgbouncer) connection. LISTEN/NOTIFY needs a session that is
    /// never handed to another client, so this must not point at a transaction pooler.
    /// </summary>
    [Required] public string DirectConnectionString { get; init; } = default!;

    /// <summary>
    /// Least-privilege role used for tenant SQL: the SQL editor and the generated data API.
    /// Cannot read the control-plane tables. See README "Database roles".
    /// </summary>
    [Required] public string TenantConnectionString { get; init; } = default!;
}

public sealed class JwtOptions
{
    public const string Section = "Jwt";

    [Required, MinLength(32)] public string AccessSecret { get; init; } = default!;
    [Required, MinLength(32)] public string RefreshSecret { get; init; } = default!;
    [Required] public string Issuer { get; init; } = "supavolt";
    [Required] public string Audience { get; init; } = "supavolt-dashboard";

    public TimeSpan AccessLifetime { get; init; } = TimeSpan.FromMinutes(15);
    public TimeSpan RefreshLifetime { get; init; } = TimeSpan.FromDays(7);
}

public sealed class ProjectKeyOptions
{
    public const string Section = "ProjectKeys";

    /// <summary>Signs anon and service-role keys. Separate from the dashboard secrets on purpose.</summary>
    [Required, MinLength(32)] public string Secret { get; init; } = default!;

    [Required] public string Issuer { get; init; } = "supavolt-projects";
}

public sealed class InviteOptions
{
    public const string Section = "Invites";

    [Required, MinLength(32)] public string Secret { get; init; } = default!;
    public TimeSpan Lifetime { get; init; } = TimeSpan.FromHours(24);
}

public sealed class ApiOptions
{
    public const string Section = "Api";

    /// <summary>Public base URL of this API, including the /api path base. Used to build project URLs.</summary>
    [Required, Url] public string BaseUrl { get; init; } = "http://localhost:5000/api";
}

public sealed class WebOptions
{
    public const string Section = "Web";

    /// <summary>Dashboard origin. Must be exact: CORS with credentials forbids wildcards.</summary>
    [Required, Url] public string Url { get; init; } = "http://localhost:3001";
}

public sealed class MailOptions
{
    public const string Section = "Mail";

    [Required, EmailAddress] public string FromAddress { get; init; } = "onboarding@example.com";
    public string FromName { get; init; } = "Supavolt";

    /// <summary>Leave empty in development to log mail to the console instead of sending it.</summary>
    public string? ApiKey { get; init; }
    public string ApiUrl { get; init; } = "https://api.resend.com/emails";
}

public sealed class StorageOptions
{
    public const string Section = "Storage";

    [Required] public string Bucket { get; init; } = default!;
    [Required] public string AccessKeyId { get; init; } = default!;
    [Required] public string SecretAccessKey { get; init; } = default!;

    /// <summary>S3 endpoint. For Cloudflare R2: https://&lt;account-id&gt;.r2.cloudflarestorage.com</summary>
    public string? ServiceUrl { get; init; }
    public string Region { get; init; } = "auto";

    /// <summary>Public base URL for objects in public buckets (an R2 custom domain or CloudFront).</summary>
    [Required, Url] public string PublicBaseUrl { get; init; } = default!;

    public TimeSpan UploadUrlLifetime { get; init; } = TimeSpan.FromMinutes(5);
    public TimeSpan DownloadUrlLifetime { get; init; } = TimeSpan.FromHours(1);
    public long MaxUploadBytes { get; init; } = 512L * 1024 * 1024;
}
