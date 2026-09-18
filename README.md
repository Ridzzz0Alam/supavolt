# Supavolt on .NET

A rebuild of Supavolt on **.NET 10 / ASP.NET Core**, with **EF Core** for the control plane,
**Dapper** for tenant SQL, **SignalR** for realtime, and **TypeScript + Next.js** for the
dashboard. The HTTP contract matches the original, so the existing `supavolt-js` SDK and Next.js
dashboard work against it with the two changes noted at the bottom.

> This code has not been compiled: it was written in an environment with no .NET SDK and no
> access to nuget.org. Expect to fix package versions and a handful of compiler errors on the
> first `dotnet build`. The structure, SQL and security model are the parts to trust.

## Layout

Two halves, one repo. Nothing is shared at build time — the contract between them is HTTP plus
`frontend/lib/types.ts`, which mirrors `Supavolt.Contracts` by hand.

```
backend/                              ASP.NET Core 10
  Supavolt.slnx
  Directory.Build.props               nullable, implicit usings, warnings-as-errors
  docker-compose.yml                  postgres 17 + minio
  db/init/01-roles.sql                the least-privilege tenant role
  src/Supavolt.Contracts/             DTOs and enums (the API's public shape)
  src/Supavolt.Api/
    Program.cs                        options, two auth schemes, four policies, endpoint mapping
    Infrastructure/
      Configuration/                  validated options records
      Entities.cs                     10 control-plane entities
      SupavoltDbContext.cs            snake_case, soft-delete filter, encrypted secrets
      Tenancy/SqlIdentifier.cs        the only way an identifier reaches SQL
      Tenancy/TenantDatabase.cs       connection factory, type map, default-value allow-list
      Tenancy/SchemaProvisioner.cs    CREATE SCHEMA, auth_users, shared trigger function
      Mail/EmailSender.cs             console sender in dev, REST sender in prod
    Common/ApiErrors.cs               AppException + SQLSTATE to HTTP mapping
    Features/                         Auth, Orgs, Members, Projects, TableEditor,
                                      SqlEditor, DataApi, Realtime, Storage, ProjectAuth

frontend/                             Next.js 16 + TypeScript
  package.json, tsconfig.json, next.config.ts, postcss.config.mjs
  middleware.ts                       token verification and refresh rotation
  app/
    layout.tsx, globals.css           fonts and design tokens
    login/, register/                 auth pages wired to server actions
    dashboard/                        post-sign-in redirect
  lib/
    api.ts                            typed fetch wrapper, ProblemDetails aware
    types.ts                          mirror of Supavolt.Contracts
    server-data.ts                    every server-component read, cookie forwarded
    actions.ts                        server actions: auth, orgs, projects, members, tables
    realtime.ts                       SignalR client replacing socket.io
    utils.ts                          cn(), formatBytes()
  README.md                           which dashboard surfaces still have to be built
```

The backend is complete across all ten features. The frontend is the shell plus the data layer:
the dashboard surfaces themselves (table editor, SQL editor, storage, realtime, members, API
docs, project-auth pages) still need building or porting — `frontend/README.md` lists each one
and the change it needs.

Each `Features/<Name>` file holds that slice's service and its endpoint group, so the mapping
from the NestJS module of the same name is one file to one file.

## Build order

If you are typing this out rather than reading the delivered files, write them in this order.
Every step is runnable before the next one starts.

**Backend** (`backend/`)

1. `Directory.Build.props`, then the `.slnx` and both `.csproj` files.
2. `Infrastructure/Configuration/SupavoltOptions.cs` and `appsettings*.json`.
3. `Program.cs` with nothing but Serilog, options binding, CORS and `/health`. **Verify a browser
   `fetch` with `credentials: 'include'` succeeds before going further** — CORS mistakes later
   look exactly like auth bugs.
4. `Infrastructure/Entities.cs`, `SupavoltDbContext.cs`, then
   `dotnet ef migrations add InitialSchema && dotnet ef database update`.
5. `Tenancy/SqlIdentifier.cs` and `Tenancy/TenantDatabase.cs`. Write these before any feature
   that builds SQL, so there is never a moment where concatenation is the easy path.
6. `Common/ApiErrors.cs`.
7. `Features/Auth/*` — `TokenService`, `ProjectKeyAuth`, `OrgRoleAuthorization`, `AuthFeature`.
   This is the foundation for every other slice; write integration tests here.
8. `Features/Orgs`, then `Features/Members` (proves the `OrgAdmin` policy end to end).
9. `Features/Projects` and `Tenancy/SchemaProvisioner.cs`.
10. `Features/TableEditor`, then `Features/SqlEditor`.
11. `Features/DataApi` — `FilterParser` first, then the service.
12. `Features/Realtime`, `Features/Storage`, `Features/ProjectAuth`.

**Frontend** (`frontend/`)

13. `package.json`, `tsconfig.json`, `next.config.ts`, `postcss.config.mjs`.
14. `app/layout.tsx` and `app/globals.css` — the design tokens, before any component.
15. `lib/api.ts`, then `lib/types.ts`. Everything else imports these two.
16. `lib/actions.ts` and `app/login`, `app/register`, `middleware.ts`. **Verify sign-in, refresh
    and sign-out end to end before building any dashboard screen** — every later page assumes
    the cookie round-trip works.
17. `lib/server-data.ts`, then the sidebar and `app/dashboard`.
18. Organizations, projects, members: the plain CRUD screens that shake out the data layer.
19. Table editor (split into four files, not one), SQL editor, storage, realtime, API docs,
    project-auth pages. See `frontend/README.md` for the per-surface notes.

## Running it

