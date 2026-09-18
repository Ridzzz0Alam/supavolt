# Repo context for Claude Code

Supavolt rebuilt on .NET 10 (ASP.NET Core, EF Core + Dapper, SignalR) with a Next.js 16
TypeScript dashboard. Two halves: `backend/` and `frontend/`. The contract between them is HTTP
plus `frontend/lib/types.ts`, which mirrors `backend/src/Supavolt.Contracts/Contracts.cs` by hand.

**Read `INSTRUCTIONS.md` first.** This codebase has never been compiled — that file is the
task-ordered runbook for getting it building, running and tested, and it lists the specific
places I expect to break.

## Conventions

- One file per feature slice: `Features/<Name>/<Name>Feature.cs` holds that slice's service and
  its endpoint group. The mapping to the original NestJS module of the same name is 1:1.
- Values in SQL are always Dapper parameters. Identifiers are always `SqlIdentifier`. There is no
  third option — if something does not fit, fix it inside `SqlIdentifier`, not at the call site.
- Three connection strings by role: admin (EF, DDL), tenant (least privilege — the data API and
  other server-built SQL), direct (the LISTEN connection only, non-pooled). User-authored SQL runs
  only as the project's own login role (`OpenProjectAsync`), never as the shared tenant role.
- Tests: `dotnet test` (integration tests need Docker). On this Windows machine Smart App Control
  blocks locally built DLLs, so run the API and tests in the compose `api` service — see README.
- Two authentication schemes: `Dashboard` (cookie-carried JWT) and `ProjectKey` (Authorization
  header). Four policies: `OrgMember`, `OrgAdmin`, `ProjectKeyAny`, `ProjectServiceRole`.
- Secrets are write-only. Service-role keys are stored hashed; OAuth client secrets are encrypted
  with `IDataProtector` and surfaced only as `*Configured: bool`.
- JSON is camelCase with string enums, to match the existing TypeScript types.

## Commands

```bash
cd backend && dotnet build && dotnet test
cd backend/src/Supavolt.Api && dotnet run          # http://localhost:5000/api
cd frontend && pnpm dev                            # http://localhost:3001
docker compose -f backend/docker-compose.yml up -d # postgres + minio
```

## Before changing behaviour

`INSTRUCTIONS.md` section 0 lists six things not to "fix" — they are security decisions that look
like bugs locally. Check that table before reversing anything.
