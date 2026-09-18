using System.Security.Claims;
using System.Security.Cryptography;
using Dapper;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Api.Infrastructure.Mail;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.ProjectAuth;

public sealed class ProjectAuthService(
    SupavoltDbContext db,
    ITenantConnectionFactory connections,
    ProjectResolver projects,
    IPasswordHasher<User> hasher,
    IEmailSender mail,
    IOptions<ApiOptions> api,
    IOptions<WebOptions> web,
    TimeProvider clock)
{
    private static readonly JsonWebTokenHandler Handler = new();
    private static readonly TimeSpan MagicLinkLifetime = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan ExchangeCodeLifetime = TimeSpan.FromMinutes(2);

    // ── End-user records live in the project's own schema ────────────────────

    public sealed record AuthUserRow(Guid Id, string Email, string? PasswordHash, bool EmailVerified, string Provider, DateTime CreatedAt);

    private async Task<AuthUserRow?> FindUserAsync(SqlIdentifier schema, string email, CancellationToken ct)
    {
        await using var conn = await connections.OpenAdminAsync(ct);

        return await conn.QuerySingleOrDefaultAsync<AuthUserRow>($"""
            SELECT id, email, password_hash, email_verified, provider, created_at
            FROM {schema}."auth_users"
            WHERE lower(email) = lower(@email)
            """, new { email });
    }

    private async Task<AuthUserRow> InsertUserAsync(
        SqlIdentifier schema, string email, string? passwordHash, AuthProvider provider,
        bool emailVerified, CancellationToken ct)
    {
        await using var conn = await connections.OpenAdminAsync(ct);

        return await conn.QuerySingleAsync<AuthUserRow>($"""
            INSERT INTO {schema}."auth_users" (email, password_hash, provider, email_verified)
            VALUES (@email, @passwordHash, @provider, @emailVerified)
            RETURNING id, email, password_hash, email_verified, provider, created_at
            """,
            new
            {
                email,
                passwordHash,
                provider = provider.ToString().ToLowerInvariant(),
                emailVerified
            });
    }

    // ── Tokens ──────────────────────────────────────────────────────────────

    private string IssueToken(Project project, AuthUserRow user)
    {
        var now = clock.GetUtcNow();

        return Handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = project.ProjectUrl,
            Audience = project.Slug,
            IssuedAt = now.UtcDateTime,
            Expires = now.AddDays(7).UtcDateTime,
            Subject = new ClaimsIdentity(
            [
                new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()),
                new Claim(ClaimTypes.Email, user.Email),
                new Claim("project_id", project.Id.ToString())
            ]),
            SigningCredentials = new SigningCredentials(
                new SymmetricSecurityKey(TokenService.KeyBytes(project.AuthJwtSecret)),
                SecurityAlgorithms.HmacSha256)
        });
    }

    /// <summary>
    /// Verifies an end-user token for a project. The original never built this, which is why its
    /// data API could not enforce per-user rules. This is the hook row-level rules hang from.
    /// </summary>
    public async Task<ClaimsPrincipal> ValidateEndUserTokenAsync(
        Guid projectId, string token, CancellationToken ct)
    {
        var project = await db.Projects.SingleOrDefaultAsync(p => p.Id == projectId, ct)
            ?? throw AppException.NotFound("Project");

        var result = await Handler.ValidateTokenAsync(token, new TokenValidationParameters
        {
            ValidIssuer = project.ProjectUrl,
            ValidAudience = project.Slug,
            IssuerSigningKey = new SymmetricSecurityKey(TokenService.KeyBytes(project.AuthJwtSecret))
        });

        if (!result.IsValid) throw AppException.Unauthorized("Invalid end-user token");

        return new ClaimsPrincipal(new ClaimsIdentity(result.ClaimsIdentity.Claims, "ProjectUser"));
    }

    // ── Email and password ──────────────────────────────────────────────────

    public async Task<ProjectAuthResponse> SignUpAsync(
        string projectSlug, SignUpRequest req, CancellationToken ct)
    {
        var project = await ProjectAsync(projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var email = req.Email.Trim();

        if (await FindUserAsync(schema, email, ct) is not null)
            throw AppException.Conflict("Email already registered");

        var placeholder = new User { Email = email };
        var hash = hasher.HashPassword(placeholder, req.Password);
        var user = await InsertUserAsync(schema, email, hash, AuthProvider.Email, false, ct);

        return new ProjectAuthResponse(ToDto(user), IssueToken(project, user));
    }

    public async Task<ProjectAuthResponse> SignInAsync(
        string projectSlug, SignInRequest req, CancellationToken ct)
    {
        var project = await ProjectAsync(projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);

        var user = await FindUserAsync(schema, req.Email.Trim(), ct);
        if (user?.PasswordHash is null) throw AppException.Unauthorized();

        var placeholder = new User { Email = user.Email };
        if (hasher.VerifyHashedPassword(placeholder, user.PasswordHash, req.Password) == PasswordVerificationResult.Failed)
            throw AppException.Unauthorized();

        return new ProjectAuthResponse(ToDto(user), IssueToken(project, user));
    }

    // ── Magic links: single use, stored as a hash ───────────────────────────

    public async Task SendMagicLinkAsync(string projectSlug, string email, CancellationToken ct)
    {
        var project = await ProjectAsync(projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var normalized = email.Trim();

        if (await FindUserAsync(schema, normalized, ct) is null)
            await InsertUserAsync(schema, normalized, null, AuthProvider.Email, true, ct);

        var raw = RandomNumberGenerator.GetHexString(48, lowercase: true);
        var now = clock.GetUtcNow();

        db.ProjectAuthTokens.Add(new ProjectAuthToken
        {
            ProjectId = project.Id,
            Email = normalized,
            Purpose = "magic_link",
            TokenHash = TokenService.Hash(raw),
            ExpiresAt = now.Add(MagicLinkLifetime)
        });
        await db.SaveChangesAsync(ct);

        var link = $"{api.Value.BaseUrl}/projects/{projectSlug}/auth/magic-link/verify?token={raw}";

        await mail.SendAsync(normalized, "Your sign-in link",
            $"""<p>Click to sign in. The link expires in 15 minutes and works once.</p><p><a href="{link}">Sign in</a></p>""",
            ct);
    }

    public async Task<ProjectAuthResponse> VerifyMagicLinkAsync(
        string projectSlug, string rawToken, CancellationToken ct)
    {
        var project = await ProjectAsync(projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var email = await ConsumeTokenAsync(project.Id, rawToken, "magic_link", ct);

        var user = await FindUserAsync(schema, email, ct) ?? throw AppException.NotFound("User");
        return new ProjectAuthResponse(ToDto(user), IssueToken(project, user));
    }

    /// <summary>
    /// OAuth callbacks hand the browser a short-lived code instead of the access token itself.
    /// The original redirected to {siteUrl}/?access_token=..., putting the token in history,
    /// referrer headers and any log along the way.
    /// </summary>
    public async Task<string> CreateExchangeCodeAsync(Project project, string email, CancellationToken ct)
    {
        var raw = RandomNumberGenerator.GetHexString(48, lowercase: true);

        db.ProjectAuthTokens.Add(new ProjectAuthToken
        {
            ProjectId = project.Id,
            Email = email,
            Purpose = "oauth_exchange",
            TokenHash = TokenService.Hash(raw),
            ExpiresAt = clock.GetUtcNow().Add(ExchangeCodeLifetime)
        });
        await db.SaveChangesAsync(ct);

        return raw;
    }

    public async Task<ProjectAuthResponse> ExchangeCodeAsync(
        string projectSlug, string code, CancellationToken ct)
    {
        var project = await ProjectAsync(projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var email = await ConsumeTokenAsync(project.Id, code, "oauth_exchange", ct);

        var user = await FindUserAsync(schema, email, ct) ?? throw AppException.NotFound("User");
        return new ProjectAuthResponse(ToDto(user), IssueToken(project, user));
    }

    private async Task<string> ConsumeTokenAsync(
        Guid projectId, string rawToken, string purpose, CancellationToken ct)
    {
        var hash = TokenService.Hash(rawToken);
        var now = clock.GetUtcNow();

        var token = await db.ProjectAuthTokens.SingleOrDefaultAsync(
            t => t.TokenHash == hash && t.Purpose == purpose && t.ProjectId == projectId, ct)
            ?? throw AppException.BadRequest("Invalid or expired link");

        if (token.ConsumedAt is not null || token.ExpiresAt <= now)
            throw AppException.BadRequest("Invalid or expired link");

        token.ConsumedAt = now;
        await db.SaveChangesAsync(ct);

        return token.Email;
    }

    public async Task<AuthUserRow> UpsertOAuthUserAsync(
        Project project, string email, AuthProvider provider, CancellationToken ct)
    {
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);

        return await FindUserAsync(schema, email, ct)
            ?? await InsertUserAsync(schema, email, null, provider, true, ct);
    }

    // ── Dashboard-facing management ─────────────────────────────────────────

    public async Task<List<ProjectAuthUserDto>> ListUsersAsync(
        string orgSlug, string projectSlug, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);

        await using var conn = await connections.OpenAdminAsync(ct);
        var rows = await conn.QueryAsync<AuthUserRow>($"""
            SELECT id, email, password_hash, email_verified, provider, created_at
            FROM {schema}."auth_users"
            ORDER BY created_at DESC
            LIMIT 500
            """);

        return rows.Select(ToDto).ToList();
    }

    /// <summary>Client secrets are never returned — only whether they are set.</summary>
    public async Task<ProjectOAuthSettingsDto> GetSettingsAsync(
        string orgSlug, string projectSlug, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);

        return new ProjectOAuthSettingsDto(
            project.SiteUrl ?? web.Value.Url,
            project.GoogleClientId,
            !string.IsNullOrEmpty(project.GoogleClientSecret),
            project.GithubClientId,
            !string.IsNullOrEmpty(project.GithubClientSecret),
            project.RedirectUrls);
    }

    public async Task<ProjectOAuthSettingsDto> UpdateSettingsAsync(
        string orgSlug, string projectSlug, UpdateOAuthSettingsRequest req, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);

        if (req.SiteUrl is { Length: > 0 } site)
        {
            if (!Uri.TryCreate(site, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https"))
                throw AppException.BadRequest("Site URL must be an absolute http(s) URL");

            project.SiteUrl = site.TrimEnd('/');
        }

        if (req.RedirectUrls is not null)
        {
            foreach (var url in req.RedirectUrls)
                if (!Uri.TryCreate(url, UriKind.Absolute, out _))
                    throw AppException.BadRequest($"Invalid redirect URL: {url}");

            project.RedirectUrls = req.RedirectUrls.Select(u => u.TrimEnd('/')).ToList();
        }

        if (req.GoogleClientId is not null) project.GoogleClientId = NullIfEmpty(req.GoogleClientId);
        if (req.GoogleClientSecret is not null) project.GoogleClientSecret = NullIfEmpty(req.GoogleClientSecret);
        if (req.GithubClientId is not null) project.GithubClientId = NullIfEmpty(req.GithubClientId);
        if (req.GithubClientSecret is not null) project.GithubClientSecret = NullIfEmpty(req.GithubClientSecret);

        await db.SaveChangesAsync(ct);
        return await GetSettingsAsync(orgSlug, projectSlug, ct);
    }

    /// <summary>A redirect target must be on the project's allow-list, or be its site URL.</summary>
    public string ResolveRedirect(Project project, string? requested)
    {
        var fallback = project.SiteUrl ?? web.Value.Url;

        if (string.IsNullOrEmpty(requested)) return fallback;

        var normalized = requested.TrimEnd('/');
        return project.RedirectUrls.Contains(normalized) || normalized == fallback
            ? normalized
            : throw AppException.BadRequest("Redirect URL is not registered for this project");
    }

    public async Task<Project> ProjectAsync(string projectSlug, CancellationToken ct) =>
        await db.Projects.SingleOrDefaultAsync(p => p.Slug == projectSlug, ct)
            ?? throw AppException.NotFound("Project");

    private static string? NullIfEmpty(string value) => value.Length == 0 ? null : value;

    private static ProjectAuthUserDto ToDto(AuthUserRow row) => new(
        row.Id,
        row.Email,
        row.EmailVerified,
        Enum.TryParse<AuthProvider>(row.Provider, true, out var p) ? p : AuthProvider.Email,
        row.CreatedAt);
}

public static class ProjectAuthEndpoints
{
    /// <summary>The SDK-facing endpoints: these are the authentication, so they are anonymous.</summary>
    public static IEndpointRouteBuilder MapProjectAuth(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/projects/{projectSlug}/auth")
            .WithTags("Project auth")
            .RequireRateLimiting(RateLimits.ProjectAuth);

        group.MapPost("/signup", (
            string projectSlug, SignUpRequest req, ProjectAuthService svc, CancellationToken ct) =>
            svc.SignUpAsync(projectSlug, req, ct));

        group.MapPost("/signin", (
            string projectSlug, SignInRequest req, ProjectAuthService svc, CancellationToken ct) =>
            svc.SignInAsync(projectSlug, req, ct));

        group.MapPost("/magic-link", async (
            string projectSlug, MagicLinkRequest req, ProjectAuthService svc, CancellationToken ct) =>
        {
            await svc.SendMagicLinkAsync(projectSlug, req.Email, ct);
            // Always the same response, so the endpoint cannot be used to enumerate accounts.
            return TypedResults.Ok(new MessageResponse("If that email exists, a link is on its way"));
        });

        group.MapGet("/magic-link/verify", (
            string projectSlug, string token, ProjectAuthService svc, CancellationToken ct) =>
            svc.VerifyMagicLinkAsync(projectSlug, token, ct));

        group.MapPost("/exchange", (
            string projectSlug, ExchangeCodeRequest req, ProjectAuthService svc, CancellationToken ct) =>
            svc.ExchangeCodeAsync(projectSlug, req.Code, ct));

        return app;
    }

    /// <summary>Dashboard management of a project's end-users and provider settings.</summary>
    public static IEndpointRouteBuilder MapProjectAuthDashboard(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/projects/{projectSlug}/auth")
            .WithTags("Project auth (dashboard)")
            .RequireAuthorization(Policies.OrgMember);

        group.MapGet("/users", (
            string slug, string projectSlug, ProjectAuthService svc, CancellationToken ct) =>
            svc.ListUsersAsync(slug, projectSlug, ct));

        group.MapGet("/settings", (
            string slug, string projectSlug, ProjectAuthService svc, CancellationToken ct) =>
            svc.GetSettingsAsync(slug, projectSlug, ct));

        group.MapPost("/settings", (
                string slug, string projectSlug, UpdateOAuthSettingsRequest req,
                ProjectAuthService svc, CancellationToken ct) =>
                svc.UpdateSettingsAsync(slug, projectSlug, req, ct))
            .RequireAuthorization(Policies.OrgAdmin);

        return app;
    }
}
