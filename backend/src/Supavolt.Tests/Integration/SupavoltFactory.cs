using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.Mvc.Testing.Handlers;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Npgsql;
using Shouldly;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Mail;
using Testcontainers.PostgreSql;

namespace Supavolt.Tests.Integration;

/// <summary>
/// The real API over a throwaway Postgres 17. One container per test run: every test registers
/// its own users and orgs, so tests stay independent without resetting the database.
/// </summary>
public sealed class SupavoltFactory : WebApplicationFactory<Program>, IAsyncLifetime
{
    private readonly PostgreSqlContainer _pg = new PostgreSqlBuilder("postgres:17")
        .WithDatabase("supavolt")
        .WithUsername("supavolt_admin")
        .WithPassword("supavolt")
        .Build();

    public CapturingEmailSender Mail { get; } = new();

    /// <summary>For tests that must set up state the API itself can no longer create.</summary>
    public string AdminConnectionString => _pg.GetConnectionString();

    public async Task InitializeAsync()
    {
        await _pg.StartAsync();

        // The same script docker-compose runs on a fresh volume: creates the least-privilege role.
        var roles = await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "01-roles.sql"));
        var result = await _pg.ExecScriptAsync(roles);
        result.ExitCode.ShouldBe(0, result.Stderr);

        var admin = new NpgsqlConnectionStringBuilder(_pg.GetConnectionString());
        var tenant = new NpgsqlConnectionStringBuilder(admin.ConnectionString)
        {
            Username = "supavolt_tenant",
            Password = "supavolt_tenant"
        };
        var direct = new NpgsqlConnectionStringBuilder(admin.ConnectionString) { Pooling = false };

        // Program.cs reads configuration while building, before WebApplicationFactory's own
        // configuration hooks run, so environment variables are the reliable channel.
        var settings = new Dictionary<string, string>
        {
            ["Database__ConnectionString"] = admin.ConnectionString,
            ["Database__TenantConnectionString"] = tenant.ConnectionString,
            ["Database__DirectConnectionString"] = direct.ConnectionString,
            ["Jwt__AccessSecret"] = new string('a', 64),
            ["Jwt__RefreshSecret"] = new string('r', 64),
            ["ProjectKeys__Secret"] = new string('p', 64),
            ["Invites__Secret"] = new string('i', 64),
            ["Storage__Bucket"] = "test",
            ["Storage__AccessKeyId"] = "test",
            ["Storage__SecretAccessKey"] = "test",
            ["Storage__ServiceUrl"] = "http://127.0.0.1:9",
            ["Storage__PublicBaseUrl"] = "http://127.0.0.1:9/test",
            ["RateLimits__AuthPermitsPerMinute"] = "10000",
            ["Google__ClientId"] = "",
            ["GitHub__ClientId"] = "",
        };
        foreach (var (key, value) in settings) Environment.SetEnvironmentVariable(key, value);

        using var scope = Services.CreateScope();
        await scope.ServiceProvider.GetRequiredService<SupavoltDbContext>().Database.MigrateAsync();
    }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");
        builder.ConfigureTestServices(services =>
        {
            services.RemoveAll<IEmailSender>();
            services.AddSingleton<IEmailSender>(Mail);
        });
    }

    public new async Task DisposeAsync()
    {
        await base.DisposeAsync();
        await _pg.DisposeAsync();
    }

    /// <summary>A cookie-carrying client, as a browser would be.</summary>
    public ApiClient NewClient()
    {
        var jar = new CookieContainer();
        var http = CreateDefaultClient(new Uri("https://localhost/api/"), new CookieContainerHandler(jar));
        return new ApiClient(http, jar);
    }

    /// <summary>A registered, signed-in dashboard user with their personal org.</summary>
    public async Task<ApiClient> NewUserAsync(string? email = null)
    {
        var client = NewClient();
        client.Email = email ?? $"u{Guid.NewGuid():N}@example.com";

        var res = await client.Http.PostAsJsonAsync("auth/register",
            new { email = client.Email, password = "correct-horse-battery", name = "Test" });
        res.StatusCode.ShouldBe(HttpStatusCode.OK, await res.Content.ReadAsStringAsync());

        var orgs = await client.Http.GetFromJsonAsync<List<Dictionary<string, object>>>("orgs");
        client.OrgSlug = orgs!.Single()["slug"].ToString()!;
        return client;
    }
}

public sealed class ApiClient(HttpClient http, CookieContainer jar)
{
    public HttpClient Http { get; } = http;
    public CookieContainer Jar { get; } = jar;
    public string Email { get; set; } = "";
    public string OrgSlug { get; set; } = "";

    public string? Cookie(string name) =>
        Jar.GetCookies(new Uri("https://localhost/")).FirstOrDefault(c => c.Name == name)?.Value;
}

public sealed partial class CapturingEmailSender : IEmailSender
{
    private readonly ConcurrentDictionary<string, string> _last = new(StringComparer.OrdinalIgnoreCase);

    public Task SendAsync(string to, string subject, string html, CancellationToken ct = default)
    {
        _last[to] = html;
        return Task.CompletedTask;
    }

    public string InviteToken(string to) =>
        TokenPattern().Match(_last[to]).Groups[1].Value is { Length: > 0 } token
            ? token
            : throw new InvalidOperationException($"No invite token mailed to {to}");

    [GeneratedRegex("token=([0-9a-f]+)")]
    private static partial Regex TokenPattern();
}

[CollectionDefinition(Name)]
public sealed class IntegrationCollection : ICollectionFixture<SupavoltFactory>
{
    public const string Name = "integration";
}
