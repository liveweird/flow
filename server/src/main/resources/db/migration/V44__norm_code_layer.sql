-- V44: the normalized CODE layer (gitlab-integration.md "PROCESS / PROFILE" and "Schema", PR 10): the connector-agnostic facts GitLab's
-- PROCESS step rebuilds from raw.gitlab_* - projects, identities, commits, merge requests and their review events. Same schema (norm) and
-- idiom as V13: every table is schema-qualified and references public.source_connections, times are epoch-millis BIGINT, the codes
-- (identity kind, event kind, details state) are Kotlin-enum-whitelisted (norm/CodeNormalization.kt) and NOT CHECK-constrained (they drive
-- no SQL-level behaviour), and there is NO foreign key between two norm tables (V13 has none): a REPLACE deletes the children explicitly.
-- Nothing here holds code, a diff, a description or a note body (decision 9), and no key holds an email (decision 10).
-- The persistence side (keys, REPLACE semantics, PURGE order) is in persistence.md "The normalized code layer (V44)".
--
-- Two ways a table is written. code_projects and code_identities are the REFERENCE rows: rebuilt WHOLESALE per connection on every PROCESS
-- run (delete the connection's rows, insert the current set), like norm.statuses / norm.people. code_commits and code_merge_requests are the
-- FACTS: one row per raw row, upserted on the raw primary key when the raw row is flagged needs_processing (a tombstone is MIRRORED in
-- deleted_at, never deleted); code_mr_events is REPLACED per merge request on every PROCESS of that merge request.
--
-- Every *_counted column is the line count AFTER the data source's path exclusions (PROCESS applies them, never the fetch). A NULL count means
-- UNKNOWN - the commit's files are still pending or terminally unavailable, or the commit is a merge (no diff is read), or (additions /
-- deletions only) an included file has unknown lines (too large, collapsed) while GitLab's stats are missing, so files_counted is set but
-- the counted additions / deletions are NULL - and is NEVER zero.
-- additions / deletions / files stay beside them as GitLab reported them (stats over ALL files). files_capped = the commit has more files
-- than were stored (the first 1,000), so the counted columns cover the stored files only.
-- processed_at / processing_version are this row's own bookkeeping (CODE_PROCESSING_VERSION, separate from Jira's PROCESSING_VERSION), written
-- in the SAME transaction as the raw row's marks. Purely additive: an old pod never reads these tables.

-- One row per project of the connection (every raw.gitlab_projects row, the moved-out and deleted markers mirrored).
CREATE TABLE norm.code_projects (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    project_id BIGINT NOT NULL,
    path_with_namespace TEXT NOT NULL,
    group_path TEXT NOT NULL,
    default_branch TEXT,
    archived BOOLEAN NOT NULL,
    moved_out_at BIGINT,
    deleted_at BIGINT,
    PRIMARY KEY (connection_id, project_id)
);

-- One row per person-key a fact can name. identity_key is "u<gitlab user id>" (kind USER) or "e<first 32 hex of sha256(canonical email)>"
-- (kind EMAIL: a commit author matching no GitLab user) - the key itself never holds an email. emails keeps what raw.gitlab_users holds
-- (USER) or the one canonical address (EMAIL), so the identity page can show an admin what to decide on; it is purged with the connection.
-- A USER row with NULL username / display_name / state is a ghost: a user id the facts name that has no raw.gitlab_users row.
CREATE TABLE norm.code_identities (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    identity_key VARCHAR(40) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    user_id BIGINT,
    username TEXT,
    display_name TEXT,
    state VARCHAR(30),
    bot BOOLEAN NOT NULL DEFAULT FALSE,
    emails JSONB NOT NULL DEFAULT '[]',
    deleted_at BIGINT,
    PRIMARY KEY (connection_id, identity_key)
);

