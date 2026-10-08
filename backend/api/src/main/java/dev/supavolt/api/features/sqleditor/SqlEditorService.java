package dev.supavolt.api.features.sqleditor;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Rows;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.infrastructure.persistence.Project;
import dev.supavolt.api.infrastructure.persistence.ProjectRepository;
import dev.supavolt.api.infrastructure.persistence.QueryHistoryItem;
import dev.supavolt.api.infrastructure.persistence.QueryHistoryRepository;
import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.SchemaProvisioner;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.contracts.Contracts.QueryHistoryDto;
import dev.supavolt.contracts.Contracts.QueryResultDto;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
public class SqlEditorService {

    private static final String INVALID_PASSWORD = "28P01";

    private final TenantConnections connections;
    private final SchemaProvisioner provisioner;
    private final ProjectResolver projects;
    private final ProjectRepository projectRepository;
    private final QueryHistoryRepository history;

    public SqlEditorService(
            TenantConnections connections,
            SchemaProvisioner provisioner,
            ProjectResolver projects,
            ProjectRepository projectRepository,
            QueryHistoryRepository history) {
        this.connections = connections;
        this.provisioner = provisioner;
        this.projects = projects;
        this.projectRepository = projectRepository;
        this.history = history;
    }

    /**
     * Opens the project's own role. Projects from before per-project roles get one on first use,
     * and a role whose password was changed from inside the editor (a role may alter its own
     * password) is re-keyed once rather than locking the project out.
     */
    private Connection openProjectConnection(Project project) throws SQLException {
        if (project.getDbRolePassword() == null) rekey(project);

        try {
            return connections.openProject(project.getDbSchema(), project.getDbRolePassword());
        } catch (PSQLException e) {
            if (!INVALID_PASSWORD.equals(e.getSQLState())) throw e;

            rekey(project);
            return connections.openProject(project.getDbSchema(), project.getDbRolePassword());
        }
    }

    private void rekey(Project project) {
        project.setDbRolePassword(provisioner.ensureProjectRole(project.getDbSchema()));
        projectRepository.save(project);
    }

    public QueryResultDto execute(String orgSlug, String projectSlug, String sql) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());

        var statements = SqlStatementSplitter.split(sql);

        if (statements.isEmpty()) throw AppException.badRequest("SQL query cannot be empty");
        if (statements.size() > 1)
            throw AppException.badRequest("Only one SQL statement per run. Remove statements after the first semicolon.");

        var start = System.nanoTime();
        var rows = new ArrayList<Map<String, Object>>();
        List<String> columns = List.of();
        long affected = 0;

        // Containment, not blocklisting: the project's role cannot touch the control plane or any
        // other project's schema, and the statement timeout bounds the damage a slow query does.
        try (var conn = openProjectConnection(project)) {
            conn.setAutoCommit(false);

            try (var setup = conn.createStatement()) {
                setup.execute("SET LOCAL search_path TO " + schema + ", public");
                setup.execute("SET LOCAL statement_timeout = '15s'");
            }

            try (var statement = conn.createStatement()) {
                statement.setEscapeProcessing(false);

                if (statement.execute(statements.get(0))) {
                    try (var rs = statement.getResultSet()) {
                        columns = Rows.columns(rs);
                        while (rs.next()) rows.add(Rows.read(rs, columns));
                    }
                } else {
                    affected = Math.max(statement.getLargeUpdateCount(), 0);
                }

                conn.commit();
            } catch (PSQLException e) {
                conn.rollback();
                // Unlike the data API, the caller wrote this SQL and runs it as their own
                // project's role, so the database's own message is the useful answer and reveals
                // nothing new.
                var server = e.getServerErrorMessage();
                throw AppException.badRequest(server != null ? server.getMessage() : e.getMessage());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQL editor connection failed", e);
        }

        var elapsedMs = (System.nanoTime() - start) / 1_000_000;
        var rowCount = rows.isEmpty() ? affected : rows.size();

        var item = new QueryHistoryItem();
        item.setProjectId(project.getId());
        item.setSql(sql);
        item.setExecutionTimeMs((int) elapsedMs);
        item.setRowCount(rowCount);
        history.save(item);

        return new QueryResultDto(rows, columns, rowCount, elapsedMs, deriveCommand(statements.get(0)));
    }

    public List<QueryHistoryDto> history(String orgSlug, String projectSlug) {
        return history.history(orgSlug, projectSlug, Limit.of(50)).stream()
                .map(h -> new QueryHistoryDto(h.getId(), h.getSql(), h.getExecutionTimeMs(), h.getRowCount(), h.getCreatedAt()))
                .toList();
    }

    private static String deriveCommand(String statement) {
        return statement.stripLeading().split("[ \\n\\t]", 2)[0].toUpperCase(Locale.ROOT);
    }
}
