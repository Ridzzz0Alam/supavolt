# INSTRUCTIONS.md — bring this repo to a working state

You are picking up a codebase that has **never been compiled or run**. It was written in a
sandbox with no .NET SDK and no access to nuget.org. The architecture, SQL and security model are
deliberate and should be preserved; the syntax, package versions and wiring need a real toolchain.

Your job, in order: get it building, get it running, get it tested, then fix what the tests find.

---

## 0. Ground rules

Read these before changing anything. Several "errors" you will hit are load-bearing design
decisions, and the obvious local fix is the wrong one.

| Do not | Why |
| --- | --- |
| Replace a Dapper parameter with string interpolation to fix a type error | Every value in this codebase is parameterised on purpose. Fix the type, keep the parameter. |
| Bypass `SqlIdentifier` and interpolate a raw string into SQL | It is the single gate for identifiers. If it rejects something valid, widen the regex in one place. |
| Delete the `supavolt_tenant` role or point tenant queries at the admin connection string | That role is what makes the SQL editor safe. Without it the editor can read the control-plane tables. |
| Return a secret from a GET endpoint to "make the UI work" | `ServiceRoleKeyHash`, OAuth client secrets and `AuthJwtSecret` are write-only by design. |
| Rename the `access_token` / `refresh_token` cookies | The Next.js middleware and the original dashboard both depend on those names. |
| Weaken `TreatWarningsAsErrors` | Turn it off temporarily if it blocks you, but turn it back on and clear the warnings before you finish. |

If a design decision genuinely blocks you, note it in a `DECISIONS.md` with the reasoning rather
than silently reversing it.

---

## 1. Prerequisites

```bash
dotnet --version     # need 10.0.x
node -v              # need 20+
pnpm -v              # or npm
docker --version
```

Install `dotnet-ef` if it is missing: `dotnet tool install --global dotnet-ef`.

---

## 2. Phase 1 — make the backend compile

```bash
cd backend
dotnet restore
```

**Expect this to fail first on package versions.** Every version in
`src/Supavolt.Api/Supavolt.Api.csproj` was written from memory and not verified against nuget.org.
Resolve each one against the real feed rather than guessing:

```bash
dotnet package search Npgsql.EntityFrameworkCore.PostgreSQL --take 1
dotnet package search EFCore.NamingConventions --take 1
dotnet package search AspNet.Security.OAuth.GitHub --take 1
dotnet package search Scalar.AspNetCore --take 1
dotnet package search AWSSDK.S3 --take 1
```

The four most likely to be wrong: `EFCore.NamingConventions` (community package, its major
version trails EF Core's), `AspNet.Security.OAuth.GitHub` (community, tracks ASP.NET Core's
major), `Scalar.AspNetCore` (fast-moving), and `AWSSDK.S3` (v4 renamed several request types).
If `Scalar.AspNetCore` is a problem, drop it and `app.MapScalarApiReference()` — the built-in
`app.MapOpenApi()` is enough.

Then:

```bash
dotnet build 2>&1 | tee /tmp/build.log
```

Work the errors from the top of the file list down. Fix compiler errors literally — do not
restructure a feature because one line does not compile.

### Known-suspect spots

These are the places I would check first. Each is my own uncertainty, not a confirmed bug, but
all of them are worth looking at before you trust the build.

**`Infrastructure/SupavoltDbContext.cs`**
- Two `HasIndex(x => x.Email)` calls on `User`, the second with `HasDatabaseName("ix_users_email_lower")`. That is almost certainly wrong — EF cannot express a functional index on `lower(email)`. Keep one plain unique index, and add the functional one as raw SQL in the migration: `migrationBuilder.Sql("CREATE UNIQUE INDEX ix_users_email_lower ON users (lower(email))")`.
- `Project.RedirectUrls` is a `List<string>` mapped to `jsonb`. EF Core needs an explicit conversion plus a `ValueComparer`, or change-tracking silently misses mutations. Either add both, or make it an owned collection.
- `AuthJwtSecret` uses the nullable `encrypted` converter with a `!`. A `ValueConverter<string?, string?>` on a non-nullable property is likely a type error. Write a second non-nullable converter.

