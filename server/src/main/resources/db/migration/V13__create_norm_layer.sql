-- The neutral normalized layer (v0.2.0 plan §0 A3/§4/§8, plan commit 8a): the FIRST tables in the
-- `norm` schema — source-agnostic facts every connector's PROCESS step rebuilds a work item's rows
-- into, one issue at a time, keyed the same way `raw.jira_issues` is ((connection_id, issue_id),
-- the STABLE Jira numeric id). `norm/WorkItemStore.kt` is the Exposed table set;
-- `norm/Normalization.kt`/`jira/JiraNormalizer.kt`/`jira/JiraProcessStream.kt` are the PROCESS
-- step's write path. Every table is schema-qualified and FKs back to `public.source_connections`,
-- the same shape `raw.jira_issues`/`raw.jira_entities` (V10) established.
CREATE SCHEMA IF NOT EXISTS norm;

-- One row per (connection, issue id) — the REPLACE target of one issue's PROCESS pass
-- (`norm/WorkItemStore.kt`'s `replaceWorkItem`): every column here is the CURRENT snapshot only —
-- history lives in the interval/change tables below, never here. `status_category` is one of
-- `TODO|IN_PROGRESS|DONE|UNKNOWN` (`norm/Tiling.kt`'s `StatusCategory`, Kotlin-enum-whitelisted,
-- not CHECK-constrained — the `raw.jira_entities.kind` idiom: it drives no SQL-level behavior).
-- `anomalies` is a JSONB array of anomaly codes `norm/Tiling.kt` flagged while tiling this issue's
-- status history (never "fixed" — flagged only, plan §8). `deleted_at`/`moved_out_at` mirror
-- `raw.jira_issues`' own tombstones (a RECONCILE tombstone is carried into the next PROCESS pass
-- via `needs_processing`). `processed_at`/`processing_version` are this row's own bookkeeping,
-- separate from `raw.jira_issues.processed_at`/`processing_version` (the raw row's processing
-- pointer) — both move together, written in the SAME transaction.
CREATE TABLE norm.work_items (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    project_key VARCHAR(20) NOT NULL,
    issue_type VARCHAR(50) NOT NULL,
    is_subtask BOOLEAN NOT NULL DEFAULT FALSE,
    parent_issue_id BIGINT,
    summary TEXT,
    status_id VARCHAR(50) NOT NULL,
    status_name VARCHAR(100) NOT NULL,
    status_category VARCHAR(20) NOT NULL,
    resolution VARCHAR(100),
    priority VARCHAR(50),
    assignee_account_id VARCHAR(100),
    reporter_account_id VARCHAR(100),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    resolved_at BIGINT,
    story_points DOUBLE PRECISION,
    original_estimate_seconds BIGINT,
    time_spent_seconds BIGINT NOT NULL DEFAULT 0,
    labels JSONB NOT NULL DEFAULT '[]',
    components JSONB NOT NULL DEFAULT '[]',
    fix_versions JSONB NOT NULL DEFAULT '[]',
    current_sprint_ids JSONB NOT NULL DEFAULT '[]',
    team_value JSONB,
    flagged BOOLEAN NOT NULL DEFAULT FALSE,
    rank VARCHAR(100),
    anomalies JSONB NOT NULL DEFAULT '[]',
    deleted_at BIGINT,
    moved_out_at BIGINT,
    processed_at BIGINT NOT NULL,
    processing_version INTEGER NOT NULL,
    PRIMARY KEY (connection_id, issue_id)
);

