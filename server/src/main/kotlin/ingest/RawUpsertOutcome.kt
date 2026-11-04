package ch.nokillswit.ingest

// What a raw write did, shared by every connector's raw store (`jira/JiraRawStore.kt`, `gitlab/GitLabRawStore.kt`).

/**
 * What a raw write did. [REFUSED]: the item is over its stored-size ceiling (Jira: `JiraStoredCeilings`) and nothing was written - an
 * existing
 * row is only touched (`fetched_at`/`last_seen_at`, so REFERENCE's end-of-pass sweep never tombstones it), never changed - and it is no
 * change for a heartbeat's progress signal ([isChange]).
 */
enum class RawUpsertOutcome { INSERTED, UNCHANGED, CHANGED, RESURRECTED, REFUSED }

/** Whether a write changed stored data: the progress signal of `StreamContext.heartbeat`. A refusal changes nothing. */
val RawUpsertOutcome.isChange: Boolean get() = this != RawUpsertOutcome.UNCHANGED && this != RawUpsertOutcome.REFUSED

/**
 * The ONE diff rule of every raw upsert (Jira's issue, entity and worklog writers, single-row and batch; GitLab's project and user
 * writers): no stored row inserts; the same sha256 on a live row is unchanged; a tombstoned row seen again is resurrected whatever
 * its hash; any other difference is a change. [tombstoned] is the stored row's own notion (a Jira issue: deleted OR moved out; a
 * GitLab project: deleted OR moved out; an entity, worklog or user: deleted).
 */
internal fun classifyUpsert(storedHash: String?, newHash: String, tombstoned: Boolean): RawUpsertOutcome = when {
    storedHash == null -> RawUpsertOutcome.INSERTED
    tombstoned -> RawUpsertOutcome.RESURRECTED
    storedHash == newHash -> RawUpsertOutcome.UNCHANGED
    else -> RawUpsertOutcome.CHANGED
}
