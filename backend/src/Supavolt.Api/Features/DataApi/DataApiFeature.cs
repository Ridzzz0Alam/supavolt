using System.Security.Claims;
using System.Text.Json;
using Dapper;
using Microsoft.Extensions.Caching.Memory;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Features.TableEditor;
using Supavolt.Api.Infrastructure.Tenancy;

namespace Supavolt.Api.Features.DataApi;

public sealed class DataApiService(
    ITenantConnectionFactory connections,
    ProjectResolver projects,
    TableEditorService tables,
    IMemoryCache cache)
{
    public async Task<IReadOnlyList<Dictionary<string, object?>>> SelectAsync(
        ClaimsPrincipal key, string projectSlug, string table, IQueryCollection query, CancellationToken ct)
    {
        var (schema, tbl) = await ResolveAsync(key, projectSlug, table, ct);
        var parsed = FilterParser.Parse(query);

        var columns = parsed.Select.Count > 0
            ? string.Join(", ", parsed.Select.Select(c => c.ToString()))
            : "*";

        var parameters = new DynamicParameters();
        var where = BuildWhere(parsed.Filters, parameters);

        parameters.Add("limit", parsed.Limit);
        parameters.Add("offset", parsed.Offset);

        var order = parsed.Order is null
            ? ""
            : $"ORDER BY {parsed.Order.Column} {(parsed.Order.Descending ? "DESC" : "ASC")}";

        var sql = $"""
            SELECT {columns} FROM {schema}.{tbl}
            {where}
            {order}
            LIMIT @limit OFFSET @offset
            """;

        await using var conn = await connections.OpenTenantAsync(ct);
        var rows = await conn.QueryAsync(sql, parameters);

        return rows.Select(r => DapperRows.ToRow((object)r)).ToList();
    }

    public async Task<Dictionary<string, object?>> InsertAsync(
        ClaimsPrincipal key, string projectSlug, string table,
        JsonElement body, CancellationToken ct)
    {
        var (schema, tbl) = await ResolveAsync(key, projectSlug, table, ct);
        var values = ReadObject(body);

        if (values.Count == 0) throw AppException.BadRequest("Request body cannot be empty");

        var parameters = new DynamicParameters();
        var columns = new List<string>();
        var placeholders = new List<string>();
        var index = 0;

        foreach (var (column, value) in values)
        {
            columns.Add(new SqlIdentifier(column, "column name").ToString());
            parameters.Add($"v{index}", value);
            placeholders.Add($"@v{index}");
            index++;
        }

        var sql = $"""
            INSERT INTO {schema}.{tbl} ({string.Join(", ", columns)})
            VALUES ({string.Join(", ", placeholders)})
            RETURNING *
            """;

        await using var conn = await connections.OpenTenantAsync(ct);
        var row = await conn.QuerySingleAsync(sql, parameters);

        return DapperRows.ToRow(row);
    }

    public async Task<Dictionary<string, object?>> UpdateAsync(
        ClaimsPrincipal key, string projectSlug, string table, string id,
        JsonElement body, CancellationToken ct)
    {
        var (schema, tbl) = await ResolveAsync(key, projectSlug, table, ct);
        var pk = new SqlIdentifier(await PrimaryKeyAsync(schema.Value, table, ct), "primary key column");
        var values = ReadObject(body);

        if (values.Count == 0) throw AppException.BadRequest("Request body cannot be empty");

        var parameters = new DynamicParameters();
        var assignments = new List<string>();
        var index = 0;

        foreach (var (column, value) in values)
        {
            var col = new SqlIdentifier(column, "column name");
            parameters.Add($"v{index}", value);
            assignments.Add($"{col} = @v{index}");
            index++;
        }

        parameters.Add("pk", id);

        await using var conn = await connections.OpenTenantAsync(ct);
        var row = await conn.QuerySingleOrDefaultAsync(
            $"UPDATE {schema}.{tbl} SET {string.Join(", ", assignments)} WHERE {pk} = @pk RETURNING *",
            parameters);

        return row is null ? throw AppException.NotFound("Row") : DapperRows.ToRow(row);
    }

    public async Task<Dictionary<string, object?>> DeleteAsync(
        ClaimsPrincipal key, string projectSlug, string table, string id, CancellationToken ct)
    {
        var (schema, tbl) = await ResolveAsync(key, projectSlug, table, ct);
        var pk = new SqlIdentifier(await PrimaryKeyAsync(schema.Value, table, ct), "primary key column");

        await using var conn = await connections.OpenTenantAsync(ct);
        var row = await conn.QuerySingleOrDefaultAsync(
            $"DELETE FROM {schema}.{tbl} WHERE {pk} = @pk RETURNING *", new { pk = id });

        return row is null ? throw AppException.NotFound("Row") : DapperRows.ToRow(row);
    }

    private static string BuildWhere(IReadOnlyList<Filter> filters, DynamicParameters parameters)
    {
        if (filters.Count == 0) return "";

        var clauses = new List<string>(filters.Count);

        for (var i = 0; i < filters.Count; i++)
        {
            var f = filters[i];

            if (f.Operator == Contracts.FilterOperator.Is)
            {
                clauses.Add($"{f.Column} IS {(f.IsNull ? "NULL" : "NOT NULL")}");
                continue;
            }

            parameters.Add($"f{i}", f.Value);
            clauses.Add($"{f.Column} {f.Operator.ToSqlOperator()} @f{i}");
        }

        return $"WHERE {string.Join(" AND ", clauses)}";
    }

    private async Task<(SqlIdentifier Schema, SqlIdentifier Table)> ResolveAsync(
        ClaimsPrincipal key, string projectSlug, string table, CancellationToken ct)
    {
        var project = await projects.ByKeyAsync(key.ProjectId(), key.KeyVersion(), projectSlug, ct);

        return (SchemaNames.ValidateProjectSchema(project.DbSchema), new SqlIdentifier(table, "table name"));
    }

    /// <summary>Cached: the original looked the primary key up from information_schema on every write.</summary>
    private Task<string> PrimaryKeyAsync(string schema, string table, CancellationToken ct) =>
        cache.GetOrCreateAsync($"pk:{schema}:{table}", entry =>
        {
            entry.AbsoluteExpirationRelativeToNow = TimeSpan.FromMinutes(5);
            return tables.GetPrimaryKeyColumnAsync(schema, table, ct);
        })!;

    private static Dictionary<string, object?> ReadObject(JsonElement body)
    {
        if (body.ValueKind != JsonValueKind.Object)
            throw AppException.BadRequest("Request body must be a JSON object");

        var result = new Dictionary<string, object?>();

        foreach (var property in body.EnumerateObject())
            result[property.Name] = property.Value.ValueKind switch
            {
                JsonValueKind.String => property.Value.GetString(),
                JsonValueKind.Number => property.Value.TryGetInt64(out var l) ? l : property.Value.GetDecimal(),
                JsonValueKind.True => true,
                JsonValueKind.False => false,
                JsonValueKind.Null => null,
                // Objects and arrays go in as jsonb text, still as a parameter.
                _ => property.Value.GetRawText()
            };

        return result;
    }
}

