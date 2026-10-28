-- The ceiling-trip pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause"): a SYNC that stops at a run ceiling
-- (LIMIT_EXCEEDED) or the per-job wall clock (TIME_EXCEEDED) without committing a page in the stream that tripped counts one in
-- `limit_trips`; after `ingest.pauseAfterLimitTrips` such runs in a row the connection is PAUSED (`paused_at`, `pause_reason`): the
-- scheduler stops enqueueing its SYNC (never its RECONCILE, which has its own back-off) until an ADMIN's "Sync now" or config PUT
-- clears the pause and the counter. A succeeded SYNC resets the counter. `pause_reason` is a closed vocabulary of constant codes
-- (never exception text), enforced here.
-- Purely additive: a NOT NULL column with a constant DEFAULT is catalog-only on PostgreSQL 11+, the nullable columns need no
-- rewrite; existing rows read as "no trips, not paused". No existing V1-V36 file changes (their bytes are immutable --
-- `MigrationChecksumTest`), no data migration. The CHECKs keep the pair together (both set or both null) and the reason closed.
ALTER TABLE public.source_connections
    ADD COLUMN limit_trips INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN paused_at BIGINT,
    ADD COLUMN pause_reason VARCHAR(100),
    ADD CONSTRAINT source_connections_limit_trips_ck CHECK (limit_trips >= 0),
    ADD CONSTRAINT source_connections_pause_ck CHECK ((paused_at IS NULL) = (pause_reason IS NULL)),
    ADD CONSTRAINT source_connections_pause_reason_ck CHECK (pause_reason IN ('LIMIT_EXCEEDED', 'TIME_EXCEEDED'));
