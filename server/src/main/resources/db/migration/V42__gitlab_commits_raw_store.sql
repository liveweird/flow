-- V42: the GitLab commits raw store (gitlab-integration.md "Schema", PR 7): the default-branch commits of the live projects and the
-- per-file line counts of each non-merge commit. A commit is immutable by SHA, so there is no payload or hash: a re-listing only touches
-- last_seen_at (and clears a tombstone). files_fetched_at null = the commit's /diff has not been read (the COMMIT FILES backlog, newest
-- first; merge commits, parent_count > 1, never enter it); set with files_error null = its file rows are complete (files_changed = their
-- count; files_capped = the 1,000-file cap cut them); files_error names a terminal failure (LIMIT_EXCEEDED: a diff page over
-- gitlab.maxResponseBytes; NOT_FOUND: the commit is gone) and files_changed stays null - the commit's stats are then all it has.
-- deleted_at = tombstoned by a force-push re-list (RECONCILE's too, PR 9). needs_processing/processed_at/processing_version are PROCESS's
-- (PR 10), the raw.jira_issues shape: a files flush and a tombstone set needs_processing again.
CREATE TABLE raw.gitlab_commits (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    project_id BIGINT NOT NULL,
    sha VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    author_name VARCHAR(255) NOT NULL,
    author_email VARCHAR(255) NOT NULL,
    authored_at BIGINT NOT NULL,
    committed_at BIGINT NOT NULL,
    parent_count SMALLINT NOT NULL,
    additions INTEGER,
    deletions INTEGER,
    files_changed INTEGER,
    files_capped BOOLEAN NOT NULL DEFAULT FALSE,
    files_fetched_at BIGINT,
    files_error VARCHAR(40),
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    deleted_at BIGINT,
    needs_processing BOOLEAN NOT NULL DEFAULT TRUE,
    processed_at BIGINT,
    processing_version INTEGER,
    PRIMARY KEY (connection_id, project_id, sha)
);
-- The COMMIT FILES backlog: pending non-merge live commits, newest first.
CREATE INDEX idx_raw_gitlab_commits_files_backlog ON raw.gitlab_commits (connection_id, committed_at DESC)
    WHERE files_fetched_at IS NULL AND parent_count <= 1 AND deleted_at IS NULL;
-- The COMMITS stream's per-project reads: the oldest live commit (the re-list window) and the not-seen sweep.
CREATE INDEX idx_raw_gitlab_commits_project_live ON raw.gitlab_commits (connection_id, project_id, committed_at) WHERE deleted_at IS NULL;
-- PROCESS's claim scan (PR 10), per connection.
CREATE INDEX idx_raw_gitlab_commits_needs_processing ON raw.gitlab_commits (connection_id) WHERE needs_processing;

-- One row per file of a commit's /diff (new_path is the key; old_path only for a rename). additions/deletions null = GitLab sent the
-- file too_large or collapsed (no hunks to count); a binary file counts 0/0. generated_file is GitLab's own linguist-generated flag.
-- Cascades with its commit: PURGE deletes commits, the files go with them; a tombstone deletes nothing.
CREATE TABLE raw.gitlab_commit_files (
    connection_id INTEGER NOT NULL,
    project_id BIGINT NOT NULL,
    sha VARCHAR(64) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    old_path VARCHAR(1024),
    additions INTEGER,
    deletions INTEGER,
    new_file BOOLEAN NOT NULL,
    deleted_file BOOLEAN NOT NULL,
    renamed_file BOOLEAN NOT NULL,
    generated_file BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (connection_id, project_id, sha, path),
    FOREIGN KEY (connection_id, project_id, sha) REFERENCES raw.gitlab_commits (connection_id, project_id, sha) ON DELETE CASCADE
);

-- The stored head was CHAR(40) in V41; a SHA-256 repository (GitLab, opt-in) has 64-hex ids. Small table, immediate rewrite.
ALTER TABLE raw.gitlab_projects ALTER COLUMN commits_head_sha TYPE VARCHAR(64);

-- The re-list epoch (epoch millis): set when a RELIST begins (relist_requested turns true) and kept until the flag clears. The sweep's candidates are
-- the live commits NOT seen since it, so a candidate the sweep keeps (touched, last_seen_at >= the epoch) never qualifies again within the same re-list
-- and repeated runs converge; a per-run pass start would re-qualify them every run.
ALTER TABLE raw.gitlab_projects ADD COLUMN relist_started_at BIGINT;