**`Features/DataApi/DataApiFeature.cs` and `TableEditor`**
- `(Dictionary<string, object?>)row` on a Dapper dynamic result. Dapper returns `DapperRow`, which implements `IDictionary<string, object>` but is **not** a `Dictionary<string, object?>`. This cast will throw at runtime, not compile time — so it will pass the build and fail the first request. Fix by projecting: `((IDictionary<string, object>)row).ToDictionary(kv => kv.Key, kv => (object?)kv.Value)`. Pull this into one extension method and use it everywhere rows are converted.

**`Features/Members/MembersFeature.cs`**
- `InviteService.SendAsync` inserts a `ProjectAuthToken` with `ProjectId = default`. There is a foreign key to `projects`, so this will fail at runtime with a FK violation. Pick one: give invites their own token table, or make `ProjectAuthToken.ProjectId` nullable. The invite table already exists — the cleanest fix is to store the token hash on `Invite` itself and delete the `ProjectAuthTokens` write.
- `EnsureNotLastAdminAsync` uses `SqlQuery<Guid>` with an `AS "Value"` alias and compares `m.role = 'Admin'`. Confirm the string the `HasConversion<string>()` actually writes (it may be `Admin`, matching the enum name) and that the alias convention is right for EF 10.

**`Features/SqlEditor/SqlEditorFeature.cs`**
- `reader.RecordsAffected` is read before `reader.CloseAsync()` but after the read loop; verify it returns what you expect for INSERT/UPDATE.
- A local named `command_` — rename it.
- `SqlStatementSplitter` is hand-written and unproven. It has tests waiting for it in section 5; run them before trusting it.

**`Features/ProjectAuth/ProjectAuthFeature.cs`**
- `ProjectAsync` uses `ContinueWith`, which is wrong style and may not compile cleanly. Rewrite as a normal `async` method with a null check.
- `AuthUserRow` is a `private sealed record` inside the class but is returned from the public `UpsertOAuthUserAsync`. That is an accessibility error. Make the record internal or public.
- The OAuth callback endpoints for per-project Google/GitHub are **not implemented** — only the code-exchange half exists. See section 7.

**`Program.cs`**
- The `.AddScheme<AuthenticationSchemeOptions, ProjectKeyAuthenticationHandler>` generic arguments and the fully-qualified `Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions` may need a using directive instead.
- `Results.Challenge` in `AuthFeature.cs` takes an unused `LinkGenerator` parameter — remove it.
- Verify `AddAuthorizationBuilder().SetDefaultPolicy(...)` exists with that signature in .NET 10.

**`Features/Storage/StorageFeature.cs`**
- AWSSDK v4 changed several APIs. `GetPreSignedURL` may need to be `GetPreSignedURLAsync`, and `DeleteObjectsRequest.Objects` may take a different element type. Check against the installed package, not the docs for v3.

---

## 3. Phase 2 — make it run

```bash
docker compose up -d
```

Confirm the tenant role exists — `db/init/01-roles.sql` only runs on a **fresh** volume:

```bash
docker compose exec postgres psql -U supavolt_admin -d supavolt -c "\du"
```

If `supavolt_tenant` is missing, run the file by hand or `docker compose down -v` and start again.

Secrets:

```bash
cd src/Supavolt.Api
dotnet user-secrets set "Jwt:AccessSecret"        "$(openssl rand -hex 32)"
dotnet user-secrets set "Jwt:RefreshSecret"       "$(openssl rand -hex 32)"
dotnet user-secrets set "ProjectKeys:Secret"      "$(openssl rand -hex 32)"
dotnet user-secrets set "Invites:Secret"          "$(openssl rand -hex 32)"
dotnet user-secrets set "Storage:AccessKeyId"     "supavolt"
dotnet user-secrets set "Storage:SecretAccessKey" "supavolt123"
```

Migration and run:

```bash
dotnet ef migrations add InitialSchema
dotnet ef database update
dotnet run
curl http://localhost:5000/api/health
```

**Expect the migration step to need hand-editing**, particularly around the `jsonb` column, the
functional unique index and the enum-to-text conversions. Read the generated migration before
applying it; do not apply a migration you have not read.

Also create the MinIO bucket named in `Storage:Bucket` (default `supavolt-dev`) via the console
at `http://localhost:9001`, or storage calls will fail with a 404 from S3.

---

## 4. Phase 3 — smoke test the whole chain

Run this end to end before writing any unit tests. It exercises every layer and will surface the
runtime problems the compiler cannot.

