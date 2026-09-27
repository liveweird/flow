-- Phase 3 (v0.3.0 M1 commit 2) norm gaps (`.claude/docs/domain-model.md` "Gaps in `norm` today"):
-- additive-only columns PROCESS needs before the `metrics` layer can be built. `PROCESSING_VERSION`
-- bumps to 2 (`norm/Normalization.kt`) alongside this migration, so every existing issue reprocesses
-- automatically on the next PROCESS pass and these new columns backfill without a data migration
-- here. No existing V1-V13 file is touched.

-- Epic membership history (parent moves), the epic dates and every custom field's current value —
-- `norm/WorkItemStore.kt`'s `replaceWorkItem` writes these on every issue's REPLACE.
ALTER TABLE norm.work_items
    ADD COLUMN hierarchy_level INTEGER,
    ADD COLUMN due_at BIGINT,
    ADD COLUMN custom_fields JSONB NOT NULL DEFAULT '{}';

-- `field_id` is the changelog item's own field id (`customfield_10030`, `duedate`, `parent`, ...) —
-- `field` alone is a display name only, unreliable across a field rename; this is the metrics
-- layer's per-field replay key (`norm/WorkItemStore.kt`'s `fieldChangesByFieldIds`).
ALTER TABLE norm.work_item_field_changes ADD COLUMN field_id VARCHAR(100);
CREATE INDEX idx_norm_work_item_field_changes_field ON norm.work_item_field_changes (connection_id, field_id, issue_id);

-- Worklog `created`/`updated` (report 14's late-logging measure) — `raw.jira_worklogs.payload`
-- already carries both; only `started` was kept here until now.
ALTER TABLE norm.work_item_worklogs
    ADD COLUMN created_at BIGINT,
    ADD COLUMN updated_at BIGINT;

-- Sprint `completeDate` — the metrics layer keys sprint periods on completion, not `end_at`.
ALTER TABLE norm.sprints ADD COLUMN complete_at BIGINT;