-- Status history, one row per tiled interval (`norm/Tiling.kt`'s `StatusInterval`, plan §8): the
-- first interval (`seq = 1`) always starts at `work_items.created_at` with `source = 'CREATED'`;
-- every later one is `'CHANGE'`. `to_at IS NULL` marks the one open interval a work item always has
-- (plan invariant: "exactly one interval is open"). `source` is Kotlin-enum-whitelisted
-- (`IntervalSource`), not CHECK-constrained, matching `raw.jira_entities.kind`'s idiom.
CREATE TABLE norm.work_item_status_intervals (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    seq INTEGER NOT NULL,
    status_id VARCHAR(50) NOT NULL,
    status_name VARCHAR(100) NOT NULL,
    status_category VARCHAR(20) NOT NULL,
    from_at BIGINT NOT NULL,
    to_at BIGINT,
    source VARCHAR(10) NOT NULL,
    UNIQUE (connection_id, issue_id, seq)
);
CREATE INDEX idx_norm_work_item_status_intervals_issue ON norm.work_item_status_intervals (connection_id, issue_id);

-- Field history for ASSIGNEE/SPRINT/FLAGGED, one row per tiled interval (`norm/Tiling.kt`'s
-- `FieldInterval`, plan §8). `field` is Kotlin-enum-whitelisted (`TrackedField`), not
-- CHECK-constrained. SPRINT's `value_id` is the LAST sprint id of a (possibly multi-valued)
-- carry-over set; `value_text` is the comma-joined sprint names Jira's own changelog `toString`
-- already carries (plan §8's "multi-valued sprint field" rule) — never recomputed from ids.
CREATE TABLE norm.work_item_field_intervals (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    field VARCHAR(20) NOT NULL,
    seq INTEGER NOT NULL,
    value_id VARCHAR(200),
    value_text VARCHAR(500),
    from_at BIGINT NOT NULL,
    to_at BIGINT,
    UNIQUE (connection_id, issue_id, field, seq)
);
CREATE INDEX idx_norm_work_item_field_intervals_issue ON norm.work_item_field_intervals (connection_id, issue_id, field);

-- Every tracked changelog item, kept verbatim (never tiled) — status, assignee, Sprint, Flagged,
-- Rank, priority, resolution, issuetype, project, Key and story points (plan §4). This is the raw
-- change feed the interval tables above are DERIVED from for the four tiled fields; the other
-- tracked fields (priority, resolution, issuetype, project, Key, story points, Rank) have no
-- interval table of their own — this is their only normalized record.
CREATE TABLE norm.work_item_field_changes (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    seq INTEGER NOT NULL,
    field VARCHAR(50) NOT NULL,
    changed_at BIGINT NOT NULL,
    from_value TEXT,
    from_text TEXT,
    to_value TEXT,
    to_text TEXT
);
CREATE INDEX idx_norm_work_item_field_changes_issue ON norm.work_item_field_changes (connection_id, issue_id);

-- One row per Jira worklog, mirrored from `raw.jira_worklogs` (already in-scope-filtered, A1) —
-- PROCESS's own copy, so a metrics query never has to join back into `raw`.
CREATE TABLE norm.work_item_worklogs (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    worklog_id BIGINT NOT NULL,
    issue_id BIGINT NOT NULL,
    author_account_id VARCHAR(100),
    started_at BIGINT NOT NULL,
    time_spent_seconds BIGINT NOT NULL,
    PRIMARY KEY (connection_id, worklog_id)
);
CREATE INDEX idx_norm_work_item_worklogs_issue ON norm.work_item_worklogs (connection_id, issue_id);

-- Reference rows, rebuilt WHOLESALE per connection on every PROCESS run (plan §8) — never
-- diffed/upserted row-by-row like the raw store's own reference data, since PROCESS already reads
-- the full current `raw.jira_entities` set every time it runs.
CREATE TABLE norm.statuses (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    status_id VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    category VARCHAR(20) NOT NULL,
    PRIMARY KEY (connection_id, status_id)
);

CREATE TABLE norm.people (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    account_id VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    email VARCHAR(254),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (connection_id, account_id)
);

CREATE TABLE norm.boards (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    board_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    board_type VARCHAR(20) NOT NULL,
    project_key VARCHAR(20),
    PRIMARY KEY (connection_id, board_id)
);

-- `status_ids` (plan §4) is the board column's mapped statuses, by id — a JSONB array rather than
-- a join table, since it is only ever read whole (the data profile's "unmapped statuses" section,
-- plan §8, arrives in plan commit 9).
CREATE TABLE norm.board_columns (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    board_id BIGINT NOT NULL,
    seq INTEGER NOT NULL,
    name VARCHAR(200) NOT NULL,
    status_ids JSONB NOT NULL DEFAULT '[]',
    PRIMARY KEY (connection_id, board_id, seq)
);

CREATE TABLE norm.sprints (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    board_id BIGINT,
    name VARCHAR(200) NOT NULL,
    state VARCHAR(20) NOT NULL,
    start_at BIGINT,
    end_at BIGINT,
    goal TEXT,
    PRIMARY KEY (connection_id, sprint_id)
);
