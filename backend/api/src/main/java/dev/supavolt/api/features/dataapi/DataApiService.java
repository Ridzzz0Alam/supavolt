package dev.supavolt.api.features.dataapi;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Rows;
import dev.supavolt.api.common.SqlParams;
import dev.supavolt.api.features.auth.ProjectKey;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.features.tableeditor.TableEditorService;
import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.contracts.Contracts.FilterOperator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class DataApiService {

    private final TenantConnections connections;
    private final ProjectResolver projects;
    private final TableEditorService tables;

    /** Cached: the original looked the primary key up from information_schema on every write. */
    private final Cache<String, String> primaryKeys = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(10_000)
            .build();

    public DataApiService(TenantConnections connections, ProjectResolver projects, TableEditorService tables) {
        this.connections = connections;
        this.projects = projects;
        this.tables = tables;
    }

    private record Target(SqlIdentifier schema, SqlIdentifier table) {

        String qualified() {
            return schema + "." + table;
        }
    }

    public List<Map<String, Object>> select(ProjectKey key, String projectSlug, String table, Map<String, String[]> query) {
        var target = resolve(key, projectSlug, table);
        var parsed = FilterParser.parse(query);

        var columns = parsed.select().isEmpty()
                ? "*"
                : String.join(", ", parsed.select().stream().map(SqlIdentifier::toString).toList());

        var params = new LinkedHashMap<String, Object>();
        var where = buildWhere(parsed.filters(), params);
        params.put("limit", parsed.limit());
        params.put("offset", parsed.offset());

        var order = parsed.order() == null
                ? ""
                : "ORDER BY " + parsed.order().column() + (parsed.order().descending() ? " DESC" : " ASC");

        var sql = "SELECT " + columns + " FROM " + target.qualified() + "\n" + where + "\n" + order
                + "\nLIMIT :limit OFFSET :offset";

        return connections.tenant().sql(sql).params(params).query(Rows.MAPPER).list();
    }

    public Map<String, Object> insert(ProjectKey key, String projectSlug, String table, JsonNode body) {
        var target = resolve(key, projectSlug, table);
        var values = readObject(body);

        var params = new LinkedHashMap<String, Object>();
        var columns = new ArrayList<String>();
        var placeholders = new ArrayList<String>();
        var index = 0;

        for (var entry : values.entrySet()) {
            columns.add(new SqlIdentifier(entry.getKey(), "column name").toString());
            params.put("v" + index, entry.getValue());
            placeholders.add(":v" + index);
            index++;
        }

        var sql = "INSERT INTO " + target.qualified() + " (" + String.join(", ", columns) + ")"
                + " VALUES (" + String.join(", ", placeholders) + ") RETURNING *";

        return connections.tenant().sql(sql).params(params).query(Rows.MAPPER).single();
    }

    public Map<String, Object> update(ProjectKey key, String projectSlug, String table, String id, JsonNode body) {
        var target = resolve(key, projectSlug, table);
        var pk = new SqlIdentifier(primaryKey(target.schema().value(), table), "primary key column");
        var values = readObject(body);

        var params = new LinkedHashMap<String, Object>();
        var assignments = new ArrayList<String>();
        var index = 0;

        for (var entry : values.entrySet()) {
            var col = new SqlIdentifier(entry.getKey(), "column name");
            params.put("v" + index, entry.getValue());
            assignments.add(col + " = :v" + index);
            index++;
        }

        params.put("pk", id);

        return connections.tenant()
                .sql("UPDATE " + target.qualified() + " SET " + String.join(", ", assignments)
                        + " WHERE " + pk + " = :pk RETURNING *")
                .params(params)
                .query(Rows.MAPPER).optional()
                .orElseThrow(() -> AppException.notFound("Row"));
    }

    public Map<String, Object> delete(ProjectKey key, String projectSlug, String table, String id) {
        var target = resolve(key, projectSlug, table);
        var pk = new SqlIdentifier(primaryKey(target.schema().value(), table), "primary key column");

        return connections.tenant()
                .sql("DELETE FROM " + target.qualified() + " WHERE " + pk + " = :pk RETURNING *")
                .param("pk", id)
                .query(Rows.MAPPER).optional()
                .orElseThrow(() -> AppException.notFound("Row"));
    }

    private static String buildWhere(List<FilterParser.Filter> filters, Map<String, Object> params) {
        if (filters.isEmpty()) return "";

        var clauses = new ArrayList<String>(filters.size());

        for (var i = 0; i < filters.size(); i++) {
            var f = filters.get(i);

            if (f.operator() == FilterOperator.IS) {
                clauses.add(f.column() + " IS " + (f.isNull() ? "NULL" : "NOT NULL"));
                continue;
            }

            params.put("f" + i, f.value());
            clauses.add(f.column() + " " + FilterParser.toSqlOperator(f.operator()) + " :f" + i);
        }

        return "WHERE " + String.join(" AND ", clauses);
    }

    private Target resolve(ProjectKey key, String projectSlug, String table) {
        var project = projects.byKey(key, projectSlug);
        return new Target(SchemaNames.validateProjectSchema(project.getDbSchema()), new SqlIdentifier(table, "table name"));
    }

    private String primaryKey(String schema, String table) {
        return primaryKeys.get("pk:" + schema + ":" + table, k -> tables.primaryKeyColumn(schema, table));
    }

    private static Map<String, Object> readObject(JsonNode body) {
        if (body == null || !body.isObject())
            throw AppException.badRequest("Request body must be a JSON object");

        var result = new LinkedHashMap<String, Object>();
        for (var property : body.properties()) result.put(property.getKey(), SqlParams.value(property.getValue()));

        if (result.isEmpty()) throw AppException.badRequest("Request body cannot be empty");
        return result;
    }
}
