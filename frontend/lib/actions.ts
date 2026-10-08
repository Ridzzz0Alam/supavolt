'use server';

import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { revalidatePath } from 'next/cache';
import { ApiError, apiGet, apiSend } from './api';
import type { Organization, Project, ProjectKeys, QueryResult, StorageObject, UploadUrl } from './types';

/**
 * Server actions. Two details differ from the original:
 *  - Set-Cookie headers from the API are copied onto the Next.js cookie store by hand, because
 *    the API and the dashboard are different origins.
 *  - Errors arrive as ProblemDetails, so `ApiError.message` is the title and is safe to show.
 */

const API_URL = process.env.API_URL ?? 'http://localhost:5000/api';

export type ActionState = { error?: string; success?: string };

async function authCookie(): Promise<string> {
  const store = await cookies();
  const token = store.get('access_token')?.value;
  if (!token) redirect('/login');
  return `access_token=${token}`;
}

async function applySetCookies(response: Response): Promise<void> {
  const store = await cookies();

  for (const header of response.headers.getSetCookie()) {
    const [pair] = header.split(';');
    const separator = pair.indexOf('=');
    const name = pair.slice(0, separator).trim();

    if (name !== 'access_token' && name !== 'refresh_token') continue;

    store.set(name, pair.slice(separator + 1), {
      httpOnly: true,
      sameSite: 'lax',
      secure: process.env.NODE_ENV === 'production',
      path: '/',
    });
  }
}

// ─── Auth ─────────────────────────────────────────────────────────────────────

export async function login(_prev: ActionState, formData: FormData): Promise<ActionState> {
  const res = await fetch(`${API_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: String(formData.get('email') ?? ''),
      password: String(formData.get('password') ?? ''),
    }),
  });

  if (!res.ok) {
    const problem = (await res.json().catch(() => ({}))) as { title?: string };
    return { error: problem.title ?? 'Sign in failed' };
  }

  await applySetCookies(res);
  redirect(await landingPath(res, formData));
}

export async function register(_prev: ActionState, formData: FormData): Promise<ActionState> {
  const res = await fetch(`${API_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: String(formData.get('name') ?? ''),
      email: String(formData.get('email') ?? ''),
      password: String(formData.get('password') ?? ''),
    }),
  });

  if (!res.ok) {
    const problem = (await res.json().catch(() => ({}))) as { title?: string };
    return { error: problem.title ?? 'Registration failed' };
  }

  await applySetCookies(res);
  redirect(await landingPath(res, formData));
}

/**
 * An invite link opened while signed out arrives as /login?invite=<token>; the form carries the
 * token through, and it is accepted here with the session just issued. Acceptance answers with a
 * redirect to the org, which is where the user lands. A spent or expired invite still signs in.
 */
async function landingPath(signIn: Response, formData: FormData): Promise<string> {
  const invite = String(formData.get('invite') ?? '');
  const access = signIn.headers
    .getSetCookie()
    .map((header) => header.split(';')[0])
    .find((pair) => pair.startsWith('access_token='));

  if (!invite || !access) return '/dashboard';

  const res = await fetch(`${API_URL}/auth/invite/accept?token=${encodeURIComponent(invite)}`, {
    headers: { Cookie: access },
    redirect: 'manual',
  });

  // Anything but a redirect into an org (an error, or back to /login) falls back to the dashboard.
  const path = res.status === 302 ? new URL(res.headers.get('location') ?? '/', API_URL).pathname : '';
  return path.startsWith('/organizations/') ? path : '/dashboard';
}

export async function signOut(): Promise<void> {
  const store = await cookies();
  const token = store.get('access_token')?.value;

  await fetch(`${API_URL}/auth/logout`, {
    method: 'POST',
    headers: token ? { Cookie: `access_token=${token}` } : {},
  });

  store.delete('access_token');
  store.delete('refresh_token');
  redirect('/login');
}

// ─── Organizations ────────────────────────────────────────────────────────────

