using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Auth;

public static class ProjectKeyClaims
{
    public const string ProjectId = "project_id";
    public const string Role = "project_role";
    public const string KeyVersion = "key_version";
}

public sealed class ProjectKeyService(IOptions<ProjectKeyOptions> options, TimeProvider clock)
{
    private readonly ProjectKeyOptions _o = options.Value;
    private static readonly JsonWebTokenHandler Handler = new();

    /// <summary>
    /// Keys do not expire — they are long-lived credentials — but they carry a version claim, so
    /// rotating a project's keys invalidates every key issued before. The original signed keys
    /// with a 100-year lifetime and no way to revoke them.
    /// </summary>
    public string Sign(Guid projectId, ProjectKeyRole role, int keyVersion) =>
        Handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = _o.Issuer,
            Audience = _o.Issuer,
            IssuedAt = clock.GetUtcNow().UtcDateTime,
            Subject = new ClaimsIdentity(
            [
                new Claim(ProjectKeyClaims.ProjectId, projectId.ToString()),
                new Claim(ProjectKeyClaims.Role, role == ProjectKeyRole.ServiceRole ? "service_role" : "anon"),
                new Claim(ProjectKeyClaims.KeyVersion, keyVersion.ToString())
            ]),
            SigningCredentials = new SigningCredentials(
                new SymmetricSecurityKey(TokenService.KeyBytes(_o.Secret)), SecurityAlgorithms.HmacSha256)
        });

    public TokenValidationParameters ValidationParameters() => new()
    {
        ValidIssuer = _o.Issuer,
        ValidAudience = _o.Issuer,
        IssuerSigningKey = new SymmetricSecurityKey(TokenService.KeyBytes(_o.Secret)),
        ValidateLifetime = false,
        ValidateIssuer = true,
        ValidateAudience = true,
        ValidateIssuerSigningKey = true
    };
}

/// <summary>
/// The second authentication scheme: an API key in the Authorization header, used by the SDK and
/// by the generated data, storage and realtime endpoints. Separate from the dashboard scheme so a
/// project key can never act on the dashboard and vice versa.
/// </summary>
public sealed class ProjectKeyAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    ProjectKeyService keys)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "ProjectKey";

    private static readonly JsonWebTokenHandler Handler = new();

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();

        // SignalR cannot set headers on a WebSocket handshake, so the hub passes the key here.
        var token = header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
            ? header["Bearer ".Length..].Trim()
            : Request.Query["access_token"].ToString();

        if (string.IsNullOrEmpty(token))
            return AuthenticateResult.NoResult();

        var result = await Handler.ValidateTokenAsync(token, keys.ValidationParameters());
        if (!result.IsValid)
            return AuthenticateResult.Fail("Invalid API key");

        var identity = new ClaimsIdentity(result.ClaimsIdentity.Claims, SchemeName);
        return AuthenticateResult.Success(
            new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName));
    }
}

public static class ProjectKeyPrincipalExtensions
{
    public static Guid ProjectId(this ClaimsPrincipal user) =>
        Guid.TryParse(user.FindFirstValue(ProjectKeyClaims.ProjectId), out var id)
            ? id
            : throw Common.AppException.Unauthorized("API key has no project");

    public static int KeyVersion(this ClaimsPrincipal user) =>
        int.TryParse(user.FindFirstValue(ProjectKeyClaims.KeyVersion), out var v) ? v : 0;

    public static bool IsServiceRole(this ClaimsPrincipal user) =>
        user.FindFirstValue(ProjectKeyClaims.Role) == "service_role";

    public static Guid UserId(this ClaimsPrincipal user) =>
        Guid.TryParse(user.FindFirstValue(ClaimTypes.NameIdentifier), out var id)
            ? id
            : throw Common.AppException.Unauthorized("Not authenticated");
}
