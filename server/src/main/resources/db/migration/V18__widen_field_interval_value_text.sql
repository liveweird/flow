-- Widen `norm.work_item_field_intervals.value_text` from VARCHAR(500) (V13) to TEXT. A SPRINT
-- interval's `value_text` is the comma-joined sprint names Jira's changelog `toString` carries
-- (`jira/JiraNormalizer.kt`, stored verbatim by `norm/Tiling.kt`): unbounded Jira text, and an issue
-- carried through ~15+ sprints overflowed 500 chars. Exposed's client-side varchar length check
-- rejected the write before any SQL was sent, so the issue stayed `needs_processing` on every PROCESS
-- pass (and, alone on its page, failed the whole PROCESS job). VARCHAR(n) -> TEXT is binary-coercible, so Postgres
-- rewrites nothing (a catalog-only change); no existing V1-V17 file changes (V13's bytes are
-- immutable — `MigrationChecksumTest`), no data migration, no reader changes.
ALTER TABLE norm.work_item_field_intervals ALTER COLUMN value_text TYPE TEXT;
