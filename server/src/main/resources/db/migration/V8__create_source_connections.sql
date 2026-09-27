-- Generic connector registry (v0.2.0 plan §3/§4): ONE source_connections table serves every
-- connector kind — Jira Cloud today, GitLab later — sharing the sync-job queue and cursors (V9)
-- and the worker still to come. Connector-specific fields (siteUrl, email, projectKeys,
-- authScheme, cloudId) live in the `settings` jsonb rather than their own columns, so a new
-- connector kind reuses this table outright. Stays in `public` (the main-session PG-schemas
-- amendment, §0 A3): this is operational/foundation state, not connector-raw data — that goes in
-- the `raw` schema starting at V10.
CREATE TABLE source_connections (
    id SERIAL PRIMARY KEY,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('JIRA_CLOUD')),
    "name" VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- FieldCipher envelope (infra/crypto/FieldCipher.kt) — the scoped read-only API token. The
    -- first EncryptedAtRest consumer (infra/db/Bootstrap.kt's encryptedAtRestServices()).
    secret TEXT NOT NULL,
    -- Connector-specific config: {siteUrl, email, projectKeys, authScheme, cloudId?} for Jira.
    -- Stored canonicalized (infra/json/CanonicalJson.kt) through the repo-local jsonb binding
    -- (infra/db/Jsonb.kt — exposed-r2dbc 1.5.0 ships no ready JSON column type).
    settings JSONB NOT NULL,

    -- Schedule
    sync_interval_minutes INTEGER NOT NULL CHECK (sync_interval_minutes BETWEEN 5 AND 1440),
    -- ISO date (YYYY-MM-DD) text rather than a DATE column — no Exposed date() dependency for a
    -- value only ever read/written whole, and validated in Kotlin (ingest/DataSource.kt).
    backfill_from VARCHAR(10) NOT NULL,
    reconcile_hour_utc INTEGER NOT NULL DEFAULT 3 CHECK (reconcile_hour_utc BETWEEN 0 AND 23),
    config_revision BIGINT NOT NULL DEFAULT 1 CHECK (config_revision >= 1),
    next_sync_at BIGINT,

    -- Sync status — populated by the worker arriving in commit 5 of the plan; NULL until the
    -- first run.
    last_sync_started_at BIGINT,
    last_sync_succeeded_at BIGINT,
    last_sync_error_code VARCHAR(100),
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_reconcile_at BIGINT,

    -- Data profile (plan commit 9) and housekeeping.
    profile JSONB,
    profile_at BIGINT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Name uniqueness among ACTIVE connections only, case-insensitively (the soft-delete convention:
-- a deleted connection frees its name).
CREATE UNIQUE INDEX uq_source_connections_name_active ON source_connections (LOWER("name")) WHERE NOT marked_as_deleted;
