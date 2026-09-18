using System.Diagnostics;
using Dapper;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.SqlEditor;

/// <summary>
/// Splits SQL into statements while respecting string literals, dollar quoting, identifiers and
/// comments. The original split on ';', so 'SELECT ...; -- x' and any literal containing a
/// semicolon were mis-counted.
/// </summary>
public static class SqlStatementSplitter
{
    private static readonly System.Text.RegularExpressions.Regex DollarTag =
        new(@"\G\$([A-Za-z_][A-Za-z0-9_]*)?\$", System.Text.RegularExpressions.RegexOptions.Compiled);

    public static List<string> Split(string sql)
    {
        var statements = new List<string>();
        var current = new System.Text.StringBuilder();
        var i = 0;

        while (i < sql.Length)
        {
            var c = sql[i];

            switch (c)
            {
                case '-' when i + 1 < sql.Length && sql[i + 1] == '-':
                    while (i < sql.Length && sql[i] != '\n') i++;
                    continue;

                case '/' when i + 1 < sql.Length && sql[i + 1] == '*':
                    i += 2;
                    while (i + 1 < sql.Length && !(sql[i] == '*' && sql[i + 1] == '/')) i++;
                    i = Math.Min(i + 2, sql.Length);
                    continue;

                case '\'' or '"':
                {
                    var quote = c;
                    current.Append(c);
                    i++;
                    while (i < sql.Length)
                    {
                        current.Append(sql[i]);
                        if (sql[i] == quote)
                        {
                            if (i + 1 < sql.Length && sql[i + 1] == quote) { current.Append(sql[++i]); i++; continue; }
                            i++;
                            break;
                        }
                        i++;
                    }
                    continue;
                }

                case '$':
                {
                    // A dollar-quote tag is $$ or $name$. Anything else ($1 parameters, a$b
                    // identifiers) is ordinary text, or "$1, $2; drop" would swallow the semicolon.
                    var tagMatch = DollarTag.Match(sql, i);
                    if (!tagMatch.Success) { current.Append(c); i++; continue; }

                    var end = i + tagMatch.Length - 1;
                    var tag = tagMatch.Value;
                    var close = sql.IndexOf(tag, end + 1, StringComparison.Ordinal);
                    if (close < 0) { current.Append(sql[i..]); i = sql.Length; continue; }

                    current.Append(sql[i..(close + tag.Length)]);
                    i = close + tag.Length;
                    continue;
                }

                case ';':
                    if (current.ToString().Trim().Length > 0) statements.Add(current.ToString().Trim());
                    current.Clear();
                    i++;
                    continue;

                default:
                    current.Append(c);
                    i++;
                    continue;
            }
        }

        if (current.ToString().Trim().Length > 0) statements.Add(current.ToString().Trim());
        return statements;
    }
}

