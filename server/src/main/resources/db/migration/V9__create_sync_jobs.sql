-- The sync-job queue (v0.2.0 plan §4/§5/§9): ONE sync_jobs table is both history and command
-- queue for every connector kind, claimed by the ingestion worker with a lease/heartbeat
-- (ingest/IngestWorker.kt). Stays in `public` (plan §0 A3) — operational state, not connector-raw
-- data. `sync_cursors` is the per-stream incremental-cursor store the streams (commit 6+) resume
-- from; it lives alongside the queue for the same reason.
CREATE TABLE sync_jobs (
    id SERIAL PRIMARY KEY,
    connection_id INTEGER NOT NULL REFERENCES source_connections(id),
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('SYNC', 'RECONCILE', 'REPROCESS', 'PURGE')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- 0 = manual ("Sync now"/"Reconcile now"/"Reprocess"), 10 = scheduler-enqueued. Claiming
    -- orders by priority ASC (0 before 10) so a manual request preempts the schedule.
    priority INTEGER NOT NULL CHECK (priority IN (0, 10)),
    -- Null for scheduler-enqueued jobs (SYNC/RECONCILE/PURGE via IngestWorker's tick).
    requested_by_user_id BIGINT REFERENCES users(id),
    -- The connection's config_revision at enqueue time — a claim finding it stale (the
    -- connection was edited since) cancels the job with CONFIG_CHANGED rather than running
    -- against config the operator already changed.
    config_revision BIGINT NOT NULL,

    requested_at BIGINT NOT NULL,
    started_at BIGINT,
    finished_at BIGINT,
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts >= 1),

    -- Lease/heartbeat (IngestWorker's claim loop): lease_owner identifies the worker instance,
    -- lease_until is when the lease expires absent a heartbeat (an expired RUNNING lease is
    -- re-claimable), heartbeat_at is purely observational. cancel_requested_at is set by the
    -- cancel endpoint against a RUNNING job (a PENDING job is cancelled immediately instead).
    lease_owner VARCHAR(200),
    lease_until BIGINT,
    heartbeat_at BIGINT,
    cancel_requested_at BIGINT,

    current_stream VARCHAR(50),
    progress JSONB,
    error_code VARCHAR(100),
    error_detail TEXT
);

-- Coalescing: at most one OPEN (PENDING/RUNNING) job per (connection, kind) — a second "Sync
-- now" while one is already pending/running hits this and the enqueue is a no-op (ingest/
-- SyncJobs.kt's `enqueue` reports `coalesced = true`). Race-free by construction (a partial
-- unique index, not a check-then-insert).
CREATE UNIQUE INDEX uq_sync_jobs_open_per_kind ON sync_jobs (connection_id, kind) WHERE status IN ('PENDING', 'RUNNING');

-- The claim scan: PENDING/RUNNING rows ordered by priority then requested_at (FOR UPDATE SKIP
-- LOCKED over this in ingest/SyncJobs.kt's `claim`); a RUNNING row's lease_until is compared
-- in Kotlin, not the index, since Postgres can't partial-index a moving "now" boundary.
CREATE INDEX idx_sync_jobs_claimable ON sync_jobs (status, priority, requested_at) WHERE status IN ('PENDING', 'RUNNING');

-- Per-connection history (GET .../sync-jobs, newest first) and the "one RUNNING per connection"
-- check in the claim loop.
CREATE INDEX idx_sync_jobs_connection_history ON sync_jobs (connection_id, requested_at DESC);

-- Per-stream incremental cursors (plan §7; the streams themselves land in commit 6+). One row
-- per (connection, stream) — `stream` names a sync phase (e.g. "issues", "changelogs").
CREATE TABLE sync_cursors (
    connection_id INTEGER NOT NULL REFERENCES source_connections(id),
    stream VARCHAR(30) NOT NULL,
    cursor JSONB NOT NULL,
    watermark_at BIGINT,
    last_completed_at BIGINT,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (connection_id, stream)
);

-- A2 (plan §0): once a PURGE job succeeds for a soft-deleted connection, this is stamped so the
-- worker's due-PURGE scan never re-enqueues it — independent of sync_jobs row retention/pruning
-- (a SUCCEEDED PURGE row is itself eventually pruned by ingest.jobRetentionDays, so re-deriving
-- "already purged" from job history would regress once that row is gone).
ALTER TABLE source_connections ADD COLUMN purged_at BIGINT;
