-- A 4th status stage, WAITING (domain-model A30): work has started but nothing is actively worked on (waiting for
-- review or test, on hold). Jira's status categories have no such state, so no status ever DEFAULTS to WAITING — an
-- admin maps it by hand — and the stage vocabulary of the five stage columns grows by one value:
--  * metrics.status_stage_map.stage (V15) — NOT_STARTED / IN_PROGRESS / DONE  -> + WAITING;
--  * metrics.dim_epic.current_stage, metrics.item_stage.stage, metrics.fact_task_delivery.current_stage and
--    metrics.agg_daily_wip.stage (V16) — those three + UNMAPPED  -> + WAITING.
-- Each column's CHECK was declared inline, so PostgreSQL named it <table>_<column>_check (verified against a migrated
-- database); a CHECK cannot be altered in place, so each one is dropped and re-added with the wider list. Purely
-- widening: every existing row already satisfies the new constraint. The re-add is two statements per table -- ADD
-- CONSTRAINT ... NOT VALID (a catalog-only change, so the ACCESS EXCLUSIVE lock is held only momentarily), then VALIDATE
-- CONSTRAINT (the one scan of the table, under a SHARE UPDATE EXCLUSIVE lock that lets reads and writes continue) --
-- so a large derived table is never blocked for the length of the scan; nothing is rewritten. No data changes — with
-- the default configuration no row is WAITING, and a configuration change bumps the revision, which makes the next
-- DERIVE rebuild the derived tables. No existing V1-V21 file changes (their bytes are immutable —
-- `MigrationChecksumTest`).
ALTER TABLE metrics.status_stage_map
    DROP CONSTRAINT status_stage_map_stage_check,
    ADD CONSTRAINT status_stage_map_stage_check
        CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'DONE')) NOT VALID;
ALTER TABLE metrics.status_stage_map
    VALIDATE CONSTRAINT status_stage_map_stage_check;

ALTER TABLE metrics.dim_epic
    DROP CONSTRAINT dim_epic_current_stage_check,
    ADD CONSTRAINT dim_epic_current_stage_check
        CHECK (current_stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.dim_epic
    VALIDATE CONSTRAINT dim_epic_current_stage_check;

ALTER TABLE metrics.item_stage
    DROP CONSTRAINT item_stage_stage_check,
    ADD CONSTRAINT item_stage_stage_check
        CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.item_stage
    VALIDATE CONSTRAINT item_stage_stage_check;

ALTER TABLE metrics.fact_task_delivery
    DROP CONSTRAINT fact_task_delivery_current_stage_check,
    ADD CONSTRAINT fact_task_delivery_current_stage_check
        CHECK (current_stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.fact_task_delivery
    VALIDATE CONSTRAINT fact_task_delivery_current_stage_check;

ALTER TABLE metrics.agg_daily_wip
    DROP CONSTRAINT agg_daily_wip_stage_check,
    ADD CONSTRAINT agg_daily_wip_stage_check
        CHECK (stage IN ('NOT_STARTED', 'IN_PROGRESS', 'WAITING', 'DONE', 'UNMAPPED')) NOT VALID;
ALTER TABLE metrics.agg_daily_wip
    VALIDATE CONSTRAINT agg_daily_wip_stage_check;
