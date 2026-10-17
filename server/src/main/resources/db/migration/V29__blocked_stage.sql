-- A 5th status stage, BLOCKED (domain-model A36): work has started but is held by an impediment (blocked, on hold,
-- waiting for a customer). It REPLACES the per-connection "Blocked" status checkbox (`metrics.blocked_statuses`, V15), which
-- allowed combinations that mean nothing for a status (In progress + blocked: nobody works on it; Not started/Done + blocked:
-- blocked time only counts inside [started_at, done_at)). Every status now has exactly ONE stage; WAITING (V22) stays the
-- queue, BLOCKED is the impediment, and Jira's Flagged marker stays an item-level mark on top of whatever the stage is.
-- No status ever DEFAULTS to BLOCKED (an admin maps it by hand, like WAITING). The stage vocabulary of the five stage
-- columns grows by one value, exactly as V22 did for WAITING:
--  * metrics.status_stage_map.stage (V15, V22) -- NOT_STARTED / IN_PROGRESS / WAITING / DONE  -> + BLOCKED;
--  * metrics.dim_epic.current_stage, metrics.item_stage.stage, metrics.fact_task_delivery.current_stage and
--    metrics.agg_daily_wip.stage (V16, V22) -- those four + UNMAPPED  -> + BLOCKED.
-- Each CHECK is named <table>_<column>_check (V22 re-added them under the same names), is dropped and re-added with the
-- wider list as ADD CONSTRAINT ... NOT VALID then VALIDATE CONSTRAINT (V22's two-step; Flyway runs the whole file in one
-- transaction, so the locks last until it commits, but nothing is rewritten). Purely widening: every existing row satisfies the
-- new constraint.
--
-- THE TICK CONVERSION. Every (connection_id, status_id) in `metrics.blocked_statuses` is converted before the table is
-- dropped, so the effective behaviour is kept wherever it meant something. Per ticked status:
--  (a) a GLOBAL `status_stage_map` row (domain_key = '') of IN_PROGRESS or WAITING becomes BLOCKED;
--  (b) a PER-DOMAIN override row of IN_PROGRESS or WAITING becomes BLOCKED (each row is judged on its own stage);
--  (c) NO global row and a norm.statuses category of IN_PROGRESS -> a new global BLOCKED row is inserted; with any other
--      category (TODO / DONE / UNKNOWN) nothing is inserted -- the tick simply goes away (rows of case (b) are converted
--      regardless). Such a status was effectively UNMAPPED until now (a connection with any stored configuration, a tick
--      included, reads its stage map as stored; the category default applies only to a connection with none), so this is
--      a deliberate step beyond equivalence: the admin ticked it to say "this means blocked", and its Jira category says
--      the work is in progress, so it becomes a started, BLOCKED status;
--  (d) a global or per-domain row of NOT_STARTED or DONE is left as it is and only the tick goes away (a tick there only
--      mattered after a reopen into that status, an edge case).
-- A ticked status whose stage was IN_PROGRESS stops counting as active time (BLOCKED is wait) and its time stops being
-- Deep dive execution (A29, IN_PROGRESS only): both are intended. Blocked time (the BLOCKED-stage spans union Flagged) and
-- WIP are otherwise unchanged. The user's live install has no ticked status, so it converts nothing.
--
-- THE RE-DERIVE. A stage change reaches every derived table, so this does what `MetricsSettingsService.bumpRevision()` does
-- (V25's fan-out), but ONLY when at least one tick existed: the shared revision moves, and one scheduler-priority DERIVE
-- (priority 10, max_attempts 3 = the `ingest.maxAttempts` default) is enqueued for every enabled, active connection that
-- had a tick, stamped with the connection's own config_revision and with `not_before` (V27) left NULL (due at once); ON
-- CONFLICT DO NOTHING coalesces into an already open DERIVE (`uq_sync_jobs_open_per_kind`). A disabled connection
-- re-derives on its next enable (its derived rows carry the older shared revision). Nothing is bumped when no status was
-- ticked. No existing V1-V28 file changes (their bytes are immutable -- `MigrationChecksumTest`).
ALTER TABLE metrics.status_stage_map
    DROP CONSTRAINT status_stage_map_stage_check,
    ADD CONSTRAINT status_stage_map_stage_check
        CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'BLOCKED', 'DONE')) NOT VALID;
ALTER TABLE metrics.status_stage_map
    VALIDATE CONSTRAINT status_stage_map_stage_check;

ALTER TABLE metrics.dim_epic
    DROP CONSTRAINT dim_epic_current_stage_check,
    ADD CONSTRAINT dim_epic_current_stage_check
        CHECK (current_stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'BLOCKED', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.dim_epic
    VALIDATE CONSTRAINT dim_epic_current_stage_check;

ALTER TABLE metrics.item_stage
    DROP CONSTRAINT item_stage_stage_check,
    ADD CONSTRAINT item_stage_stage_check
        CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'BLOCKED', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.item_stage
    VALIDATE CONSTRAINT item_stage_stage_check;

ALTER TABLE metrics.fact_task_delivery
    DROP CONSTRAINT fact_task_delivery_current_stage_check,
    ADD CONSTRAINT fact_task_delivery_current_stage_check
        CHECK (current_stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'BLOCKED', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.fact_task_delivery
    VALIDATE CONSTRAINT fact_task_delivery_current_stage_check;

ALTER TABLE metrics.agg_daily_wip
    DROP CONSTRAINT agg_daily_wip_stage_check,
    ADD CONSTRAINT agg_daily_wip_stage_check
        CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'BLOCKED', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.agg_daily_wip
    VALIDATE CONSTRAINT agg_daily_wip_stage_check;

-- Cases (a) and (b): the ticked status's IN_PROGRESS / WAITING rows, global and per-domain, become BLOCKED.
UPDATE metrics.status_stage_map m SET stage = 'BLOCKED'
FROM metrics.blocked_statuses b
WHERE m.connection_id = b.connection_id AND m.status_id = b.status_id AND m.stage IN ('IN_PROGRESS', 'WAITING');

-- Case (c): a ticked status with no global row (effectively UNMAPPED until now) whose Jira category is IN_PROGRESS gets a global
-- BLOCKED row.
INSERT INTO metrics.status_stage_map (connection_id, status_id, domain_key, stage)
SELECT b.connection_id, b.status_id, '', 'BLOCKED'
FROM metrics.blocked_statuses b
JOIN norm.statuses s ON s.connection_id = b.connection_id AND s.status_id = b.status_id AND s.category = 'IN_PROGRESS'
WHERE NOT EXISTS (
    SELECT 1 FROM metrics.status_stage_map m
    WHERE m.connection_id = b.connection_id AND m.status_id = b.status_id AND m.domain_key = ''
)
ON CONFLICT DO NOTHING;

UPDATE metrics.settings SET config_revision = config_revision + 1
WHERE id = 1 AND EXISTS (SELECT 1 FROM metrics.blocked_statuses);

INSERT INTO public.sync_jobs (connection_id, kind, status, priority, config_revision, requested_at, attempt, max_attempts)
SELECT c.id, 'DERIVE', 'PENDING', 10, c.config_revision, (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT, 0, 3
FROM public.source_connections c
WHERE c.enabled AND NOT c.marked_as_deleted AND c.id IN (SELECT connection_id FROM metrics.blocked_statuses)
ON CONFLICT DO NOTHING;

-- The tick is now a stage; the table has no reader or writer left.
DROP TABLE metrics.blocked_statuses;
