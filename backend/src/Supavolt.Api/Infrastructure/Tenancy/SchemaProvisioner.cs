using Dapper;
using Microsoft.Extensions.Options;
using Supavolt.Api.Infrastructure.Configuration;

namespace Supavolt.Api.Infrastructure.Tenancy;

public interface ISchemaProvisioner
{
    /// <summary>Creates the schema and its role. Returns the role password for the caller to store.</summary>
    Task<string> ProvisionProjectAsync(string schema, CancellationToken ct = default);

    /// <summary>Creates or re-keys the project's login role and its grants. Idempotent.</summary>
    Task<string> EnsureProjectRoleAsync(string schema, CancellationToken ct = default);

    Task EnsureInternalObjectsAsync(CancellationToken ct = default);
}

public sealed class SchemaProvisioner(
    ITenantConnectionFactory connections,
    IOptions<DatabaseOptions> dbOptions,
    ILogger<SchemaProvisioner> log) : ISchemaProvisioner
{
    /// <summary>
    /// Creates the project schema, grants the tenant role access to it, and creates auth_users
    /// up front. The original created auth_users lazily on every single auth call.
    /// </summary>
    public async Task<string> ProvisionProjectAsync(string schema, CancellationToken ct = default)
    {
        var id = SchemaNames.ValidateProjectSchema(schema);
        var tenantRole = new SqlIdentifier(TenantRoleName(), "tenant role");

        await using var conn = await connections.OpenAdminAsync(ct);

        // The shared tenant role runs only server-built SQL (the data API and table editor), so
        // it needs data access but never CREATE. User SQL runs as the project role instead.
        await conn.ExecuteAsync($"CREATE SCHEMA IF NOT EXISTS {id}");
        await conn.ExecuteAsync($"GRANT USAGE ON SCHEMA {id} TO {tenantRole}");
        await conn.ExecuteAsync(
            $"ALTER DEFAULT PRIVILEGES IN SCHEMA {id} GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {tenantRole}");
        await conn.ExecuteAsync(
            $"ALTER DEFAULT PRIVILEGES IN SCHEMA {id} GRANT USAGE, SELECT ON SEQUENCES TO {tenantRole}");

        await conn.ExecuteAsync($"""
            CREATE TABLE IF NOT EXISTS {id}."auth_users" (
              id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
              email          text NOT NULL,
              password_hash  text,
              email_verified boolean NOT NULL DEFAULT false,
              provider       text NOT NULL DEFAULT 'email',
              created_at     timestamptz NOT NULL DEFAULT now()
            )
            """);

        // Case-insensitive uniqueness: the fix for Alice@x.com vs alice@x.com.
        await conn.ExecuteAsync(
            $"""CREATE UNIQUE INDEX IF NOT EXISTS "auth_users_email_lower_idx" ON {id}."auth_users" (lower(email))""");

        log.LogInformation("Provisioned schema {Schema}", schema);

        return await EnsureProjectRoleAsync(schema, ct);
    }

    /// <summary>
    /// One login role per project, named after its schema. A shared role for user SQL let any
    /// project read and drop every other project's tables; SET ROLE does not fix that, because
    /// user SQL could SET ROLE to any role the login is a member of. A separate login can't.
    /// </summary>
    public async Task<string> EnsureProjectRoleAsync(string schema, CancellationToken ct = default)
    {
        var id = SchemaNames.ValidateProjectSchema(schema);
        var role = id; // Roles and schemas live in separate namespaces; one name is easiest to audit.
        var tenantRole = new SqlIdentifier(TenantRoleName(), "tenant role");
        var password = System.Security.Cryptography.RandomNumberGenerator.GetHexString(48, lowercase: true);

        await using var conn = await connections.OpenAdminAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);

        // DDL cannot take bind parameters, so Postgres quotes the values itself: they stay Dapper
        // parameters all the way into format().
        var roleSql = await conn.ExecuteScalarAsync<string>("""
            SELECT format(
              CASE WHEN EXISTS (SELECT 1 FROM pg_roles WHERE rolname = @role)
                   THEN 'ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 10 PASSWORD %L'
                   ELSE 'CREATE ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION CONNECTION LIMIT 10 PASSWORD %L'
              END, @role, @password)
            """, new { role = role.Value, password }, tx);
        await conn.ExecuteAsync(roleSql!, transaction: tx);

        var database = new SqlIdentifier(
            await conn.ExecuteScalarAsync<string>("SELECT current_database()", transaction: tx) ?? "", "database");

        string[] grants =
        [
            $"GRANT CONNECT ON DATABASE {database} TO {role}",
            $"GRANT USAGE, CREATE ON SCHEMA {id} TO {role}",
            $"GRANT ALL ON ALL TABLES IN SCHEMA {id} TO {role}",
            $"GRANT ALL ON ALL SEQUENCES IN SCHEMA {id} TO {role}",
            $"ALTER DEFAULT PRIVILEGES IN SCHEMA {id} GRANT ALL ON TABLES TO {role}",
            $"ALTER DEFAULT PRIVILEGES IN SCHEMA {id} GRANT ALL ON SEQUENCES TO {role}",
            // Tables the project creates itself must stay reachable by the data API.
            $"ALTER DEFAULT PRIVILEGES FOR ROLE {role} IN SCHEMA {id} GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {tenantRole}",
            $"ALTER DEFAULT PRIVILEGES FOR ROLE {role} IN SCHEMA {id} GRANT USAGE, SELECT ON SEQUENCES TO {tenantRole}",
            // Projects created before per-project roles granted CREATE to the shared role.
            $"REVOKE CREATE ON SCHEMA {id} FROM {tenantRole}",
        ];
        foreach (var grant in grants) await conn.ExecuteAsync(grant, transaction: tx);

        // Hand over anything the shared role created here under the old model. Names are quoted
        // by format(%I) because user SQL may have created names SqlIdentifier would reject.
        var handovers = await conn.QueryAsync<string>("""
            SELECT format('ALTER %s %I.%I OWNER TO %I',
                     CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                                    WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
                     n.nspname, c.relname, @role)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = @schema
              AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
              AND pg_get_userbyid(c.relowner) = @tenant
            """, new { role = role.Value, schema = id.Value, tenant = tenantRole.Value }, tx);
        foreach (var statement in handovers) await conn.ExecuteAsync(statement, transaction: tx);

        // Ownership was the shared role's only access to the tables just handed over; grant data
        // access explicitly so the data API keeps working on them.
        await conn.ExecuteAsync(
            $"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA {id} TO {tenantRole}", transaction: tx);
        await conn.ExecuteAsync(
            $"GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA {id} TO {tenantRole}", transaction: tx);

        await tx.CommitAsync(ct);
        log.LogInformation("Ensured role for {Schema}", schema);
        return password;
    }

    /// <summary>
    /// One shared trigger function for the whole platform, created once at boot. The original
    /// generated a function per table and a Postgres channel per project and table, which costs a
    /// connection per subscription and runs into the 63-byte channel name limit.
    /// </summary>
    public async Task EnsureInternalObjectsAsync(CancellationToken ct = default)
    {
        await using var conn = await connections.OpenAdminAsync(ct);

        await conn.ExecuteAsync($"CREATE SCHEMA IF NOT EXISTS \"{SchemaNames.Internal}\"");

        await conn.ExecuteAsync($"""
            CREATE OR REPLACE FUNCTION "{SchemaNames.Internal}".notify_change()
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

              PERFORM pg_notify('{SchemaNames.RealtimeChannel}', payload::text);
              IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
            END;
            $$ LANGUAGE plpgsql;
            """);

        log.LogInformation("Internal realtime objects ensured");
    }

    private string TenantRoleName()
    {
        var builder = new Npgsql.NpgsqlConnectionStringBuilder(dbOptions.Value.TenantConnectionString);
        return builder.Username ?? throw new InvalidOperationException("Tenant connection string has no username");
    }
}
