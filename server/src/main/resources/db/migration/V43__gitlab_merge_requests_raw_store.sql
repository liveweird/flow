-- V43: the GitLab merge-request raw store (gitlab-integration.md "Schema", PR 8): the merge requests of the configured groups' projects and
-- their review activity. A merge request is MUTABLE, so it carries a slim payload (a projection: no description, labels, URLs or merge
-- status) canonicalized + sha256-hashed like raw.gitlab_projects; the hash decides "changed" and moves changed_at. The queryable columns
-- duplicate the payload's fields (the V41 shape). details_synced_at is the MR DETAILS stamp: null, or older than changed_at, = the notes,
-- approvals, reviewers and GraphQL diff stats are stale (the backlog, newest updated_at first); details_error names a terminal failure
-- (NOT_FOUND: the notes answered 404 - the MR is gone or invisible, RECONCILE (PR 9) decides; LIMIT_EXCEEDED: a notes page over
-- gitlab.maxResponseBytes). additions/deletions/file_count are GraphQL's diffStatsSummary, null = not asked yet or a gap (a GraphQL error
-- is never a job failure). notes_capped = the 1,000-note cap cut the events. deleted_at is RECONCILE's (PR 9); nothing here tombstones.
-- needs_processing/processed_at/processing_version are PROCESS's (PR 10), the raw.jira_issues shape: a change and a details stamp set it.
CREATE TABLE raw.gitlab_merge_requests (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    mr_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    iid BIGINT NOT NULL,
    state VARCHAR(30) NOT NULL,
    draft BOOLEAN NOT NULL,
    author_id BIGINT,
    merge_user_id BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    merged_at BIGINT,
    closed_at BIGINT,
    source_branch VARCHAR(255) NOT NULL,
    target_branch VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    head_sha VARCHAR(64),
    merge_commit_sha VARCHAR(64),
    squash_commit_sha VARCHAR(64),
    reviewer_ids JSONB NOT NULL,
    assignee_ids JSONB NOT NULL,
    user_notes_count INTEGER,
    additions INTEGER,
    deletions INTEGER,
    file_count INTEGER,
    notes_capped BOOLEAN NOT NULL DEFAULT FALSE,
    details_synced_at BIGINT,
    details_error VARCHAR(40),
    payload JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    changed_at BIGINT NOT NULL,
    deleted_at BIGINT,
    needs_processing BOOLEAN NOT NULL DEFAULT TRUE,
    processed_at BIGINT,
    processing_version INTEGER,
    PRIMARY KEY (connection_id, mr_id)
);
-- An iid is unique within its project: the GraphQL answer (iid -> row) and RECONCILE's per-project re-list (PR 9) read by it.
CREATE UNIQUE INDEX idx_raw_gitlab_mrs_project_iid ON raw.gitlab_merge_requests (connection_id, project_id, iid);
-- The MR DETAILS backlog, newest first. The predicate has no constant, so a generic plan matches it (the V42 literal lesson does not apply).
CREATE INDEX idx_raw_gitlab_mrs_details_backlog ON raw.gitlab_merge_requests (connection_id, updated_at DESC)
    WHERE deleted_at IS NULL AND (details_synced_at IS NULL OR details_synced_at < changed_at);
-- PROCESS's claim scan (PR 10), per connection.
CREATE INDEX idx_raw_gitlab_mrs_needs_processing ON raw.gitlab_merge_requests (connection_id) WHERE needs_processing;

-- One row per note (source NOTE, source_id = the note id), per approver of the legacy /approvals object (APPROVAL, source_id = the user id,
-- event_at null: the endpoint carries no time) and per entry of /reviewers (REVIEWER, source_id = the user id, action = the state). A note's
-- body is NEVER stored: a system note is reduced to an action code (APPROVAL, UNAPPROVAL, CHANGES_REQUESTED, REVIEW_REQUESTED,
-- REVIEW_REQUEST_REMOVED, COMMITS_ADDED, MERGED, CLOSED, REOPENED, DRAFT, READY, OTHER_SYSTEM) and a user note to COMMENT or DIFF_COMMENT;
-- targets holds only the @usernames a review-request note names. The set is REPLACED whenever the merge request's details are refreshed.
-- Cascades with its merge request: PURGE deletes merge requests, the events go with them.
CREATE TABLE raw.gitlab_mr_events (
    connection_id INTEGER NOT NULL,
    mr_id BIGINT NOT NULL,
    source VARCHAR(10) NOT NULL,
    source_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    actor_id BIGINT,
    event_at BIGINT,
    system BOOLEAN NOT NULL,
    note_type VARCHAR(30),
    action VARCHAR(40) NOT NULL,
    targets JSONB,
    PRIMARY KEY (connection_id, mr_id, source, source_id),
    FOREIGN KEY (connection_id, mr_id) REFERENCES raw.gitlab_merge_requests (connection_id, mr_id) ON DELETE CASCADE
);
