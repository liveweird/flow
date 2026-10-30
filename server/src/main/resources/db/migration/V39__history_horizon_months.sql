-- V39: the history horizon (domain-model A48), a global metrics setting: work older than this many months is hidden from the reports
-- (and, in a later release, floors the data-source backfill). NULL = off (no horizon); the column defaults to 24, so an existing
-- deployment wakes up at 24 months. It is read at QUERY time over stored figures; no derived row depends on it, so no config_revision
-- bump and no DERIVE is enqueued here, and no raw/norm/metrics row is ever removed because of it.
ALTER TABLE metrics.settings
    ADD COLUMN history_horizon_months INTEGER NULL DEFAULT 24
        CHECK (history_horizon_months IS NULL OR (history_horizon_months >= 6 AND history_horizon_months <= 120));
