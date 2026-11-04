package ch.nokillswit.ingest

/**
 * A connection's `backfillFrom` (`yyyy-MM-dd`, `source_connections.backfill_from`) as epoch millis -
 * UTC midnight of that day. The ONE conversion shared by every connector's first-run window (Jira's ISSUES stream and its
 * RECONCILE sweep, GitLab's commit walk), so they all name the same instant.
 */
fun backfillFromEpochMillis(backfillFrom: String): Long =
    java.time.LocalDate.parse(backfillFrom).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
