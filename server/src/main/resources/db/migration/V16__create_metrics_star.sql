-- The `metrics` schema — the derived star (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md`
-- "Analytical model (`metrics` schema)", the phase-3 implementation plan §4 "V16 — the star").
-- Every table here is `connection_id`-scoped and rebuilt WHOLESALE per DERIVE run, except
-- `dim_date` (global, upserted `ON CONFLICT (day) DO UPDATE`) and `fact_sprint_snapshot`
-- (append-only, immutable once written — see the trigger at the end of this file). Every row
-- carries `config_revision` (invariant 12: "every live number is reproducible from `norm` + one
-- configuration revision"). Interval storage follows `metrics.team_membership`'s own precedent
-- (V15): `valid_from BIGINT NOT NULL, valid_to BIGINT NULL` half-open pairs, `int8range(valid_from,
-- valid_to, '[)')` for GiST overlap indexes — no `tstzrange` (no r2dbc-postgresql codec for it).

-- `sync_jobs.kind` gains `DERIVE` (v0.3.0 M3 commit 7, `.claude/docs/ingestion.md` "The DERIVE job
-- kind"): a connector-agnostic job, enqueued after every successful SYNC/RECONCILE/REPROCESS and
-- after every metrics-config mutation, run by `metrics/MetricsDeriver.kt` (never touches Jira).
ALTER TABLE sync_jobs DROP CONSTRAINT sync_jobs_kind_check;
ALTER TABLE sync_jobs ADD CONSTRAINT sync_jobs_kind_check CHECK (kind IN ('SYNC', 'RECONCILE', 'REPROCESS', 'PURGE', 'DERIVE'));

-- ---------------------------------------------------------------------------------------------
-- Dimensions
-- ---------------------------------------------------------------------------------------------

-- The ONE global calendar dimension — `day` is a plain VARCHAR(10) ISO date string (the
-- `source_connections.backfill_from` precedent, V8: read/written whole, validated in Kotlin, no
-- SQL DATE column type needed); `day_start_ms`/`day_end_ms` are that day's boundaries in the
-- configured zone at the time it was computed (`metrics/WorkingCalendar.kt`), and `is_working_day`
-- folds in the configured weekend days AND holidays as of that same computation. Upserted
-- `ON CONFLICT (day) DO UPDATE` so two concurrent DERIVE runs never fight over the same row
-- (`.claude/docs/domain-model.md`'s "Configuration" table already documents weekend/holiday config;
-- a changed calendar setting simply overwrites the flag on its next DERIVE).
CREATE TABLE metrics.dim_date (
    day VARCHAR(10) PRIMARY KEY,
    day_start_ms BIGINT NOT NULL,
    day_end_ms BIGINT NOT NULL,
    is_working_day BOOLEAN NOT NULL,
    config_revision BIGINT NOT NULL
);

CREATE TABLE metrics.dim_domain (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    domain_key VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    project_keys JSONB NOT NULL DEFAULT '[]',
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, domain_key)
);

-- Every level-0, non-epic issue (D2: sub-tasks roll up into their parent but stay visible here for
-- drill-down). `work_category_source` names WHERE `work_category` came from (D8: the task's own
-- value, else its epic's) — `NONE` when neither the task nor its epic carries one.
CREATE TABLE metrics.dim_task (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    issue_type VARCHAR(50) NOT NULL,
    activity_type VARCHAR(50) NOT NULL,
    work_category VARCHAR(100),
    work_category_source VARCHAR(10) NOT NULL CHECK (work_category_source IN ('OWN', 'EPIC', 'NONE')),
    is_subtask BOOLEAN NOT NULL DEFAULT FALSE,
    parent_task_id BIGINT,
    domain_key VARCHAR(50),
    epic_id BIGINT,
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, issue_id)
);

-- An epic is hierarchy level 1 (`norm.work_items.hierarchy_level`) — its own row, never a `dim_task`
-- row (D2: "An epic is never a TASK").
CREATE TABLE metrics.dim_epic (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    summary TEXT,
    domain_key VARCHAR(50),
    work_category VARCHAR(100),
    current_stage VARCHAR(20) NOT NULL CHECK (current_stage IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'UNMAPPED')),
    start_at BIGINT,
    due_at BIGINT,
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, issue_id)
);

-- `team_id` is nullable until an admin maps the sprint's board to a team (`metrics.board_team_map`,
-- V15). `capacity_source` (plan A3, populated by commit 8's sprint step) is `CONFIGURED` when
-- `metrics.team_sprint_capacity` carries an override, `DEFAULT` when DERIVE computed one itself
-- (members x working days), added here even though commit 8 is the first writer, so the column
-- never needs a later migration.
CREATE TABLE metrics.dim_sprint (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    board_id BIGINT,
    team_id INTEGER REFERENCES teams(id),
    name VARCHAR(200) NOT NULL,
    state VARCHAR(20) NOT NULL,
    start_at BIGINT,
    end_at BIGINT,
    complete_at BIGINT,
    capacity_md NUMERIC(8, 2),
    capacity_source VARCHAR(10) CHECK (capacity_source IN ('CONFIGURED', 'DEFAULT')),
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, sprint_id)
);
CREATE INDEX idx_metrics_dim_sprint_team ON metrics.dim_sprint (connection_id, team_id, complete_at);

-- `dim_org`/`dim_team`/`dim_user` are `public.teams` + `metrics.team_membership` + `norm.people` —
-- no physical copy (`.claude/docs/domain-model.md`'s own note).

-- ---------------------------------------------------------------------------------------------
-- Bridges — effective-dated, `valid_from BIGINT NOT NULL, valid_to BIGINT NULL` (open = current)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE metrics.task_epic (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    epic_id BIGINT,
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_task_epic_issue ON metrics.task_epic (connection_id, issue_id, valid_from);

CREATE TABLE metrics.task_domain (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    domain_key VARCHAR(50),
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_task_domain_issue ON metrics.task_domain (connection_id, issue_id, valid_from);

CREATE TABLE metrics.task_assignee (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    account_id VARCHAR(100),
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_task_assignee_issue ON metrics.task_assignee (connection_id, issue_id, valid_from);

-- Derived from the SPRINT field's SET-VALUED changes (from/to id lists diffed per event), NOT the
-- last-id interval — carry-over produces one row per sprint the task was ever in, each with its own
-- `valid_from`/`valid_to` (`metrics/DeriveKernels.kt`'s `sprintMembership`). One of the two
-- overlap-joined bridges (alongside `item_stage`), hence the GiST expression index.
CREATE TABLE metrics.task_sprint (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    sprint_id BIGINT NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_task_sprint_issue ON metrics.task_sprint (connection_id, issue_id, valid_from);
CREATE INDEX idx_metrics_task_sprint_gist ON metrics.task_sprint
    USING gist (connection_id, int8range(valid_from, valid_to, '[)'));

-- `estimate_md IS NULL` means unestimated (0 SP also counts as unestimated at write time —
-- `metrics/DeriveKernels.kt`'s `estimateTimeline`, never stored as a literal 0 row).
CREATE TABLE metrics.item_estimate (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    estimate_md NUMERIC(8, 2),
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_item_estimate_issue ON metrics.item_estimate (connection_id, issue_id, valid_from);

-- `item_status` + `status_stage_map` tiled into per-stage intervals (`metrics/DeriveKernels.kt`'s
-- `stageIntervals`) — `UNMAPPED` when the status carries no `status_stage_map` row (flagged, never
-- guessed). The other overlap-joined bridge, hence its own GiST expression index.
CREATE TABLE metrics.item_stage (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    stage VARCHAR(20) NOT NULL CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'UNMAPPED')),
    status_id VARCHAR(50) NOT NULL,
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_item_stage_issue ON metrics.item_stage (connection_id, issue_id, valid_from);
CREATE INDEX idx_metrics_item_stage_gist ON metrics.item_stage
    USING gist (connection_id, int8range(valid_from, valid_to, '[)'));

-- FLAGGED true, or a configured blocked status (`metrics.blocked_statuses`, V15) — the union,
-- already merged/deduplicated by `metrics/DeriveKernels.kt`'s `blockedIntervals`.
CREATE TABLE metrics.item_blocked (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    reason VARCHAR(10) NOT NULL CHECK (reason IN ('FLAGGED', 'STATUS')),
    valid_from BIGINT NOT NULL,
    valid_to BIGINT
);
CREATE INDEX idx_metrics_item_blocked_issue ON metrics.item_blocked (connection_id, issue_id, valid_from);

-- ---------------------------------------------------------------------------------------------
-- Facts
-- ---------------------------------------------------------------------------------------------

-- `credit_team_id` is NULLABLE, not the `team_id = 0` UNASSIGNED sentinel (that sentinel is a
-- report-query-param/agg-scope-id convention over VARCHAR ids, not a real FK target here) — D5's
-- delivery credit (sprint team, else assignee's team) is simply absent when neither resolves.
CREATE TABLE metrics.fact_task_delivery (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    done_at BIGINT,
    reopen_count INTEGER NOT NULL DEFAULT 0,
    estimate_at_start_md NUMERIC(8, 2),
    estimate_at_done_md NUMERIC(8, 2),
    estimate_current_md NUMERIC(8, 2),
    estimate_source VARCHAR(10) NOT NULL CHECK (estimate_source IN ('OWN', 'SUBTASKS', 'NONE')),
    estimate_changes_after_start INTEGER NOT NULL DEFAULT 0,
    estimated_late BOOLEAN NOT NULL DEFAULT FALSE,
    actual_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    has_worklogs BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_ms BIGINT NOT NULL DEFAULT 0,
    blocked_working_days NUMERIC(10, 4) NOT NULL DEFAULT 0,
    cycle_ms BIGINT,
    cycle_working_days NUMERIC(10, 4),
    lead_ms BIGINT,
    lead_working_days NUMERIC(10, 4),
    active_ms BIGINT NOT NULL DEFAULT 0,
    wait_ms BIGINT NOT NULL DEFAULT 0,
    assignee_account_id_at_done VARCHAR(100),
    assignee_team_id_at_done INTEGER REFERENCES teams(id),
    sprint_id_at_done BIGINT,
    sprint_team_id_at_done INTEGER REFERENCES teams(id),
    credit_team_id INTEGER REFERENCES teams(id),
    domain_key VARCHAR(50),
    epic_id BIGINT,
    epic_domain_key VARCHAR(50),
    cross_domain BOOLEAN NOT NULL DEFAULT FALSE,
    activity_type VARCHAR(50) NOT NULL,
    work_category VARCHAR(100),
    is_subtask BOOLEAN NOT NULL DEFAULT FALSE,
    parent_task_id BIGINT,
    current_stage VARCHAR(20) NOT NULL CHECK (current_stage IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'UNMAPPED')),
    flags JSONB NOT NULL DEFAULT '[]',
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, issue_id)
);
CREATE INDEX idx_metrics_fact_task_delivery_done ON metrics.fact_task_delivery (connection_id, done_at);
CREATE INDEX idx_metrics_fact_task_delivery_credit_team ON metrics.fact_task_delivery (connection_id, credit_team_id, done_at);
CREATE INDEX idx_metrics_fact_task_delivery_assignee ON metrics.fact_task_delivery (connection_id, assignee_account_id_at_done, done_at);
CREATE INDEX idx_metrics_fact_task_delivery_wip ON metrics.fact_task_delivery (connection_id, started_at) WHERE done_at IS NULL;

CREATE TABLE metrics.fact_epic_delivery (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    started_at BIGINT,
    done_at BIGINT,
    own_estimate_at_start_md NUMERIC(8, 2),
    own_estimate_at_done_md NUMERIC(8, 2),
    own_estimate_current_md NUMERIC(8, 2),
    estimate_changes_after_start INTEGER NOT NULL DEFAULT 0,
    child_sum_estimate_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    budget_source VARCHAR(10) NOT NULL CHECK (budget_source IN ('OWN', 'CHILDREN')),
    actual_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    cycle_ms BIGINT,
    cycle_working_days NUMERIC(10, 4),
    blocked_ms BIGINT NOT NULL DEFAULT 0,
    blocked_working_days NUMERIC(10, 4) NOT NULL DEFAULT 0,
    domain_key VARCHAR(50),
    work_category VARCHAR(100),
    drift_flags JSONB NOT NULL DEFAULT '[]',
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, issue_id)
);
CREATE INDEX idx_metrics_fact_epic_delivery_done ON metrics.fact_epic_delivery (connection_id, done_at);

CREATE TABLE metrics.fact_sprint_scope (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    issue_id BIGINT NOT NULL,
    added_at BIGINT,
    removed_at BIGINT,
    committed BOOLEAN NOT NULL DEFAULT FALSE,
    in_scope_at_close BOOLEAN NOT NULL DEFAULT FALSE,
    estimate_at_commitment_md NUMERIC(8, 2),
    estimate_at_close_md NUMERIC(8, 2),
    estimate_at_done_md NUMERIC(8, 2),
    assignee_at_commitment VARCHAR(100),
    done_in_sprint BOOLEAN NOT NULL DEFAULT FALSE,
    carried_over BOOLEAN NOT NULL DEFAULT FALSE,
    dropped BOOLEAN NOT NULL DEFAULT FALSE,
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, sprint_id, issue_id)
);
CREATE INDEX idx_metrics_fact_sprint_scope_sprint ON metrics.fact_sprint_scope (connection_id, sprint_id);

CREATE TABLE metrics.fact_sprint (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    team_id INTEGER REFERENCES teams(id),
    complete_at BIGINT,
    committed_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    committed_items INTEGER NOT NULL DEFAULT 0,
    added_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    added_items INTEGER NOT NULL DEFAULT 0,
    removed_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    removed_items INTEGER NOT NULL DEFAULT 0,
    final_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    final_items INTEGER NOT NULL DEFAULT 0,
    delivered_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    delivered_items INTEGER NOT NULL DEFAULT 0,
    carried_over_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    carried_over_items INTEGER NOT NULL DEFAULT 0,
    dropped_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    dropped_items INTEGER NOT NULL DEFAULT 0,
    capacity_md NUMERIC(8, 2),
    load NUMERIC(8, 4),
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, sprint_id)
);
CREATE INDEX idx_metrics_fact_sprint_team ON metrics.fact_sprint (connection_id, team_id, complete_at);

-- Append-only, frozen at sprint completion (D13) — the immutability trigger at the end of this
-- file forbids UPDATE/DELETE outside the PURGE step's own `SET LOCAL metrics.allow_snapshot_delete
-- = 'on'`. `scope` is the frozen `fact_sprint_scope` rows for this sprint, so a per-user velocity
-- read from the snapshot never needs a child table. `reconstructed` is true for a sprint that
-- completed BEFORE this connection's first successful DERIVE run ever processed it.
CREATE TABLE metrics.fact_sprint_snapshot (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    team_id INTEGER REFERENCES teams(id),
    complete_at BIGINT,
    committed_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    committed_items INTEGER NOT NULL DEFAULT 0,
    added_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    added_items INTEGER NOT NULL DEFAULT 0,
    removed_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    removed_items INTEGER NOT NULL DEFAULT 0,
    final_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    final_items INTEGER NOT NULL DEFAULT 0,
    delivered_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    delivered_items INTEGER NOT NULL DEFAULT 0,
    carried_over_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    carried_over_items INTEGER NOT NULL DEFAULT 0,
    dropped_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    dropped_items INTEGER NOT NULL DEFAULT 0,
    capacity_md NUMERIC(8, 2),
    load NUMERIC(8, 4),
    scope JSONB NOT NULL DEFAULT '[]',
    config_revision BIGINT NOT NULL,
    processing_version INTEGER NOT NULL,
    reconstructed BOOLEAN NOT NULL DEFAULT FALSE,
    snapshot_at BIGINT NOT NULL,
    PRIMARY KEY (connection_id, sprint_id)
);

-- The first trigger in this repo (`.claude/docs/persistence.md`): a snapshot, once written, never
-- changes — the store exposes no update path either, but the DB is the one that actually enforces
-- it. `current_setting(..., true)` returns NULL rather than raising when the setting was never
-- `SET LOCAL` at all (the `true` "missing_ok" flag), so an ordinary session's UPDATE/DELETE always
-- raises; only the PURGE step's own `SET LOCAL metrics.allow_snapshot_delete = 'on'` bypasses it.
CREATE FUNCTION metrics.forbid_snapshot_change() RETURNS trigger AS $$
BEGIN
    IF current_setting('metrics.allow_snapshot_delete', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'metrics.fact_sprint_snapshot rows are immutable once written';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_metrics_fact_sprint_snapshot_immutable
    BEFORE UPDATE OR DELETE ON metrics.fact_sprint_snapshot
    FOR EACH ROW EXECUTE FUNCTION metrics.forbid_snapshot_change();

CREATE TABLE metrics.fact_worklog (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    worklog_id BIGINT NOT NULL,
    issue_id BIGINT NOT NULL,
    author_account_id VARCHAR(100),
    author_team_id INTEGER REFERENCES teams(id),
    started_at BIGINT NOT NULL,
    created_at BIGINT,
    late_ms BIGINT,
    md NUMERIC(8, 4) NOT NULL,
    task_domain_key VARCHAR(50),
    epic_id BIGINT,
    epic_domain_key VARCHAR(50),
    activity_type VARCHAR(50),
    work_category VARCHAR(100),
    sprint_id_at_started BIGINT,
    sprint_team_id_at_started INTEGER REFERENCES teams(id),
    foreign_work BOOLEAN NOT NULL DEFAULT FALSE,
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, worklog_id)
);
CREATE INDEX idx_metrics_fact_worklog_started ON metrics.fact_worklog (connection_id, started_at);
CREATE INDEX idx_metrics_fact_worklog_author_team ON metrics.fact_worklog (connection_id, author_team_id, started_at);

CREATE TABLE metrics.fact_epic_plan (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    issue_id BIGINT NOT NULL,
    baseline_seq INTEGER NOT NULL,
    baselined_at BIGINT NOT NULL,
    start_at BIGINT,
    due_at BIGINT,
    budget_md NUMERIC(10, 2),
    budget_source VARCHAR(10) NOT NULL CHECK (budget_source IN ('OWN', 'CHILDREN')),
    superseded_at BIGINT,
    config_revision BIGINT NOT NULL
);
CREATE INDEX idx_metrics_fact_epic_plan_issue ON metrics.fact_epic_plan (connection_id, issue_id, baseline_seq);

CREATE TABLE metrics.agg_daily_wip (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    scope_kind VARCHAR(10) NOT NULL CHECK (scope_kind IN ('TEAM', 'DOMAIN', 'EPIC')),
    scope_id VARCHAR(60) NOT NULL,
    day VARCHAR(10) NOT NULL,
    item_kind VARCHAR(10) NOT NULL CHECK (item_kind IN ('TASK', 'EPIC')),
    status_id VARCHAR(50) NOT NULL,
    stage VARCHAR(20) NOT NULL CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'UNMAPPED')),
    item_count INTEGER NOT NULL DEFAULT 0,
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, scope_kind, scope_id, day, item_kind, status_id, stage)
);

CREATE TABLE metrics.agg_daily_flow (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    scope_kind VARCHAR(10) NOT NULL CHECK (scope_kind IN ('TEAM', 'DOMAIN', 'EPIC')),
    scope_id VARCHAR(60) NOT NULL,
    day VARCHAR(10) NOT NULL,
    backlog_items INTEGER NOT NULL DEFAULT 0,
    backlog_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    pv_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ev_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ac_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    throughput_items INTEGER NOT NULL DEFAULT 0,
    throughput_md NUMERIC(10, 2) NOT NULL DEFAULT 0,
    config_revision BIGINT NOT NULL,
    PRIMARY KEY (connection_id, scope_kind, scope_id, day)
);
