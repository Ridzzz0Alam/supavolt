using Dapper;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.TableEditor;

public sealed class TableEditorService(ITenantConnectionFactory connections, ProjectResolver projects)
{
    // ── Introspection: schema and table are VALUES here, so they are parameters, not text ──

    private const string TablesSql = """
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = @schema AND table_type = 'BASE TABLE'
        ORDER BY table_name
        """;

    private const string ColumnsSql = """
        SELECT column_name, data_type, is_nullable, column_default
        FROM information_schema.columns
        WHERE table_schema = @schema AND table_name = @table
        ORDER BY ordinal_position
        """;

    private const string PrimaryKeySql = """
        SELECT kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'PRIMARY KEY'
          AND tc.table_schema = @schema AND tc.table_name = @table
        ORDER BY kcu.ordinal_position
        """;

    private const string ForeignKeySql = """
        SELECT kcu.column_name, ccu.table_name AS foreign_table, ccu.column_name AS foreign_column
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        JOIN information_schema.constraint_column_usage ccu
          ON ccu.constraint_name = tc.constraint_name
        WHERE tc.constraint_type = 'FOREIGN KEY'
          AND tc.table_schema = @schema AND tc.table_name = @table
        """;

    public async Task<List<string>> ListTablesAsync(string orgSlug, string projectSlug, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        await using var conn = await connections.OpenAdminAsync(ct);

        var rows = await conn.QueryAsync<string>(TablesSql, new { schema = project.DbSchema });
        return rows.ToList();
    }

    public async Task<TableInfoDto> GetTableAsync(
        string orgSlug, string projectSlug, string table, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        _ = new SqlIdentifier(table, "table name");

        await using var conn = await connections.OpenAdminAsync(ct);
        var args = new { schema = project.DbSchema, table };

        var columns = (await conn.QueryAsync<ColumnRow>(ColumnsSql, args)).ToList();
        if (columns.Count == 0) throw AppException.NotFound($"Table \"{table}\"");

        var pks = (await conn.QueryAsync<string>(PrimaryKeySql, args)).ToHashSet(StringComparer.Ordinal);
        var fks = (await conn.QueryAsync<ForeignKeyRow>(ForeignKeySql, args))
            .ToDictionary(f => f.ColumnName, f => new ForeignKeyRef(f.ForeignTable, f.ForeignColumn), StringComparer.Ordinal);

        return new TableInfoDto(table, columns.Select(c => new TableColumnDto(
            c.ColumnName,
            ColumnTypes.FromPg(c.DataType),
            c.IsNullable == "YES",
            pks.Contains(c.ColumnName),
            c.ColumnDefault,
            fks.GetValueOrDefault(c.ColumnName))).ToList());
    }

    public async Task<string> GetPrimaryKeyColumnAsync(string schema, string table, CancellationToken ct)
    {
        await using var conn = await connections.OpenAdminAsync(ct);
        var pk = await conn.QueryFirstOrDefaultAsync<string>(PrimaryKeySql, new { schema, table });

        return pk ?? throw AppException.BadRequest($"Table \"{table}\" has no primary key");
    }

    public async Task<TableRowsDto> GetRowsAsync(
        string orgSlug, string projectSlug, string table, int limit, int offset, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");

        await using var conn = await connections.OpenTenantAsync(ct);

        var rows = await conn.QueryAsync($"SELECT * FROM {schema}.{tbl} LIMIT @limit OFFSET @offset",
            new { limit = Math.Clamp(limit, 1, 1000), offset = Math.Max(offset, 0) });

        var count = await conn.ExecuteScalarAsync<long>($"SELECT count(*) FROM {schema}.{tbl}");

        return new TableRowsDto(rows.Select(r => DapperRows.ToRow((object)r)).ToList(), count);
    }

    public async Task CreateTableAsync(
        string orgSlug, string projectSlug, CreateTableRequest req, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var table = new SqlIdentifier(req.Name, "table name");

        if (req.Columns.Count == 0) throw AppException.BadRequest("A table needs at least one column");

        var pkColumns = req.Columns.Where(c => c.IsPrimaryKey).ToList();
        var definitions = new List<string>();
        var constraints = new List<string>();

        foreach (var col in req.Columns)
        {
            var name = new SqlIdentifier(col.Name, "column name");
            var parts = new List<string> { $"{name} {col.Type.ToSql()}" };

            if (col.IsPrimaryKey && col.Type == ColumnType.Bigint && string.IsNullOrWhiteSpace(col.DefaultValue))
                parts[0] += " GENERATED ALWAYS AS IDENTITY";

            if (pkColumns.Count == 1 && col.IsPrimaryKey) parts.Add("PRIMARY KEY");
            if (!col.IsNullable && !col.IsPrimaryKey) parts.Add("NOT NULL");

            if (!string.IsNullOrWhiteSpace(col.DefaultValue))
                parts.Add($"DEFAULT {ColumnTypes.FormatDefault(col.DefaultValue, col.Type)}");

            definitions.Add(string.Join(' ', parts));

            if (col.ForeignKeyTable is { Length: > 0 } fkTable && col.ForeignKeyColumn is { Length: > 0 } fkColumn)
            {
                var refTable = new SqlIdentifier(fkTable, "foreign key table");
                var refColumn = new SqlIdentifier(fkColumn, "foreign key column");
                constraints.Add($"FOREIGN KEY ({name}) REFERENCES {schema}.{refTable} ({refColumn})");
            }
        }

        if (pkColumns.Count > 1)
        {
            var cols = pkColumns.Select(c => new SqlIdentifier(c.Name, "column name").ToString());
            constraints.Add($"PRIMARY KEY ({string.Join(", ", cols)})");
        }

        var body = string.Join(", ", definitions.Concat(constraints));

        await using var conn = await connections.OpenAdminAsync(ct);
        await conn.ExecuteAsync($"CREATE TABLE {schema}.{table} ({body})");
    }

