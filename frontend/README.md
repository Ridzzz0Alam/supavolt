# Supavolt dashboard (Next.js 16 + TypeScript)

Talks to the .NET API in `../backend`. Nothing is proxied: the API runs on its own origin and
the browser sends the auth cookie cross-origin, which is why the API's CORS policy names this
exact URL and sets `AllowCredentials`.

## What is here

```
app/
  layout.tsx            fonts, globals
  globals.css           design tokens for Tailwind 4 + shadcn/ui
  login/page.tsx        email + password, Google and GitHub
  register/page.tsx
  dashboard/page.tsx    post-sign-in redirect
lib/
  api.ts                typed fetch wrapper, ProblemDetails aware
  types.ts              mirror of Supavolt.Contracts
  server-data.ts        every server-component read, cookie forwarded
  actions.ts            server actions: auth, orgs, projects, members, tables
  realtime.ts           SignalR client replacing socket.io
  utils.ts              cn(), formatBytes()
middleware.ts           token verification and refresh rotation
```

## What still has to be built

The dashboard surfaces themselves. These port from the original repo's `apps/web` with only the
import swaps noted below — the components are unchanged React, since the API contract matches.

| Surface | Original file | Change needed |
| --- | --- | --- |
| Sidebar and top nav | `components/app-sidebar.tsx`, `top-nav.tsx` | Drop the `console.log` in top-nav |
| Organizations list, new org | `app/(dashboard)/organizations/...` | Read through `lib/server-data.ts` |
| Projects list, create modal | `features/projects/*` | Show the service-role key once from the create action's `keys` |
| Members | `features/members/members-client.tsx` | Fix the dead `onValueChange` handler on the role select |
| Table editor | `features/table-editor/*` | Split the 737-line client into list panel, tab bar, grid, row sheet |
| SQL editor | `features/sql-editor/*` | None; Monaco and the result grid work as-is |
| Realtime | `features/realtime/realtime-client.tsx` | Use `lib/realtime.ts` instead of `socket.io-client` |
| Storage | `features/storage/storage-client.tsx` | Replace the UploadThing `UploadButton` with the presigned-PUT flow |
| API docs | `features/api-docs/*` | None |
| Project auth pages | `features/project-auth/*` | Secrets are now write-only: render `googleConfigured` rather than the secret |

Two global swaps when porting: `@supavolt/types` becomes `@/lib/types`, and the axios client
becomes `@/lib/api`.

Also delete `apps/web/templates/` if you copy from the original — it holds byte-identical copies
of about fifteen feature files and will drift the moment someone edits one.

## Running

```bash
pnpm install
cp .env.example .env.local   # then fill JWT_ACCESS_SECRET to match the API
pnpm dev                     # http://localhost:3001
```

`JWT_ACCESS_SECRET` must equal the API's `Jwt:AccessSecret`, because `middleware.ts` verifies the
access token locally with jose rather than calling the API on every navigation.

## The presigned upload flow

Storage no longer proxies bytes through the API:

```ts
const { uploadUrl, objectKey } = await apiSend<UploadUrl>(
  'POST', `/orgs/${slug}/projects/${projectSlug}/storage/buckets/${bucketId}/upload-url`,
  { fileName: file.name, contentType: file.type, size: file.size },
);

await fetch(uploadUrl, { method: 'PUT', body: file, headers: { 'Content-Type': file.type } });

const object = await apiSend<StorageObject>(
  'POST', `/orgs/${slug}/projects/${projectSlug}/storage/buckets/${bucketId}/objects`,
  { name: file.name, size: file.size, contentType: file.type, objectKey },
);
```

The PUT goes straight to S3/R2, so a 500 MB upload costs the API nothing.
