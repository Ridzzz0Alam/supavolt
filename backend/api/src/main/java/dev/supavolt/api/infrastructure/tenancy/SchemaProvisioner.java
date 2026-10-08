package dev.supavolt.api.infrastructure.tenancy;

import dev.supavolt.api.common.Tokens;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

@Component
public class SchemaProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SchemaProvisioner.class);

    private final TenantConnections connections;

    public SchemaProvisioner(TenantConnections connections) {
        this.connections = connections;
    }

    /**
     * Creates the project schema, grants the tenant role access to it, and creates auth_users
     * up front. Returns the project role's password for the caller to store.
     */
    public String provisionProject(String schema) {
        var id = SchemaNames.validateProjectSchema(schema);
        var tenantRole = new SqlIdentifier(connections.tenantRole(), "tenant role");
        var ddl = connections.adminDdl();

        // The shared tenant role runs only server-built SQL (the data API and table editor), so
        // it needs data access but never CREATE. User SQL runs as the project role instead.
        ddl.execute("CREATE SCHEMA IF NOT EXISTS " + id);
        ddl.execute("GRANT USAGE ON SCHEMA " + id + " TO " + tenantRole);
        ddl.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + id
                + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + tenantRole);
        ddl.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + id
                + " GRANT USAGE, SELECT ON SEQUENCES TO " + tenantRole);

        ddl.execute("""
                CREATE TABLE IF NOT EXISTS %s."auth_users" (
                  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                  email          text NOT NULL,
                  password_hash  text,
                  email_verified boolean NOT NULL DEFAULT false,
                  provider       text NOT NULL DEFAULT 'email',
                  created_at     timestamptz NOT NULL DEFAULT now()
                )
                """.formatted(id));

        // Case-insensitive uniqueness: the fix for Alice@x.com vs alice@x.com.
        ddl.execute("CREATE UNIQUE INDEX IF NOT EXISTS \"auth_users_email_lower_idx\" ON " + id
                + ".\"auth_users\" (lower(email))");

        log.info("Provisioned schema {}", schema);
        return ensureProjectRole(schema);
    }

    /**
     * One login role per project, named after its schema. A shared role for user SQL let any
     * project read and drop every other project's tables; SET ROLE does not fix that, because
     * user SQL could SET ROLE to any role the login is a member of. A separate login can't.
     * Creates or re-keys the role and its grants. Idempotent.
     */
    public String ensureProjectRole(String schema) {
        var id = SchemaNames.validateProjectSchema(schema);
        var role = id; // Roles and schemas live in separate namespaces; one name is easiest to audit.
        var tenantRole = new SqlIdentifier(connections.tenantRole(), "tenant role");
        var password = Tokens.randomHex(48);

        try (var conn = connections.adminDataSource().getConnection()) {
            conn.setAutoCommit(false);
            var single = new SingleConnectionDataSource(conn, true);
            var sql = JdbcClient.create(single);
            var ddl = new JdbcTemplate(single);

            try {
                // DDL cannot take bind parameters, so Postgres quotes the values itself: they stay
                // JDBC parameters all the way into format().
                var roleSql = sql.sql("""
                        SELECT format(
                          CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :role)
                               THEN 'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 10 PASSWORD %L'
                               ELSE 'CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 10 PASSWORD %L'
                          END, :role, :password)
                        """)
                        .param("role", role.value())
                        .param("password", password)
                        .query(String.class).single();
                ddl.execute(roleSql);

                var database = new SqlIdentifier(
                        sql.sql("SELECT current_database()").query(String.class).single(), "database");

                var grants = List.of(
                        "GRANT CONNECT ON DATABASE " + database + " TO " + role,
                        "GRANT USAGE, CREATE ON SCHEMA " + id + " TO " + role,
                        "GRANT ALL ON ALL TABLES IN SCHEMA " + id + " TO " + role,
                        "GRANT ALL ON ALL SEQUENCES IN SCHEMA " + id + " TO " + role,
                        "ALTER DEFAULT PRIVILEGES IN SCHEMA " + id + " GRANT ALL ON TABLES TO " + role,
                        "ALTER DEFAULT PRIVILEGES IN SCHEMA " + id + " GRANT ALL ON SEQUENCES TO " + role,
                        // Tables the project creates itself must stay reachable by the data API.
                        "ALTER DEFAULT PRIVILEGES FOR ROLE " + role + " IN SCHEMA " + id
                                + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + tenantRole,
                        "ALTER DEFAULT PRIVILEGES FOR ROLE " + role + " IN SCHEMA " + id
                                + " GRANT USAGE, SELECT ON SEQUENCES TO " + tenantRole,
                        // Projects created before per-project roles granted CREATE to the shared role.
                        "REVOKE CREATE ON SCHEMA " + id + " FROM " + tenantRole);
                for (var grant : grants) ddl.execute(grant);

                // Hand over anything the shared role created here under the old model. Names are
                // quoted by format(%I) because user SQL may have created names SqlIdentifier rejects.
                var handovers = sql.sql("""
                        SELECT format('ALTER %s %I.%I OWNER TO %I',
                                 CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                                                WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
                                 n.nspname, c.relname, :role)
                        FROM pg_class c
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = :schema
                          AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                          AND pg_get_userbyid(c.relowner) = :tenant
                        """)
                        .param("role", role.value())
                        .param("schema", id.value())
                        .param("tenant", tenantRole.value())
                        .query(String.class).list();
                for (var statement : handovers) ddl.execute(statement);

                // Ownership was the shared role's only access to the tables just handed over;
                // grant data access explicitly so the data API keeps working on them.
                ddl.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + id + " TO " + tenantRole);
                ddl.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA " + id + " TO " + tenantRole);

                conn.commit();
            } catch (RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not ensure the role for " + schema, e);
        }

        log.info("Ensured role for {}", schema);
        return password;
    }

    /**
     * One shared trigger function for the whole platform, created once at boot (after Flyway has
     * run). The original generated a function per table and a channel per project and table.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void ensureInternalObjects() {
        var ddl = connections.adminDdl();

        ddl.execute("CREATE SCHEMA IF NOT EXISTS \"" + SchemaNames.INTERNAL + "\"");
        ddl.execute("""
                CREATE OR REPLACE FUNCTION "%s".notify_change()
                RETURNS TRIGGER AS $$
                DECLARE
                  payload json;
                  new_row json;
                  old_row json;
                BEGIN
                  IF TG_OP = 'DELETE' THEN
                    old_row := row_to_json(OLD);
                    new_row := NULL;
                  ELSIF TG_OP = 'UPDATE' THEN
                    old_row := row_to_json(OLD);
                    new_row := row_to_json(NEW);
                  ELSE
                    old_row := NULL;
                    new_row := row_to_json(NEW);
                  END IF;

                  payload := json_build_object(
                    'type',      TG_OP,
                    'table',     TG_TABLE_NAME,
                    'projectId', TG_ARGV[0],
                    'record',    new_row,
                    'oldRecord', old_row,
                    'timestamp', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MSOF')
                  );

                  -- pg_notify caps the payload at 8000 bytes. Send identifiers only when the row is
                  -- too large and let the client refetch, rather than losing the event entirely.
                  IF octet_length(payload::text) > 7500 THEN
                    payload := json_build_object(
                      'type',      TG_OP,
                      'table',     TG_TABLE_NAME,
                      'projectId', TG_ARGV[0],
                      'truncated', true,
                      'timestamp', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MSOF')
                    );
                  END IF;

                  PERFORM pg_notify('%s', payload::text);
                  IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
                END;
                $$ LANGUAGE plpgsql;
                """.formatted(SchemaNames.INTERNAL, SchemaNames.REALTIME_CHANNEL));

        log.info("Internal realtime objects ensured");
    }
}
