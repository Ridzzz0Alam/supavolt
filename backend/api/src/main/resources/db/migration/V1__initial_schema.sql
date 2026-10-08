-- Control-plane schema. This is the end state of the three EF Core migrations the .NET API had
-- (InitialSchema, InviteTokenHash, ProjectDbRole), so a database those created is already at V1:
-- Flyway baselines it at version 1 instead of running this file (spring.flyway.baseline-on-migrate).

CREATE TABLE organizations (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    slug       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_organizations PRIMARY KEY (id)
);

CREATE TABLE users (
    id            uuid        NOT NULL DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    name          text,
    avatar_url    text,
    password_hash text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id)
);

CREATE TABLE invites (
    id                 uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id             uuid        NOT NULL,
    email              text        NOT NULL,
    invited_by_user_id uuid        NOT NULL,
    token_hash         text        NOT NULL DEFAULT '',
    expires_at         timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    accepted_at        timestamptz,
    revoked_at         timestamptz,
    CONSTRAINT pk_invites PRIMARY KEY (id),
    CONSTRAINT fk_invites_organizations_org_id FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE
);

CREATE TABLE projects (
    id                    uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id                uuid        NOT NULL,
    name                  text        NOT NULL,
    slug                  text        NOT NULL,
    db_schema             text        NOT NULL,
    project_url           text        NOT NULL,
    anon_key              text        NOT NULL,
    service_role_key_hash text        NOT NULL,
    key_version           integer     NOT NULL,
    auth_jwt_secret       text        NOT NULL,
    db_role_password      text,
    site_url              text,
    redirect_urls         jsonb       NOT NULL,
    google_client_id      text,
    google_client_secret  text,
    github_client_id      text,
    github_client_secret  text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT fk_projects_organizations_org_id FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE
);

CREATE TABLE org_members (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    org_id     uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    role       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    removed_at timestamptz,
    CONSTRAINT pk_org_members PRIMARY KEY (id),
    CONSTRAINT fk_org_members_organizations_org_id FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_org_members_users_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE refresh_tokens (
    id                   uuid        NOT NULL DEFAULT gen_random_uuid(),
    user_id              uuid        NOT NULL,
    token_hash           text        NOT NULL,
    expires_at           timestamptz NOT NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    revoked_at           timestamptz,
    replaced_by_token_id uuid,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_users_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE project_auth_tokens (
    id          uuid        NOT NULL DEFAULT gen_random_uuid(),
    project_id  uuid        NOT NULL,
    token_hash  text        NOT NULL,
    email       text        NOT NULL,
    purpose     text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_project_auth_tokens PRIMARY KEY (id),
    CONSTRAINT fk_project_auth_tokens_projects_project_id FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE query_history (
    id                uuid        NOT NULL DEFAULT gen_random_uuid(),
    project_id        uuid        NOT NULL,
    sql               text        NOT NULL,
    execution_time_ms integer     NOT NULL,
    row_count         bigint      NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_query_history PRIMARY KEY (id),
    CONSTRAINT fk_query_history_projects_project_id FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE storage_buckets (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    project_id uuid        NOT NULL,
    name       text        NOT NULL,
    access     text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_storage_buckets PRIMARY KEY (id),
    CONSTRAINT fk_storage_buckets_projects_project_id FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE storage_objects (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    bucket_id  uuid        NOT NULL,
    name       text        NOT NULL,
    size       bigint      NOT NULL,
    mime_type  text        NOT NULL,
    object_key text        NOT NULL,
    url        text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_storage_objects PRIMARY KEY (id),
    CONSTRAINT fk_storage_objects_storage_buckets_bucket_id FOREIGN KEY (bucket_id) REFERENCES storage_buckets (id) ON DELETE CASCADE
);

CREATE INDEX ix_invites_org_id_email ON invites (org_id, email);
CREATE UNIQUE INDEX ix_invites_token_hash ON invites (token_hash);
CREATE INDEX ix_org_members_org_id_user_id ON org_members (org_id, user_id);
CREATE INDEX ix_org_members_user_id ON org_members (user_id);
CREATE UNIQUE INDEX ix_organizations_slug ON organizations (slug);
CREATE INDEX ix_project_auth_tokens_project_id ON project_auth_tokens (project_id);
CREATE UNIQUE INDEX ix_project_auth_tokens_token_hash ON project_auth_tokens (token_hash);
CREATE UNIQUE INDEX ix_projects_db_schema ON projects (db_schema);
CREATE INDEX ix_projects_org_id ON projects (org_id);
CREATE UNIQUE INDEX ix_projects_slug ON projects (slug);
CREATE INDEX ix_query_history_project_id_created_at ON query_history (project_id, created_at);
CREATE UNIQUE INDEX ix_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE UNIQUE INDEX ix_storage_buckets_project_id_name ON storage_buckets (project_id, name);
CREATE INDEX ix_storage_objects_bucket_id ON storage_objects (bucket_id);
CREATE UNIQUE INDEX ix_users_email ON users (email);

-- Case-insensitive uniqueness: the original allowed Alice@x.com and alice@x.com to coexist.
CREATE UNIQUE INDEX ix_users_email_lower ON users (lower(email));