```bash
API=http://localhost:5000/api
JAR=/tmp/supavolt.cookies

# 1. Register (writes both cookies)
curl -sc $JAR -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"dev@example.com","password":"correct-horse-battery","name":"Dev"}'

# 2. The personal org created by registration
curl -sb $JAR $API/orgs | tee /tmp/orgs.json
ORG=$(jq -r '.[0].slug' /tmp/orgs.json)

# 3. Create a project — returns the service-role key exactly once
curl -sb $JAR -X POST $API/orgs/$ORG/projects -H 'Content-Type: application/json' \
  -d '{"name":"Demo"}' | tee /tmp/project.json
PROJ=$(jq -r '.project.slug' /tmp/project.json)
ANON=$(jq -r '.keys.anonKey' /tmp/project.json)
SERVICE=$(jq -r '.keys.serviceRoleKey' /tmp/project.json)

# 4. Create a table
curl -sb $JAR -X POST $API/orgs/$ORG/projects/$PROJ/tables -H 'Content-Type: application/json' \
  -d '{"name":"todos","columns":[
        {"name":"id","type":"bigint","isNullable":false,"isPrimaryKey":true},
        {"name":"title","type":"text","isNullable":false,"isPrimaryKey":false},
        {"name":"done","type":"boolean","isNullable":false,"isPrimaryKey":false,"defaultValue":"false"}]}'

# 5. Write through the data API with the service-role key
curl -s -X POST $API/projects/$PROJ/rest/todos \
  -H "Authorization: Bearer $SERVICE" -H 'Content-Type: application/json' \
  -d '{"title":"first"}'

# 6. Read with the anon key, and filter
curl -s "$API/projects/$PROJ/rest/todos?done=eq.false&order=id.desc&limit=10" \
  -H "Authorization: Bearer $ANON"

# 7. Anon must NOT be able to write — expect 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/projects/$PROJ/rest/todos \
  -H "Authorization: Bearer $ANON" -H 'Content-Type: application/json' -d '{"title":"nope"}'

# 8. SQL editor
curl -sb $JAR -X POST $API/orgs/$ORG/projects/$PROJ/sql -H 'Content-Type: application/json' \
  -d '{"sql":"select count(*) from todos"}'

# 9. Two statements must be rejected — expect 400
curl -sb $JAR -X POST $API/orgs/$ORG/projects/$PROJ/sql -H 'Content-Type: application/json' \
  -d '{"sql":"select 1; select 2"}'

# 10. Realtime: enable the trigger, then watch
curl -sb $JAR -X POST $API/orgs/$ORG/projects/$PROJ/realtime/todos/enable
```

For step 10, connect a SignalR client to `http://localhost:5000/realtime?access_token=$ANON`,
invoke `Subscribe("todos")`, then repeat step 5 and confirm an `event` message arrives. A
ten-line Node script using `@microsoft/signalr` is the fastest check.

**Each of these is a pass/fail gate.** Steps 7 and 9 failing open are security regressions, not
cosmetic bugs.

---

## 5. Phase 4 — the test project

Create it once the smoke test passes:

```bash
cd backend
dotnet new xunit -n Supavolt.Tests -o src/Supavolt.Tests --framework net10.0
dotnet sln add src/Supavolt.Tests
cd src/Supavolt.Tests
dotnet add reference ../Supavolt.Api
dotnet add package Testcontainers.PostgreSql
dotnet add package Microsoft.AspNetCore.Mvc.Testing
dotnet add package Shouldly
```

Write these in order. The first three are pure unit tests with no database and will catch the
most per minute spent.

**`SqlIdentifierTests`**
- Accepts `users`, `_private`, `a1`, a 63-character name.
- Rejects `""`, `1abc`, `user name`, `users; DROP TABLE x`, `"quoted"`, a 64-character name, and every non-ASCII case you can think of.
- `ToString()` returns the double-quoted form.

**`FilterParserTests`**
- `?name=eq.Shirt` produces one `Eq` filter with value `"Shirt"`.
- `?price=lt.100` types the value as a number, not a string.
- `?created_at=gte.2026-01-01` types as `DateTimeOffset`.
- `?deleted_at=is.null` and `is.not null` set `IsNull` correctly and produce no parameter.
- `?price=lte100` (missing dot) throws a 400, and `?price=bogus.1` (unknown operator) throws a 400. **These two are the regression the parser exists for** — the original silently ignored them and returned the whole table.
- `limit` clamps to 1000; `offset` floors at 0.
- A column name of `id; DROP TABLE x` throws.

