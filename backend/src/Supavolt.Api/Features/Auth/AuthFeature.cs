using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.Google;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Slugify;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Members;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Auth;

public sealed class AuthService(
    SupavoltDbContext db,
    TokenService tokens,
    IPasswordHasher<User> hasher,
    ISlugHelper slugs)
{
    public async Task<IssuedTokens> RegisterAsync(RegisterRequest req, CancellationToken ct)
    {
        var email = req.Email.Trim().ToLowerInvariant();

        if (await db.Users.AnyAsync(u => u.Email == email, ct))
            throw AppException.Conflict("Email already in use");

        var user = new User { Email = email, Name = req.Name.Trim() };
        user.PasswordHash = hasher.HashPassword(user, req.Password);

        db.Users.Add(user);
        await db.SaveChangesAsync(ct);

        await CreatePersonalOrgAsync(user, ct);
        return await tokens.IssueAsync(user, ct);
    }

    public async Task<IssuedTokens> LoginAsync(LoginRequest req, CancellationToken ct)
    {
        var email = req.Email.Trim().ToLowerInvariant();
        var user = await db.Users.SingleOrDefaultAsync(u => u.Email == email, ct);

        if (user?.PasswordHash is null)
            throw AppException.Unauthorized();

        var verdict = hasher.VerifyHashedPassword(user, user.PasswordHash, req.Password);
        if (verdict == PasswordVerificationResult.Failed)
            throw AppException.Unauthorized();

        if (verdict == PasswordVerificationResult.SuccessRehashNeeded)
        {
            user.PasswordHash = hasher.HashPassword(user, req.Password);
            await db.SaveChangesAsync(ct);
        }

        return await tokens.IssueAsync(user, ct);
    }

    /// <summary>Called from an OAuth callback once the external identity is established.</summary>
    public async Task<IssuedTokens> SignInExternalAsync(
        string email, string? name, string? avatarUrl, CancellationToken ct)
    {
        var normalized = email.Trim().ToLowerInvariant();
        var user = await db.Users.SingleOrDefaultAsync(u => u.Email == normalized, ct);

        if (user is null)
        {
            user = new User { Email = normalized, Name = name, AvatarUrl = avatarUrl };
            db.Users.Add(user);
            await db.SaveChangesAsync(ct);
            await CreatePersonalOrgAsync(user, ct);
        }
        else if (user.AvatarUrl is null && avatarUrl is not null)
        {
            user.AvatarUrl = avatarUrl;
            await db.SaveChangesAsync(ct);
        }

        return await tokens.IssueAsync(user, ct);
    }

    private async Task CreatePersonalOrgAsync(User user, CancellationToken ct)
    {
        var display = string.IsNullOrWhiteSpace(user.Name) ? user.Email.Split('@')[0] : user.Name;
        var org = new Organization
        {
            Name = $"{display}'s Org",
            Slug = $"{slugs.GenerateSlug($"{display}-org")}-{RandomNumberGenerator.GetHexString(6, lowercase: true)}"
        };

        db.Organizations.Add(org);
        db.OrgMembers.Add(new OrgMember { Org = org, UserId = user.Id, Role = OrgRole.Admin });
        await db.SaveChangesAsync(ct);
    }
}

