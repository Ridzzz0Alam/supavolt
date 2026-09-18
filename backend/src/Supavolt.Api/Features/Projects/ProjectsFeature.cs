using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Slugify;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Projects;

public sealed class ProjectsService(
    SupavoltDbContext db,
    ISchemaProvisioner provisioner,
    ProjectKeyService keys,
    ISlugHelper slugs,
    IOptions<ApiOptions> api)
{
    public Task<List<ProjectDto>> ListAsync(string orgSlug, CancellationToken ct) =>
        db.Projects
            .Where(p => p.Org.Slug == orgSlug)
            .OrderBy(p => p.Name)
            .Select(p => ToDto(p))
            .ToListAsync(ct);

    public async Task<ProjectDto> GetAsync(string orgSlug, string projectSlug, CancellationToken ct) =>
        await db.Projects
            .Where(p => p.Org.Slug == orgSlug && p.Slug == projectSlug)
            .Select(p => ToDto(p))
            .SingleOrDefaultAsync(ct)
        ?? throw AppException.NotFound("Project");

    /// <summary>
    /// Schema creation is DDL and therefore not transactional with the row insert on every
    /// Postgres setup, so the row is written first and the schema second: a failed provision
    /// leaves an unusable project row to retry, never a schema with no owner.
    /// </summary>
    public async Task<(ProjectDto Project, ProjectKeysResponse Keys)> CreateAsync(
        Guid orgId, CreateProjectRequest req, CancellationToken ct)
    {
        var slug = $"{slugs.GenerateSlug(req.Name)}-{RandomNumberGenerator.GetHexString(6, lowercase: true)}";
        var schema = SchemaNames.NewProjectSchema();

        var project = new Project
        {
            OrgId = orgId,
            Name = req.Name.Trim(),
            Slug = slug,
            DbSchema = schema,
            ProjectUrl = $"{api.Value.BaseUrl}/projects/{slug}",
            AuthJwtSecret = RandomNumberGenerator.GetHexString(64, lowercase: true),
            KeyVersion = 1,
            AnonKey = "",
            ServiceRoleKeyHash = ""
        };

        db.Projects.Add(project);
        await db.SaveChangesAsync(ct);

        var issued = MintKeys(project);
        await db.SaveChangesAsync(ct);

        project.DbRolePassword = await provisioner.ProvisionProjectAsync(schema, ct);
        await db.SaveChangesAsync(ct);

        return (ToDto(project), issued);
    }

    /// <summary>Bumping KeyVersion invalidates every key issued before this call.</summary>
    public async Task<ProjectKeysResponse> RotateKeysAsync(
        string orgSlug, string projectSlug, CancellationToken ct)
    {
        var project = await db.Projects
            .SingleOrDefaultAsync(p => p.Org.Slug == orgSlug && p.Slug == projectSlug, ct)
            ?? throw AppException.NotFound("Project");

        project.KeyVersion += 1;
        var issued = MintKeys(project);
        await db.SaveChangesAsync(ct);

        return issued;
    }

    private ProjectKeysResponse MintKeys(Project project)
    {
        var anon = keys.Sign(project.Id, ProjectKeyRole.Anon, project.KeyVersion);
        var service = keys.Sign(project.Id, ProjectKeyRole.ServiceRole, project.KeyVersion);

        project.AnonKey = anon;                                   // public by design
        project.ServiceRoleKeyHash = TokenService.Hash(service);  // shown once, never stored in full

        return new ProjectKeysResponse(anon, service, project.KeyVersion);
    }

    private static ProjectDto ToDto(Project p) => new(
        p.Id, p.OrgId, p.Name, p.Slug, p.DbSchema, p.ProjectUrl, p.AnonKey, p.CreatedAt, p.UpdatedAt);
}

/// <summary>
/// Shared by every project-scoped feature: resolves a project from either a dashboard route
/// (org slug + project slug) or a project key, and asserts the key matches the URL.
/// </summary>
public sealed class ProjectResolver(SupavoltDbContext db)
{
    public async Task<Project> ByDashboardRouteAsync(string orgSlug, string projectSlug, CancellationToken ct) =>
        await db.Projects
            .SingleOrDefaultAsync(p => p.Org.Slug == orgSlug && p.Slug == projectSlug, ct)
        ?? throw AppException.NotFound("Project");

    /// <summary>
    /// Validates three things the original checked only partly: the project exists, the key's
    /// project matches the slug in the URL, and the key version is current.
    /// </summary>
    public async Task<Project> ByKeyAsync(
        Guid projectId, int keyVersion, string projectSlug, CancellationToken ct)
    {
        var project = await db.Projects.SingleOrDefaultAsync(p => p.Id == projectId, ct)
            ?? throw AppException.NotFound("Project");

        if (project.Slug != projectSlug)
            throw AppException.Forbidden("API key does not match this project URL");

        if (project.KeyVersion != keyVersion)
            throw AppException.Unauthorized("API key has been rotated");

        return project;
    }

    /// <summary>For callers with no project slug in the route, such as the realtime hub.</summary>
    public Task<bool> IsCurrentKeyAsync(Guid projectId, int keyVersion, CancellationToken ct) =>
        db.Projects.AnyAsync(p => p.Id == projectId && p.KeyVersion == keyVersion, ct);
}

public static class ProjectsEndpoints
{
    public static IEndpointRouteBuilder MapProjects(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/projects")
            .WithTags("Projects")
            .RequireAuthorization(Policies.OrgMember);

        group.MapGet("/", (string slug, ProjectsService svc, CancellationToken ct) =>
            svc.ListAsync(slug, ct));

        group.MapGet("/{projectSlug}", (string slug, string projectSlug, ProjectsService svc, CancellationToken ct) =>
            svc.GetAsync(slug, projectSlug, ct));

        group.MapPost("/", async (
                string slug, CreateProjectRequest req, HttpContext http,
                ProjectsService svc, CancellationToken ct) =>
            {
                var (project, keys) = await svc.CreateAsync(http.OrgId(), req, ct);
                return TypedResults.Created(project.ProjectUrl, new { project, keys });
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapPost("/{projectSlug}/keys/rotate", (
                string slug, string projectSlug, ProjectsService svc, CancellationToken ct) =>
                svc.RotateKeysAsync(slug, projectSlug, ct))
            .RequireAuthorization(Policies.OrgAdmin);

        return app;
    }
}
