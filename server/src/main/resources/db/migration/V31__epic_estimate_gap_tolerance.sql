-- V31: the epic estimate-gap tolerance (Data quality's "estimate differs from its tasks" finding, the Home overview) becomes a
-- metrics setting, default 25 % (the former constant EPIC_ESTIMATE_GAP_TOLERANCE in reports/DataQualityEpics.kt). It is read at
-- QUERY time over stored figures; no derived row depends on it, so no config_revision bump and no DERIVE is enqueued here.
ALTER TABLE metrics.settings
    ADD COLUMN epic_estimate_gap_tolerance_percent INTEGER NOT NULL DEFAULT 25
        CHECK (epic_estimate_gap_tolerance_percent >= 0 AND epic_estimate_gap_tolerance_percent <= 100);
