# Repo context for Claude Code

Supavolt: a Java 17 / Spring Boot 4 API (Spring MVC, Spring Security, JPA + JdbcClient, raw
WebSockets) with a Next.js 16 TypeScript dashboard. Two halves: `backend/` and `frontend/`. The
contract between them is HTTP plus `frontend/lib/types.ts`, which mirrors
`backend/contracts/src/main/java/dev/supavolt/contracts/Contracts.java` by hand.

The backend was ported from .NET 10; `DECISIONS.md` section 8 records what changed in the port and
why. `INSTRUCTIONS.md` is the runbook for building, running and smoke-testing it.

## Conventions

- One package per feature slice: `features/<name>/` holds that slice's service and controller.
  The mapping to the original NestJS module of the same name is 1:1.
- Values in SQL are always JDBC parameters (`:name` with JdbcClient). Identifiers are always
  `SqlIdentifier`. There is no third option — if something does not fit, fix it inside
  `SqlIdentifier`, not at the call site. DDL goes through `TenantConnections.adminDdl()`.
- Three database roles: admin (`spring.datasource.*`: JPA, Flyway, DDL), tenant (least privilege —
  the data API and other server-built SQL), direct (the LISTEN connection only, non-pooled).
  User-authored SQL runs only as the project's own login role (`openProject`), never as the shared
  tenant role.
- Control-plane schema changes are Flyway migrations in `api/src/main/resources/db/migration`;
  Hibernate only validates (`ddl-auto: validate`).
- Two authentication schemes as two filter chains in `SecurityConfig`: Dashboard (cookie-carried
  JWT) and ProjectKey (Authorization header, or `access_token` query for WebSockets). Org policies
  are `@PreAuthorize("@orgAccess.member(#slug)")` / `admin(#slug)`; project-key policies are path
  rules in `SecurityConfig`.
- Secrets are write-only. Service-role keys are stored hashed; OAuth client secrets are encrypted
  with `SecretProtector` (AES-GCM) and surfaced only as `*Configured: bool`.
- JSON is camelCase with lowercase string enums, to match the existing TypeScript types.
- `/realtime` speaks the SignalR JSON hub protocol (`RealtimeHub`), because the dashboard uses
  `@microsoft/signalr`. Keep it wire-compatible.
- The build treats compiler warnings as errors (`-Xlint:all -Werror`). Fix warnings; don't
  suppress them.

## Commands

```bash
cd backend && ./mvnw verify                               # build + all tests (tests need Docker)
backend/scripts/new-secrets.sh                            # once: ~/.config/supavolt/secrets.yml
cd backend && ./mvnw -pl api -am spring-boot:run          # http://localhost:5000/api (dev profile)
cd frontend && pnpm dev                                   # http://localhost:3001
docker compose -f backend/docker-compose.yml up -d        # postgres + S3 storage (RustFS)
```

## Before changing behaviour

`INSTRUCTIONS.md` section 0 lists things not to "fix" — they are security decisions that look
like bugs locally. Check that table before reversing anything.
