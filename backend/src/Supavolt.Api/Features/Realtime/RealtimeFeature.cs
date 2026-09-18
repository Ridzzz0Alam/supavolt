using System.Security.Claims;
using System.Text.Json;
using System.Text.Json.Serialization;
using Dapper;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Npgsql;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Realtime;

public static class RealtimeGroups
{
    public static string Project(Guid projectId) => $"project:{projectId}";
    public static string Table(Guid projectId, string table) => $"project:{projectId}:table:{table}";
}

[Authorize(AuthenticationSchemes = ProjectKeyAuthenticationHandler.SchemeName)]
public sealed class RealtimeHub(ProjectResolver projects, ILogger<RealtimeHub> log) : Hub
{
    public override async Task OnConnectedAsync()
    {
        var projectId = Context.User!.ProjectId();

        // Same rule as the data API: a key issued before the last rotation is dead.
        if (!await projects.IsCurrentKeyAsync(projectId, Context.User!.KeyVersion(), Context.ConnectionAborted))
        {
            log.LogInformation("Realtime connection rejected: stale key for project {Project}", projectId);
            Context.Abort();
            return;
        }

        await Groups.AddToGroupAsync(Context.ConnectionId, RealtimeGroups.Project(projectId));
        log.LogDebug("Realtime client {Connection} joined project {Project}", Context.ConnectionId, projectId);
        await base.OnConnectedAsync();
    }

    /// <summary>
    /// Joining a table group. Postgres does the filtering per group, so an anon key only receives
    /// events for tables it explicitly subscribed to.
    /// </summary>
    public async Task Subscribe(string table)
    {
        if (!SqlIdentifier.TryCreate(table?.Trim(), out var identifier)) return;

        var projectId = Context.User!.ProjectId();
        await Groups.AddToGroupAsync(Context.ConnectionId, RealtimeGroups.Table(projectId, identifier.Value));
    }

    public async Task Unsubscribe(string table)
    {
        if (!SqlIdentifier.TryCreate(table?.Trim(), out var identifier)) return;

        var projectId = Context.User!.ProjectId();
        await Groups.RemoveFromGroupAsync(Context.ConnectionId, RealtimeGroups.Table(projectId, identifier.Value));
    }
}

/// <summary>
/// Holds one direct Postgres connection for the whole instance, parked on WaitAsync, and fans
/// notifications out through SignalR. The original opened a connection per channel, per project
/// and table.
/// </summary>
public sealed class NotificationListener(
    ITenantConnectionFactory connections,
    IHubContext<RealtimeHub> hub,
    ILogger<NotificationListener> log) : BackgroundService
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseUpper) }
    };

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(1);

        while (!ct.IsCancellationRequested)
        {
            try
            {
                await using var conn = connections.CreateListenConnection();
                conn.Notification += OnNotification;

                await conn.OpenAsync(ct);
                await conn.ExecuteAsync($"LISTEN {SchemaNames.RealtimeChannel}");

                log.LogInformation("Listening on channel {Channel}", SchemaNames.RealtimeChannel);
                backoff = TimeSpan.FromSeconds(1);

                while (!ct.IsCancellationRequested)
                    await conn.WaitAsync(ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception ex)
            {
                log.LogError(ex, "Realtime listener dropped; reconnecting in {Delay}", backoff);
                await Task.Delay(backoff, ct);
                backoff = TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 30));
            }
        }
    }

    private void OnNotification(object? sender, NpgsqlNotificationEventArgs e)
    {
        try
        {
            var payload = JsonSerializer.Deserialize<NotifyPayload>(e.Payload, Json);
            if (payload is null) return;

            var evt = new RealtimeEvent(
                payload.Type switch
                {
                    "INSERT" => RealtimeEventType.Insert,
                    "UPDATE" => RealtimeEventType.Update,
                    _ => RealtimeEventType.Delete
                },
                payload.Table,
                payload.Record,
                payload.OldRecord,
                payload.ProjectId,
                payload.Timestamp);

            // Fire and forget: the hub handles its own transport failures, and blocking here
            // would stall the listener connection.
            _ = hub.Clients
                .Group(RealtimeGroups.Table(payload.ProjectId, payload.Table))
                .SendAsync("event", evt, CancellationToken.None);
        }
        catch (JsonException ex)
        {
            log.LogWarning(ex, "Malformed realtime payload discarded");
        }
    }

    private sealed record NotifyPayload(
        string Type,
        string Table,
        Guid ProjectId,
        Dictionary<string, object?>? Record,
        Dictionary<string, object?>? OldRecord,
        DateTimeOffset Timestamp,
        bool Truncated = false);
}

public sealed class TriggerService(ITenantConnectionFactory connections, ProjectResolver projects)
{
    public async Task EnableAsync(string orgSlug, string projectSlug, string table, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");
        var trigger = new SqlIdentifier($"{tbl.Value}_realtime", "trigger name");

        await using var conn = await connections.OpenAdminAsync(ct);

        await conn.ExecuteAsync($"DROP TRIGGER IF EXISTS {trigger} ON {schema}.{tbl}");
        await conn.ExecuteAsync($"""
            CREATE TRIGGER {trigger}
            AFTER INSERT OR UPDATE OR DELETE ON {schema}.{tbl}
            FOR EACH ROW
            EXECUTE FUNCTION "{SchemaNames.Internal}".notify_change('{project.Id}')
            """);
    }

    public async Task DisableAsync(string orgSlug, string projectSlug, string table, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");
        var trigger = new SqlIdentifier($"{tbl.Value}_realtime", "trigger name");

        await using var conn = await connections.OpenAdminAsync(ct);
        await conn.ExecuteAsync($"DROP TRIGGER IF EXISTS {trigger} ON {schema}.{tbl}");
    }

    /// <summary>Which tables currently have the realtime trigger, so the dashboard can show state.</summary>
    public async Task<List<string>> EnabledTablesAsync(string orgSlug, string projectSlug, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);

        await using var conn = await connections.OpenAdminAsync(ct);
        var rows = await conn.QueryAsync<string>("""
            SELECT c.relname
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = @schema AND NOT t.tgisinternal AND t.tgname LIKE '%_realtime'
            """, new { schema = project.DbSchema });

        return rows.ToList();
    }
}

public static class RealtimeEndpoints
{
    public static IEndpointRouteBuilder MapRealtime(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/projects/{projectSlug}/realtime")
            .WithTags("Realtime")
            .RequireAuthorization(Policies.OrgMember);

        group.MapGet("/", (string slug, string projectSlug, TriggerService svc, CancellationToken ct) =>
            svc.EnabledTablesAsync(slug, projectSlug, ct));

        group.MapPost("/{table}/enable", async (
                string slug, string projectSlug, string table, TriggerService svc, CancellationToken ct) =>
            {
                await svc.EnableAsync(slug, projectSlug, table, ct);
                return TypedResults.Ok(new MessageResponse($"Realtime enabled for {table}"));
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapDelete("/{table}/disable", async (
                string slug, string projectSlug, string table, TriggerService svc, CancellationToken ct) =>
            {
                await svc.DisableAsync(slug, projectSlug, table, ct);
                return TypedResults.Ok(new MessageResponse($"Realtime disabled for {table}"));
            })
            .RequireAuthorization(Policies.OrgAdmin);

        return app;
    }
}
