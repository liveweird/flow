-- The `metrics` schema — configuration (v0.3.0 M1 commit 3, `.claude/docs/domain-model.md`
-- "Configuration", the phase-3 implementation plan §4 "V15"). `metrics` interprets `norm`'s facts
-- under ONE global configuration revision (`metrics.settings.config_revision`, bumped inside
-- every config mutation's own transaction — global settings, per-connection maps, team membership
-- alike); every row the DERIVE job writes later stamps the revision it was built under.
--
-- btree_gist ships in postgres contrib (present in the official postgres:18-alpine image used by
-- compose, k8s and Testcontainers) and has been TRUSTED since PG13 — CREATE on the database
-- suffices, no superuser (the V4 unaccent idiom). It is needed here to let `team_membership`'s
-- EXCLUDE constraint use `=` on a non-range column (`account_id`) alongside the `int8range`
-- overlap operator in the SAME GiST index.
CREATE SCHEMA IF NOT EXISTS metrics;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- The ONE global settings row (`metrics/MetricsConfigService.kt`), enforced as a singleton by the
-- `id = 1` CHECK. `time_zone` defaults to `Europe/Warsaw` (main-session amendment A4 — the unit is
-- Polish; an admin can change it), not UTC. `hours_per_day` is a manual setting in v0.3.0 (A5) —
-- reading Jira's own time-tracking configuration is deferred to BACKLOG.md. `weekend_days` is a
-- JSONB array of ISO weekday numbers (1=Monday..7=Sunday); `holidays` a JSONB array of ISO dates;
-- `aging_percentiles` a JSONB array of percentile numbers. `updated_by_user_id` is nullable — the
-- seeded row has never been touched by an admin yet.
CREATE TABLE metrics.settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    config_revision BIGINT NOT NULL DEFAULT 1,
    hours_per_day NUMERIC(4, 2) NOT NULL DEFAULT 8 CHECK (hours_per_day > 0 AND hours_per_day <= 24),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Europe/Warsaw',
    weekend_days JSONB NOT NULL DEFAULT '[6,7]',
    holidays JSONB NOT NULL DEFAULT '[]',
    commitment_grace_minutes INTEGER NOT NULL DEFAULT 0 CHECK (commitment_grace_minutes >= 0),
    min_sample_size INTEGER NOT NULL DEFAULT 5 CHECK (min_sample_size >= 1),
    aging_window_items INTEGER NOT NULL DEFAULT 50 CHECK (aging_window_items >= 1),
    aging_percentiles JSONB NOT NULL DEFAULT '[50,85,95]',
    backlog_window_sprints INTEGER NOT NULL DEFAULT 3 CHECK (backlog_window_sprints >= 1),
    epic_drift_days INTEGER NOT NULL DEFAULT 14 CHECK (epic_drift_days >= 0),
    updated_at BIGINT NOT NULL DEFAULT 0,
    updated_by_user_id BIGINT REFERENCES users(id)
);
INSERT INTO metrics.settings (id) VALUES (1) ON CONFLICT DO NOTHING;

-- Status → stage (per connection; `domain_key = ''` means "every domain" — the per-domain
-- override column exists at the schema level, but the v0.3.0 UI only ever edits the `''` row).
CREATE TABLE metrics.status_stage_map (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    status_id VARCHAR(50) NOT NULL,
    domain_key VARCHAR(50) NOT NULL DEFAULT '',
    stage VARCHAR(20) NOT NULL CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE')),
    PRIMARY KEY (connection_id, status_id, domain_key)
);

-- Which field id backs each configurable role, per connection (`ESTIMATE_TASK`/`ESTIMATE_EPIC`/
-- `EPIC_START`/`EPIC_DUE`/`WORK_CATEGORY`) — a Kotlin-enum-whitelisted CHECK (it drives DERIVE's
-- own field lookup, the `sync_jobs.status` idiom).
CREATE TABLE metrics.field_config (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('ESTIMATE_TASK', 'ESTIMATE_EPIC', 'EPIC_START', 'EPIC_DUE', 'WORK_CATEGORY')),
    field_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (connection_id, role)
);

