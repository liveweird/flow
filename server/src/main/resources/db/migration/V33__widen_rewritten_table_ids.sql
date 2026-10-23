-- Widen the surrogate ids of the eleven tables PROCESS / DERIVE delete and re-insert WHOLE on every run (checkup 3, D9-3; decided
-- 2026-10-23). V13 / V16 declared them `SERIAL` (int32, max 2 147 483 647) and a sequence is never reset by a DELETE, so every
-- re-derive spends fresh ids: on the dev stack `metrics.item_stage_id_seq` stood at 8.3 M after ~26 days. At a larger tenant or a
-- tighter sync interval the sequence would run dry and every DERIVE would fail with "nextval: reached maximum value".
--
-- The set is exactly the `id SERIAL` tables of V13 and V16 (the only other SERIAL ids -- sync_jobs, derive_runs, teams, source_connections,
-- team_membership, public_holidays -- are not rewritten wholesale and stay small). No table references any of these ids (no foreign
-- key points at them), so no referencing column needs widening. `ALTER COLUMN ... TYPE bigint` keeps every value and the
-- `nextval('<table>_id_seq')` default; `ALTER SEQUENCE ... AS bigint` lifts the sequence's maximum to bigint's (a sequence declared
-- `AS integer` caps at 2^31-1 regardless of the column).
--
-- COST: each ALTER COLUMN TYPE rewrites the table (and its indexes) under an ACCESS EXCLUSIVE lock, so reads and writes on that table
-- wait until the migration commits. The live row counts are a small fraction of the sequence values (every derive replaces a
-- connection's rows, so a sequence at 8.3 M does not mean 8.3 M rows; a derive holds on the order of 10^5 rows per table), so the
-- whole migration is expected to take seconds, well under a minute, inside Flyway's single transaction at startup before this pod
-- serves.
--
-- LOCK ORDER AND OLD PODS: per table the COLUMN is altered first, then its sequence. ALTER SEQUENCE also takes a lock that blocks
-- `nextval()` until the commit, so a still-running OLD PROCESS/DERIVE (it holds row locks on the table and calls nextval) could meet
-- this migration in the opposite order and deadlock (Flyway fails, the pod restarts). Column-first makes the migration queue on the
-- table lock exactly where such a writer already holds it, but the upgrade must still be stop-the-world: scale the other Deployment
-- to 0 before the first new pod starts (`.claude/docs/persistence.md`, the V33 entry), as for V25 and V29.

ALTER TABLE norm.work_item_status_intervals ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE norm.work_item_status_intervals_id_seq AS bigint;
ALTER TABLE norm.work_item_field_intervals ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE norm.work_item_field_intervals_id_seq AS bigint;
ALTER TABLE norm.work_item_field_changes ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE norm.work_item_field_changes_id_seq AS bigint;
ALTER TABLE metrics.task_epic ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.task_epic_id_seq AS bigint;
ALTER TABLE metrics.task_domain ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.task_domain_id_seq AS bigint;
ALTER TABLE metrics.task_assignee ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.task_assignee_id_seq AS bigint;
ALTER TABLE metrics.task_sprint ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.task_sprint_id_seq AS bigint;
ALTER TABLE metrics.item_estimate ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.item_estimate_id_seq AS bigint;
ALTER TABLE metrics.item_stage ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.item_stage_id_seq AS bigint;
ALTER TABLE metrics.item_blocked ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.item_blocked_id_seq AS bigint;
ALTER TABLE metrics.fact_epic_plan ALTER COLUMN id TYPE bigint;
ALTER SEQUENCE metrics.fact_epic_plan_id_seq AS bigint;
