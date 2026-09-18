/**
 * Hand-mirrored from backend/src/Supavolt.Contracts/Contracts.cs.
 *
 * The API serialises camelCase with string enums, so these line up field for field. If you would
 * rather generate this, the API exposes an OpenAPI document at /api/openapi/v1.json in
 * development — point openapi-typescript at it and delete this file.
 */

export type OrgRole = 'developer' | 'admin';
export type ProjectKeyRole = 'anon' | 'serviceRole';
export type BucketAccess = 'public' | 'private';
export type AuthProvider = 'email' | 'google' | 'github';

export type ColumnType =
  | 'text'
  | 'integer'
  | 'bigint'
  | 'boolean'
  | 'timestamp'
  | 'uuid'
  | 'jsonb'
  | 'numeric';

export const COLUMN_TYPES: ColumnType[] = [
  'text',
  'integer',
  'bigint',
  'boolean',
  'timestamp',
  'uuid',
  'jsonb',
  'numeric',
];

// ─── Auth ─────────────────────────────────────────────────────────────────────

export interface CurrentUser {
  id: string;
  email: string;
  name: string | null;
  avatarUrl: string | null;
}

// ─── Organizations and members ────────────────────────────────────────────────

export interface Organization {
  id: string;
  name: string;
  slug: string;
  role: OrgRole;
  projectCount: number;
  memberCount: number;
  createdAt: string;
}

export interface OrgMember {
  id: string;
  role: OrgRole;
  createdAt: string;
  user: {
    id: string;
    name: string | null;
    email: string;
    avatarUrl: string | null;
  };
}

// ─── Projects ─────────────────────────────────────────────────────────────────

export interface Project {
  id: string;
  orgId: string;
  name: string;
  slug: string;
  dbSchema: string;
  projectUrl: string;
  anonKey: string;
  createdAt: string;
  updatedAt: string;
}

/** Returned once, at creation or rotation. The service role key is never readable again. */
export interface ProjectKeys {
  anonKey: string;
  serviceRoleKey: string;
  keyVersion: number;
}

// ─── Table editor ─────────────────────────────────────────────────────────────

export interface TableColumn {
  name: string;
  type: ColumnType;
  isNullable: boolean;
  isPrimaryKey: boolean;
  defaultValue: string | null;
  foreignKey: { table: string; column: string } | null;
}

export interface TableInfo {
  name: string;
  columns: TableColumn[];
}

export interface TableRows {
  rows: Record<string, unknown>[];
  count: number;
}

export interface CreateColumnInput {
  name: string;
  type: ColumnType;
  isNullable: boolean;
  isPrimaryKey: boolean;
  defaultValue?: string;
  foreignKeyTable?: string;
  foreignKeyColumn?: string;
}

// ─── SQL editor ───────────────────────────────────────────────────────────────

export interface QueryResult {
  rows: Record<string, unknown>[];
  columns: string[];
  rowCount: number;
  executionTimeMs: number;
  command: string;
}

export interface QueryHistoryItem {
  id: string;
  sql: string;
  executionTimeMs: number;
  rowCount: number;
  createdAt: string;
}

// ─── Storage ──────────────────────────────────────────────────────────────────

export interface StorageBucket {
  id: string;
  projectId: string;
  name: string;
  access: BucketAccess;
  createdAt: string;
}

export interface StorageObject {
  id: string;
  bucketId: string;
  name: string;
  size: number;
  mimeType: string;
  objectKey: string;
  url: string;
  createdAt: string;
}

export interface UploadUrl {
  uploadUrl: string;
  objectKey: string;
  expiresAt: string;
}

// ─── Project (end-user) auth ──────────────────────────────────────────────────

export interface ProjectAuthUser {
  id: string;
  email: string;
  emailVerified: boolean;
  provider: AuthProvider;
  createdAt: string;
}

export interface ProjectOAuthSettings {
  siteUrl: string | null;
  googleClientId: string | null;
  googleConfigured: boolean;
  githubClientId: string | null;
  githubConfigured: boolean;
  redirectUrls: string[];
}
