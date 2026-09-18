using Supavolt.Contracts;

namespace Supavolt.Api.Infrastructure;

public class User
{
    public Guid Id { get; set; }
    public string Email { get; set; } = default!;
    public string? Name { get; set; }
    public string? AvatarUrl { get; set; }
    public string? PasswordHash { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }

    public List<OrgMember> Memberships { get; set; } = [];
}

/// <summary>
/// Stored refresh tokens. The original re-signed a new refresh token from the old one with no
/// server-side record, so a stolen token stayed valid for its full 7 days. Rows here are hashed,
/// rotated on use, and revocable.
/// </summary>
public class RefreshToken
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    public User User { get; set; } = default!;

    /// <summary>SHA-256 of the token. The plaintext only ever lives in the cookie.</summary>
    public string TokenHash { get; set; } = default!;

    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }

    /// <summary>Set when this token was rotated, so reuse of an old token is detectable.</summary>
    public Guid? ReplacedByTokenId { get; set; }
}

public class Organization
{
    public Guid Id { get; set; }
    public string Name { get; set; } = default!;
    public string Slug { get; set; } = default!;
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }

    public List<OrgMember> Members { get; set; } = [];
    public List<Project> Projects { get; set; } = [];
}

public class OrgMember
{
    public Guid Id { get; set; }
    public Guid OrgId { get; set; }
    public Organization Org { get; set; } = default!;
    public Guid UserId { get; set; }
    public User User { get; set; } = default!;
    public OrgRole Role { get; set; } = OrgRole.Developer;
    public DateTimeOffset CreatedAt { get; set; }

    /// <summary>Soft delete. A global query filter hides removed rows from every query.</summary>
    public DateTimeOffset? RemovedAt { get; set; }
}

public class Invite
{
    public Guid Id { get; set; }
    public Guid OrgId { get; set; }
    public Organization Org { get; set; } = default!;
    public string Email { get; set; } = default!;
    public Guid InvitedByUserId { get; set; }
    /// <summary>SHA-256 of the emailed token. The raw token is never stored.</summary>
    public string TokenHash { get; set; } = default!;
    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? AcceptedAt { get; set; }
    public DateTimeOffset? RevokedAt { get; set; }
}

public class Project
{
    public Guid Id { get; set; }
    public Guid OrgId { get; set; }
    public Organization Org { get; set; } = default!;
    public string Name { get; set; } = default!;
    public string Slug { get; set; } = default!;

    /// <summary>Always proj_&lt;8 hex&gt;. Generated here, never accepted from a caller.</summary>
    public string DbSchema { get; set; } = default!;

    public string ProjectUrl { get; set; } = default!;

    /// <summary>Anon key in full: it is a public credential by design.</summary>
    public string AnonKey { get; set; } = default!;

    /// <summary>SHA-256 of the service-role key. Plaintext is returned once, at creation or rotation.</summary>
    public string ServiceRoleKeyHash { get; set; } = default!;

    /// <summary>Bumped on rotation; carried as a claim so old keys stop validating.</summary>
    public int KeyVersion { get; set; } = 1;

    /// <summary>Signs end-user tokens for this project only.</summary>
    public string AuthJwtSecret { get; set; } = default!;

    /// <summary>
    /// Password of this project's own Postgres login role, which the SQL editor connects as.
    /// Encrypted at rest. Null for projects created before per-project roles; filled on first use.
    /// </summary>
    public string? DbRolePassword { get; set; }

    public string? SiteUrl { get; set; }
    public List<string> RedirectUrls { get; set; } = [];

    // Encrypted at rest through a value converter backed by IDataProtector.
    public string? GoogleClientId { get; set; }
    public string? GoogleClientSecret { get; set; }
    public string? GithubClientId { get; set; }
    public string? GithubClientSecret { get; set; }

    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset UpdatedAt { get; set; }

    public List<StorageBucket> Buckets { get; set; } = [];
}

public class QueryHistoryItem
{
    public Guid Id { get; set; }
    public Guid ProjectId { get; set; }
    public Project Project { get; set; } = default!;
    public string Sql { get; set; } = default!;
    public int ExecutionTimeMs { get; set; }
    public long RowCount { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
}

public class StorageBucket
{
    public Guid Id { get; set; }
    public Guid ProjectId { get; set; }
    public Project Project { get; set; } = default!;
    public string Name { get; set; } = default!;
    public BucketAccess Access { get; set; } = BucketAccess.Public;
    public DateTimeOffset CreatedAt { get; set; }

    public List<StorageObject> Objects { get; set; } = [];
}

public class StorageObject
{
    public Guid Id { get; set; }
    public Guid BucketId { get; set; }
    public StorageBucket Bucket { get; set; } = default!;
    public string Name { get; set; } = default!;
    public long Size { get; set; }
    public string MimeType { get; set; } = default!;

    /// <summary>Key in the object store: {projectId}/{bucketId}/{guid}/{fileName}.</summary>
    public string ObjectKey { get; set; } = default!;

    /// <summary>Public URL for public buckets; empty for private ones, which are signed on demand.</summary>
    public string Url { get; set; } = "";

    public DateTimeOffset CreatedAt { get; set; }
}

/// <summary>Single-use magic-link and OAuth-exchange tokens for project end-users.</summary>
public class ProjectAuthToken
{
    public Guid Id { get; set; }
    public Guid ProjectId { get; set; }
    public Project Project { get; set; } = default!;
    public string TokenHash { get; set; } = default!;
    public string Email { get; set; } = default!;
    public string Purpose { get; set; } = default!;   // "magic_link" | "oauth_exchange"
    public DateTimeOffset ExpiresAt { get; set; }
    public DateTimeOffset? ConsumedAt { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
}
