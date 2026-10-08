-- Back-off for a failing RECONCILE, mirroring what SYNC already has (consecutive_failures / next_sync_at, V8).
-- A RECONCILE is due while last_reconcile_at predates today's reconcile_hour_utc boundary, and only a SUCCESSFUL run
-- stamps last_reconcile_at, so a RECONCILE that failed (on a real tenant: every attempt) stayed due and the scheduler
-- re-enqueued it on every tick (~15 s) — 113 failed jobs in under 30 minutes. A failed run now records
-- reconcile_failures and a next_reconcile_at (now + 15 min x 2^failures, capped at 6 h, the same cap SYNC uses), and
-- the due scan skips the connection until then; a success resets both.
-- Purely additive: a NOT NULL column with a constant DEFAULT is catalog-only on PostgreSQL 11+, the nullable column
-- needs no rewrite; existing rows read as "no failures, no back-off". No existing V1-V20 file changes (their bytes are
-- immutable — `MigrationChecksumTest`), no data migration.
ALTER TABLE public.source_connections
    ADD COLUMN reconcile_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_reconcile_at BIGINT;
