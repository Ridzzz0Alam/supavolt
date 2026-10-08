package dev.supavolt.api.features.realtime;

import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TriggerService {

    private final TenantConnections connections;
    private final ProjectResolver projects;

    public TriggerService(TenantConnections connections, ProjectResolver projects) {
        this.connections = connections;
        this.projects = projects;
    }

    public void enable(String orgSlug, String projectSlug, String table) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");
        var trigger = new SqlIdentifier(tbl.value() + "_realtime", "trigger name");
        var ddl = connections.adminDdl();

        // The project id is a UUID we generated, so the trigger argument cannot carry SQL.
        ddl.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + schema + "." + tbl);
        ddl.execute("CREATE TRIGGER " + trigger
                + " AFTER INSERT OR UPDATE OR DELETE ON " + schema + "." + tbl
                + " FOR EACH ROW EXECUTE FUNCTION \"" + SchemaNames.INTERNAL + "\".notify_change('" + project.getId() + "')");
    }

    public void disable(String orgSlug, String projectSlug, String table) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);
        var schema = SchemaNames.validateProjectSchema(project.getDbSchema());
        var tbl = new SqlIdentifier(table, "table name");
        var trigger = new SqlIdentifier(tbl.value() + "_realtime", "trigger name");

        connections.adminDdl().execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + schema + "." + tbl);
    }

    /** Which tables currently have the realtime trigger, so the dashboard can show state. */
    public List<String> enabledTables(String orgSlug, String projectSlug) {
        var project = projects.byDashboardRoute(orgSlug, projectSlug);

        return connections.admin().sql("""
                        SELECT c.relname
                        FROM pg_trigger t
                        JOIN pg_class c ON c.oid = t.tgrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = :schema AND NOT t.tgisinternal AND t.tgname LIKE '%_realtime'
                        """)
                .param("schema", project.getDbSchema())
                .query(String.class).list();
    }
}