public static class AuthEndpoints
{
    public static IEndpointRouteBuilder MapAuth(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/auth").WithTags("Auth");

        group.MapPost("/register", async (
            RegisterRequest req, AuthService auth, IOptions<JwtOptions> jwt,
            IWebHostEnvironment env, HttpResponse res, CancellationToken ct) =>
        {
            var issued = await auth.RegisterAsync(req, ct);
            AuthCookies.Write(res, issued, jwt.Value, !env.IsDevelopment());
            return TypedResults.Ok(new MessageResponse("Registered successfully"));
        })
        .RequireRateLimiting(RateLimits.Auth);

        group.MapPost("/login", async (
            LoginRequest req, AuthService auth, IOptions<JwtOptions> jwt,
            IWebHostEnvironment env, HttpResponse res, CancellationToken ct) =>
        {
            var issued = await auth.LoginAsync(req, ct);
            AuthCookies.Write(res, issued, jwt.Value, !env.IsDevelopment());
            return TypedResults.Ok(new MessageResponse("Logged in successfully"));
        })
        .RequireRateLimiting(RateLimits.Auth);

        group.MapPost("/refresh", async (
            HttpRequest httpReq, HttpResponse res, TokenService tokens,
            IOptions<JwtOptions> jwt, IWebHostEnvironment env, CancellationToken ct) =>
        {
            var presented = httpReq.Cookies[AuthCookies.RefreshToken];
            var issued = await tokens.RotateAsync(presented, ct);
            AuthCookies.Write(res, issued, jwt.Value, !env.IsDevelopment());
            return TypedResults.Ok(new MessageResponse("Tokens refreshed"));
        });

        group.MapPost("/logout", async (
            ClaimsPrincipal user, HttpResponse res, TokenService tokens, CancellationToken ct) =>
        {
            if (user.Identity?.IsAuthenticated == true)
                await tokens.RevokeAllAsync(user.UserId(), ct);

            AuthCookies.Clear(res);
            return TypedResults.Ok(new MessageResponse("Logged out successfully"));
        });

        group.MapGet("/me", async (ClaimsPrincipal user, SupavoltDbContext db, CancellationToken ct) =>
        {
            var id = user.UserId();
            var me = await db.Users
                .Where(u => u.Id == id)
                .Select(u => new CurrentUserResponse(u.Id, u.Email, u.Name, u.AvatarUrl))
                .SingleOrDefaultAsync(ct);

            return me is null ? Results.Unauthorized() : Results.Ok(me);
        })
        .RequireAuthorization();

        // ── OAuth ────────────────────────────────────────────────────────────
        // The framework handlers own state and PKCE. The original built the redirect URL by hand
        // and accepted any 'code' with no CSRF protection at all.

        group.MapGet("/google", () =>
            Results.Challenge(
                new AuthenticationProperties { RedirectUri = "/api/auth/external/callback" },
                [GoogleDefaults.AuthenticationScheme]));

        group.MapGet("/github", () =>
            Results.Challenge(
                new AuthenticationProperties { RedirectUri = "/api/auth/external/callback" },
                ["GitHub"]));

        group.MapGet("/external/callback", async (
            HttpContext http, AuthService auth, TokenService tokens,
            IOptions<JwtOptions> jwt, IOptions<WebOptions> web,
            IWebHostEnvironment env, CancellationToken ct) =>
        {
            var result = await http.AuthenticateAsync(CookieAuthenticationDefaults.AuthenticationScheme);
            if (!result.Succeeded)
                return Results.Redirect($"{web.Value.Url}/login?error=oauth");

            var email = result.Principal.FindFirstValue(ClaimTypes.Email)
                        ?? throw AppException.BadRequest("The provider did not return an email address");

            var issued = await auth.SignInExternalAsync(
                email,
                result.Principal.FindFirstValue(ClaimTypes.Name),
                result.Principal.FindFirstValue("urn:supavolt:avatar"),
                ct);

            AuthCookies.Write(http.Response, issued, jwt.Value, !env.IsDevelopment());
            await http.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);

            return Results.Redirect($"{web.Value.Url}/dashboard");
        });

        // Invite acceptance requires a signed-in session: the original granted membership to
        // anyone who opened the link, including a mail client prefetching it.
        group.MapGet("/invite/accept", async (
            string token, ClaimsPrincipal user, InviteService invites,
            IOptions<WebOptions> web, CancellationToken ct) =>
        {
            if (user.Identity?.IsAuthenticated != true)
                return Results.Redirect($"{web.Value.Url}/login?invite={Uri.EscapeDataString(token)}");

            var slug = await invites.AcceptAsync(token, user.UserId(), ct);
            return Results.Redirect($"{web.Value.Url}/organizations/{slug}/projects");
        });

        return app;
    }
}

public static class RateLimits
{
    public const string Auth = "auth";
    public const string ProjectAuth = "project-auth";
}
