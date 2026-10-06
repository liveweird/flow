package ch.nokillswit.jira

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.norm.Normalization
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemStore
import io.r2dbc.spi.R2dbcException
import org.slf4j.LoggerFactory

/** `raw.jira_issues`'s own claim-scan batch size (v0.2.0 plan §8) — the PROCESS step's page size. */
private const val PROCESS_BATCH_SIZE = 50

/** One issue that failed PROCESS on its own — the id is what the log line names. */
private class IssueFailure(val issueId: Long, val error: Exception)

/**
 * Exposed's own text for a value longer than its `varchar(n)` column. Exposed enforces the length
 * CLIENT-side (`VarCharColumnType.validateValueBeforeUpdate` throws an [IllegalArgumentException]
 * before any SQL is sent), so such a row never produces a PostgreSQL SQLSTATE. The message is
 * Exposed's, not ours: `JiraProcessFailureClassTest` (against real Exposed) and `NormalizationPipelineTest`'s
 * over-long-issue-key test pin it, so an Exposed upgrade that rewords it goes red instead of silently
 * reclassifying bad rows as outages.
 */
private const val EXPOSED_VARCHAR_TOO_LONG = "Value can't be stored to database column because exceeds length"

/**
 * True when [this] (or any cause) is a bad VALUE: a PostgreSQL data exception (SQLSTATE class 22 — value too
 * long, NUL, out of range …) or Exposed's client-side `varchar(n)` length rejection (an [IllegalArgumentException]
 * whose message starts with [EXPOSED_VARCHAR_TOO_LONG] — deliberately NOT every [IllegalArgumentException], which
 * would mask programming errors as bad rows). Retrying the same value cannot succeed. This is the narrow class
 * the wholesale reference rebuild tolerates: an integrity violation there is the writer's own bug.
 */
internal fun Throwable.isBadValueError(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if ((cur as? R2dbcException)?.sqlState?.startsWith("22") == true) return true
        if (cur is IllegalArgumentException && cur.message?.startsWith(EXPOSED_VARCHAR_TOO_LONG) == true) return true
        cur = cur.cause
    }
    return false
}

/**
 * True when [this] (or any cause) is a bad ROW: a [bad value][isBadValueError] or an integrity violation
 * (SQLSTATE class 23 — PK/unique/FK/NOT NULL/CHECK) on a PER-ISSUE write, where another issue's row or stale
 * `norm` state can legitimately collide. Retrying the same row cannot succeed, so it must not be mistaken for
 * an outage.
 */
internal fun Throwable.isDataError(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if ((cur as? R2dbcException)?.sqlState?.startsWith("23") == true) return true
        cur = cur.cause
    }
    return isBadValueError()
}

private val log = LoggerFactory.getLogger(JiraProcessStream::class.java)

