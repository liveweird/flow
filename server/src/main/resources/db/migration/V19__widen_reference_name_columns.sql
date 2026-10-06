-- Widen every Jira-supplied FREE-TEXT NAME column from VARCHAR(n) (V13/V15/V16) to TEXT:
--  * the reference rows PROCESS rebuilds wholesale — status, board and board-column names, sprint names
--    (norm.* and the metrics.dim_sprint copy DERIVE writes from them), people's display names and e-mail;
--  * the per-issue copies of the same names — the status name (norm.work_items and
--    norm.work_item_status_intervals, filled from the norm.statuses lookup), the issue type, resolution and
--    priority names — and everything DERIVE copies the issue type into (metrics.dim_task.issue_type, the
--    activity type that defaults to it in dim_task/fact_task_delivery/fact_worklog, and the
--    metrics.activity_type_map pair that maps one onto the other);
--  * the changelog field name (norm.work_item_field_changes.field — for a custom field it is the field's
--    DISPLAY name, up to 255 characters) and the work-category option id/label the metrics config stores
--    (metrics.work_category_map.value_id/value_name — for a primitive-valued field the value text IS the id).
-- They hold unbounded Jira text with no meaning for a limit beyond storage. The reference rebuild runs
-- outside PROCESS's per-issue bad-row classifier, so Exposed's client-side varchar length check used to
-- reject an over-long value before any SQL was sent and fail the entire run on every pass; widening only the
-- reference column would just have turned that into issues failing quietly (the status name is also written
-- per issue). The V18 precedent, one table over. Identifiers, keys and enum-like values (status_id,
-- account_id, board_type, state, project_key, issue_key, ...) stay bounded: the reference rebuild skips and logs
-- a row whose bounded value overflows, and a per-issue one is a counted bad row.
-- VARCHAR(n) -> TEXT is binary-coercible, so PostgreSQL rewrites neither the table nor any index (a
-- catalog-only change; the only indexes on these columns are the primary keys of metrics.activity_type_map and
-- metrics.work_category_map, which are reused as-is); no existing V1-V18 file changes (their bytes are
-- immutable — `MigrationChecksumTest`), no data migration, no reader changes.
ALTER TABLE norm.statuses ALTER COLUMN name TYPE TEXT;
ALTER TABLE norm.people ALTER COLUMN display_name TYPE TEXT;
ALTER TABLE norm.people ALTER COLUMN email TYPE TEXT;
ALTER TABLE norm.boards ALTER COLUMN name TYPE TEXT;
ALTER TABLE norm.board_columns ALTER COLUMN name TYPE TEXT;
ALTER TABLE norm.sprints ALTER COLUMN name TYPE TEXT;
ALTER TABLE norm.work_items ALTER COLUMN issue_type TYPE TEXT;
ALTER TABLE norm.work_items ALTER COLUMN status_name TYPE TEXT;
ALTER TABLE norm.work_items ALTER COLUMN resolution TYPE TEXT;
ALTER TABLE norm.work_items ALTER COLUMN priority TYPE TEXT;
ALTER TABLE norm.work_item_status_intervals ALTER COLUMN status_name TYPE TEXT;
ALTER TABLE metrics.dim_sprint ALTER COLUMN name TYPE TEXT;
ALTER TABLE metrics.dim_task ALTER COLUMN issue_type TYPE TEXT;
ALTER TABLE metrics.dim_task ALTER COLUMN activity_type TYPE TEXT;
ALTER TABLE metrics.fact_task_delivery ALTER COLUMN activity_type TYPE TEXT;
ALTER TABLE metrics.fact_worklog ALTER COLUMN activity_type TYPE TEXT;
ALTER TABLE metrics.activity_type_map ALTER COLUMN issue_type TYPE TEXT;
ALTER TABLE metrics.activity_type_map ALTER COLUMN activity_type TYPE TEXT;
ALTER TABLE norm.work_item_field_changes ALTER COLUMN field TYPE TEXT;
ALTER TABLE metrics.work_category_map ALTER COLUMN value_id TYPE TEXT;
ALTER TABLE metrics.work_category_map ALTER COLUMN value_name TYPE TEXT;
