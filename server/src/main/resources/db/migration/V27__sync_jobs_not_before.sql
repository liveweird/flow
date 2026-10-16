-- Debounced DERIVE (storage optimization, decision D): a configuration-caused DERIVE is scheduled no earlier than
-- "now + a quiet period", and a further edit inside that period pushes the time out, so a burst of edits becomes ONE
-- DERIVE. `not_before` (epoch millis) is that time: the claim skips a PENDING job whose `not_before` is still in the
-- future. NULL (every existing row, every SYNC/RECONCILE/REPROCESS/PURGE, every manual and chained DERIVE) = due at once.
-- Nullable, no default, no backfill: a metadata-only change. `idx_sync_jobs_claimable` is unchanged - the condition is
-- a filter over the (at most one per connection and kind) open rows the partial index already narrows to.
ALTER TABLE sync_jobs ADD COLUMN not_before BIGINT;