/**
 * The PROCESS stream (v0.2.0 plan §7/§8, plan commit 8a): rebuilds `norm.*` reference rows once per
 * run, then replaces every stale raw issue's normalized rows page by page
 * (`jira/JiraRawStore.kt`'s `issuesToProcess`, `needs_processing OR processing_version IS DISTINCT
 * FROM` [PROCESSING_VERSION]).
 *
 * **One transaction per PAGE, isolation kept by a per-issue fallback.** A page (50 issues) is read
 * in three queries (issues, changelogs, worklogs), each issue is normalized in memory (a pure
 * function: an issue that fails there is skipped and touches nothing), and the whole page's rows are
 * written in ONE `context.transaction { }` — `WorkItemStore.replaceWorkItems` (one delete, one insert
 * batch per table) plus `JiraRawStore.markProcessedBatch`. Plan §8 asks for "one tx per batch,
 * per-issue failure isolated", and PostgreSQL aborts a whole transaction on its FIRST failing
 * statement, so a DB error in the page write would roll back every OTHER issue of the page too.
 * That case therefore falls back to the per-issue path — each issue of the page in its OWN
 * transaction ([processOneIssueSafely], matching `jira/JiraChangelogStream.kt`'s own per-issue
 * fallback) — where a failing issue is left `needs_processing = true` and the rest still land. The
 * fast path is the common one; measured, per-issue transactions cost ~13 statement round trips PER
 * ISSUE (`.claude/docs/build-times.md` "PROCESS"). [StreamContext.heartbeat] and the
 * `issuesProcessed`/`issuesFailed` progress counters are flushed ONCE per page either way. The page
 * read locks its raw rows `FOR UPDATE` until commit, and so does the per-issue fallback's read (a
 * concurrent re-flag waits, never lost). If the page failed in the database AND every issue then
 * failed on its own, and NONE of those failures is a bad-row error ([isDataError]: SQLSTATE class 22
 * or 23, or Exposed's client-side column-length check), the page error is rethrown: the job fails (a SYNC is
 * rescheduled with backoff; a REPROCESS/RECONCILE needs a manual re-run) instead of ending SUCCEEDED over
 * stale `norm` — that pattern is an outage, not a row. A bad row alone on its page ends the run normally
 * (`issuesFailed`, retried by the next PROCESS pass).
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
        val issueTypeHierarchy =
            JiraNormalizer.issueTypeHierarchy(rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.ISSUE_TYPE.name))
        val skippedReferenceRows = rebuildReferenceRows(connectionId)
        if (skippedReferenceRows > 0) context.incrementProgress("referenceRowsSkipped", skippedReferenceRows.toLong())

        // Keyset paging: each stale issue is visited ONCE per run. An issue that fails stays flagged
        // for the NEXT PROCESS pass (plan §8) instead of being re-claimed by this loop forever.
        var afterIssueId: Long? = null
        while (true) {
            val batchIds = rawStore.issuesToProcess(connectionId, PROCESSING_VERSION, PROCESS_BATCH_SIZE, afterIssueId)
            if (batchIds.isEmpty()) break
            processBatch(context, connectionId, batchIds, fieldIds, statusLookup, issueTypeHierarchy)
            afterIssueId = batchIds.last()
            context.heartbeat()
        }
    }

    private suspend fun processBatch(
        context: StreamContext,
        connectionId: UInt,
        batchIds: List<Long>,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
        issueTypeHierarchy: Map<String, Int>,
    ) {
        val now = context.clock()
        var pageError: Exception? = null
        val pageFailures = catchingFailures<List<IssueFailure>?>(
            block = {
                context.transaction { processPage(connectionId, batchIds, fieldIds, statusLookup, issueTypeHierarchy, now) }
            },
            onFailure = { failure ->
                pageError = failure
                log.warn(
                    "PROCESS page of {} issue(s) [{}..{}] failed as a unit, retrying one by one: {}",
                    batchIds.size, batchIds.first(), batchIds.last(), failure.message, failure,
                )
                null
            },
        )
        val failures = pageFailures ?: batchIds.mapNotNull { issueId ->
            processOneIssueSafely(context, connectionId, issueId, fieldIds, statusLookup, issueTypeHierarchy)
        }
        // A page-level DB error where EVERY issue then failed on its own too — and none of the failures
        // is a data/integrity error (a bad ROW) — looks like an outage: end the job (it fails and is
        // retried by the scheduler/operator) rather than finish SUCCEEDED and chain a DERIVE on stale
        // `norm`. A PARTIAL failure, or an all-bad-rows page, keeps the isolated-issue outcome
        // (`issuesFailed`, retried by the next PROCESS pass).
        pageError?.let { error -> if (failures.size == batchIds.size && failures.none { it.error.isDataError() }) throw error }
        context.incrementProgress("issuesProcessed", (batchIds.size - failures.size).toLong())
        if (failures.isNotEmpty()) context.incrementProgress("issuesFailed", failures.size.toLong())
        // `StreamContext.progress` is counters-only (Long) — the failures' detail is logged rather
        // than threaded through it, the same "counter + a logged detail, not a second progress
        // shape" trade `ingest/IngestWorker.kt`'s job-level `error_detail` makes for the WHOLE job
        // (there, one column; here, a per-page log line is enough for an isolated per-issue failure
        // that a later PROCESS pass simply retries).
        failures.firstOrNull()?.let {
            log.warn(
                "PROCESS batch: {} of {} issue(s) failed, issue id(s) {}; first: {}",
                failures.size, batchIds.size, failures.map { failure -> failure.issueId }, it.error.message, it.error,
            )
        }
    }

    /**
     * The fast path, INSIDE the page transaction: three batched reads (the issue read takes the raw
     * rows `FOR UPDATE`, see [JiraRawStore.issuesForProcessing]), in-memory normalization, one batched
     * REPLACE + one batched `markProcessed` stamping the sha256 that was read. Returns the issues that
     * failed NORMALIZATION — they were skipped without touching the database, so the transaction stays
     * valid and every other issue of the page still lands (an issue a concurrent PURGE removed is
     * simply absent from the read and counts as done, as in the per-issue path). A DB error
     * propagates and aborts the page. Memory: a page holds its 50 issues' payloads, changelogs and
     * worklogs at once — bounded by the data (fine at this scale: ~a few MB per page).
     */
    private suspend fun processPage(
        connectionId: UInt,
        batchIds: List<Long>,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
        issueTypeHierarchy: Map<String, Int>,
        now: Long,
    ): List<IssueFailure> {
        val rawIssues = rawStore.issuesForProcessing(connectionId, batchIds)
        val changelogs = rawStore.changelogPayloadsForIssues(connectionId, batchIds)
        val worklogs = rawStore.worklogPayloadsForIssues(connectionId, batchIds)
        val failures = mutableListOf<IssueFailure>()
        val normalized = mutableListOf<NormalizedIssue>()
        rawIssues.forEach { raw ->
            val issueChangelogs = changelogs[raw.issueId].orEmpty()
            val issueWorklogs = worklogs[raw.issueId].orEmpty()
            catchingFailures(
                block = { normalized.add(normalizeRaw(raw, issueChangelogs, issueWorklogs, fieldIds, statusLookup, issueTypeHierarchy)) },
                onFailure = { failures.add(IssueFailure(raw.issueId, it)) },
            )
        }
        workItemStore.replaceWorkItems(connectionId, normalized, now)
        val processedIds = normalized.map { it.issueId }.toSet()
        val readShas = rawIssues.filter { it.issueId in processedIds }.associate { it.issueId to it.sha256 }
        rawStore.markProcessedBatch(connectionId, readShas, now, PROCESSING_VERSION)
        return failures
    }

    /**
     * The per-issue fallback (plan §8): the issue is simply left `needs_processing = true` (its raw
     * row is never touched on this path) for the NEXT PROCESS pass to retry — never rethrown, never
     * aborting the rest of the page. Returns the failure, or null on success.
     */
    private suspend fun processOneIssueSafely(
        context: StreamContext,
        connectionId: UInt,
        issueId: Long,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
        issueTypeHierarchy: Map<String, Int>,
    ): IssueFailure? = catchingFailures<IssueFailure?>(
        block = {
            context.transaction { processOneIssue(connectionId, issueId, fieldIds, statusLookup, issueTypeHierarchy, context.clock()) }
            null
        },
        onFailure = { IssueFailure(issueId, it) },
    )

    private suspend fun processOneIssue(
        connectionId: UInt,
        issueId: Long,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
        issueTypeHierarchy: Map<String, Int>,
        now: Long,
    ) {
        val raw = rawStore.issueForProcessing(connectionId, issueId) ?: return // a concurrent PURGE already removed it — nothing to do.
        val changelogs = rawStore.changelogPayloadsForIssue(connectionId, issueId)
        val worklogs = rawStore.worklogPayloadsForIssue(connectionId, issueId)
        val normalized = normalizeRaw(raw, changelogs, worklogs, fieldIds, statusLookup, issueTypeHierarchy)
        workItemStore.replaceWorkItem(connectionId, normalized, now)
        rawStore.markProcessed(connectionId, issueId, raw.sha256, now, PROCESSING_VERSION)
    }

    /** One raw issue plus its changelog/worklog payloads → its tiled write-shape (pure — no database access). */
    private fun normalizeRaw(
        raw: JiraRawStore.RawIssueForProcessing,
        changelogs: List<String>,
        worklogs: List<String>,
        fieldIds: JiraFieldIds,
        statusLookup: Map<String, Pair<String, StatusCategory>>,
        issueTypeHierarchy: Map<String, Int>,
    ): NormalizedIssue {
        val tombstone = when {
            raw.deletedAt != null -> TombstoneKind.DELETED
            raw.movedOutAt != null -> TombstoneKind.MOVED_OUT
            else -> TombstoneKind.NONE
        }
        // The RAW tombstone time, verbatim — never `now`: a PROCESSING_VERSION bump reprocesses
        // every already-tombstoned issue, and `now` would reset every one of them to THIS run's own
        // clock (v0.3.0 M1 commit 2 review fix). Exactly one of the two is ever non-null.
        val tombstoneAtMs = raw.deletedAt ?: raw.movedOutAt
        val input =
            JiraNormalizer.normalizeIssue(raw.payloadJson, changelogs, worklogs, fieldIds, tombstone, issueTypeHierarchy, tombstoneAtMs)
        return Normalization.normalize(input) { statusId -> statusLookup[statusId] ?: (statusId to StatusCategory.UNKNOWN) }
    }

    /**
     * Reference rows, rebuilt WHOLESALE per connection (plan §8) — read fresh from `raw.jira_entities` every PROCESS run.
     * Returns how many rows were left out (a table that failed whole counts as ONE). The free-text columns (names,
     * display names, e-mail) are `TEXT` (V19), so what can still overflow is a bounded KEY/enum value: the store skips
     * and logs that row ([WorkItemStore.replaceStatuses] and friends), and a table whose rebuild still fails with a bad
     * VALUE ([isBadValueError]: SQLSTATE class 22 or Exposed's length check) keeps its previous rows — logged — instead
     * of failing the whole run (these writes sit outside the per-issue bad-row classifier). Anything else ends the run:
     * a connection loss (an outage), a payload that does not parse (a connector bug) or an INTEGRITY violation (class
     * 23 — NOT NULL, duplicate keys: the wholesale delete-then-insert rebuild has no legitimate collision, so that is
     * a writer bug that must be loud, unlike a per-issue row that can collide with stale state).
     */
    private suspend fun rebuildReferenceRows(connectionId: UInt): Int {
        val statusPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.STATUS.name)
        val userPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.USER.name)
        val boardPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.BOARD.name)
        val boardConfigPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.BOARD_CONFIGURATION.name)
        val sprintPayloads = rawStore.entityPayloadsByKind(connectionId, JiraEntityKind.SPRINT.name)
        return rebuildReferenceTable("norm.statuses") {
            workItemStore.replaceStatuses(connectionId, JiraNormalizer.statusRefs(statusPayloads))
        } + rebuildReferenceTable("norm.people") {
            workItemStore.replacePeople(connectionId, JiraNormalizer.peopleRefs(userPayloads))
        } + rebuildReferenceTable("norm.boards") {
            workItemStore.replaceBoards(connectionId, JiraNormalizer.boardRefs(boardPayloads, boardConfigPayloads))
        } + rebuildReferenceTable("norm.sprints") {
            workItemStore.replaceSprints(connectionId, JiraNormalizer.sprintRefs(sprintPayloads))
        }
    }

    /** One reference table's rebuild: its skipped-row count, or `1` when a bad value kept the previous rows; other failures propagate. */
    private suspend fun rebuildReferenceTable(table: String, rebuild: suspend () -> Int): Int = catchingFailures(
        block = { rebuild() },
        onFailure = { failure ->
            if (!failure.isBadValueError()) throw failure
            log.warn("PROCESS: {} rebuild failed on a bad value, keeping the previous rows: {}", table, failure.message, failure)
            1
        },
    )

    private fun buildStatusLookup(statusPayloads: List<String>): Map<String, Pair<String, StatusCategory>> =
        JiraNormalizer.statusRefs(statusPayloads).associate { it.statusId to (it.name to it.category) }
}
