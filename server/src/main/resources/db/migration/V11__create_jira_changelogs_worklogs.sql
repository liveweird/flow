-- The Jira changelog/worklog raw stores (v0.2.0 plan §4/§7 V11, A1): joins raw.jira_issues/
-- raw.jira_entities (V10) in the `raw` schema (plan §0 A3), the CHANGELOGS/WORKLOGS streams'
-- target (jira/JiraChangelogStream.kt, jira/JiraWorklogStream.kt).

-- One row per (connection, Jira history id) — APPEND-ONLY: a changelog history is immutable once
-- Jira creates it, so there is no diff/tombstone rule here (unlike raw.jira_issues/raw.jira_entities)
-- — `jira/JiraRawStore.kt`'s insertChangelog is a plain `ON CONFLICT DO NOTHING` keyed by the PK,
-- safe to re-run after a crash (both the bulkfetch batch path and the per-issue fallback path dedup
-- the same way). `payload` is the full history object exactly as Jira returned it (bulkfetch's
-- `changeHistories[]` entry, or the per-issue fallback's `histories[]` entry — same shape either
-- way), canonicalized (infra/json/CanonicalJson.kt). History ids are numeric and globally unique on
-- a real Jira instance, but the PK stays scoped to `connection_id` like every other raw table here.
CREATE TABLE raw.jira_changelogs (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    history_id BIGINT NOT NULL,
    issue_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    author_account_id VARCHAR(100),
    payload JSONB NOT NULL,
    fetched_at BIGINT NOT NULL,
    PRIMARY KEY (connection_id, history_id)
);

-- The normalization layer's future per-issue changelog replay (plan §8) and the pipeline test's own
-- per-issue assertions: every history for one issue, oldest first.
CREATE INDEX idx_raw_jira_changelogs_issue ON raw.jira_changelogs (connection_id, issue_id, created_at);

-- One row per (connection, Jira worklog id) — IN-SCOPE ISSUES ONLY (plan §0 A1): the architect's
-- original design would have kept every worklog the instance-wide `/worklog/updated` feed ever
-- returns, sweeping every OTHER unit's time tracking into Flow back to `backfill_from`. This table
-- only ever gets a row the WORKLOGS stream (jira/JiraWorklogStream.kt) has already checked against a
-- known, non-tombstoned raw.jira_issues row for THIS connection — everything else is dropped before
-- any write. Same sha256 diff/tombstone rule as raw.jira_issues/raw.jira_entities
-- (jira/JiraRawStore.kt's upsertWorklog/tombstoneWorklog): a worklog CAN be edited after creation
-- (time-spent corrections), unlike a changelog history, so it needs the same "changed_at only moves
-- on a genuine content change" rule the issue/entity tables use.
CREATE TABLE raw.jira_worklogs (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    worklog_id BIGINT NOT NULL,
    issue_id BIGINT NOT NULL,
    worklog_updated_at BIGINT NOT NULL,
    payload JSONB NOT NULL,
    sha256 CHAR(64) NOT NULL,
    first_seen_at BIGINT NOT NULL,
    fetched_at BIGINT NOT NULL,
    changed_at BIGINT NOT NULL,
    deleted_at BIGINT,
    PRIMARY KEY (connection_id, worklog_id)
);

-- The WORKLOGS stream's own per-issue read path and the normalization layer's future per-issue
-- worklog replay (plan §8): every worklog for one issue.
CREATE INDEX idx_raw_jira_worklogs_issue ON raw.jira_worklogs (connection_id, issue_id);
