-- Domain-model A35: an estimate that first appears AFTER an item is done is a backfill, not an adjustment. DERIVE's
-- `estimateSnapshots` kernel now bounds `estimate_changes_after_start` by `done_at`, bases `estimated_late` of a done item on its
-- estimate AT DONE, and flags the backfilled item `estimated_after_done`. Both facts carry both flags: the epic fact had no
-- `estimated_late` at all (report 5 re-derived it with the old rule), so it gains the kernel's answer next to the new flag.
-- Purely additive: every new column is NOT NULL DEFAULT FALSE, so existing rows read as "not late / not after done" until the
-- DERIVE below rewrites them (facts are rebuilt wholesale per run).
ALTER TABLE metrics.fact_task_delivery
    ADD COLUMN estimated_after_done BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE metrics.fact_epic_delivery
    ADD COLUMN estimated_late BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN estimated_after_done BOOLEAN NOT NULL DEFAULT FALSE;

-- The rule changes every stored estimate-adjustment number, so every connection must re-derive after the deploy: this does what
-- `MetricsSettingsService.bumpRevision()` does for a settings PUT (the V25 precedent). The shared revision moves and one
-- scheduler-priority DERIVE (priority 10, max_attempts 3 = the `ingest.maxAttempts` default) is enqueued for every enabled,
-- active connection, stamped with the connection's own config_revision; ON CONFLICT DO NOTHING coalesces into an already open
-- DERIVE (`uq_sync_jobs_open_per_kind`). A fresh database has no connection, so it only moves the revision.
UPDATE metrics.settings SET config_revision = config_revision + 1 WHERE id = 1;

INSERT INTO public.sync_jobs (connection_id, kind, status, priority, config_revision, requested_at, attempt, max_attempts)
SELECT c.id, 'DERIVE', 'PENDING', 10, c.config_revision, (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::BIGINT, 0, 3
FROM public.source_connections c
WHERE c.enabled AND NOT c.marked_as_deleted
ON CONFLICT DO NOTHING;
