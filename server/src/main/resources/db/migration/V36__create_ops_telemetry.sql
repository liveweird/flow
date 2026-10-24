-- In-app telemetry (observability.md "Telemetry"): each Flow process samples ITSELF into the `ops` schema; the admin telemetry
-- panel reads these rows. `ops` holds Flow's own operational state, never project data.
CREATE SCHEMA IF NOT EXISTS ops;

-- One row per process run (a JVM start): who sampled. Identity and limits only - never JVM arguments, environment or
-- system properties. `last_seen` is stamped on every sample tick; rows unseen for 90 days are pruned (cascading their samples).
CREATE TABLE ops.telemetry_processes (
    id SERIAL PRIMARY KEY,
    role VARCHAR(16) NOT NULL,
    instance VARCHAR(100) NOT NULL,
    started_at BIGINT NOT NULL,
    last_seen BIGINT NOT NULL,
    build_version VARCHAR(64),
    build_commit VARCHAR(64),
    processors INT NOT NULL,
    heap_max_bytes BIGINT NOT NULL,
    container_limit_bytes BIGINT,
    CONSTRAINT uq_telemetry_processes_identity UNIQUE (role, instance, started_at)
);
CREATE INDEX idx_telemetry_processes_last_seen ON ops.telemetry_processes (last_seen);

-- ONE narrow table for every metric (the metric names, kinds and units live in telemetry/TelemetryMetrics.kt, so a new metric
-- needs no migration). `resolution` is the sample's width in seconds: a raw sample stores the sampler's REAL interval (60..1800,
-- a divisor of 3600, so the tier is `resolution < 3600`; kept 7 days), 3600 = an hourly rollup (kept 90 days; `n` = the raw
-- samples folded in, `avg` their n-weighted mean, `max` the largest raw max, so a 5-minute peak survives the rollup).
-- Hard-deleted by the prune (persistence.md "Telemetry (V36)").
CREATE TABLE ops.telemetry_samples (
    resolution INT NOT NULL CHECK (resolution BETWEEN 60 AND 3600 AND 3600 % resolution = 0),
    ts BIGINT NOT NULL,
    process_id INT NOT NULL REFERENCES ops.telemetry_processes (id) ON DELETE CASCADE,
    metric VARCHAR(48) NOT NULL,
    label VARCHAR(120) NOT NULL DEFAULT '',
    n INT NOT NULL DEFAULT 1,
    avg DOUBLE PRECISION NOT NULL,
    max DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (resolution, metric, ts, process_id, label)
);
-- The prune and the hourly rollup scan by (resolution, ts); the FK index keeps a process delete's cascade off a table scan.
CREATE INDEX idx_telemetry_samples_prune ON ops.telemetry_samples (resolution, ts);
CREATE INDEX idx_telemetry_samples_process ON ops.telemetry_samples (process_id);
