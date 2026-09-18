-- The least-privilege role used for user-authored SQL and the generated data API.
-- This is what makes the SQL editor safe without blocklisting keywords: the role simply
-- cannot see the control-plane tables or drop a schema it does not own.
CREATE ROLE supavolt_tenant WITH LOGIN PASSWORD 'supavolt_tenant' NOCREATEDB NOCREATEROLE NOSUPERUSER;

REVOKE ALL ON SCHEMA public FROM supavolt_tenant;
GRANT CONNECT ON DATABASE supavolt TO supavolt_tenant;
GRANT USAGE ON SCHEMA public TO supavolt_tenant;

-- Control-plane tables are owned by supavolt_admin and never granted to the tenant role.
ALTER DEFAULT PRIVILEGES FOR ROLE supavolt_admin IN SCHEMA public REVOKE ALL ON TABLES FROM supavolt_tenant;
