-- Drop the redundant per-issue index on norm.work_item_field_intervals (storage optimization).
-- idx_norm_work_item_field_intervals_issue (connection_id, issue_id, field) is a strict prefix of the unique key
-- (connection_id, issue_id, field, seq) from V13, so the unique index serves every query it served. It held ~1.7 MB.
DROP INDEX IF EXISTS norm.idx_norm_work_item_field_intervals_issue;
