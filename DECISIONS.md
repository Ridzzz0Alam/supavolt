# DECISIONS.md

Changes made while bringing the repo to a working state that alter the original design, or that
someone reading the code later would otherwise wonder about. Nothing in the `INSTRUCTIONS.md`
section 0 table was reversed: `supavolt_tenant` still exists and still runs the data API, values
are still Dapper parameters, identifiers still go through `SqlIdentifier`, secrets are still
write-only, the cookie names are unchanged and `TreatWarningsAsErrors` is on with zero warnings.

## 1. Per-project Postgres login roles for the SQL editor (security fix)

**Found:** every project schema was granted `USAGE, CREATE` to the one shared `supavolt_tenant`
role, and the SQL editor ran as that role. Any signed-in user could list every project schema
from `pg_namespace` and then read, write, drop or plant tables in any other organization's
project, including its `auth_users` password hashes. Confirmed against the running API.

**Decision:** each project gets its own `LOGIN` role, named after its schema (`proj_xxxxxxxx`),
with grants on its own schema only. The SQL editor connects as that role
(`ITenantConnectionFactory.OpenProjectAsync`). `supavolt_tenant` keeps data access for the data
API and table-editor reads, which run only server-built SQL, but loses `CREATE`.

**Why not `SET ROLE`:** a single login that is a member of every project role lets user SQL run
`SET ROLE proj_other`. Only a separate login keeps projects apart.

**Details:**
- The role password is 48 random hex characters, stored in `projects.db_role_password` through
  the same `IDataProtector` converter as the OAuth secrets. It is never returned by the API.
- DDL cannot take bind parameters, so `CREATE/ALTER ROLE` is built by Postgres's own
  `format('%I', '%L')` from Dapper parameters. Values stay parameters end to end.
- Projects from before this change get their role on first SQL editor use, and tables the shared
  role created are handed over to the project role, with data grants back to `supavolt_tenant`.
- A role may change its own password from inside the editor; the next connection fails with
  `28P01` and the API re-keys the role once instead of locking the project out.
- Each project connection pool is capped at 5 and roles at 10 connections.
- Still visible: schema names in `pg_catalog`. That is metadata, not data; hiding it needs a
  separate database per project.

Tests: `Sql_editor_cannot_touch_another_projects_schema` (six attacks),
`Tables_created_in_the_sql_editor_are_reachable_by_the_data_api`,
`Legacy_tables_owned_by_the_shared_role_are_handed_over`,
`Sql_editor_survives_the_role_changing_its_own_password`.

## 2. The SQL editor returns the Postgres error message

`AppExceptionHandler` hides raw Postgres messages, which is right for the data API because anon
keys are public. The SQL editor now catches `PostgresException` itself and returns `MessageText`
as the 400 title. The caller is a signed-in org member running their own SQL as their own
project's role, so the message reveals nothing they could not already query, and without it the
editor only ever said "Database error". The global handler is unchanged.

## 3. Invite tokens live on `invites.token_hash`

`InviteService` wrote invite tokens into `project_auth_tokens` with `ProjectId = default`, which
violates the foreign key, so every invite failed. Following the runbook's suggestion, the token
hash moved onto `Invite` (unique index), and acceptance looks the invite up by hash and rejects
accepted, revoked and expired ones. Migration `InviteTokenHash` deletes pre-existing invites with
no token, which could never have been accepted.

## 4. Dashboard OAuth providers are registered only when configured

`AddGoogle`/`AddGitHub` with an empty `ClientId` made every request return 500, because remote
handlers validate their options on each request. They are now registered only when their client id
is set. Without them, `/auth/google` and `/auth/github` have no scheme to challenge.

## 5. Other runtime fixes the smoke test and tests found

- `SqlIdentifier` used `$`, which in .NET also matches before a trailing newline, so `"users\n"`
  passed. Now `\z`, fixed inside `SqlIdentifier` as the conventions require.
- `SqlStatementSplitter` treated any `$…$` span as a dollar quote, so `select $1, $2; drop table x`
  counted as one statement. Tags now follow Postgres's grammar (`$$` or `$name$`).
- The realtime hub never checked the key version, so rotated anon keys could still subscribe. It
  now rejects stale keys on connect.
- Presigned S3 URLs were always `https`, which fails against plain-http MinIO. The protocol now
  follows `Storage:ServiceUrl`.
- SignalR serialised event types as numbers (`0`) while the TypeScript client expects `"insert"`.
- `DapperRow` was cast to `Dictionary<string, object?>` (runtime `InvalidCastException`); one
  `DapperRows.ToRow` helper replaces the five casts.
- Dapper row records are PascalCase over snake_case columns: `MatchNamesWithUnderscores` is on.
- `GET /orgs` sorted after projecting into a record, which EF cannot translate.
- Request logging now wraps the exception handler, so logs show the status the client received.

## 6. Configuration and packages

- `AWSSDK.S3` 4.0.9 → 4.0.103.3 and `Microsoft.AspNetCore.OpenApi` 10.0.12 (added; `AddOpenApi`
  is not in the shared framework): the older versions pulled in packages with known
  vulnerabilities, which fail restore under warnings-as-errors.
- `RateLimits:AuthPermitsPerMinute` (default 10, as before) is configurable so integration tests,
  where every client shares the address "unknown", can raise it.
- `docker-compose.yml` uses `quay.io/minio/minio`; `minio/minio` is no longer on Docker Hub.
- An `api` compose profile runs the API in an SDK container. On this machine Windows Smart App
  Control blocks unsigned locally built DLLs, and turning it off cannot be undone, so the API,
  migrations and tests run in Docker. See README.

## 7. Frontend

The original `apps/web` was not available, so the dashboard surfaces were written fresh against
`lib/server-data.ts` and `lib/actions.ts` rather than ported: organizations, projects (the
service-role key is shown once), members, table editor, SQL editor, storage (presigned upload),
realtime, project auth and API. Two loader fallbacks in `server-data.ts` redirected to the page
that had just failed, which looped forever; they now fall back to the projects list. `lib/api.ts`
accepts empty 201 bodies.

## Still open

- `INSTRUCTIONS.md` section 7 gaps are untouched: per-project OAuth callbacks, row-level security,
  a .NET SDK, and a Redis backplane.
- Registering a storage object does not check that the upload happened; a registered key with no
  object just 404s on download.
- Site URL can be set but not cleared through the API (blank means "keep").