public sealed class SqlEditorService(
    ITenantConnectionFactory connections,
    ISchemaProvisioner provisioner,
    ProjectResolver projects,
    SupavoltDbContext db)
{
    /// <summary>
    /// Opens the project's own role. Projects from before per-project roles get one on first use,
    /// and a role whose password was changed from inside the editor (a role may alter its own
    /// password) is re-keyed once rather than locking the project out.
    /// </summary>
    private async Task<NpgsqlConnection> OpenProjectConnectionAsync(Project project, CancellationToken ct)
    {
        if (project.DbRolePassword is null)
        {
            project.DbRolePassword = await provisioner.EnsureProjectRoleAsync(project.DbSchema, ct);
            await db.SaveChangesAsync(ct);
        }

        try
        {
            return await connections.OpenProjectAsync(project.DbSchema, project.DbRolePassword, ct);
        }
        catch (PostgresException ex) when (ex.SqlState == PostgresErrorCodes.InvalidPassword)
        {
            project.DbRolePassword = await provisioner.EnsureProjectRoleAsync(project.DbSchema, ct);
            await db.SaveChangesAsync(ct);
            return await connections.OpenProjectAsync(project.DbSchema, project.DbRolePassword, ct);
        }
    }

    public async Task<QueryResultDto> ExecuteAsync(
        string orgSlug, string projectSlug, string sql, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);

        var statements = SqlStatementSplitter.Split(sql);

        if (statements.Count == 0) throw AppException.BadRequest("SQL query cannot be empty");
        if (statements.Count > 1)
            throw AppException.BadRequest("Only one SQL statement per run. Remove statements after the first semicolon.");

        // Containment, not blocklisting: the project's role cannot touch the control plane or any
        // other project's schema, and the statement timeout bounds the damage a slow query does.
        await using var conn = await OpenProjectConnectionAsync(project, ct);
        await using var tx = await conn.BeginTransactionAsync(ct);

        await conn.ExecuteAsync($"SET LOCAL search_path TO {schema}, public");
        await conn.ExecuteAsync("SET LOCAL statement_timeout = '15s'");

        var stopwatch = Stopwatch.StartNew();

        List<string> columns;
        var rows = new List<Dictionary<string, object?>>();
        int affected;

        try
        {
            await using var command = new NpgsqlCommand(statements[0], conn, (NpgsqlTransaction)tx);
            await using var reader = await command.ExecuteReaderAsync(ct);

            columns = Enumerable.Range(0, reader.FieldCount).Select(reader.GetName).ToList();

            while (await reader.ReadAsync(ct))
            {
                var row = new Dictionary<string, object?>(columns.Count);
                for (var i = 0; i < columns.Count; i++)
                    row[columns[i]] = await reader.IsDBNullAsync(i, ct) ? null : reader.GetValue(i);

                rows.Add(row);
            }

            // Npgsql only finalises RecordsAffected once the reader has been closed.
            await reader.CloseAsync();
            affected = reader.RecordsAffected;
        }
        catch (PostgresException pg)
        {
            // Unlike the data API, the caller wrote this SQL and runs it as their own project's
            // role, so the database's own message is the useful answer and reveals nothing new.
            throw AppException.BadRequest(pg.MessageText);
        }
        var commandTag = DeriveCommand(statements[0]);
        stopwatch.Stop();

        await tx.CommitAsync(ct);

        var rowCount = rows.Count > 0 ? rows.Count : Math.Max(affected, 0);

        db.QueryHistory.Add(new QueryHistoryItem
        {
            ProjectId = project.Id,
            Sql = sql,
            ExecutionTimeMs = (int)stopwatch.ElapsedMilliseconds,
            RowCount = rowCount
        });
        await db.SaveChangesAsync(ct);

        return new QueryResultDto(rows, columns, rowCount, stopwatch.ElapsedMilliseconds, commandTag);
    }

    public Task<List<QueryHistoryDto>> HistoryAsync(string orgSlug, string projectSlug, CancellationToken ct) =>
        db.QueryHistory
            .Where(h => h.Project.Slug == projectSlug && h.Project.Org.Slug == orgSlug)
            .OrderByDescending(h => h.CreatedAt)
            .Take(50)
            .Select(h => new QueryHistoryDto(h.Id, h.Sql, h.ExecutionTimeMs, h.RowCount, h.CreatedAt))
            .ToListAsync(ct);

    private static string DeriveCommand(string statement)
    {
        var first = statement.TrimStart().Split([' ', '\n', '\t'], 2)[0];
        return first.ToUpperInvariant();
    }
}

public static class SqlEditorEndpoints
{
    public static IEndpointRouteBuilder MapSqlEditor(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/projects/{projectSlug}/sql")
            .WithTags("SQL editor")
            .RequireAuthorization(Policies.OrgMember);

        group.MapPost("/", (
            string slug, string projectSlug, ExecuteQueryRequest req,
            SqlEditorService svc, CancellationToken ct) =>
            svc.ExecuteAsync(slug, projectSlug, req.Sql, ct));

        group.MapGet("/history", (
            string slug, string projectSlug, SqlEditorService svc, CancellationToken ct) =>
            svc.HistoryAsync(slug, projectSlug, ct));

        return app;
    }
}