-- One row per raw.gitlab_commits row (the same primary key). identity_key is the resolved author. is_merge = more than one parent (a merge
-- commit carries no counted lines). title is the raw store's commit title (never the message body).
CREATE TABLE norm.code_commits (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    project_id BIGINT NOT NULL,
    sha VARCHAR(64) NOT NULL,
    identity_key VARCHAR(40) NOT NULL,
    title TEXT NOT NULL,
    authored_at BIGINT NOT NULL,
    committed_at BIGINT NOT NULL,
    is_merge BOOLEAN NOT NULL,
    additions INTEGER,
    deletions INTEGER,
    files INTEGER,
    additions_counted INTEGER,
    deletions_counted INTEGER,
    files_counted INTEGER,
    files_capped BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at BIGINT,
    processed_at BIGINT NOT NULL,
    processing_version INTEGER NOT NULL,
    PRIMARY KEY (connection_id, project_id, sha)
);
-- The per-person commit reads of the reports and the identity page (live commits only).
CREATE INDEX idx_norm_code_commits_identity ON norm.code_commits (connection_id, identity_key, committed_at) WHERE deleted_at IS NULL;

-- One row per raw.gitlab_merge_requests row (the same primary key). author_key / merge_user_key are "u<id>" keys; reviewer_keys /
-- assignee_keys are sorted JSON arrays of them. additions / deletions / file_count are GitLab's diff stats (NULL = a gap, and the line counts
-- are NOT exclusion-aware). details_state says whether the merge request's events are current: READ (complete), PENDING (the details read is
-- stale, so the event set is empty or old) or UNREADABLE (a terminal details error) - a report reads the state, never "no events".
CREATE TABLE norm.code_merge_requests (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    mr_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    iid BIGINT NOT NULL,
    state VARCHAR(30) NOT NULL,
    draft BOOLEAN NOT NULL,
    author_key VARCHAR(40),
    merge_user_key VARCHAR(40),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    merged_at BIGINT,
    closed_at BIGINT,
    source_branch TEXT NOT NULL,
    target_branch TEXT NOT NULL,
    title TEXT NOT NULL,
    head_sha VARCHAR(64),
    merge_commit_sha VARCHAR(64),
    squash_commit_sha VARCHAR(64),
    reviewer_keys JSONB NOT NULL DEFAULT '[]',
    assignee_keys JSONB NOT NULL DEFAULT '[]',
    additions INTEGER,
    deletions INTEGER,
    file_count INTEGER,
    notes_capped BOOLEAN NOT NULL DEFAULT FALSE,
    details_state VARCHAR(12) NOT NULL,
    deleted_at BIGINT,
    processed_at BIGINT NOT NULL,
    processing_version INTEGER NOT NULL,
    PRIMARY KEY (connection_id, mr_id)
);
-- An iid is unique within its project (the raw table's rule); PR 11's links and the reports read a merge request by it.
CREATE UNIQUE INDEX idx_norm_code_mrs_project_iid ON norm.code_merge_requests (connection_id, project_id, iid);
-- The per-author merge request reads of the reports (live merge requests only). reviewer_keys / assignee_keys carry no index: a report that
-- needs one adds it.
CREATE INDEX idx_norm_code_mrs_author ON norm.code_merge_requests (connection_id, author_key, created_at) WHERE deleted_at IS NULL;

-- The review events of a merge request, in a deterministic order (seq = 1..n per merge request: event time ascending with nulls last, then
-- kind, actor, raw source, raw source id). seq is the position in the CURRENT event set of the merge request, renumbered on every PROCESS of
-- it: never reference an event by (mr_id, seq) across runs. kind is one of COMMENT, DIFF_COMMENT, APPROVAL, REVIEW_REQUESTED, REVIEWED, CHANGES_REQUESTED.
-- event_at NULL = the source carries no time (the legacy approvals endpoint). target_keys = the people a REVIEW_REQUESTED event asked, as
-- "u<id>" keys (NULL for every other kind, and for a REVIEW_REQUESTED whose usernames all failed to resolve). A note's body is never stored.
-- No surrogate id: the composite key only orders the current set.
CREATE TABLE norm.code_mr_events (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    mr_id BIGINT NOT NULL,
    seq INTEGER NOT NULL,
    kind VARCHAR(20) NOT NULL,
    actor_key VARCHAR(40),
    event_at BIGINT,
    target_keys JSONB,
    PRIMARY KEY (connection_id, mr_id, seq)
);
-- The per-person review reads (live merge requests are filtered by joining code_merge_requests).
CREATE INDEX idx_norm_code_mr_events_actor ON norm.code_mr_events (connection_id, actor_key, event_at);
