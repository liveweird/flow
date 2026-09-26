-- The Jira raw store (v0.2.0 plan §4/§7, V10): the FIRST tables in the `raw` schema (plan §0 A3) —
-- connector-raw data, keeping the connector prefix (`jira_`), distinct from `public` (foundation +
-- operational tables) and the `norm` schema arriving with the normalized layer (V12). Every name is
-- schema-qualified; the FK back to `source_connections` stays explicit about living in `public`.
CREATE SCHEMA IF NOT EXISTS raw;

-- One row per (connection, Jira numeric issue id) — the REFERENCE/ISSUES streams' target
-- (ingest/Stream.kt, jira/JiraReferenceStream.kt, jira/JiraIssuesStream.kt). `payload` is the
-- canonicalized `search/jql` issue document (infra/json/CanonicalJson.kt) with its sha256; a page
-- re-fetch that hashes identically only bumps `fetched_at` (jira/JiraRawStore.kt's diff rule), so
-- `changed_at` tracks genuine content changes only. `changelog_synced_at`/`worklogs_synced_at`
-- are populated by the CHANGELOGS/WORKLOGS streams landing in plan commit 7 (A1) — NULL here means
-- "never synced", the seed state a fresh backfilled row starts in. `needs_processing` drives the
-- PROCESS step (plan commit 8); `deleted_at`/`moved_out_at` are the RECONCILE stream's tombstones
-- (plan commit 7) — resurrected (cleared) if the issue is seen again by a later ISSUES page.
CREATE TABLE raw.jira_issues (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    project_id BIGINT NOT NULL,
    project_key VARCHAR(20) NOT NULL,
    issue_updated_at BIGINT NOT NULL,
    payload JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    fetched_at BIGINT NOT NULL,
    changed_at BIGINT NOT NULL,
    changelog_synced_at BIGINT,
    worklogs_synced_at BIGINT,
    needs_processing BOOLEAN NOT NULL DEFAULT TRUE,
    processed_at BIGINT,
    processed_hash CHAR(64),
    processing_version INTEGER,
    deleted_at BIGINT,
    moved_out_at BIGINT,
    PRIMARY KEY (connection_id, issue_id)
);

-- The PROCESS step's claim scan (plan commit 8): rows still needing a rebuild, per connection.
CREATE INDEX idx_raw_jira_issues_needs_processing ON raw.jira_issues (connection_id) WHERE needs_processing;

-- The CHANGELOGS stream's claim scan (plan commit 7): in-scope (never tombstoned) issues whose
-- history has never been synced.
CREATE INDEX idx_raw_jira_issues_stale_changelog
    ON raw.jira_issues (connection_id)
    WHERE changelog_synced_at IS NULL AND deleted_at IS NULL;

-- The WORKLOGS stream's per-issue backfill scan (plan commit 7, A1): in-scope issues whose
-- worklogs have never been synced.
CREATE INDEX idx_raw_jira_issues_stale_worklogs
    ON raw.jira_issues (connection_id)
    WHERE worklogs_synced_at IS NULL AND deleted_at IS NULL;

-- Every OTHER Jira reference-data kind the REFERENCE stream tracks (jira/JiraReferenceStream.kt):
-- fields, statuses, status categories, projects, per-project statuses, issue types, priorities,
-- resolutions, issue link types, users, boards, board configuration, board sprints. `kind` is the
-- Kotlin JiraEntityKind enum's whitelist (no CHECK — it drives no cross-cutting SQL behavior, the
-- `users.role`/`sync_jobs.status` idiom is reserved for columns a CHECK constraint can usefully
-- pin). One row per (connection, kind, entity id) — `entity_id` is TEXT-shaped (VARCHAR) because
-- Jira ids are numeric for most kinds but an opaque accountId string for USER.
CREATE TABLE raw.jira_entities (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    kind VARCHAR(30) NOT NULL,
    entity_id VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    changed_at BIGINT NOT NULL,
    deleted_at BIGINT,
    PRIMARY KEY (connection_id, kind, entity_id)
);

-- The REFERENCE stream's end-of-pass tombstone sweep: entities of a given kind not seen since the
-- pass started.
CREATE INDEX idx_raw_jira_entities_last_seen ON raw.jira_entities (connection_id, kind, last_seen_at);
