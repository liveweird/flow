package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.norm.Normalization
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemStore
import org.slf4j.LoggerFactory

/** `raw.jira_issues`'s own claim-scan batch size (v0.2.0 plan §8) — the PROCESS step's page size. */
private const val PROCESS_BATCH_SIZE = 50

private val log = LoggerFactory.getLogger(JiraProcessStream::class.java)

/**
 * The PROCESS stream (v0.2.0 plan §7/§8, plan commit 8a): rebuilds `norm.*` reference rows once per
 * run, then replaces every stale raw issue's normalized rows batch by batch
 * (`jira/JiraRawStore.kt`'s `issuesToProcess`, `needs_processing OR processing_version IS DISTINCT
 * FROM` [PROCESSING_VERSION]).
 *
 * **Per-issue transaction, per-BATCH heartbeat** — a deliberate reading of plan §8's "one tx per
 * batch, per-issue failure isolated": PostgreSQL aborts an entire transaction on the FIRST failing
 * statement (unlike a savepoint-per-statement model), so sharing one transaction across a whole
 * batch would make one issue's failure roll back every OTHER issue already written in that same
 * batch — the opposite of "isolated". Each issue therefore gets its OWN `context.transaction { }`
 * (matching `jira/JiraChangelogStream.kt`'s own per-issue fallback path, which hit the identical
 * tension and resolved it the same way), while [StreamContext.heartbeat] — and the batch's own
 * `issuesProcessed`/`issuesFailed` progress counters — are still only flushed ONCE per batch,
 * matching the plan's literal heartbeat/progress cadence.
 */
class JiraProcessStream(
    private val rawStore: JiraRawStore,
    private val workItemStore: WorkItemStore,
) : Stream {
    override val name: String = "process"

    override suspend fun run(context: StreamContext) {
        val connectionId = context.connectionId
        val fieldIds = JiraNormalizer.discoverFieldIds(rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.FIELD.name))
        val statusLookup = buildStatusLookup(rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.STATUS.name))
        rebuildReferenceRows(connectionId)

        while (true) {
            val batchIds = rawStore.issuesToProcess(connectionId, PROCESSING_VERSION, PROCESS_BATCH_SIZE)
            if (batchIds.isEmpty()) break
            processBatch(context, connectionId, batchIds, fieldIds, statusLookup)
            context.heartbeat()
        }
    }

    private suspend fun processBatch(
        context: StreamContext,
        connectionId: UInt,
        batchIds: List<Long>,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
    ) {
        var firstFailure: Exception? = null
        var failedInBatch = 0
        batchIds.forEach { issueId ->
            val failure = processOneIssueSafely(context, connectionId, issueId, fieldIds, statusLookup)
            if (failure == null) {
                context.incrementProgress("issuesProcessed")
            } else {
                if (firstFailure == null) firstFailure = failure
                failedInBatch++
                context.incrementProgress("issuesFailed")
            }
        }
        // `StreamContext.progress` is counters-only (Long) — the first failure's OWN detail is
        // logged rather than threaded through it, the same "counter + a logged detail, not a
        // second progress shape" trade `ingest/IngestWorker.kt`'s job-level `error_detail` makes for
        // the WHOLE job (there, one column; here, a per-batch log line is enough for an isolated
        // per-issue failure that a later PROCESS pass simply retries).
        firstFailure?.let { log.warn("PROCESS batch: {} of {} issue(s) failed; first: {}", failedInBatch, batchIds.size, it.message, it) }
    }

    /**
     * A per-issue failure is isolated (plan §8): the issue is simply left `needs_processing = true`
     * (its raw row is never touched on this path) for the NEXT PROCESS pass to retry — never
     * rethrown, never aborting the rest of the batch. Returns the caught exception, or null on success.
     */
    private suspend fun processOneIssueSafely(
        context: StreamContext,
        connectionId: UInt,
        issueId: Long,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
    ): Exception? = try {
        context.transaction { processOneIssue(connectionId, issueId, fieldIds, statusLookup, context.clock()) }
        null
    } catch (failure: Exception) {
        failure
    }

    private suspend fun processOneIssue(
        connectionId: UInt,
        issueId: Long,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
        now: Long,
    ) {
        val raw = rawStore.issueForProcessing(connectionId, issueId) ?: return // a concurrent PURGE already removed it — nothing to do.
        val changelogs = rawStore.changelogPayloadsForIssue(connectionId, issueId)
        val worklogs = rawStore.worklogPayloadsForIssue(connectionId, issueId)
        val tombstone = when {
            raw.deletedAt != null -> TombstoneKind.DELETED
            raw.movedOutAt != null -> TombstoneKind.MOVED_OUT
            else -> TombstoneKind.NONE
        }
        val input = JiraNormalizer.normalizeIssue(raw.payloadJson, changelogs, worklogs, fieldIds, tombstone)
        val normalized = Normalization.normalize(input) { statusId -> statusLookup[statusId] ?: (statusId to StatusCategory.UNKNOWN) }
        workItemStore.replaceWorkItem(connectionId, normalized, now)
        rawStore.markProcessed(connectionId, issueId, raw.sha256, now, PROCESSING_VERSION)
    }

    /** Reference rows, rebuilt WHOLESALE per connection (plan §8) — read fresh from `raw.jira_entities` every PROCESS run. */
    private suspend fun rebuildReferenceRows(connectionId: UInt) {
        val statusPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.STATUS.name)
        workItemStore.replaceStatuses(connectionId, JiraNormalizer.statusRefs(statusPayloads))
        val userPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.USER.name)
        workItemStore.replacePeople(connectionId, JiraNormalizer.peopleRefs(userPayloads))
        workItemStore.replaceBoards(
            connectionId,
            JiraNormalizer.boardRefs(
                rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.BOARD.name),
                rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.BOARD_CONFIGURATION.name),
            ),
        )
        val sprintPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.SPRINT.name)
        workItemStore.replaceSprints(connectionId, JiraNormalizer.sprintRefs(sprintPayloads))
    }

    private fun buildStatusLookup(statusPayloads: List<String>): Map<String, Pair<String, StatusCategory>> =
        JiraNormalizer.statusRefs(statusPayloads).associate { it.statusId to (it.name to it.category) }
}
