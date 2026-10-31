-- V41: the GitLab raw store (gitlab-integration.md "Schema", PR 6) — the configured groups' live projects and the
-- instance's users, slim payloads (a projection, never the full document) canonicalized + sha256-hashed like raw.jira_*.
-- group_path/group_id are the CONFIGURED group a project was listed under; moved_out_at = not seen under any configured
-- group in a completed pass (cleared when seen again); deleted_at is RECONCILE's (PR 9); commits_head_sha and
-- relist_requested are the COMMITS stream's (PR 7), null/false until then.
CREATE TABLE raw.gitlab_projects (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    project_id BIGINT NOT NULL,
    path_with_namespace VARCHAR(1024) NOT NULL,
    group_path VARCHAR(255) NOT NULL,
    group_id BIGINT NOT NULL,
    default_branch VARCHAR(255),
    archived BOOLEAN NOT NULL,
    payload JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    changed_at BIGINT NOT NULL,
    moved_out_at BIGINT,
    deleted_at BIGINT,
    commits_head_sha CHAR(40),
    relist_requested BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (connection_id, project_id)
);
CREATE INDEX idx_raw_gitlab_projects_last_seen ON raw.gitlab_projects (connection_id, last_seen_at);

-- emails: the sorted, distinct, lowercased union of email/commit_email/public_email and the confirmed secondary emails
-- (/users/:id/emails, admin token) — the identity proposals' input (PR 11); also inside payload, so one hash covers both.
CREATE TABLE raw.gitlab_users (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    user_id BIGINT NOT NULL,
    username VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    state VARCHAR(30) NOT NULL,
    bot BOOLEAN NOT NULL,
    emails JSONB NOT NULL,
    payload JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    changed_at BIGINT NOT NULL,
    deleted_at BIGINT,
    PRIMARY KEY (connection_id, user_id)
);
CREATE INDEX idx_raw_gitlab_users_last_seen ON raw.gitlab_users (connection_id, last_seen_at);
