# Supavolt

**A self-hostable backend-as-a-service**: create a project and you get a Postgres schema, a REST
API over its tables, file storage, realtime change events and end-user authentication, all
managed from a web dashboard.

It is built with **.NET 10 / ASP.NET Core** (EF Core, Dapper, SignalR) and a **Next.js 16 +
TypeScript** dashboard, on **PostgreSQL 17** and **S3-compatible storage** (MinIO locally).

- [What you get](#what-you-get)
- [How it fits together](#how-it-fits-together)
- [Run it locally](#run-it-locally)
- [How the main flows work](#how-the-main-flows-work)
- [Security model](#security-model)
- [Repository layout](#repository-layout)
- [API reference](#api-reference)
- [Tests](#tests)
- [Troubleshooting](#troubleshooting)
- [Not built yet](#not-built-yet)

---

## What you get

| Feature | What it does |
| --- | --- |
| **Organizations & members** | Sign up, create orgs, invite teammates as `admin` or `developer`. |
| **Projects** | Each project gets its own Postgres schema, an **anon key** (public, read-only) and a **service-role key** (secret, full access, shown once). |
| **Table editor** | Create tables and browse rows from the dashboard. |
| **SQL editor** | Run SQL against your project, with history. Runs under a database role that can only see that project. |
| **REST data API** | `GET/POST/PATCH/DELETE /api/projects/{project}/rest/{table}` with filters like `?price=lt.100&order=id.desc`. |
| **Storage** | Buckets and files. Uploads go straight from the browser to S3 via presigned URLs. |
| **Realtime** | Turn on a table and every insert, update and delete is pushed to subscribed clients over WebSockets. |
| **Project auth** | Sign-up and sign-in for *your app's* users, with per-project JWTs. |

---

## How it fits together

```mermaid
flowchart LR
    subgraph People
        dev["Developer<br/>(browser)"]
        app["Your app<br/>(uses anon / service key)"]
    end

    subgraph Dashboard["Next.js dashboard :3001"]
        mw["middleware.ts<br/>verifies and refreshes the session"]
        pages["Server components<br/>and server actions"]
    end

    subgraph API["ASP.NET Core API :5000"]
        auth["Two auth schemes<br/>Dashboard cookie / ProjectKey header"]
        features["Features<br/>Auth · Orgs · Members · Projects<br/>TableEditor · SqlEditor · DataApi<br/>Storage · Realtime · ProjectAuth"]
        hub["SignalR hub<br/>/realtime"]
        listener["Notification listener<br/>(one LISTEN connection)"]
    end

    subgraph Data
        pg[("PostgreSQL 17<br/>control plane + one schema per project")]
        s3[("MinIO / S3<br/>file bytes")]
    end

    dev --> mw --> pages -->|"HTTP + cookie"| auth
    app -->|"HTTP + Bearer key"| auth
    app <-->|"WebSocket"| hub
    auth --> features
    features --> pg
    features -->|"presign URLs"| s3
    dev -.->|"PUT file bytes directly"| s3
    pg -.->|"pg_notify"| listener --> hub
```

The two halves share no code at build time. The contract between them is HTTP plus
`frontend/lib/types.ts`, which mirrors `backend/src/Supavolt.Contracts/Contracts.cs` by hand.

### What lives where in Postgres

```mermaid
flowchart TB
    subgraph db["Postgres database: supavolt"]
        subgraph public["schema public (control plane, admin only)"]
            users[users] --- rt[refresh_tokens]
            orgs[organizations] --- members[org_members]
            orgs --- invites[invites]
            orgs --- projects[projects]
            projects --- buckets[storage_buckets] --- objects[storage_objects]
            projects --- history[query_history]
        end
        subgraph p1["schema proj_1a2b3c4d (project A)"]
            a1[auth_users]
            a2["your tables…"]
        end
        subgraph p2["schema proj_9f8e7d6c (project B)"]
            b1[auth_users]
            b2["your tables…"]
        end
        internal["schema supavolt_internal<br/>shared realtime trigger function"]
    end
```

<details>
<summary>Control-plane entity diagram</summary>

```mermaid
erDiagram
    USER ||--o{ REFRESH_TOKEN : has
    USER ||--o{ ORG_MEMBER : "belongs via"
    ORGANIZATION ||--o{ ORG_MEMBER : has
    ORGANIZATION ||--o{ INVITE : sends
    ORGANIZATION ||--o{ PROJECT : owns
    PROJECT ||--o{ STORAGE_BUCKET : has
    STORAGE_BUCKET ||--o{ STORAGE_OBJECT : contains
    PROJECT ||--o{ QUERY_HISTORY : records
    PROJECT ||--o{ PROJECT_AUTH_TOKEN : issues

    USER {
        uuid id
        string email
        string password_hash
    }
    REFRESH_TOKEN {
        uuid id
        string token_hash
        timestamptz revoked_at
    }
    ORGANIZATION {
        uuid id
        string name
        string slug
    }
    ORG_MEMBER {
        uuid id
        string role
        timestamptz removed_at
    }
    INVITE {
        uuid id
        string email
        string token_hash
        timestamptz expires_at
    }
    PROJECT {
        uuid id
        string slug
        string db_schema
        int key_version
        string service_role_key_hash
    }
    STORAGE_BUCKET {
        uuid id
        string name
        string access
    }
    STORAGE_OBJECT {
        uuid id
        string object_key
        long size
    }
    QUERY_HISTORY {
        uuid id
        string sql
        int execution_time_ms
    }
    PROJECT_AUTH_TOKEN {
        uuid id
        string purpose
        string token_hash
    }
```

</details>

---

## Run it locally

You need three things running: **Postgres + MinIO** (in Docker), the **API**, and the
**dashboard**. There are two ways to run the API. Pick one:

```mermaid
flowchart TD
    start([Start]) --> prereq["Install prerequisites<br/>Docker, .NET 10 SDK, Node 20+"]
    prereq --> clone["Clone the repo"]
    clone --> infra["Step 1: start Postgres + MinIO<br/>docker compose up -d"]
    infra --> secrets["Step 2: set API secrets<br/>dotnet user-secrets"]
    secrets --> choice{"Can your machine run<br/>locally built .NET DLLs?"}
    choice -->|"Yes: macOS, Linux,<br/>most Windows"| native["Option A: run natively<br/>dotnet ef database update<br/>dotnet run"]
    choice -->|"No: Windows with<br/>Smart App Control on"| docker["Option B: run the API in Docker<br/>docker compose --profile api up -d"]
    native --> bucket["Step 4: create the storage bucket"]
    docker --> bucket
    bucket --> web["Step 5: start the dashboard<br/>pnpm install && pnpm dev"]
    web --> done(["Open http://localhost:3001"])
```

### Prerequisites

| Tool | Version | Check |
| --- | --- | --- |
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | any recent | `docker --version` |
| [.NET SDK](https://dotnet.microsoft.com/download/dotnet/10.0) | 10.0.x | `dotnet --version` |
| [Node.js](https://nodejs.org/) | 20 or newer | `node -v` |
| pnpm (optional, `npx pnpm` works too) | any | `pnpm -v` |
| Git | any | `git --version` |

The commands below are for a **bash-style shell**: macOS/Linux Terminal, or **Git Bash** on
Windows. Git Bash includes `openssl`, which is used to generate secrets.

### Step 1: Clone and start Postgres + MinIO

```bash
git clone https://github.com/Ridzzz0Alam/supavolt.git
cd supavolt/backend
docker compose up -d
```

This starts:

| Container | Port | Login |
| --- | --- | --- |
| `postgres` | 5432 | `supavolt_admin` / `supavolt`, database `supavolt` |
| `minio` | 9000 (S3), 9001 (web console) | `supavolt` / `supavolt123` |

On first start, Postgres runs `db/init/01-roles.sql`, which creates the least-privilege
`supavolt_tenant` role. Check that it exists:

```bash
docker compose exec postgres psql -U supavolt_admin -d supavolt -c "\du"
```

### Step 2: Set the API secrets

Secrets never go in `appsettings.json`. They live in .NET's per-user secret store, outside the
repo:

```bash
cd src/Supavolt.Api
dotnet user-secrets set "Jwt:AccessSecret"        "$(openssl rand -hex 32)"
dotnet user-secrets set "Jwt:RefreshSecret"       "$(openssl rand -hex 32)"
dotnet user-secrets set "ProjectKeys:Secret"      "$(openssl rand -hex 32)"
dotnet user-secrets set "Invites:Secret"          "$(openssl rand -hex 32)"
dotnet user-secrets set "Storage:AccessKeyId"     "supavolt"
dotnet user-secrets set "Storage:SecretAccessKey" "supavolt123"
```

<details>
<summary>Using PowerShell 7 instead of bash?</summary>

```powershell
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower() }
cd src/Supavolt.Api
dotnet user-secrets set "Jwt:AccessSecret"   (New-Secret)
dotnet user-secrets set "Jwt:RefreshSecret"  (New-Secret)
dotnet user-secrets set "ProjectKeys:Secret" (New-Secret)
dotnet user-secrets set "Invites:Secret"     (New-Secret)
dotnet user-secrets set "Storage:AccessKeyId"     "supavolt"
dotnet user-secrets set "Storage:SecretAccessKey" "supavolt123"
```

</details>

Optional: to enable "Continue with Google / GitHub" on the dashboard login page, also set
`Google:ClientId`, `Google:ClientSecret`, `GitHub:ClientId` and `GitHub:ClientSecret`. If they
are not set, those buttons do nothing and everything else works.

### Step 3: Create the database tables and run the API

#### Option A: run natively (macOS, Linux, most Windows machines)

```bash
# still in backend/src/Supavolt.Api
dotnet tool restore            # installs dotnet-ef, pinned in backend/dotnet-tools.json
dotnet ef database update      # creates the control-plane tables
dotnet run --no-launch-profile --urls http://localhost:5000
```

#### Option B: run the API in Docker

Use this if `dotnet run` fails with **"An Application Control policy has blocked this file"**.
Windows Smart App Control blocks DLLs you have just compiled, and turning it off cannot be undone,
so running the API in a Linux container avoids it. It reads the same user-secrets from Step 2.

```bash
cd backend
docker compose --profile api up -d                  # builds and starts the API (first run takes a minute)
docker compose exec api dotnet tool restore
docker compose exec api dotnet ef database update
docker compose --profile api restart api            # restart now that the tables exist
```

> **macOS / Linux with Option B:** the container looks for secrets under `%APPDATA%`, which only
> exists on Windows. Create `backend/.env` containing
> `SUPAVOLT_SECRETS_DIR=${HOME}/.microsoft/usersecrets/supavolt-api` first.

**Either way, check it's up:**

```bash
curl http://localhost:5000/api/health
# {"status":"ok","timestamp":"..."}
```

In development there is also an interactive API reference at **http://localhost:5000/scalar/v1**.

### Step 4: Create the storage bucket

```bash
cd backend
docker compose exec minio sh -c 'mc alias set local http://localhost:9000 supavolt supavolt123 && mc mb -p local/supavolt-dev'
```

Or open the MinIO console at http://localhost:9001, sign in with `supavolt` / `supavolt123` and
create a bucket named `supavolt-dev`.

### Step 5: Start the dashboard

```bash
cd frontend
cp .env.example .env.local
```

Open `frontend/.env.local` and set `JWT_ACCESS_SECRET` to **the same value** as the API's
`Jwt:AccessSecret`. The dashboard verifies session tokens itself, so the two must match. To see the
value:

```bash
cd backend/src/Supavolt.Api && dotnet user-secrets list
```

The finished `frontend/.env.local` looks like this:

```ini
NEXT_PUBLIC_API_URL=http://localhost:5000/api
API_URL=http://localhost:5000/api
JWT_ACCESS_SECRET=<same as Jwt:AccessSecret>
JWT_ISSUER=supavolt
JWT_AUDIENCE=supavolt-dashboard
```

Then install and run:

```bash
pnpm install      # or: npx pnpm install
pnpm dev          # or: npx next dev --port 3001
```

Open **http://localhost:3001**, register an account, and create your first project.

### Try it out

1. **Projects**: create one and copy the **service-role key**. It is shown only once.
2. **Table editor**: create a `todos` table with a `title` text column.
3. **SQL editor**: `insert into todos (title) values ('hello');`
4. **REST API**: read it back with the anon key:
   ```bash
   curl "http://localhost:5000/api/projects/<project-slug>/rest/todos" \
     -H "Authorization: Bearer <anon key>"
   ```
5. **Realtime**: tick `todos`, then insert another row from the SQL editor in a second tab and
   watch the event arrive.
6. **Storage**: create a bucket and upload a file.

### Stopping and starting again

```bash
# stop (keeps all data)
cd backend && docker compose --profile api stop       # and Ctrl+C the dashboard / dotnet run

# start again later
cd backend && docker compose up -d                    # Option B: docker compose --profile api up -d
cd backend/src/Supavolt.Api && dotnet run --no-launch-profile --urls http://localhost:5000   # Option A only
cd frontend && pnpm dev

# wipe everything and start fresh (deletes all data)
cd backend && docker compose --profile api down -v
```

### Ports at a glance

| Port | Service |
| --- | --- |
| 3001 | Dashboard (Next.js) |
| 5000 | API: `/api/...`, realtime hub at `/realtime`, API docs at `/scalar/v1` |
| 5432 | PostgreSQL |
| 9000 / 9001 | MinIO S3 API / MinIO web console |

---

## How the main flows work

### Signing in to the dashboard

The dashboard never stores tokens in JavaScript. The API sets two **HttpOnly cookies**, and the
Next.js middleware checks them on every page load.

```mermaid
sequenceDiagram
    autonumber
    actor U as Developer
    participant N as Next.js (server action + middleware)
    participant A as API /api/auth
    participant DB as Postgres

    U->>N: submit email + password
    N->>A: POST /auth/login
    A->>DB: verify password hash, store hashed refresh token
    A-->>N: Set-Cookie access_token (15 min) + refresh_token (7 days)
    N-->>U: cookies copied onto the dashboard origin, redirect to /dashboard

    Note over U,N: Later, after the access token expires
    U->>N: open any /organizations page
    N->>N: middleware: access_token invalid or expired
    N->>A: POST /auth/refresh with refresh_token
    A->>DB: revoke old refresh token, issue a new pair
    A-->>N: new cookies
    N-->>U: page renders, user never noticed

    Note over A,DB: Presenting an already-used refresh token counts as theft:<br/>every live token for that user is revoked.
```

### Calling the REST data API with a project key

```mermaid
sequenceDiagram
    autonumber
    participant C as Your app
    participant H as ProjectKey auth handler
    participant R as ProjectResolver
    participant F as FilterParser
    participant DB as Postgres (as supavolt_tenant)

    C->>H: GET /api/projects/demo-1a2b3c/rest/todos?done=eq.false<br/>Authorization: Bearer anon-key
    H->>H: check the JWT signature (project_id, role, key_version)
    H->>R: project from the key
    R->>R: key's project matches the URL? (else 403)<br/>key_version is current? (else 401: rotated)
    R->>F: parse the query string
    F->>F: unknown operator or missing dot: 400<br/>column names go through SqlIdentifier
    F->>DB: SELECT … FROM "proj_…"."todos" WHERE "done" = @p0
    DB-->>C: 200 [ rows as JSON ]
    Note over C,H: POST/PATCH/DELETE with the anon key: 403.<br/>Writes need the service-role key.
```

Filter syntax: `?column=op.value` with `eq, neq, gt, gte, lt, lte, like, ilike, is`, plus
`select=a,b`, `order=col.desc`, `limit` (max 1000) and `offset`.

### Creating a project

```mermaid
sequenceDiagram
    autonumber
    actor U as Developer
    participant A as API
    participant DB as Postgres (admin)

    U->>A: POST /orgs/{org}/projects {name}
    A->>DB: insert project row, generate schema name proj_xxxxxxxx
    A->>A: sign anon key + service-role key (key_version 1)<br/>store only a hash of the service-role key
    A->>DB: CREATE SCHEMA, auth_users table, grants
    A->>DB: CREATE ROLE proj_xxxxxxxx LOGIN<br/>(password stored encrypted)
    A-->>U: project + both keys (the service-role key is never shown again)
```

### Uploading a file (presigned URL)

File bytes never pass through the API, so a large upload costs the server nothing.

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant A as API
    participant S as MinIO / S3

    B->>A: POST …/buckets/{id}/upload-url {fileName, contentType, size}
    A->>A: build key {projectId}/{bucketId}/{uuid}/{fileName}
    A-->>B: presigned PUT URL (valid 5 minutes)
    B->>S: PUT file bytes directly
    S-->>B: 200
    B->>A: POST …/buckets/{id}/objects {objectKey, …}
    A->>A: object key must start with this project's prefix (else 403)
    A-->>B: stored object (public URL, or signed URL on request)
```

### Realtime change events

```mermaid
sequenceDiagram
    autonumber
    participant W as Writer (SQL editor / data API)
    participant PG as Postgres
    participant L as NotificationListener (one per API instance)
    participant H as SignalR hub /realtime
    participant C as Subscribed client

    C->>H: connect with anon key, then Subscribe("todos")
    H->>H: key version current? (stale keys are disconnected)
    H->>H: join group project:{id}:table:todos
    W->>PG: INSERT INTO todos …
    PG->>PG: trigger calls supavolt_internal.notify_change()
    PG-->>L: pg_notify('supavolt_events', {type, table, record})
    L->>H: send to group project:{id}:table:todos
    H-->>C: event { type: "insert", table: "todos", record: {…} }
```

One Postgres channel, one trigger function and one listening connection serve every project and
table. Running several API instances needs a Redis backplane (see [Not built yet](#not-built-yet)).

---

## Security model

### Who can call what

```mermaid
flowchart LR
    subgraph Callers
        d["Dashboard user<br/>access_token cookie"]
        k["App with project key<br/>Authorization: Bearer"]
    end

    subgraph Schemes["Authentication schemes"]
        s1[Dashboard]
        s2[ProjectKey]
    end

    subgraph Policies["Authorization policies"]
        p1["OrgMember<br/>any role in the org"]
        p2["OrgAdmin<br/>admin in the org"]
        p3["ProjectKeyAny<br/>anon or service role"]
        p4["ProjectServiceRole<br/>service role only"]
    end

    d --> s1 --> p1 & p2
    k --> s2 --> p3 & p4

    p1 --> e1["view projects, tables, members,<br/>run SQL, upload and delete files"]
    p2 --> e2["create projects, rotate keys,<br/>create tables and buckets,<br/>turn realtime on/off, manage members"]
    p3 --> e3["REST reads, realtime subscribe,<br/>storage reads"]
    p4 --> e4["REST writes, storage writes"]
```

A project key can never act on dashboard routes, and a dashboard session can never call the data
API. They are separate schemes on purpose.

### Database roles: why one project can't see another

User-written SQL is contained by **Postgres privileges**, not by scanning the SQL text for
dangerous words, because that is easy to get around.

```mermaid
flowchart TB
    admin["supavolt_admin<br/>EF Core, DDL, migrations"]
    tenant["supavolt_tenant<br/>data API + table-editor reads<br/>(server-built SQL only)"]
    roleA["proj_1a2b3c4d<br/>SQL editor for project A"]
    roleB["proj_9f8e7d6c<br/>SQL editor for project B"]

    cp[("public schema<br/>control plane")]
    sa[("schema proj_1a2b3c4d")]
    sb[("schema proj_9f8e7d6c")]

    admin -->|full| cp & sa & sb
    tenant -->|"read/write rows, no CREATE"| sa & sb
    tenant -.-x|no access| cp
    roleA -->|"full, own schema only"| sa
    roleA -.-x|no access| sb & cp
    roleB -->|"full, own schema only"| sb
    roleB -.-x|no access| sa & cp
```

| Connection | Postgres role | Used by |
| --- | --- | --- |
| `Database:ConnectionString` | `supavolt_admin` | EF Core, DDL, introspection |
| `Database:TenantConnectionString` | `supavolt_tenant` | data API, table-editor reads |
| per project (derived) | `proj_xxxxxxxx` | the SQL editor for that project |
| `Database:DirectConnectionString` | `supavolt_admin`, not pooled | the single realtime LISTEN connection |

Every SQL editor run is also wrapped in `SET LOCAL statement_timeout = '15s'`.

### Other rules the code follows

- **Values are always Dapper parameters. Identifiers (table, column, schema names) always go
  through `SqlIdentifier`**, which allows only `^[a-zA-Z_][a-zA-Z0-9_]{0,62}$`.
- **Secrets are write-only.** Service-role keys are stored as hashes. OAuth client secrets and
  project role passwords are encrypted with ASP.NET Data Protection, and the API reports only
  `googleConfigured: true`, never the secret itself.
- **Keys can be rotated.** Every key carries a `key_version`. Rotating bumps the version, so all
  older keys, including open realtime connections, stop working.
- **Errors don't leak internals.** Postgres errors become RFC 9457 ProblemDetails without raw
  database messages. The one exception is the SQL editor, which shows the real message because
  its user wrote the SQL.

See [`DECISIONS.md`](DECISIONS.md) for the reasoning behind these choices and what changed while
the codebase was brought up.

---

## Repository layout

```
supavolt/
├── README.md                     you are here
├── DECISIONS.md                  design decisions and the fixes behind them
├── INSTRUCTIONS.md               the original runbook for bringing the code up
├── backend/
│   ├── docker-compose.yml        postgres, minio, and the optional `api` container
│   ├── db/init/01-roles.sql      creates the supavolt_tenant role
│   ├── dotnet-tools.json         pins dotnet-ef
│   ├── Directory.Build.props     net10.0, nullable, warnings as errors
│   └── src/
│       ├── Supavolt.Contracts/   request/response DTOs and enums (the API's public shape)
│       ├── Supavolt.Api/
│       │   ├── Program.cs        options, auth schemes, policies, middleware, endpoints
│       │   ├── Common/           error mapping, Dapper row helper
│       │   ├── Infrastructure/   entities, DbContext, options, mail, tenancy (SqlIdentifier,
│       │   │                     connection factory, schema and role provisioning)
│       │   ├── Features/         one file per feature: service + endpoints
│       │   │   Auth · Orgs · Members · Projects · TableEditor · SqlEditor
│       │   │   DataApi · Realtime · Storage · ProjectAuth
│       │   └── Migrations/       EF Core migrations
│       └── Supavolt.Tests/       unit tests + integration tests (Testcontainers)
└── frontend/
    ├── middleware.ts             session check and refresh on every page load
    ├── app/                      routes: login, register, organizations, projects,
    │                             database, sql, storage, realtime, auth, api, members
    ├── features/                 client components per dashboard area
    ├── components/               shared UI primitives
    └── lib/
        ├── api.ts                typed fetch wrapper
        ├── types.ts              hand-kept mirror of Supavolt.Contracts
        ├── server-data.ts        all server-side reads, cookie forwarded
        ├── actions.ts            all server actions (writes)
        └── realtime.ts           SignalR client
```

---

## API reference

All routes are under `http://localhost:5000/api`. Full interactive docs: `/scalar/v1` (development).

| Area | Routes | Auth |
| --- | --- | --- |
| Dashboard auth | `POST /auth/register` · `/auth/login` · `/auth/refresh` · `/auth/logout` · `GET /auth/me` · `GET /auth/invite/accept` | cookies |
| Orgs | `GET/POST /orgs` (any signed-in user) · `GET /orgs/{org}` | OrgMember |
| Members | `GET /orgs/{org}/members` · `POST …/invite` · `PATCH …/{id}/role` · `DELETE …/{id}` | read: OrgMember, changes: OrgAdmin |
| Projects | `GET /orgs/{org}/projects` · `GET …/{project}` · `POST /orgs/{org}/projects` · `POST …/{project}/keys/rotate` | read: OrgMember, create/rotate: OrgAdmin |
| Tables | `…/projects/{project}/tables` (list, create, drop, columns, rows) | read: OrgMember, schema changes: OrgAdmin |
| SQL | `POST …/projects/{project}/sql` · `GET …/sql/history` | OrgMember |
| Realtime | `GET …/realtime` · `POST …/realtime/{table}/enable` · `DELETE …/disable` | read: OrgMember, toggle: OrgAdmin |
| Storage | `…/projects/{project}/storage/…` buckets, objects, upload URLs, signed URLs | files: OrgMember, buckets: OrgAdmin |
| **Data API** | `GET /projects/{project}/rest/{table}` · `POST` · `PATCH/DELETE …/{table}/{id}` | read: any project key, write: service role |
| **App storage** | `/projects/{project}/storage/buckets/{name}/…` | read: any project key, write: service role |
| **App auth** | `POST /projects/{project}/auth/signup` · `/signin` · `/magic-link` · `/exchange` | public, rate-limited |
| Health | `GET /health` | none |
| Realtime hub | `ws://localhost:5000/realtime?access_token=<key>` | project key |

---

## Tests

```bash
cd backend
dotnet test                                                   # Option A
docker compose exec -w /src api dotnet test src/Supavolt.Tests   # Option B
```

```mermaid
flowchart LR
    subgraph Unit["Unit tests (no database)"]
        u1[SqlIdentifier]
        u2[FilterParser]
        u3[SqlStatementSplitter]
        u4[Project keys and versions]
    end
    subgraph Integration["Integration tests (real API + Postgres 17 in Docker)"]
        i1[Sign-in, refresh rotation, token reuse]
        i2[Org roles, invites, last-admin rule]
        i3[Key scoping, rotation, anon write ban]
        i4[Storage prefix checks]
        i5[SQL editor isolation between projects]
    end
```

Integration tests start their own throwaway Postgres with
[Testcontainers](https://dotnet.testcontainers.org/), so **Docker must be running**. They never
touch your development database.

---

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `dotnet run` / `dotnet ef`: *"An Application Control policy has blocked this file"* | Windows Smart App Control blocks freshly compiled DLLs. Use [Option B](#option-b-run-the-api-in-docker). |
| Dashboard keeps sending you back to `/login` | `JWT_ACCESS_SECRET` in `frontend/.env.local` doesn't match the API's `Jwt:AccessSecret`. Restart `pnpm dev` after changing it. |
| API won't start: *"… is required"* / options validation error | A secret from Step 2 is missing. Run `dotnet user-secrets list` in `backend/src/Supavolt.Api`. |
| Every API call returns 500 right after first start | Migrations haven't run. Do Step 3's `dotnet ef database update` (and restart the API in Option B). |
| File upload fails / download 404s | The `supavolt-dev` bucket doesn't exist in MinIO. See Step 4. |
| `supavolt_tenant` role missing | `01-roles.sql` only runs on a **fresh** Postgres volume. Run `docker compose down -v` (deletes data) and `up -d` again. |
| Port 3001 or 5000 already in use | Another process holds it. On Windows: `Get-NetTCPConnection -LocalPort 5000` shows the owner. WSL's `wslrelay.exe` sometimes holds 5000; `wsl --shutdown` frees it. |
| `pnpm install` stops with *ignored build scripts* | Already answered in `frontend/pnpm-workspace.yaml`. Make sure you have the latest version of that file. |
| Many 429 errors on login/register | Auth routes allow 10 requests per minute per IP. Wait a minute. |

---

## Not built yet

- **Row-level security.** The anon key can read every row of every table in its project.
  `ProjectAuthService.ValidateEndUserTokenAsync` is the hook to build it on.
- **Per-project Google/GitHub sign-in for app users.** Credentials can be saved, but the OAuth
  redirect and callback endpoints are not written.
- **Multiple API instances.** Realtime needs a Redis backplane
  (`Microsoft.AspNetCore.SignalR.StackExchangeRedis`) and a single elected listener.
- **A .NET client SDK.** The HTTP API is stable enough to write one on `HttpClient` and
  `HubConnectionBuilder`.
