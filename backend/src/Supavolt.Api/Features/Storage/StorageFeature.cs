using System.Security.Claims;
using Amazon.S3;
using Amazon.S3.Model;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Storage;

public interface IObjectStore
{
    string BuildKey(Guid projectId, Guid bucketId, string fileName);
    Task<(string Url, DateTimeOffset ExpiresAt)> PresignPutAsync(string key, string contentType);
    Task<(string Url, DateTimeOffset ExpiresAt)> PresignGetAsync(string key);
    string PublicUrl(string key);
    Task DeleteAsync(IEnumerable<string> keys, CancellationToken ct);
}

public sealed class S3ObjectStore(IAmazonS3 s3, IOptions<StorageOptions> options, TimeProvider clock) : IObjectStore
{
    private readonly StorageOptions _o = options.Value;

    // The SDK presigns https by default; a plain-http endpoint such as local MinIO needs http.
    private Protocol UrlProtocol =>
        _o.ServiceUrl?.StartsWith("http://", StringComparison.OrdinalIgnoreCase) == true ? Protocol.HTTP : Protocol.HTTPS;

    public string BuildKey(Guid projectId, Guid bucketId, string fileName)
    {
        // The key is built server-side so a caller cannot write outside its own prefix.
        var safeName = Path.GetFileName(fileName).Replace('\\', '_');
        return $"{projectId}/{bucketId}/{Guid.CreateVersion7()}/{safeName}";
    }

    public Task<(string, DateTimeOffset)> PresignPutAsync(string key, string contentType)
    {
        var expires = clock.GetUtcNow().Add(_o.UploadUrlLifetime);

        var url = s3.GetPreSignedURL(new GetPreSignedUrlRequest
        {
            BucketName = _o.Bucket,
            Key = key,
            Verb = HttpVerb.PUT,
            ContentType = contentType,
            Protocol = UrlProtocol,
            Expires = expires.UtcDateTime
        });

        return Task.FromResult((url, expires));
    }

    public Task<(string, DateTimeOffset)> PresignGetAsync(string key)
    {
        var expires = clock.GetUtcNow().Add(_o.DownloadUrlLifetime);

        var url = s3.GetPreSignedURL(new GetPreSignedUrlRequest
        {
            BucketName = _o.Bucket,
            Key = key,
            Verb = HttpVerb.GET,
            Protocol = UrlProtocol,
            Expires = expires.UtcDateTime
        });

        return Task.FromResult((url, expires));
    }

    public string PublicUrl(string key) => $"{_o.PublicBaseUrl.TrimEnd('/')}/{key}";

    public async Task DeleteAsync(IEnumerable<string> keys, CancellationToken ct)
    {
        // S3 batch delete caps at 1000 keys per request.
        foreach (var batch in keys.Chunk(1000))
            await s3.DeleteObjectsAsync(new DeleteObjectsRequest
            {
                BucketName = _o.Bucket,
                Objects = batch.Select(k => new KeyVersion { Key = k }).ToList()
            }, ct);
    }
}

