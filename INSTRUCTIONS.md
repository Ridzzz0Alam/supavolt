# INSTRUCTIONS.md — build, run and verify the Java backend

The backend is Java 17 / Spring Boot 4, ported from a .NET 10 API (DECISIONS.md section 8 records
the port). The architecture, SQL and security model carried over unchanged and are deliberate.
This is the runbook for building it, running it, and proving it works.

---

## 0. Ground rules

Read these before changing anything. Several "errors" you may hit are load-bearing design
decisions, and the obvious local fix is the wrong one.

| Do not | Why |
| --- | --- |
| Replace a JDBC parameter with string concatenation to fix a type error | Every value in this codebase is parameterised on purpose. Fix the type, keep the parameter. |
| Bypass `SqlIdentifier` and concatenate a raw string into SQL | It is the single gate for identifiers. If it rejects something valid, widen the pattern in one place. |
| Delete the `supavolt_tenant` role or point tenant queries at the admin DataSource | That role is what keeps server-built tenant SQL away from the control plane. |
| Run user SQL on anything but the project's own role (`TenantConnections.openProject`) | A shared role let one project read and drop another's tables (DECISIONS.md section 1). |
| Return a secret from a GET endpoint to "make the UI work" | `serviceRoleKeyHash`, OAuth client secrets and `authJwtSecret` are write-only by design. |
| Rename the `access_token` / `refresh_token` cookies | The Next.js middleware depends on those names. |
| Change the `/realtime` wire format | It is the SignalR JSON protocol because `frontend/lib/realtime.ts` uses `@microsoft/signalr`. |
| Edit a Flyway migration that has been applied anywhere | Add `V2__…` instead; Flyway refuses to start on a changed checksum. |
| Weaken `-Werror` | Turn it off temporarily if it blocks you, but turn it back on and clear the warnings. |

If a design decision genuinely blocks you, record it in `DECISIONS.md` with the reasoning rather
than silently reversing it.

---

## 1. Prerequisites

```bash
java -version        # 17 or newer (or use the compose `api` profile: Docker only)
node -v              # 20+
pnpm -v              # or npm
docker --version
```

Maven comes with the repo (`backend/mvnw`).

---

## 2. Build and test

```bash
cd backend
./mvnw verify
```

This compiles with every lint warning as an error, runs the unit tests and the integration tests
(which start Postgres 17 through Testcontainers, so Docker must be running), and writes a coverage
report to `api/target/site/jacoco/index.html`.

The suites, in `api/src/test/java/dev/supavolt/api/`:

- **Unit:** `SqlIdentifierTest`, `FilterParserTest` (including the two regressions the parser
  exists for: `?price=lte100` and `?price=bogus.1` are 400s), `SqlStatementSplitterTest`,
  `ProjectKeyTest`, `AspNetIdentityPasswordHasherTest` (hashes written by .NET's
  `PasswordHasher` still verify).
- **Integration (`ApiTest`, `RealtimeTest`):** registration and refresh rotation with reuse
  detection; org roles, invites and the last-admin rule; key scoping, rotation and the anon write
  ban; SQL editor isolation between projects (six attacks); storage prefix checks; end-user auth;
  write-only secrets; projects carrying secrets from the .NET API; and the realtime protocol end to
  end, including stale keys being disconnected.

---

## 3. Run it

```bash
cd backend
docker compose up -d              # postgres + storage; db/init/01-roles.sql runs on a fresh volume
scripts/new-secrets.sh            # once: ~/.config/supavolt/secrets.yml
./mvnw -pl api -am spring-boot:run
curl http://localhost:5000/api/health
```

Confirm the tenant role exists — `db/init/01-roles.sql` only runs on a **fresh** volume:

```bash
docker compose exec postgres psql -U supavolt_admin -d supavolt -c "\du"
```

Flyway creates the control-plane tables on start. The dev profile creates the `supavolt-dev`
bucket. A database the .NET API migrated is baselined at V1 rather than migrated again.

---

## 4. Smoke test the whole chain

Run this end to end after any change to auth, tenancy or the data API.

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

For step 10, connect `@microsoft/signalr` to `http://localhost:5000/realtime` with
`accessTokenFactory: () => ANON`, invoke `Subscribe("todos")`, repeat step 5 and confirm an
`event` message arrives. `RealtimeTest` does the same at the protocol level.

**Each of these is a pass/fail gate.** Steps 7 and 9 failing open are security regressions, not
cosmetic bugs.

---

## 5. The frontend

```bash
cd frontend
cp .env.example .env.local        # JWT_ACCESS_SECRET = supavolt.jwt.access-secret
pnpm install
pnpm typecheck
pnpm dev
```

The dashboard needed no changes for the port: the HTTP contract, cookies, ProblemDetails errors
and realtime protocol are the same.

One thing to watch in `lib/actions.ts` and `lib/server-data.ts`: Next.js implements `redirect()` by
throwing. Never call it inside a `try` block whose `catch` swallows exceptions, or the redirect
turns into a caught error. The current code keeps redirects outside `try` — preserve that.

---

## 6. Known gaps — features that are absent, not broken

Do not treat these as bugs to be discovered. They were left out deliberately, and each needs a
decision from the repo owner before you build it.

1. **Per-project OAuth callbacks.** `ProjectAuthService` has the code-exchange half and stores per-project Google/GitHub credentials, but the `/projects/{slug}/auth/google` and `/github` endpoints and their callbacks are not written. They cannot use Spring's `oauth2Login`, because credentials vary per project — you need a manual flow with `state` and PKCE stored server-side. Follow the pattern in `createExchangeCode`: the callback must redirect with a one-time code, never with the access token.
2. **Row-level security.** The anon key can read every row of every table. `validateEndUserToken` is the hook. Doing this properly means a Postgres role per key type and RLS policies, with the end-user claims set per request.
3. **A Java client SDK.** The TypeScript one still works against this API.
4. **Multiple API instances.** Realtime groups live in memory; see the README.

---

## 7. Definition of done for a change

- [ ] `./mvnw verify` green, with `-Werror` on
- [ ] Every step of the section 4 smoke test passes, including the two that must fail (steps 7 and 9)
- [ ] `pnpm typecheck` clean and sign-in works end to end
- [ ] A realtime event reaches a subscribed client
- [ ] No secret appears in any GET response body (check `/api/openapi/v1.json` for `secret` and `serviceRole`)
- [ ] Anything you reversed from the original design is recorded in `DECISIONS.md`
