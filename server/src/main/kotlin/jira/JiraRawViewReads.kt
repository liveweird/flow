package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.RawIssueDetail
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The read-only views over the raw rows (v0.2.0 plan §9): the raw issue inspector's lookups, the data profile's bulk
 * reads, the status endpoint's counts, and the test-only row counts.
 */
internal class JiraRawViewReads(private val database: R2dbcDatabase) {

    private fun ResultRow.toRawIssueDetail() = RawIssueDetail(
        issueId = this[Issues.issueId],
        issueKey = this[Issues.issueKey],
        payloadJson = this[Issues.payload],
        sha256 = this[Issues.sha256],
        fetchedAt = this[Issues.fetchedAt],
        changedAt = this[Issues.changedAt],
        deletedAt = this[Issues.deletedAt],
        movedOutAt = this[Issues.movedOutAt],
        needsProcessing = this[Issues.needsProcessing],
    )

    /** The raw issue inspector's "all digits" branch (v0.2.0 plan §9/§12 item 8b) — looked up by the stable Jira id. */
    suspend fun issueById(connectionId: UInt, issueId: Long): RawIssueDetail? = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }
            .toList().singleOrNull()?.toRawIssueDetail()
    }

    /** The raw issue inspector's key branch (v0.2.0 plan §9/§12 item 8b) — looked up by the CURRENT `issue_key` (a move rewrites it). */
    suspend fun issueByKey(connectionId: UInt, issueKey: String): RawIssueDetail? = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueKey eq issueKey) }
            .toList().singleOrNull()?.toRawIssueDetail()
    }

    /**
     * Every non-deleted `raw.jira_entities` row of [kind] for a connection, WITH its `entity_id`
     * (v0.2.0 plan §8/§12 item 9) — unlike [JiraRawProcessReads.entityPayloadsByKind], which drops the id since PROCESS's
     * reference-row rebuild never needs it (the payload alone carries its own `id` field). The data
     * profile's workflow section needs the id (here, a project KEY for `PROJECT_STATUSES`) to know
     * WHICH project a payload belongs to.
     */
    suspend fun entityRowsByKind(connectionId: UInt, kind: String): List<Pair<String, String>> = suspendTransaction(database) {
        Entities.select(Entities.entityId, Entities.payload)
            .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and Entities.deletedAt.isNull() }
            .map { it[Entities.entityId] to it[Entities.payload] }.toList()
    }

    /**
     * Every LIVE (non-tombstoned) issue's raw payload for a connection (v0.2.0 plan §8/§12 item 9)
     * — the data profile's custom-field fill-rate scan.
     */
    suspend fun issuePayloads(connectionId: UInt): List<String> = suspendTransaction(database) {
        Issues.select(Issues.payload)
            .where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.movedOutAt.isNull() }
            .map { it[Issues.payload] }.toList()
    }

    /** `GET …/{id}/status` counts (v0.2.0 plan §9): every `raw.jira_entities` row for a connection, grouped by kind. */
    suspend fun entityCountsByKind(connectionId: UInt): Map<String, Long> = suspendTransaction(database) {
        Entities.select(Entities.kind).where { Entities.connectionId eq connectionId }
            .toList().groupingBy { it[Entities.kind] }.eachCount().mapValues { it.value.toLong() }
    }

    /**
     * `GET …/{id}/status` counts (v0.2.0 plan §9): raw issues tombstoned as deleted or moved out,
     * and issues still flagged for processing.
     */
    suspend fun countIssuesDeleted(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNotNull() }.count()
    }

    suspend fun countIssuesMovedOut(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and Issues.movedOutAt.isNotNull() }.count()
    }

    suspend fun countNeedsProcessing(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.needsProcessing eq true) }.count()
    }

    /** Test-only inspection: the raw row count for a connection (`JiraRawStoreTest`/`JiraSyncPipelineTest`). */
    internal suspend fun countIssues(connectionId: UInt): Long = suspendTransaction(database) {
        Issues.selectAll().where { Issues.connectionId eq connectionId }.count()
    }

    /** Test-only inspection: the changelog history row count for a connection. */
    internal suspend fun countChangelogs(connectionId: UInt): Long = suspendTransaction(database) {
        Changelogs.selectAll().where { Changelogs.connectionId eq connectionId }.count()
    }

    /** Test-only inspection: the worklog row count for a connection, optionally excluding tombstoned rows. */
    internal suspend fun countWorklogs(connectionId: UInt, excludeDeleted: Boolean = false): Long = suspendTransaction(database) {
        val predicate = if (excludeDeleted) {
            (Worklogs.connectionId eq connectionId) and Worklogs.deletedAt.isNull()
        } else {
            Worklogs.connectionId eq connectionId
        }
        Worklogs.selectAll().where { predicate }.count()
    }
}