    public async Task DropTableAsync(string orgSlug, string projectSlug, string table, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");

        await using var conn = await connections.OpenAdminAsync(ct);
        await conn.ExecuteAsync($"DROP TABLE IF EXISTS {schema}.{tbl} CASCADE");
    }

    public async Task AddColumnAsync(
        string orgSlug, string projectSlug, string table, AddColumnRequest req, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");
        var col = new SqlIdentifier(req.Name, "column name");

        var definition = $"{col} {req.Type.ToSql()}";
        if (!string.IsNullOrWhiteSpace(req.DefaultValue))
            definition += $" DEFAULT {ColumnTypes.FormatDefault(req.DefaultValue, req.Type)}";

        await using var conn = await connections.OpenAdminAsync(ct);
        await conn.ExecuteAsync($"ALTER TABLE {schema}.{tbl} ADD COLUMN {definition}");
    }

    public async Task DropColumnAsync(
        string orgSlug, string projectSlug, string table, string column, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");
        var col = new SqlIdentifier(column, "column name");

        await using var conn = await connections.OpenAdminAsync(ct);
        await conn.ExecuteAsync($"ALTER TABLE {schema}.{tbl} DROP COLUMN {col}");
    }

    /// <summary>Column names are identifiers and validated; every value is a Dapper parameter.</summary>
    public async Task UpdateRowAsync(
        string orgSlug, string projectSlug, string table, string pkValue,
        UpdateRowRequest req, CancellationToken ct)
    {
        var project = await projects.ByDashboardRouteAsync(orgSlug, projectSlug, ct);
        var schema = SchemaNames.ValidateProjectSchema(project.DbSchema);
        var tbl = new SqlIdentifier(table, "table name");
        var pk = new SqlIdentifier(req.PkColumn, "primary key column");

        if (req.Updates.Count == 0) throw AppException.BadRequest("No columns to update");

        var parameters = new DynamicParameters();
        var assignments = new List<string>();
        var index = 0;

        foreach (var (column, value) in req.Updates)
        {
            var col = new SqlIdentifier(column, "column name");
            parameters.Add($"v{index}", value);
            assignments.Add($"{col} = @v{index}");
            index++;
        }

        parameters.Add("pk", pkValue);

        await using var conn = await connections.OpenTenantAsync(ct);
        var affected = await conn.ExecuteAsync(
            $"UPDATE {schema}.{tbl} SET {string.Join(", ", assignments)} WHERE {pk} = @pk", parameters);

        if (affected == 0) throw AppException.NotFound("Row");
    }

    private sealed record ColumnRow(string ColumnName, string DataType, string IsNullable, string? ColumnDefault);
    private sealed record ForeignKeyRow(string ColumnName, string ForeignTable, string ForeignColumn);
}

public static class TableEditorEndpoints
{
    public static IEndpointRouteBuilder MapTableEditor(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/projects/{projectSlug}/tables")
            .WithTags("Table editor")
            .RequireAuthorization(Policies.OrgMember);

        group.MapGet("/", (string slug, string projectSlug, TableEditorService svc, CancellationToken ct) =>
            svc.ListTablesAsync(slug, projectSlug, ct));

        group.MapGet("/{table}", (
            string slug, string projectSlug, string table, TableEditorService svc, CancellationToken ct) =>
            svc.GetTableAsync(slug, projectSlug, table, ct));

        group.MapGet("/{table}/rows", (
            string slug, string projectSlug, string table,
            TableEditorService svc, CancellationToken ct, int limit = 100, int offset = 0) =>
            svc.GetRowsAsync(slug, projectSlug, table, limit, offset, ct));

        group.MapPost("/", async (
                string slug, string projectSlug, CreateTableRequest req,
                TableEditorService svc, CancellationToken ct) =>
            {
                await svc.CreateTableAsync(slug, projectSlug, req, ct);
                return TypedResults.Created($"/orgs/{slug}/projects/{projectSlug}/tables/{req.Name}");
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapDelete("/{table}", async (
                string slug, string projectSlug, string table, TableEditorService svc, CancellationToken ct) =>
            {
                await svc.DropTableAsync(slug, projectSlug, table, ct);
                return TypedResults.NoContent();
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapPatch("/{table}/columns", async (
                string slug, string projectSlug, string table, AddColumnRequest req,
                TableEditorService svc, CancellationToken ct) =>
            {
                await svc.AddColumnAsync(slug, projectSlug, table, req, ct);
                return TypedResults.NoContent();
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapDelete("/{table}/columns/{column}", async (
                string slug, string projectSlug, string table, string column,
                TableEditorService svc, CancellationToken ct) =>
            {
                await svc.DropColumnAsync(slug, projectSlug, table, column, ct);
                return TypedResults.NoContent();
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapPatch("/{table}/rows/{pkValue}", async (
            string slug, string projectSlug, string table, string pkValue,
            UpdateRowRequest req, TableEditorService svc, CancellationToken ct) =>
        {
            await svc.UpdateRowAsync(slug, projectSlug, table, pkValue, req, ct);
            return TypedResults.NoContent();
        });

        return app;
    }
}
