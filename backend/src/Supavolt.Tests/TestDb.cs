using Microsoft.AspNetCore.DataProtection;
using Microsoft.EntityFrameworkCore;
using Supavolt.Api.Infrastructure;

namespace Supavolt.Tests;

/// <summary>An EF in-memory context for unit tests that need entities but not SQL.</summary>
public static class TestDb
{
    public static SupavoltDbContext InMemory() =>
        new(new DbContextOptionsBuilder<SupavoltDbContext>()
                .UseInMemoryDatabase(Guid.NewGuid().ToString())
                .Options,
            new EphemeralDataProtectionProvider());

    public static Project AddProject(SupavoltDbContext db, int keyVersion)
    {
        var org = new Organization { Id = Guid.NewGuid(), Name = "Org", Slug = "org-" + Guid.NewGuid().ToString("N")[..6] };
        var project = new Project
        {
            Id = Guid.NewGuid(),
            Org = org,
            Name = "Demo",
            Slug = "demo-" + Guid.NewGuid().ToString("N")[..6],
            DbSchema = "proj_" + Guid.NewGuid().ToString("N")[..8],
            ProjectUrl = "http://localhost/api/projects/demo",
            AnonKey = "anon",
            ServiceRoleKeyHash = "hash",
            AuthJwtSecret = new string('s', 64),
            KeyVersion = keyVersion
        };

        db.Projects.Add(project);
        db.SaveChanges();
        return project;
    }
}