export async function createOrganization(
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  const cookie = await authCookie();
  let slug: string;

  try {
    const org = await apiSend<Organization>(
      'POST',
      '/orgs',
      { name: String(formData.get('name') ?? '') },
      { cookie },
    );
    slug = org.slug;
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }

  revalidatePath('/organizations');
  redirect(`/organizations/${slug}/projects`);
}

// ─── Projects ─────────────────────────────────────────────────────────────────

/**
 * Returns the freshly minted keys so the UI can show the service-role key once. The API only
 * stores its hash, so this is the single moment it is ever visible.
 */
export async function createProject(
  orgSlug: string,
  _prev: ActionState & { keys?: ProjectKeys },
  formData: FormData,
): Promise<ActionState & { keys?: ProjectKeys; project?: Project }> {
  const cookie = await authCookie();

  try {
    const created = await apiSend<{ project: Project; keys: ProjectKeys }>(
      'POST',
      `/orgs/${orgSlug}/projects`,
      { name: String(formData.get('name') ?? '') },
      { cookie },
    );

    revalidatePath(`/organizations/${orgSlug}/projects`);
    return { success: 'Project created', project: created.project, keys: created.keys };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function rotateProjectKeys(
  orgSlug: string,
  projectSlug: string,
): Promise<ActionState & { keys?: ProjectKeys }> {
  const cookie = await authCookie();

  try {
    const keys = await apiSend<ProjectKeys>(
      'POST',
      `/orgs/${orgSlug}/projects/${projectSlug}/keys/rotate`,
      undefined,
      { cookie },
    );
    return { success: 'Keys rotated. Update every client that uses them.', keys };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

// ─── Members ──────────────────────────────────────────────────────────────────

export async function inviteMember(
  orgSlug: string,
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('POST', `/orgs/${orgSlug}/members/invite`, {
      email: String(formData.get('email') ?? ''),
    }, { cookie });

    revalidatePath(`/organizations/${orgSlug}/settings/members`);
    return { success: 'Invite sent' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function updateMemberRole(
  orgSlug: string,
  memberId: string,
  role: 'admin' | 'developer',
): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('PATCH', `/orgs/${orgSlug}/members/${memberId}/role`, { role }, { cookie });
    revalidatePath(`/organizations/${orgSlug}/settings/members`);
    return { success: 'Role updated' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function removeMember(orgSlug: string, memberId: string): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('DELETE', `/orgs/${orgSlug}/members/${memberId}`, undefined, { cookie });
    revalidatePath(`/organizations/${orgSlug}/settings/members`);
    return { success: 'Member removed' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

// ─── Table editor ─────────────────────────────────────────────────────────────

export async function createTable(
  orgSlug: string,
  projectSlug: string,
  payload: { name: string; columns: unknown[] },
): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('POST', `/orgs/${orgSlug}/projects/${projectSlug}/tables`, payload, { cookie });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/database`);
    return { success: 'Table created' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function dropTable(
  orgSlug: string,
  projectSlug: string,
  table: string,
): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('DELETE', `/orgs/${orgSlug}/projects/${projectSlug}/tables/${table}`, undefined, {
      cookie,
    });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/database`);
    return { success: 'Table deleted' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

// ─── SQL editor ───────────────────────────────────────────────────────────────

export async function runSql(
  orgSlug: string,
  projectSlug: string,
  sql: string,
): Promise<ActionState & { result?: QueryResult }> {
  const cookie = await authCookie();

  try {
    const result = await apiSend<QueryResult>(
      'POST',
      `/orgs/${orgSlug}/projects/${projectSlug}/sql`,
      { sql },
      { cookie },
    );
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/database`);
    return { result };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

// ─── Realtime ─────────────────────────────────────────────────────────────────

export async function setRealtime(
  orgSlug: string,
  projectSlug: string,
  table: string,
  enabled: boolean,
): Promise<ActionState> {
  const cookie = await authCookie();
  const base = `/orgs/${orgSlug}/projects/${projectSlug}/realtime/${encodeURIComponent(table)}`;

  try {
    if (enabled) await apiSend('POST', `${base}/enable`, undefined, { cookie });
    else await apiSend('DELETE', `${base}/disable`, undefined, { cookie });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/realtime`);
    return { success: enabled ? `Realtime enabled for ${table}` : `Realtime disabled for ${table}` };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

// ─── Storage ──────────────────────────────────────────────────────────────────

export async function createBucket(
  orgSlug: string,
  projectSlug: string,
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('POST', `/orgs/${orgSlug}/projects/${projectSlug}/storage/buckets`, {
      name: String(formData.get('name') ?? ''),
      access: formData.get('access') === 'public' ? 'public' : 'private',
    }, { cookie });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/storage`);
    return { success: 'Bucket created' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function deleteBucket(orgSlug: string, projectSlug: string, bucketId: string): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('DELETE', `/orgs/${orgSlug}/projects/${projectSlug}/storage/buckets/${bucketId}`, undefined, { cookie });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/storage`);
    return { success: 'Bucket deleted' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

/** Step one of the presigned flow; the browser then PUTs the bytes straight to object storage. */
export async function requestUploadUrl(
  orgSlug: string,
  projectSlug: string,
  bucketId: string,
  file: { fileName: string; contentType: string; size: number },
): Promise<ActionState & { upload?: UploadUrl }> {
  const cookie = await authCookie();

  try {
    const upload = await apiSend<UploadUrl>(
      'POST',
      `/orgs/${orgSlug}/projects/${projectSlug}/storage/buckets/${bucketId}/upload-url`,
      file,
      { cookie },
    );
    return { upload };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function registerObject(
  orgSlug: string,
  projectSlug: string,
  bucketId: string,
  object: { name: string; size: number; contentType: string; objectKey: string },
): Promise<ActionState & { object?: StorageObject }> {
  const cookie = await authCookie();

  try {
    const created = await apiSend<StorageObject>(
      'POST',
      `/orgs/${orgSlug}/projects/${projectSlug}/storage/buckets/${bucketId}/objects`,
      object,
      { cookie },
    );
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/storage`);
    return { object: created };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function deleteObject(orgSlug: string, projectSlug: string, objectId: string): Promise<ActionState> {
  const cookie = await authCookie();

  try {
    await apiSend('DELETE', `/orgs/${orgSlug}/projects/${projectSlug}/storage/objects/${objectId}`, undefined, { cookie });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/storage`);
    return { success: 'File deleted' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

export async function getSignedUrl(
  orgSlug: string,
  projectSlug: string,
  objectId: string,
): Promise<ActionState & { url?: string }> {
  const cookie = await authCookie();

  try {
    const { url } = await apiGet<{ url: string }>(
      `/orgs/${orgSlug}/projects/${projectSlug}/storage/objects/${objectId}/signed-url`,
      { cookie },
    );
    return { url };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}

// ─── Project auth ─────────────────────────────────────────────────────────────

/**
 * Secrets are write-only: an empty secret field means "leave it as it is" (null), and only the
 * explicit clear checkbox sends "" to remove one.
 */
export async function updateAuthSettings(
  orgSlug: string,
  projectSlug: string,
  _prev: ActionState,
  formData: FormData,
): Promise<ActionState> {
  const cookie = await authCookie();
  const text = (name: string) => String(formData.get(name) ?? '').trim();
  const secret = (name: string) =>
    formData.get(`${name}Clear`) === 'on' ? '' : text(name) || null;

  try {
    await apiSend('POST', `/orgs/${orgSlug}/projects/${projectSlug}/auth/settings`, {
      siteUrl: text('siteUrl') || null,
      redirectUrls: text('redirectUrls').split(/\s+/).filter(Boolean),
      googleClientId: text('googleClientId'),
      googleClientSecret: secret('googleClientSecret'),
      githubClientId: text('githubClientId'),
      githubClientSecret: secret('githubClientSecret'),
    }, { cookie });
    revalidatePath(`/organizations/${orgSlug}/${projectSlug}/auth`);
    return { success: 'Settings saved' };
  } catch (error) {
    return { error: error instanceof ApiError ? error.message : 'Something went wrong' };
  }
}
