using Microsoft.AspNetCore.Authorization;
using Microsoft.EntityFrameworkCore;
using Supavolt.Api.Infrastructure;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Auth;

public static class Policies
{
    public const string OrgMember = "OrgMember";
    public const string OrgAdmin = "OrgAdmin";
    public const string ProjectKeyAny = "ProjectKeyAny";
    public const string ProjectServiceRole = "ProjectServiceRole";
}

public sealed class OrgRoleRequirement(OrgRole? minimumRole) : IAuthorizationRequirement
{
    public OrgRole? MinimumRole { get; } = minimumRole;
}

/// <summary>
/// The equivalent of the original's OrgRoleGuard: reads the {slug} route value, resolves the
/// caller's active membership, and caches it on the HttpContext so a request that hits the policy
/// twice only queries once. Admin satisfies a Developer requirement; the original compared roles
/// for exact equality, which meant an admin was refused any developer-scoped route.
/// </summary>
public sealed class OrgRoleHandler(IHttpContextAccessor accessor, IServiceScopeFactory scopes)
    : AuthorizationHandler<OrgRoleRequirement>
{
    private const string CacheKey = "supavolt.org_membership";

    protected override async Task HandleRequirementAsync(
        AuthorizationHandlerContext context, OrgRoleRequirement requirement)
    {
        var http = accessor.HttpContext;
        if (http is null) return;

        if (!context.User.Identity?.IsAuthenticated ?? true) return;

        var slug = http.GetRouteValue("slug") as string;
        if (string.IsNullOrEmpty(slug)) return;

        var membership = await ResolveAsync(http, context.User.UserId(), slug);
        if (membership is null) return;

        if (requirement.MinimumRole is null || membership.Role >= requirement.MinimumRole)
        {
            http.Items["supavolt.org_id"] = membership.OrgId;
            http.Items["supavolt.org_role"] = membership.Role;
            context.Succeed(requirement);
        }
    }

    private async Task<MembershipInfo?> ResolveAsync(HttpContext http, Guid userId, string slug)
    {
        if (http.Items.TryGetValue(CacheKey, out var cached) && cached is MembershipInfo hit && hit.Slug == slug)
            return hit;

        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<SupavoltDbContext>();

        var row = await db.OrgMembers
            .Where(m => m.Org.Slug == slug && m.UserId == userId)
            .Select(m => new MembershipInfo(slug, m.OrgId, m.Role))
            .SingleOrDefaultAsync();

        if (row is not null) http.Items[CacheKey] = row;
        return row;
    }

    private sealed record MembershipInfo(string Slug, Guid OrgId, OrgRole Role);
}

public static class HttpContextOrgExtensions
{
    public static Guid OrgId(this HttpContext http) =>
        http.Items["supavolt.org_id"] as Guid?
        ?? throw Common.AppException.Forbidden("Organization context missing");

    public static OrgRole OrgRole(this HttpContext http) =>
        http.Items["supavolt.org_role"] as OrgRole? ?? Contracts.OrgRole.Developer;
}