public sealed class StorageService(
    SupavoltDbContext db,
    IObjectStore store,
    ProjectResolver projects,
    IOptions<StorageOptions> options)
{
    public Task<List<StorageBucketDto>> ListBucketsAsync(Guid projectId, CancellationToken ct) =>
        db.StorageBuckets
            .Where(b => b.ProjectId == projectId)
            .OrderBy(b => b.Name)
            .Select(b => new StorageBucketDto(b.Id, b.ProjectId, b.Name, b.Access, b.CreatedAt))
            .ToListAsync(ct);

    public async Task<StorageBucketDto> CreateBucketAsync(
        Guid projectId, CreateBucketRequest req, CancellationToken ct)
    {
        var name = req.Name.Trim();
        if (name.Length == 0) throw AppException.BadRequest("Bucket name is required");

        var bucket = new StorageBucket { ProjectId = projectId, Name = name, Access = req.Access };
        db.StorageBuckets.Add(bucket);
        await db.SaveChangesAsync(ct);

        return new StorageBucketDto(bucket.Id, projectId, bucket.Name, bucket.Access, bucket.CreatedAt);
    }

    /// <summary>
    /// Every lookup is scoped by project id. The original took a bucket or object id straight from
    /// the route with no ownership check, so any member of any org could delete any object.
    /// </summary>
    private async Task<StorageBucket> BucketAsync(Guid projectId, Guid bucketId, CancellationToken ct) =>
        await db.StorageBuckets.SingleOrDefaultAsync(b => b.Id == bucketId && b.ProjectId == projectId, ct)
        ?? throw AppException.NotFound("Bucket");

    private async Task<StorageBucket> BucketByNameAsync(Guid projectId, string name, CancellationToken ct) =>
        await db.StorageBuckets.SingleOrDefaultAsync(b => b.Name == name && b.ProjectId == projectId, ct)
        ?? throw AppException.NotFound("Bucket");

    private async Task<StorageObject> ObjectAsync(Guid projectId, Guid objectId, CancellationToken ct) =>
        await db.StorageObjects
            .Include(o => o.Bucket)
            .SingleOrDefaultAsync(o => o.Id == objectId && o.Bucket.ProjectId == projectId, ct)
        ?? throw AppException.NotFound("File");

    public async Task DeleteBucketAsync(Guid projectId, Guid bucketId, CancellationToken ct)
    {
        var bucket = await BucketAsync(projectId, bucketId, ct);

        var keys = await db.StorageObjects
            .Where(o => o.BucketId == bucket.Id)
            .Select(o => o.ObjectKey)
            .ToListAsync(ct);

        if (keys.Count > 0) await store.DeleteAsync(keys, ct);

        db.StorageBuckets.Remove(bucket);
        await db.SaveChangesAsync(ct);
    }

    public Task<List<StorageObjectDto>> ListObjectsAsync(Guid projectId, Guid bucketId, CancellationToken ct) =>
        db.StorageObjects
            .Where(o => o.BucketId == bucketId && o.Bucket.ProjectId == projectId)
            .OrderByDescending(o => o.CreatedAt)
            .Select(o => new StorageObjectDto(
                o.Id, o.BucketId, o.Name, o.Size, o.MimeType, o.ObjectKey, o.Url, o.CreatedAt))
            .ToListAsync(ct);

    public async Task<UploadUrlResponse> CreateUploadUrlAsync(
        Guid projectId, Guid bucketId, UploadUrlRequest req, CancellationToken ct)
    {
        if (req.Size > options.Value.MaxUploadBytes)
            throw AppException.BadRequest($"File exceeds the {options.Value.MaxUploadBytes / 1024 / 1024} MB limit");

        var bucket = await BucketAsync(projectId, bucketId, ct);
        var key = store.BuildKey(projectId, bucket.Id, req.FileName);
        var (url, expires) = await store.PresignPutAsync(key, req.ContentType);

        return new UploadUrlResponse(url, key, expires);
    }

    public async Task<StorageObjectDto> RegisterObjectAsync(
        Guid projectId, Guid bucketId, RegisterObjectRequest req, CancellationToken ct)
    {
        var bucket = await BucketAsync(projectId, bucketId, ct);

        // The key was minted by CreateUploadUrlAsync for this project and bucket. Re-check the
        // prefix so a caller cannot register a key belonging to another project.
        if (!req.ObjectKey.StartsWith($"{projectId}/{bucket.Id}/", StringComparison.Ordinal))
            throw AppException.Forbidden("Object key does not belong to this bucket");

        var entity = new StorageObject
        {
            BucketId = bucket.Id,
            Name = req.Name,
            Size = req.Size,
            MimeType = req.ContentType,
            ObjectKey = req.ObjectKey,
            Url = bucket.Access == BucketAccess.Public ? store.PublicUrl(req.ObjectKey) : ""
        };

        db.StorageObjects.Add(entity);
        await db.SaveChangesAsync(ct);

        return new StorageObjectDto(
            entity.Id, entity.BucketId, entity.Name, entity.Size,
            entity.MimeType, entity.ObjectKey, entity.Url, entity.CreatedAt);
    }

    public async Task DeleteObjectAsync(Guid projectId, Guid objectId, CancellationToken ct)
    {
        var obj = await ObjectAsync(projectId, objectId, ct);

        await store.DeleteAsync([obj.ObjectKey], ct);
        db.StorageObjects.Remove(obj);
        await db.SaveChangesAsync(ct);
    }

    public async Task<SignedUrlResponse> SignedUrlAsync(Guid projectId, Guid objectId, CancellationToken ct)
    {
        var obj = await ObjectAsync(projectId, objectId, ct);
        var (url, expires) = await store.PresignGetAsync(obj.ObjectKey);

        return new SignedUrlResponse(url, expires);
    }

    public async Task<Guid> BucketIdByNameAsync(Guid projectId, string bucketName, CancellationToken ct) =>
        (await BucketByNameAsync(projectId, bucketName, ct)).Id;

    public Task<Project> ResolveByKeyAsync(ClaimsPrincipal key, string projectSlug, CancellationToken ct) =>
        projects.ByKeyAsync(key.ProjectId(), key.KeyVersion(), projectSlug, ct);
}

