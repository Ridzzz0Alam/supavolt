using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using Slugify;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Infrastructure;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Orgs;

public sealed class OrgsService(SupavoltDbContext db, ISlugHelper slugs)
{
    /// <summary>One query with two correlated subqueries — no N+1, no GROUP BY over a left join.</summary>
    public Task<List<OrganizationDto>> ListForUserAsync(Guid userId, CancellationToken ct) =>
        db.OrgMembers
            .Where(m => m.UserId == userId)
            .OrderBy(m => m.Org.Name)
            .Select(m => new OrganizationDto(
                m.Org.Id,
                m.Org.Name,
                m.Org.Slug,
                m.Role,
                m.Org.Projects.Count,
                m.Org.Members.Count(x => x.RemovedAt == null),
                m.Org.CreatedAt))
            .ToListAsync(ct);

    public async Task<OrganizationDto> GetAsync(string slug, Guid userId, CancellationToken ct) =>
        await db.OrgMembers
            .Where(m => m.Org.Slug == slug && m.UserId == userId)
            .Select(m => new OrganizationDto(
                m.Org.Id, m.Org.Name, m.Org.Slug, m.Role,
                m.Org.Projects.Count,
                m.Org.Members.Count(x => x.RemovedAt == null),
                m.Org.CreatedAt))
            .SingleOrDefaultAsync(ct)
        ?? throw AppException.NotFound("Organization");

    public async Task<OrganizationDto> CreateAsync(CreateOrgRequest req, Guid userId, CancellationToken ct)
    {
        var org = new Organization
        {
            Name = req.Name.Trim(),
            Slug = $"{slugs.GenerateSlug($"{req.Name}-org")}-{RandomNumberGenerator.GetHexString(6, lowercase: true)}"
        };

        db.Organizations.Add(org);
        db.OrgMembers.Add(new OrgMember { Org = org, UserId = userId, Role = OrgRole.Admin });
        await db.SaveChangesAsync(ct);

        return new OrganizationDto(org.Id, org.Name, org.Slug, OrgRole.Admin, 0, 1, org.CreatedAt);
    }
}

public static class OrgsEndpoints
{
    public static IEndpointRouteBuilder MapOrgs(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs").WithTags("Organizations").RequireAuthorization();

        group.MapGet("/", (ClaimsPrincipal user, OrgsService svc, CancellationToken ct) =>
            svc.ListForUserAsync(user.UserId(), ct));

        group.MapPost("/", (CreateOrgRequest req, ClaimsPrincipal user, OrgsService svc, CancellationToken ct) =>
            svc.CreateAsync(req, user.UserId(), ct));

        group.MapGet("/{slug}", (string slug, ClaimsPrincipal user, OrgsService svc, CancellationToken ct) =>
            svc.GetAsync(slug, user.UserId(), ct))
            .RequireAuthorization(Policies.OrgMember);

        return app;
    }
}
