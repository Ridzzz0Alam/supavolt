using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Microsoft.IdentityModel.Tokens;
using Supavolt.Api.Common;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;

namespace Supavolt.Api.Features.Auth;

public sealed record IssuedTokens(string AccessToken, string RefreshToken, DateTimeOffset RefreshExpiresAt);

public sealed class TokenService(
    SupavoltDbContext db,
    IOptions<JwtOptions> jwtOptions,
    TimeProvider clock)
{
    private readonly JwtOptions _jwt = jwtOptions.Value;
    private static readonly JsonWebTokenHandler Handler = new();

    public static byte[] KeyBytes(string secret) => Encoding.UTF8.GetBytes(secret);

    public string CreateAccessToken(User user)
    {
        var now = clock.GetUtcNow();

        return Handler.CreateToken(new SecurityTokenDescriptor
        {
            Issuer = _jwt.Issuer,
            Audience = _jwt.Audience,
            IssuedAt = now.UtcDateTime,
            NotBefore = now.UtcDateTime,
            Expires = now.Add(_jwt.AccessLifetime).UtcDateTime,
            Subject = new ClaimsIdentity(
            [
                new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()),
                new Claim(ClaimTypes.Email, user.Email),
                new Claim("name", user.Name ?? "")
            ]),
            SigningCredentials = new SigningCredentials(
                new SymmetricSecurityKey(KeyBytes(_jwt.AccessSecret)), SecurityAlgorithms.HmacSha256)
        });
    }

    /// <summary>
    /// Issues an opaque refresh token and stores only its hash. Rotation with reuse detection:
    /// presenting an already-rotated token revokes the whole chain for that user.
    /// </summary>
    public async Task<IssuedTokens> IssueAsync(User user, CancellationToken ct)
    {
        var raw = RandomNumberGenerator.GetHexString(64, lowercase: true);
        var expires = clock.GetUtcNow().Add(_jwt.RefreshLifetime);

        db.RefreshTokens.Add(new RefreshToken
        {
            UserId = user.Id,
            TokenHash = Hash(raw),
            ExpiresAt = expires
        });
        await db.SaveChangesAsync(ct);

        return new IssuedTokens(CreateAccessToken(user), raw, expires);
    }

    public async Task<IssuedTokens> RotateAsync(string? presented, CancellationToken ct)
    {
        if (string.IsNullOrWhiteSpace(presented))
            throw AppException.Unauthorized("No refresh token");

        var hash = Hash(presented);
        var stored = await db.RefreshTokens
            .Include(t => t.User)
            .SingleOrDefaultAsync(t => t.TokenHash == hash, ct)
            ?? throw AppException.Unauthorized("Invalid refresh token");

        var now = clock.GetUtcNow();

        if (stored.RevokedAt is not null)
        {
            // Reuse of a rotated token means it leaked. Kill every live token for this user.
            await db.RefreshTokens
                .Where(t => t.UserId == stored.UserId && t.RevokedAt == null)
                .ExecuteUpdateAsync(s => s.SetProperty(t => t.RevokedAt, now), ct);

            throw AppException.Unauthorized("Refresh token reuse detected; please sign in again");
        }

        if (stored.ExpiresAt <= now)
            throw AppException.Unauthorized("Refresh token expired");

        var next = await IssueAsync(stored.User, ct);

        stored.RevokedAt = now;
        await db.SaveChangesAsync(ct);

        return next;
    }

    public async Task RevokeAllAsync(Guid userId, CancellationToken ct)
    {
        var now = clock.GetUtcNow();
        await db.RefreshTokens
            .Where(t => t.UserId == userId && t.RevokedAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(t => t.RevokedAt, now), ct);
    }

    public static string Hash(string value) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
}

public static class AuthCookies
{
    public const string AccessToken = "access_token";
    public const string RefreshToken = "refresh_token";

    public static void Write(HttpResponse res, IssuedTokens tokens, JwtOptions jwt, bool secure)
    {
        var common = new CookieOptions
        {
            HttpOnly = true,
            Secure = secure,
            SameSite = SameSiteMode.Lax,
            Path = "/"
        };

        res.Cookies.Append(AccessToken, tokens.AccessToken, new CookieOptions
        {
            HttpOnly = common.HttpOnly,
            Secure = common.Secure,
            SameSite = common.SameSite,
            Path = common.Path,
            MaxAge = jwt.AccessLifetime
        });

        res.Cookies.Append(RefreshToken, tokens.RefreshToken, new CookieOptions
        {
            HttpOnly = common.HttpOnly,
            Secure = common.Secure,
            SameSite = common.SameSite,
            Path = common.Path,
            MaxAge = jwt.RefreshLifetime
        });
    }

    public static void Clear(HttpResponse res)
    {
        res.Cookies.Delete(AccessToken);
        res.Cookies.Delete(RefreshToken);
    }
}
