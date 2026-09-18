using Microsoft.AspNetCore.DataProtection;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.ChangeTracking;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace Supavolt.Api.Infrastructure;

public class SupavoltDbContext(DbContextOptions<SupavoltDbContext> options, IDataProtectionProvider protection)
    : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();
    public DbSet<Organization> Organizations => Set<Organization>();
    public DbSet<OrgMember> OrgMembers => Set<OrgMember>();
    public DbSet<Invite> Invites => Set<Invite>();
    public DbSet<Project> Projects => Set<Project>();
    public DbSet<QueryHistoryItem> QueryHistory => Set<QueryHistoryItem>();
    public DbSet<StorageBucket> StorageBuckets => Set<StorageBucket>();
    public DbSet<StorageObject> StorageObjects => Set<StorageObject>();
    public DbSet<ProjectAuthToken> ProjectAuthTokens => Set<ProjectAuthToken>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        // Encrypts per-project OAuth client secrets at rest. The rest of the code sees plaintext.
        var protector = protection.CreateProtector("Supavolt.ProjectSecrets.v1");
        var encrypted = new ValueConverter<string?, string?>(
            v => v == null ? null : protector.Protect(v),
            v => v == null ? null : protector.Unprotect(v));
        var encryptedRequired = new ValueConverter<string, string>(
            v => protector.Protect(v),
            v => protector.Unprotect(v));

        var stringList = new ValueConverter<List<string>, string>(
            v => JsonSerializer.Serialize(v, (JsonSerializerOptions?)null),
            v => JsonSerializer.Deserialize<List<string>>(v, (JsonSerializerOptions?)null) ?? new List<string>());
        var stringListComparer = new ValueComparer<List<string>>(
            (a, c) => a!.SequenceEqual(c!),
            v => v.Aggregate(0, (h, s) => HashCode.Combine(h, s.GetHashCode())),
            v => v.ToList());

        b.Entity<User>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.Property(x => x.UpdatedAt).HasDefaultValueSql("now()");
            // Case-insensitive uniqueness: the original allowed Alice@x.com and alice@x.com to coexist.
            // The functional unique index on lower(email) is created by raw SQL in the migration.
            e.HasIndex(x => x.Email).IsUnique();
        });

        b.Entity<RefreshToken>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.TokenHash).IsUnique();
            e.HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<Organization>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.Property(x => x.UpdatedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.Slug).IsUnique();
        });

        b.Entity<OrgMember>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.Property(x => x.Role).HasConversion<string>();
            e.HasOne(x => x.Org).WithMany(o => o.Members).HasForeignKey(x => x.OrgId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne(x => x.User).WithMany(u => u.Memberships).HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
            e.HasIndex(x => new { x.OrgId, x.UserId });
            // Every membership query in the original had to remember "removed_at is null". Now it can't forget.
            e.HasQueryFilter(x => x.RemovedAt == null);
        });

        b.Entity<Invite>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.HasOne(x => x.Org).WithMany().HasForeignKey(x => x.OrgId).OnDelete(DeleteBehavior.Cascade);
            e.HasIndex(x => new { x.OrgId, x.Email });
            e.HasIndex(x => x.TokenHash).IsUnique();
        });

        b.Entity<Project>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.Property(x => x.UpdatedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.Slug).IsUnique();
            e.HasIndex(x => x.DbSchema).IsUnique();
            e.Property(x => x.RedirectUrls).HasColumnType("jsonb").HasConversion(stringList, stringListComparer);
            e.Property(x => x.GoogleClientSecret).HasConversion(encrypted);
            e.Property(x => x.GithubClientSecret).HasConversion(encrypted);
            e.Property(x => x.AuthJwtSecret).HasConversion(encryptedRequired);
            e.Property(x => x.DbRolePassword).HasConversion(encrypted);
            e.HasOne(x => x.Org).WithMany(o => o.Projects).HasForeignKey(x => x.OrgId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<QueryHistoryItem>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.HasOne(x => x.Project).WithMany().HasForeignKey(x => x.ProjectId).OnDelete(DeleteBehavior.Cascade);
            e.HasIndex(x => new { x.ProjectId, x.CreatedAt });
        });

        b.Entity<StorageBucket>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.Property(x => x.Access).HasConversion<string>();
            e.HasOne(x => x.Project).WithMany(p => p.Buckets).HasForeignKey(x => x.ProjectId).OnDelete(DeleteBehavior.Cascade);
            e.HasIndex(x => new { x.ProjectId, x.Name }).IsUnique();
        });

        b.Entity<StorageObject>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.HasOne(x => x.Bucket).WithMany(bk => bk.Objects).HasForeignKey(x => x.BucketId).OnDelete(DeleteBehavior.Cascade);
            e.HasIndex(x => x.BucketId);
        });

        b.Entity<ProjectAuthToken>(e =>
        {
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasDefaultValueSql("gen_random_uuid()");
            e.Property(x => x.CreatedAt).HasDefaultValueSql("now()");
            e.HasIndex(x => x.TokenHash).IsUnique();
            e.HasOne(x => x.Project).WithMany().HasForeignKey(x => x.ProjectId).OnDelete(DeleteBehavior.Cascade);
        });
    }
}