public static class StorageEndpoints
{
    /// <summary>Dashboard surface: bucket management, keyed by org route and bucket id.</summary>
    public static IEndpointRouteBuilder MapStorage(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/projects/{projectSlug}/storage")
            .WithTags("Storage")
            .RequireAuthorization(Policies.OrgMember);

        group.MapGet("/buckets", async (
            string slug, string projectSlug, ProjectResolver projects, StorageService svc, CancellationToken ct) =>
        {
            var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
            return await svc.ListBucketsAsync(project.Id, ct);
        });

        group.MapPost("/buckets", async (
                string slug, string projectSlug, CreateBucketRequest req,
                ProjectResolver projects, StorageService svc, CancellationToken ct) =>
            {
                var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
                return TypedResults.Ok(await svc.CreateBucketAsync(project.Id, req, ct));
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapDelete("/buckets/{bucketId:guid}", async (
                string slug, string projectSlug, Guid bucketId,
                ProjectResolver projects, StorageService svc, CancellationToken ct) =>
            {
                var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
                await svc.DeleteBucketAsync(project.Id, bucketId, ct);
                return TypedResults.NoContent();
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapGet("/buckets/{bucketId:guid}/objects", async (
            string slug, string projectSlug, Guid bucketId,
            ProjectResolver projects, StorageService svc, CancellationToken ct) =>
        {
            var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
            return await svc.ListObjectsAsync(project.Id, bucketId, ct);
        });

        group.MapPost("/buckets/{bucketId:guid}/upload-url", async (
            string slug, string projectSlug, Guid bucketId, UploadUrlRequest req,
            ProjectResolver projects, StorageService svc, CancellationToken ct) =>
        {
            var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
            return await svc.CreateUploadUrlAsync(project.Id, bucketId, req, ct);
        });

        group.MapPost("/buckets/{bucketId:guid}/objects", async (
            string slug, string projectSlug, Guid bucketId, RegisterObjectRequest req,
            ProjectResolver projects, StorageService svc, CancellationToken ct) =>
        {
            var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
            return await svc.RegisterObjectAsync(project.Id, bucketId, req, ct);
        });

        group.MapDelete("/objects/{objectId:guid}", async (
            string slug, string projectSlug, Guid objectId,
            ProjectResolver projects, StorageService svc, CancellationToken ct) =>
        {
            var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
            await svc.DeleteObjectAsync(project.Id, objectId, ct);
            return TypedResults.NoContent();
        });

        group.MapGet("/objects/{objectId:guid}/signed-url", async (
            string slug, string projectSlug, Guid objectId,
            ProjectResolver projects, StorageService svc, CancellationToken ct) =>
        {
            var project = await projects.ByDashboardRouteAsync(slug, projectSlug, ct);
            return await svc.SignedUrlAsync(project.Id, objectId, ct);
        });

        return app;
    }

    /// <summary>SDK surface: same operations addressed by bucket name and authorised by project key.</summary>
    public static IEndpointRouteBuilder MapProjectStorage(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/projects/{projectSlug}/storage")
            .WithTags("Storage (SDK)")
            .RequireAuthorization(Policies.ProjectKeyAny);

        group.MapGet("/buckets/{bucketName}/objects", async (
            string projectSlug, string bucketName, ClaimsPrincipal key,
            StorageService svc, CancellationToken ct) =>
        {
            var project = await svc.ResolveByKeyAsync(key, projectSlug, ct);
            var bucketId = await svc.BucketIdByNameAsync(project.Id, bucketName, ct);
            return await svc.ListObjectsAsync(project.Id, bucketId, ct);
        });

        group.MapPost("/buckets/{bucketName}/upload-url", async (
                string projectSlug, string bucketName, UploadUrlRequest req, ClaimsPrincipal key,
                StorageService svc, CancellationToken ct) =>
            {
                var project = await svc.ResolveByKeyAsync(key, projectSlug, ct);
                var bucketId = await svc.BucketIdByNameAsync(project.Id, bucketName, ct);
                return await svc.CreateUploadUrlAsync(project.Id, bucketId, req, ct);
            })
            .RequireAuthorization(Policies.ProjectServiceRole);

        group.MapPost("/buckets/{bucketName}/objects", async (
                string projectSlug, string bucketName, RegisterObjectRequest req, ClaimsPrincipal key,
                StorageService svc, CancellationToken ct) =>
            {
                var project = await svc.ResolveByKeyAsync(key, projectSlug, ct);
                var bucketId = await svc.BucketIdByNameAsync(project.Id, bucketName, ct);
                return await svc.RegisterObjectAsync(project.Id, bucketId, req, ct);
            })
            .RequireAuthorization(Policies.ProjectServiceRole);

        group.MapDelete("/objects/{objectId:guid}", async (
                string projectSlug, Guid objectId, ClaimsPrincipal key,
                StorageService svc, CancellationToken ct) =>
            {
                var project = await svc.ResolveByKeyAsync(key, projectSlug, ct);
                await svc.DeleteObjectAsync(project.Id, objectId, ct);
                return TypedResults.NoContent();
            })
            .RequireAuthorization(Policies.ProjectServiceRole);

        group.MapGet("/objects/{objectId:guid}/signed-url", async (
            string projectSlug, Guid objectId, ClaimsPrincipal key,
            StorageService svc, CancellationToken ct) =>
        {
            var project = await svc.ResolveByKeyAsync(key, projectSlug, ct);
            return await svc.SignedUrlAsync(project.Id, objectId, ct);
        });

        return app;
    }
}