-- Project → domain, per connection. 1:1 by default (the metrics-config PUT seeds this from the
-- connection's own project keys when unconfigured).
CREATE TABLE metrics.domain_map (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    project_key VARCHAR(20) NOT NULL,
    domain_key VARCHAR(50) NOT NULL,
    domain_name VARCHAR(100) NOT NULL,
    PRIMARY KEY (connection_id, project_key)
);

-- Board → team, per connection — D10's "one board per team": a board maps to at most one team,
-- enforced by the UNIQUE(team_id) below (a clash is 23505 → 409, named in
-- plugins/ErrorHandling.kt's UNIQUE_CONSTRAINT_DETAILS).
CREATE TABLE metrics.board_team_map (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    board_id BIGINT NOT NULL,
    team_id INTEGER NOT NULL REFERENCES teams(id),
    PRIMARY KEY (connection_id, board_id),
    CONSTRAINT uq_metrics_board_team_map_team_id UNIQUE (team_id)
);

-- An admin-edited MD capacity override per sprint, per connection — DERIVE falls back to a
-- computed default (members × working days, A3) when no row exists here.
CREATE TABLE metrics.team_sprint_capacity (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    capacity_md NUMERIC(8, 2) NOT NULL CHECK (capacity_md >= 0),
    PRIMARY KEY (connection_id, sprint_id)
);

-- Issue type → activity type, per connection. 1:1 by default (D6: activity types are standard
-- issue types).
CREATE TABLE metrics.activity_type_map (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_type VARCHAR(50) NOT NULL,
    activity_type VARCHAR(50) NOT NULL,
    PRIMARY KEY (connection_id, issue_type)
);

-- The configured work-category field's distinct option values → category (D8: the task's own
-- value, else its epic's). `value_name` is the option's display label (informational only —
-- `category` is what DERIVE groups by).
CREATE TABLE metrics.work_category_map (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    value_id VARCHAR(100) NOT NULL,
    value_name VARCHAR(200),
    category VARCHAR(100) NOT NULL,
    PRIMARY KEY (connection_id, value_id)
);

-- Statuses counted as "blocked" alongside the Flagged field, per connection — no columns beyond
-- the key; presence alone is the whole rule.
CREATE TABLE metrics.blocked_statuses (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    status_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (connection_id, status_id)
);

-- Dated Jira-user team membership (D1, `.claude/docs/domain-model.md`): Flow-owned and
-- effective-dated, since Jira itself keeps no membership history and the ≤1-team-at-a-time rule
-- needs one. Global by `account_id` (Atlassian account ids span sites, not scoped to one
-- connection — two connections to the same site share account ids, D12's risk note).
-- `valid_to IS NULL` means open-ended (the current membership). The EXCLUDE constraint is
-- invariant 1 ("a user belongs to ≤1 team at any instant") enforced race-free at the database,
-- never a check-then-insert race in Kotlin: two half-open `[valid_from, valid_to)` ranges for the
-- SAME account_id may never overlap. A violation raises SQLSTATE 23P01, mapped in
-- plugins/ErrorHandling.kt to 409 "Overlapping team membership for this account".
CREATE TABLE metrics.team_membership (
    id SERIAL PRIMARY KEY,
    account_id VARCHAR(100) NOT NULL,
    team_id INTEGER NOT NULL REFERENCES teams(id),
    valid_from BIGINT NOT NULL,
    valid_to BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT excl_metrics_team_membership_overlap
        EXCLUDE USING gist (account_id WITH =, int8range(valid_from, valid_to, '[)') WITH &&)
);
CREATE INDEX idx_metrics_team_membership_team ON metrics.team_membership (team_id, valid_from);

-- One row per DERIVE run (v0.3.0 M3+, created here so no later migration needs to add it):
-- `job_id` is a plain column, deliberately NOT a foreign key to `sync_jobs.id` — the
-- `raw.jira_reconcile_seen` rationale (`.claude/docs/persistence.md`): a derive_runs row must
-- never be able to hold a sync_jobs row hostage from its own hard-delete prune.
CREATE TABLE metrics.derive_runs (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    job_id INTEGER,
    config_revision BIGINT NOT NULL,
    processing_version INTEGER NOT NULL,
    started_at BIGINT NOT NULL,
    finished_at BIGINT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    row_counts JSONB,
    error_detail TEXT
);
CREATE INDEX idx_metrics_derive_runs_connection ON metrics.derive_runs (connection_id, started_at DESC);
