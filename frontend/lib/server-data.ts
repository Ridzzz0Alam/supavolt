import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { apiGet, ApiError } from './api';
import type {
  Organization,
  OrgMember,
  Project,
  ProjectAuthUser,
  ProjectOAuthSettings,
  QueryHistoryItem,
  StorageBucket,
  StorageObject,
  TableInfo,
  TableRows,
} from './types';

/**
 * Every server component reads through here. One place builds the Cookie header, and one place
 * decides where an unauthenticated or missing-resource response sends the user.
 */

async function authCookie(): Promise<string> {
  const store = await cookies();
  const token = store.get('access_token')?.value;
  if (!token) redirect('/login');
  return `access_token=${token}`;
}

async function load<T>(path: string, onFailure: string): Promise<T> {
  const cookie = await authCookie();

  try {
    return await apiGet<T>(path, { cookie });
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) redirect('/login');
    redirect(onFailure);
  }
}

// ─── Organizations ────────────────────────────────────────────────────────────

export const getMyOrganizations = () => load<Organization[]>('/orgs', '/login');

export const getOrganization = (slug: string) =>
  load<Organization>(`/orgs/${slug}`, '/organizations');

export const getMembers = (slug: string) =>
  load<OrgMember[]>(`/orgs/${slug}/members`, '/organizations');

// ─── Projects ─────────────────────────────────────────────────────────────────

export const getProjects = (slug: string) =>
  load<Project[]>(`/orgs/${slug}/projects`, '/organizations');

export const getProject = (slug: string, projectSlug: string) =>
  load<Project>(`/orgs/${slug}/projects/${projectSlug}`, `/organizations/${slug}/projects`);

// ─── Table editor ─────────────────────────────────────────────────────────────

export const getTables = (slug: string, projectSlug: string) =>
  load<string[]>(`/orgs/${slug}/projects/${projectSlug}/tables`, `/organizations/${slug}/projects`);

export const getTableInfo = (slug: string, projectSlug: string, table: string) =>
  load<TableInfo>(
    `/orgs/${slug}/projects/${projectSlug}/tables/${table}`,
    // Not the database page: that page loads this table again, so a failure would loop.
    `/organizations/${slug}/projects`,
  );

export const getTableRows = (slug: string, projectSlug: string, table: string, limit = 100) =>
  load<TableRows>(
    `/orgs/${slug}/projects/${projectSlug}/tables/${table}/rows?limit=${limit}`,
    `/organizations/${slug}/projects`,
  );

// ─── SQL editor ───────────────────────────────────────────────────────────────

export const getSqlHistory = (slug: string, projectSlug: string) =>
  load<QueryHistoryItem[]>(
    `/orgs/${slug}/projects/${projectSlug}/sql/history`,
    `/organizations/${slug}/projects`,
  );

// ─── Realtime ─────────────────────────────────────────────────────────────────

export const getRealtimeTables = (slug: string, projectSlug: string) =>
  load<string[]>(
    `/orgs/${slug}/projects/${projectSlug}/realtime`,
    `/organizations/${slug}/projects`,
  );

// ─── Storage ──────────────────────────────────────────────────────────────────

export const getBuckets = (slug: string, projectSlug: string) =>
  load<StorageBucket[]>(
    `/orgs/${slug}/projects/${projectSlug}/storage/buckets`,
    `/organizations/${slug}/projects`,
  );

export const getObjects = (slug: string, projectSlug: string, bucketId: string) =>
  load<StorageObject[]>(
    `/orgs/${slug}/projects/${projectSlug}/storage/buckets/${bucketId}/objects`,
    `/organizations/${slug}/${projectSlug}/storage`,
  );

// ─── Project auth ─────────────────────────────────────────────────────────────

export const getProjectAuthUsers = (slug: string, projectSlug: string) =>
  load<ProjectAuthUser[]>(
    `/orgs/${slug}/projects/${projectSlug}/auth/users`,
    `/organizations/${slug}/projects`,
  );

export const getProjectAuthSettings = (slug: string, projectSlug: string) =>
  load<ProjectOAuthSettings>(
    `/orgs/${slug}/projects/${projectSlug}/auth/settings`,
    `/organizations/${slug}/projects`,
  );
