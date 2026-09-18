namespace Supavolt.Contracts;

// ─── Enums ────────────────────────────────────────────────────────────────────

public enum OrgRole { Developer = 0, Admin = 1 }

public enum ProjectKeyRole { Anon = 0, ServiceRole = 1 }

public enum BucketAccess { Public = 0, Private = 1 }

public enum AuthProvider { Email = 0, Google = 1, Github = 2 }

/// <summary>The only column types the table editor will emit. Anything else is a 400.</summary>
public enum ColumnType { Text, Integer, Bigint, Boolean, Timestamp, Uuid, Jsonb, Numeric }

public enum FilterOperator { Eq, Neq, Gt, Gte, Lt, Lte, Like, Ilike, Is }

public enum RealtimeEventType { Insert, Update, Delete }

// ─── Platform auth ────────────────────────────────────────────────────────────

public record RegisterRequest(string Email, string Password, string Name);
public record LoginRequest(string Email, string Password);
public record MessageResponse(string Message);
public record CurrentUserResponse(Guid Id, string Email, string? Name, string? AvatarUrl);

// ─── Organizations and members ────────────────────────────────────────────────

public record CreateOrgRequest(string Name);

public record OrganizationDto(
    Guid Id,
    string Name,
    string Slug,
    OrgRole Role,
    int ProjectCount,
    int MemberCount,
    DateTimeOffset CreatedAt);

public record OrgMemberDto(
    Guid Id,
    OrgRole Role,
    DateTimeOffset CreatedAt,
    OrgMemberUserDto User);

public record OrgMemberUserDto(Guid Id, string? Name, string Email, string? AvatarUrl);

public record InviteMemberRequest(string Email);
public record UpdateRoleRequest(OrgRole Role);

// ─── Projects ─────────────────────────────────────────────────────────────────

public record CreateProjectRequest(string Name);

public record ProjectDto(
    Guid Id,
    Guid OrgId,
    string Name,
    string Slug,
    string DbSchema,
    string ProjectUrl,
    string AnonKey,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

/// <summary>Returned once, at creation or rotation. The service role key is never readable again.</summary>
public record ProjectKeysResponse(string AnonKey, string ServiceRoleKey, int KeyVersion);

// ─── Table editor ─────────────────────────────────────────────────────────────

public record ForeignKeyRef(string Table, string Column);

public record TableColumnDto(
    string Name,
    ColumnType Type,
    bool IsNullable,
    bool IsPrimaryKey,
    string? DefaultValue,
    ForeignKeyRef? ForeignKey);

public record TableInfoDto(string Name, IReadOnlyList<TableColumnDto> Columns);

public record TableRowsDto(IReadOnlyList<Dictionary<string, object?>> Rows, long Count);

public record CreateColumnRequest(
    string Name,
    ColumnType Type,
    bool IsNullable,
    bool IsPrimaryKey,
    string? DefaultValue = null,
    string? ForeignKeyTable = null,
    string? ForeignKeyColumn = null);

public record CreateTableRequest(string Name, IReadOnlyList<CreateColumnRequest> Columns);

public record AddColumnRequest(string Name, ColumnType Type, string? DefaultValue = null);

public record UpdateRowRequest(string PkColumn, Dictionary<string, object?> Updates);

// ─── SQL editor ───────────────────────────────────────────────────────────────

public record ExecuteQueryRequest(string Sql);

public record QueryResultDto(
    IReadOnlyList<Dictionary<string, object?>> Rows,
    IReadOnlyList<string> Columns,
    long RowCount,
    long ExecutionTimeMs,
    string Command);

public record QueryHistoryDto(
    Guid Id,
    string Sql,
    int ExecutionTimeMs,
    long RowCount,
    DateTimeOffset CreatedAt);

// ─── Realtime ─────────────────────────────────────────────────────────────────

public record RealtimeEvent(
    RealtimeEventType Type,
    string Table,
    Dictionary<string, object?>? Record,
    Dictionary<string, object?>? OldRecord,
    Guid ProjectId,
    DateTimeOffset Timestamp);

// ─── Storage ──────────────────────────────────────────────────────────────────

public record CreateBucketRequest(string Name, BucketAccess Access);

public record StorageBucketDto(Guid Id, Guid ProjectId, string Name, BucketAccess Access, DateTimeOffset CreatedAt);

public record StorageObjectDto(
    Guid Id,
    Guid BucketId,
    string Name,
    long Size,
    string MimeType,
    string ObjectKey,
    string Url,
    DateTimeOffset CreatedAt);

public record UploadUrlRequest(string FileName, string ContentType, long Size);
public record UploadUrlResponse(string UploadUrl, string ObjectKey, DateTimeOffset ExpiresAt);
public record RegisterObjectRequest(string Name, long Size, string ContentType, string ObjectKey);
public record SignedUrlResponse(string Url, DateTimeOffset ExpiresAt);

// ─── Project (end-user) auth ──────────────────────────────────────────────────

public record SignUpRequest(string Email, string Password);
public record SignInRequest(string Email, string Password);
public record MagicLinkRequest(string Email);
public record ExchangeCodeRequest(string Code);

public record ProjectAuthUserDto(
    Guid Id,
    string Email,
    bool EmailVerified,
    AuthProvider Provider,
    DateTimeOffset CreatedAt);

public record ProjectAuthResponse(ProjectAuthUserDto User, string AccessToken);

public record ProjectOAuthSettingsDto(
    string? SiteUrl,
    string? GoogleClientId,
    bool GoogleConfigured,
    string? GithubClientId,
    bool GithubConfigured,
    IReadOnlyList<string> RedirectUrls);

public record UpdateOAuthSettingsRequest(
    string? SiteUrl,
    string? GoogleClientId,
    string? GoogleClientSecret,
    string? GithubClientId,
    string? GithubClientSecret,
    IReadOnlyList<string>? RedirectUrls);