**`SqlStatementSplitterTests`**
- `select 1` → one statement.
- `select 1; select 2` → two.
- `select ';'` → **one** (semicolon inside a literal).
- `select 1; -- trailing comment` → one.
- `select 1 /* ; */` → one.
- A `$$ ... ; ... $$` function body → one.
- `select "weird;name" from t` → one.

**`ProjectKeyTests`** (no database)
- A key signed at version 1 fails validation against a project at version 2.
- An anon key does not carry the `service_role` claim.

**Integration tests** (Testcontainers + `WebApplicationFactory`)
- Register, then `/auth/me` returns the user.
- Refresh rotates: the old refresh token is rejected on second use, **and** using it revokes the whole chain.
- A developer-role member gets 403 on an `OrgAdmin` route; an admin gets 200 on an `OrgMember` route (the original compared roles for equality and failed this).
- Demoting the last admin returns 400.
- A project key for project A returns 403 against project B's URL.
- An anon key gets 403 on POST to the data API.
- Deleting a storage object belonging to another project returns 404, not 204.
- Creating a project creates the schema and `auth_users` inside it.
- The SQL editor cannot read `public.users` when connected as the tenant role — this test is the proof the least-privilege model works.

---

## 6. Phase 5 — the frontend

```bash
cd frontend
pnpm install         # expect version resolution to need adjusting
pnpm typecheck
pnpm dev
```

The dependency versions in `package.json` are also unverified. Resolve them against the registry.
`@microsoft/signalr`, `@monaco-editor/react` and `radix-ui` are the ones to check first.

Then, in order:

1. `pnpm typecheck` clean.
2. Sign-in, refresh and sign-out working end to end against the running API, including the cookie round-trip across origins. If the cookie is not stored, the problem is CORS or `SameSite`, not the action code.
3. `app/dashboard` redirecting correctly.
4. Then build the dashboard surfaces. `frontend/README.md` has the table: each surface, its source file in the original repo, and the change it needs. Do them one at a time, verifying in the browser before starting the next.

Suggested order: sidebar and org switcher → organizations list → projects list and create modal (show the service-role key once, since it is never retrievable again) → members → table editor → SQL editor → realtime → storage → API docs → project-auth pages.

One thing to watch in `lib/actions.ts` and `lib/server-data.ts`: Next.js implements `redirect()` by
throwing. Never call it inside a `try` block whose `catch` swallows exceptions, or the redirect
turns into a caught error. The current code keeps redirects outside `try` — preserve that.

---

## 7. Known gaps — features that are absent, not broken

Do not treat these as bugs to be discovered. They were left out deliberately, and each needs a
decision from the repo owner before you build it.

1. **Per-project OAuth callbacks.** `ProjectAuthService` has the code-exchange half and stores per-project Google/GitHub credentials, but the `/projects/{slug}/auth/google` and `/github` endpoints and their callbacks are not written. They cannot use the framework handlers, because credentials vary per project — you need a manual flow with `state` and PKCE stored server-side. Follow the pattern in `CreateExchangeCodeAsync`: the callback must redirect with a one-time code, never with the access token.
2. **Row-level security.** The anon key can read every row of every table. `ValidateEndUserTokenAsync` is the hook. Doing this properly means a Postgres role per key type and RLS policies, with the end-user claims set per request.
3. **A .NET client SDK.** The TypeScript one still works against this API.
4. **Redis backplane for SignalR.** Single-instance only as it stands; see the README.

---

## 8. Definition of done

- [ ] `dotnet build` clean with `TreatWarningsAsErrors` on
- [ ] `dotnet ef database update` applies to an empty database without hand-editing
- [ ] Every step of the section 4 smoke test passes, including the two that must fail (steps 7 and 9)
- [ ] `dotnet test` green, with the section 5 list covered
- [ ] `pnpm typecheck` clean and sign-in works end to end
- [ ] A realtime event reaches a subscribed client
- [ ] No secret appears in any GET response body (grep the OpenAPI document for `secret` and `serviceRole`)
- [ ] Anything you reversed from the original design is recorded in `DECISIONS.md`

Report back with: what failed to compile and why, the runtime failures the smoke test found,
anything in section 2's suspect list that turned out fine, and any design decision you had to
reverse.
