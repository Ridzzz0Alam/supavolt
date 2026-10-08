package dev.supavolt.api.features.tableeditor;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Rows;
import dev.supavolt.api.common.SqlParams;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.infrastructure.tenancy.ColumnTypes;
import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.contracts.Contracts.AddColumnRequest;
import dev.supavolt.contracts.Contracts.ColumnType;
import dev.supavolt.contracts.Contracts.CreateTableRequest;
import dev.supavolt.contracts.Contracts.ForeignKeyRef;
import dev.supavolt.contracts.Contracts.TableColumnDto;
import dev.supavolt.contracts.Contracts.TableInfoDto;
import dev.supavolt.contracts.Contracts.TableRowsDto;
import dev.supavolt.contracts.Contracts.UpdateRowRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TableEditorService {

    // ── Introspection: schema and table are VALUES here, so they are parameters, not text ──

    private static final String TABLES_SQL = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = :schema AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

    private static final String COLUMNS_SQL = """
            SELECT column_name, data_type, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = :schema AND table_name = :table
            ORDER BY ordinal_position
            """;

    private static final String PRIMARY_KEY_SQL = """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            WHERE tc.constraint_type = 'PRIMARY KEY'
              AND tc.table_schema = :schema AND tc.table_name = :table
            ORDER BY kcu.ordinal_position
            """;

    private static final String FOREIGN_KEY_SQL = """
            SELECT kcu.column_name, ccu.table_name AS foreign_table, ccu.column_name AS foreign_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage ccu
              ON ccu.constraint_name = tc.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.table_schema = :schema AND tc.table_name = :table
            """;

    private final TenantConnections connections;
    private final ProjectResolver projects;

    public TableEditorService(TenantConnections connections, ProjectResolver projects) {
        this.connections = connections;
        this.projects = projects;
    }

    public List<String> listTables(String orgSlug, String projectSlug) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        return connections.admin().sql(TABLES_SQL).param("schema", project.getDbSchema()).query(String.class).list();
    }

    public TableInfoDto getTable(String orgSlug, String projectSlug, String table) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        new SqlIdentifier(table, "table name");

        var params = Map.<String, Object>of("schema", project.getDbSchema(), "table", table);
        var admin = connections.admin();

        var columns = admin.sql(COLUMNS_SQL).params(params).query(Rows.MAPPER).list();
        if (columns.isEmpty()) throw AppException.notFound("Table \"" + table + "\"");

        var pks = new HashSet<>(admin.sql(PRIMARY_KEY_SQL).params(params).query(String.class).list());
        var fks = new HashMap<String, ForeignKeyRef>();
        for (var fk : admin.sql(FOREIGN_KEY_SQL).params(params).query(Rows.MAPPER).list())
            fks.put((String) fk.get("column_name"),
                    new ForeignKeyRef((String) fk.get("foreign_table"), (String) fk.get("foreign_column")));

        var result = new ArrayList<TableColumnDto>();
        for (var c : columns) {
            var name = (String) c.get("column_name");
            result.add(new TableColumnDto(
                    name,
                    ColumnTypes.fromPg((String) c.get("data_type")),
                    "YES".equals(c.get("is_nullable")),
                    pks.contains(name),
                    (String) c.get("column_default"),
                    fks.get(name)));
        }

        return new TableInfoDto(table, result);
    }

    public String primaryKeyColumn(String schema, String table) {
        return connections.admin().sql(PRIMARY_KEY_SQL)
                .param("schema", schema)
                .param("table", table)
                .query(String.class).list().stream().findFirst()
                .orElseThrow(() -> AppException.badRequest("Table \"" + table + "\" has no primary key"));
    }

    public TableRowsDto getRows(String orgSlug, String projectSlug, String table, int limit, int offset) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");
        var tenant = connections.tenant();

        var rows = tenant.sql("SELECT * FROM " + schema + "." + tbl + " LIMIT :limit OFFSET :offset")
                .param("limit", Math.max(1, Math.min(limit, 1000)))
                .param("offset", Math.max(offset, 0))
                .query(Rows.MAPPER).list();

        var count = tenant.sql("SELECT count(*) FROM " + schema + "." + tbl).query(Long.class).single();

        return new TableRowsDto(rows, count);
    }

    public void createTable(String orgSlug, String projectSlug, CreateTableRequest req) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var table = new SqlIdentifier(req.name(), "table name");

        if (req.columns().isEmpty()) throw AppException.badRequest("A table needs at least one column");

        var pkColumns = req.columns().stream().filter(c -> c.isPrimaryKey()).toList();
        var definitions = new ArrayList<String>();
        var constraints = new ArrayList<String>();

        for (var col : req.columns()) {
            var name = new SqlIdentifier(col.name(), "column name");
            var definition = new StringBuilder(name + " " + ColumnTypes.toSql(col.type()));
            var hasDefault = col.defaultValue() != null && !col.defaultValue().isBlank();

            if (col.isPrimaryKey() && col.type() == ColumnType.BIGINT && !hasDefault)
                definition.append(" GENERATED ALWAYS AS IDENTITY");

            if (pkColumns.size() == 1 && col.isPrimaryKey()) definition.append(" PRIMARY KEY");
            if (!col.isNullable() && !col.isPrimaryKey()) definition.append(" NOT NULL");

            if (hasDefault)
                definition.append(" DEFAULT ").append(ColumnTypes.formatDefault(col.defaultValue(), col.type()));

            definitions.add(definition.toString());

            if (col.foreignKeyTable() != null && !col.foreignKeyTable().isEmpty()
                    && col.foreignKeyColumn() != null && !col.foreignKeyColumn().isEmpty()) {
                var refTable = new SqlIdentifier(col.foreignKeyTable(), "foreign key table");
                var refColumn = new SqlIdentifier(col.foreignKeyColumn(), "foreign key column");
                constraints.add("FOREIGN KEY (" + name + ") REFERENCES " + schema + "." + refTable + " (" + refColumn + ")");
            }
        }

        if (pkColumns.size() > 1) {
            var cols = pkColumns.stream().map(c -> new SqlIdentifier(c.name(), "column name").toString()).toList();
            constraints.add("PRIMARY KEY (" + String.join(", ", cols) + ")");
        }

        definitions.addAll(constraints);
        connections.adminDdl().execute("CREATE TABLE " + schema + "." + table + " (" + String.join(", ", definitions) + ")");
    }

    public void dropTable(String orgSlug, String projectSlug, String table) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");

        connections.adminDdl().execute("DROP TABLE IF EXISTS " + schema + "." + tbl + " CASCADE");
    }

    public void addColumn(String orgSlug, String projectSlug, String table, AddColumnRequest req) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");
        var col = new SqlIdentifier(req.name(), "column name");

        var definition = col + " " + ColumnTypes.toSql(req.type());
        if (req.defaultValue() != null && !req.defaultValue().isBlank())
            definition += " DEFAULT " + ColumnTypes.formatDefault(req.defaultValue(), req.type());

        connections.adminDdl().execute("ALTER TABLE " + schema + "." + tbl + " ADD COLUMN " + definition);
    }

    public void dropColumn(String orgSlug, String projectSlug, String table, String column) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");
        var col = new SqlIdentifier(column, "column name");

        connections.adminDdl().execute("ALTER TABLE " + schema + "." + tbl + " DROP COLUMN " + col);
    }

    /** Column names are identifiers and validated; every value is a JDBC parameter. */
    public void updateRow(String orgSlug, String projectSlug, String table, String pkValue, UpdateRowRequest req) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");
        var pk = new SqlIdentifier(req.pkColumn(), "primary key column");

        if (req.updates().isEmpty()) throw AppException.badRequest("No columns to update");

        var params = new LinkedHashMap<String, Object>();
        var assignments = new ArrayList<String>();
        var index = 0;

        for (var entry : req.updates().entrySet()) {
            var col = new SqlIdentifier(entry.getKey(), "column name");
            params.put("v" + index, SqlParams.value(entry.getValue()));
            assignments.add(col + " = :v" + index);
            index++;
        }

        params.put("pk", pkValue);

        var affected = connections.tenant()
                .sql("UPDATE " + schema + "." + tbl + " SET " + String.join(", ", assignments) + " WHERE " + pk + " = :pk")
                .params(params)
                .update();

        if (affected == 0) throw AppException.notFound("Row");
    }
}
