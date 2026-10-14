package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.RawIssueDetail
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * [JiraRawViewReads.customFieldFill]'s result: [total] LIVE issues and, per `fields` key, [nonNullByField] — how many of them
 * carry a value there (a key absent from the map has none).
 */
data class CustomFieldFill(val total: Long, val nonNullByField: Map<String, Long>)

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
     * The data profile's custom-field fill-rate input (v0.2.0 plan §8/§12 item 9): the LIVE (not tombstoned, not moved out)
     * issue count and, per `fields` key, how many of those issues "have a value" there.
     *
     * The count runs INSIDE Postgres (`jsonb_each` over `raw.jira_issues.payload`) instead of keyset-paging payloads into the JVM:
     * the heap holds one `(key, count)` pair per DISTINCT field key (about 1,400 on a real Jira site) however many issues the
     * project has — the first real-site SYNC (2,105 issues, 59 MB of JSON) OOMed at `-Xmx256m` while PROFILE held every payload,
     * and a paged variant would still parse every payload in Kotlin — and it is one round trip. Both reads share ONE transaction
     * so [CustomFieldFill.total] and the counts see the same snapshot.
     *
     * "Has a value" is exactly the old Kotlin check: a missing key, a JSON `null` and an empty array do NOT count (an
     * empty array is how Jira reports an unset Sprint/Flagged); `""`, `{}`, `0`, `false` and any other value DO. Every key is
     * returned (no `customfield_` filter) — the caller picks the custom-field ids it cares about. Only the numeric connection
     * id is interpolated into the SQL text, never a string.
     */
    suspend fun customFieldFill(connectionId: UInt): CustomFieldFill = suspendTransaction(database) {
        val total = Issues.selectAll()
            .where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.movedOutAt.isNull() }
            .count()
        // A payload whose `fields` is not an object contributes nothing (jsonb_each would raise on it).
        val rows: Flow<Pair<String, Long>?>? = exec(
            """
            SELECT f.key, count(*) FROM raw.jira_issues i,
              jsonb_each(CASE WHEN jsonb_typeof(i.payload -> 'fields') = 'object' THEN i.payload -> 'fields' ELSE '{}'::jsonb END) f
            WHERE i.connection_id = $connectionId AND i.deleted_at IS NULL AND i.moved_out_at IS NULL
              AND jsonb_typeof(f.value) <> 'null'
              AND NOT (jsonb_typeof(f.value) = 'array' AND jsonb_array_length(f.value) = 0)
            GROUP BY f.key
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, String::class.java)!! to row.get(1, Long::class.javaObjectType)!! }
        CustomFieldFill(total, rows?.toList()?.filterNotNull()?.toMap().orEmpty())
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
