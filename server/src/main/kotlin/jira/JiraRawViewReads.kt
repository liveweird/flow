package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.RawIssueDetail
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
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

    /** The stable Jira id of the issue with this CURRENT key (a move rewrites it), without reading its payload. */
    suspend fun issueIdByKey(connectionId: UInt, issueKey: String): Long? = suspendTransaction(database) {
        Issues.select(Issues.issueId).where { (Issues.connectionId eq connectionId) and (Issues.issueKey eq issueKey) }
            .toList().singleOrNull()?.get(Issues.issueId)
    }

    /**
     * The stored text of one issue in bytes - its payload plus its histories plus its live worklogs, `octet_length` of the jsonb text,
     * summed inside Postgres (one statement; only numbers reach the SQL text). The raw issue inspector compares it with
     * `jira.processPageBytes` BEFORE it reads any of that text, so the `web` role never holds an issue PROCESS would refuse. 0 for no row.
     */
    suspend fun storedBytesForIssue(connectionId: UInt, issueId: Long): Long = suspendTransaction(database) {
        val sums: Flow<Long?>? = exec(
            """
            SELECT (SELECT COALESCE(sum(octet_length(payload::text)), 0) FROM raw.jira_issues
                    WHERE connection_id = $connectionId AND issue_id = $issueId)
                 + (SELECT COALESCE(sum(octet_length(payload::text)), 0) FROM raw.jira_changelogs
                    WHERE connection_id = $connectionId AND issue_id = $issueId)
                 + (SELECT COALESCE(sum(octet_length(payload::text)), 0) FROM raw.jira_worklogs
                    WHERE connection_id = $connectionId AND issue_id = $issueId AND deleted_at IS NULL)
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType)!! }
        sums?.toList()?.firstOrNull() ?: 0L
    }

    /**
     * The OLDEST `last_seen_at` among the connection's LIVE entities of [kind], `null` when it has none: when a full pass last
     * saw them all.
     */
    suspend fun oldestLiveEntitySeenAt(connectionId: UInt, kind: String): Long? = suspendTransaction(database) {
        val oldest = Entities.lastSeenAt.min()
        Entities.select(oldest)
            .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and Entities.deletedAt.isNull() }
            .toList().singleOrNull()?.get(oldest)
    }

    /**
     * The data profile's custom-field fill-rate input (v0.2.0 plan §8/§12 item 9): the LIVE (not tombstoned, not moved out)
     * issue count and, per `fields` key, how many of those issues "have a value" there.
     *
     * The count runs INSIDE Postgres (`jsonb_each` over `raw.jira_issues.payload`) instead of keyset-paging payloads into the JVM:
     * the heap holds one `(key, count)` pair per DISTINCT field key (about 1,400 on a real Jira site) however many issues the
     * project has — the first real-site SYNC (2,105 issues, 59 MB of JSON) OOMed at `-Xmx256m` while PROFILE held every payload,
     * and a paged variant would still parse every payload in Kotlin — and it is one round trip. The live-issue [CustomFieldFill.total]
     * comes out of the SAME statement (a `live` CTE read by both branches of a `UNION ALL`; the row with a NULL key is the total), so
     * counts and total see ONE snapshot — two statements would each take their own under READ COMMITTED.
     *
     * "Has a value" is exactly the old Kotlin check: a missing key, a JSON `null` and an empty array do NOT count (an
     * empty array is how Jira reports an unset Sprint/Flagged); `""`, `{}`, `0`, `false` and any other value DO. Every key is
     * returned (no `customfield_` filter) — the caller picks the custom-field ids it cares about. Only the numeric connection
     * id is interpolated into the SQL text, never a string.
     */
    suspend fun customFieldFill(connectionId: UInt): CustomFieldFill = suspendTransaction(database) {
        // A payload whose `fields` is not an object contributes nothing (jsonb_each would raise on it) but still counts in the total.
        // `f.value <> '[]'::jsonb` is jsonb equality: only an empty array equals it, and it is safe on any value type.
        val rows: Flow<Pair<String?, Long>?>? = exec(
            """
            WITH live AS (
              SELECT payload FROM raw.jira_issues
              WHERE connection_id = $connectionId AND deleted_at IS NULL AND moved_out_at IS NULL
            )
            SELECT f.key, count(*) FROM live,
              jsonb_each(CASE WHEN jsonb_typeof(live.payload -> 'fields') = 'object' THEN live.payload -> 'fields' ELSE '{}'::jsonb END) f
            WHERE jsonb_typeof(f.value) <> 'null' AND f.value <> '[]'::jsonb
            GROUP BY f.key
            UNION ALL
            SELECT NULL, count(*) FROM live
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, String::class.java) to row.get(1, Long::class.javaObjectType)!! }
        val all = rows?.toList()?.filterNotNull().orEmpty()
        CustomFieldFill(
            total = all.firstOrNull { it.first == null }?.second ?: 0L,
            nonNullByField = all.mapNotNull { (key, n) -> key?.let { it to n } }.toMap(),
        )
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
