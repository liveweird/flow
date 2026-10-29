-- The RECONCILE ceiling-trip pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause"), V37's twin: a RECONCILE that stops at
-- a run ceiling (LIMIT_EXCEEDED) or the per-job wall clock (TIME_EXCEEDED) counts one in `reconcile_limit_trips` - whether or not it
-- stored a change, because its pass is a hard cap that starts over every run (it never resumes like ISSUES, so progress within a run
-- makes the next run no different); after `ingest.pauseAfterLimitTrips` such runs in a row the connection's RECONCILE is PAUSED
-- (`reconcile_paused_at`, `reconcile_pause_reason`): the scheduler stops enqueueing it (never the SYNC, which has its own pause) until
-- an ADMIN's "Reconcile now" or config PUT clears the pause and the counter. A succeeded RECONCILE resets the counter.
-- `reconcile_pause_reason` is a closed vocabulary of constant codes (never exception text), enforced here.
-- Purely additive: a NOT NULL column with a constant DEFAULT is catalog-only on PostgreSQL 11+, the nullable columns need no
-- rewrite; existing rows read as "no trips, not paused". No existing V1-V37 file changes (their bytes are immutable --
-- `MigrationChecksumTest`), no data migration. The CHECKs keep the pair together (both set or both null) and the reason closed.
ALTER TABLE public.source_connections
    ADD COLUMN reconcile_limit_trips INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reconcile_paused_at BIGINT,
    ADD COLUMN reconcile_pause_reason VARCHAR(100),
    ADD CONSTRAINT source_connections_reconcile_limit_trips_ck CHECK (reconcile_limit_trips >= 0),
    ADD CONSTRAINT source_connections_reconcile_pause_ck CHECK ((reconcile_paused_at IS NULL) = (reconcile_pause_reason IS NULL)),
    ADD CONSTRAINT source_connections_reconcile_pause_reason_ck CHECK (reconcile_pause_reason IN ('LIMIT_EXCEEDED', 'TIME_EXCEEDED'));