public static class DataApiEndpoints
{
    public static IEndpointRouteBuilder MapDataApi(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/projects/{projectSlug}/rest")
            .WithTags("Data API")
            .RequireAuthorization(Policies.ProjectKeyAny);

        group.MapGet("/{table}", (
            string projectSlug, string table, ClaimsPrincipal key,
            HttpRequest request, DataApiService svc, CancellationToken ct) =>
            svc.SelectAsync(key, projectSlug, table, request.Query, ct));

        group.MapPost("/{table}", async (
                string projectSlug, string table, JsonElement body, ClaimsPrincipal key,
                DataApiService svc, CancellationToken ct) =>
                TypedResults.Created((string?)null, await svc.InsertAsync(key, projectSlug, table, body, ct)))
            .RequireAuthorization(Policies.ProjectServiceRole);

        group.MapPatch("/{table}/{id}", (
                string projectSlug, string table, string id, JsonElement body, ClaimsPrincipal key,
                DataApiService svc, CancellationToken ct) =>
                svc.UpdateAsync(key, projectSlug, table, id, body, ct))
            .RequireAuthorization(Policies.ProjectServiceRole);

        group.MapDelete("/{table}/{id}", (
                string projectSlug, string table, string id, ClaimsPrincipal key,
                DataApiService svc, CancellationToken ct) =>
                svc.DeleteAsync(key, projectSlug, table, id, ct))
            .RequireAuthorization(Policies.ProjectServiceRole);

        return app;
    }
}