```bash
# 1. Dependencies
docker compose up -d

# 2. Secrets — never in appsettings
cd backend/src/Supavolt.Api
dotnet user-secrets set "Jwt:AccessSecret"      "$(openssl rand -hex 32)"
dotnet user-secrets set "Jwt:RefreshSecret"     "$(openssl rand -hex 32)"
dotnet user-secrets set "ProjectKeys:Secret"    "$(openssl rand -hex 32)"
dotnet user-secrets set "Invites:Secret"        "$(openssl rand -hex 32)"
dotnet user-secrets set "Storage:AccessKeyId"     "supavolt"
dotnet user-secrets set "Storage:SecretAccessKey" "supavolt123"
# Optional, for dashboard OAuth:
dotnet user-secrets set "Google:ClientId"     "..."
dotnet user-secrets set "Google:ClientSecret" "..."
dotnet user-secrets set "GitHub:ClientId"     "..."
dotnet user-secrets set "GitHub:ClientSecret" "..."

# 3. Schema (dotnet-ef is pinned in backend/dotnet-tools.json)
dotnet tool restore
dotnet ef database update

# 4. Run
dotnet run
# API on http://localhost:5000/api, OpenAPI UI at /scalar/v1, hub at /realtime
```

### Running the API in Docker instead

If the host cannot load freshly built assemblies (Windows Smart App Control blocks unsigned DLLs,
so `dotnet run` and `dotnet ef` fail with "An Application Control policy has blocked this file"),
or you would rather not install the SDK, run the API in an SDK container. It uses your
user-secrets store read-only and the same Postgres and MinIO:

```bash
cd backend
docker compose --profile api up -d                 # API on http://localhost:5000/api
docker compose exec api dotnet tool restore
docker compose exec api dotnet ef database update
docker compose --profile api restart api           # after code changes
docker compose exec -w /src api dotnet test src/Supavolt.Tests
```

Create the MinIO bucket once: `docker compose exec minio sh -c 'mc alias set local
http://localhost:9000 supavolt supavolt123 && mc mb -p local/supavolt-dev'`.

## Tests

```bash
cd backend && dotnet test
```

`src/Supavolt.Tests` has pure unit tests (`SqlIdentifier`, `FilterParser`,
`SqlStatementSplitter`, project keys) and integration tests that run the real API against a
throwaway Postgres 17 started by Testcontainers, so Docker must be running. The integration tests
cover the auth cookie flow and refresh-token reuse, org roles and the last-admin rule, key scoping
and rotation, the anon-key write ban, storage prefix checks, and tenant isolation in the SQL editor.

Frontend:

```bash
cd frontend
pnpm install
pnpm dev   # http://localhost:3001
```

`frontend/.env.local`:

```
NEXT_PUBLIC_API_URL=http://localhost:5000/api
API_URL=http://localhost:5000/api
JWT_ACCESS_SECRET=<the same value as Jwt:AccessSecret>
JWT_ISSUER=supavolt
JWT_AUDIENCE=supavolt-dashboard
```

## Database roles

The SQL editor is made safe by privileges, not by inspecting SQL text. The original blocked
`DROP SCHEMA` and `TRUNCATE` by checking whether the statement text started with them, which a
leading comment defeats. `SET LOCAL statement_timeout = '15s'` bounds what a slow query can hold.

- `supavolt_tenant` (from `backend/db/init/01-roles.sql`) runs only SQL the server builds: the data
  API and table-editor reads. It has data access to project schemas and no `CREATE` anywhere.
- Each project has its own login role, named after its schema, that the SQL editor connects as.
  It can reach its own schema and nothing else, so one project cannot read or drop another's
  tables. Its password is generated server-side and stored encrypted. See `DECISIONS.md`.

Three configured connection strings, plus the per-project credentials derived from the tenant one:

| Setting | Role | Used by |
| --- | --- | --- |
| `Database:ConnectionString` | admin | EF Core, DDL, introspection |
| `Database:TenantConnectionString` | `supavolt_tenant` | data API, table-editor reads |
| (per project) | `proj_xxxxxxxx` | SQL editor |
| `Database:DirectConnectionString` | admin, `Pooling=false` | the LISTEN connection only |

## Realtime

One Postgres channel (`supavolt_events`), one shared trigger function in `supavolt_internal`,
one listener connection per API instance, and SignalR groups (`project:{id}:table:{name}`) doing
the routing. Enabling realtime on a table is a single `CREATE TRIGGER` that passes the project id
as a trigger argument.

The original created a function per table and a channel per project and table, which costs one
connection per subscription and runs into Postgres's 63-byte channel name limit.

Running more than one instance: add `Microsoft.AspNetCore.SignalR.StackExchangeRedis` and
`AddStackExchangeRedisBackplane`. The listener then fires on every instance, so elect a single
listener with a Redis lock or accept duplicate fan-out.

## Frontend changes from the original

Only two things in the original `apps/web` need touching when you port a surface across:

1. `socket.io-client` becomes `@microsoft/signalr` — `frontend/lib/realtime.ts` is a drop-in class
   with the same `subscribe`/`unsubscribe` surface.
2. UploadThing's `genUploader` becomes the presigned-PUT flow: `POST .../upload-url`, `PUT` the
   file straight at S3/R2, then `POST .../objects` to record it.

Everything else — routes, server actions, cookie forwarding, components — works unchanged,
because the API keeps the same paths and camelCase JSON.

## Not built here

- **Row-level security.** The anon key can read every row of every table, exactly as in the
  original. `ProjectAuthService.ValidateEndUserTokenAsync` is the hook to build it on: give each
  key type a Postgres role, set `request.jwt.claims` per request, and write RLS policies.
- **A .NET client SDK.** The TypeScript one still works; a `Supavolt.Client` library is a few
  hundred lines on `HttpClient` and `HubConnectionBuilder`.
