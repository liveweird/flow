package ch.nokillswit

import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawIssueInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `JiraRawViewReads.customFieldFill` (the data profile's custom-field fill rate, counted in Postgres): the "has a value" semantics per
 * JSON shape — a missing key, `null` and `[]` do not count; `""`, `{}`, `0` and a filled value do — and a tombstoned or moved-out issue
 * is excluded from both the counts and the total. Runs on its OWN connection, never the shared synced fixture.
 */
class JiraProfileCustomFieldFillTest {
    private val fieldId = "customfield_99001"

    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())

    private fun issue(issueId: Long, fieldJson: String?): RawIssueInput {
        val custom = fieldJson?.let { ""","$fieldId":$it""" }.orEmpty()
        return rawIssue(issueId, """{"id":"$issueId","key":"ENG-$issueId","fields":{"summary":"s"$custom}}""")
    }

    private fun rawIssue(issueId: Long, payloadJson: String): RawIssueInput {
        return RawIssueInput(
            issueId = issueId,
            issueKey = "ENG-$issueId",
            projectId = 1L,
            projectKey = "ENG",
            issueUpdatedAt = 1_000,
            payload = Json.parseToJsonElement(payloadJson),
        )
    }

    private suspend fun tombstone(connId: UInt, issueId: Long, movedOut: Boolean) {
        suspendTransaction(sharedDatabaseForTests()) {
            val row = (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId)
            JiraRawStore.Issues.update({ row }) {
                if (movedOut) it[movedOutAt] = 2_000L else it[deletedAt] = 2_000L
            }
        }
    }

    @Test
    fun `fill counts values by JSON shape and excludes tombstoned and moved-out issues`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fill", enabled = false)
        val store = rawStore()
        val shapes = listOf(null, "null", "[]", "\"\"", "{}", "0", "\"filled\"")
        shapes.forEachIndexed { i, shape -> store.upsertIssue(connId, issue(i + 1L, shape), now = 1_000) }
        store.upsertIssue(connId, issue(100, "\"deleted\""), now = 1_000)
        store.upsertIssue(connId, issue(101, "\"moved\""), now = 1_000)
        tombstone(connId, 100, movedOut = false)
        tombstone(connId, 101, movedOut = true)
        // Live issues whose `fields` is JSON null / absent: they contribute to no key but count in the total.
        store.upsertIssue(connId, rawIssue(102, """{"id":"102","key":"ENG-102","fields":null}"""), now = 1_000)
        store.upsertIssue(connId, rawIssue(103, """{"id":"103","key":"ENG-103"}"""), now = 1_000)
        // A DIFFERENT connection's filled issue must not leak into this one's counts.
        val otherConnId = SyncedStubFixture.createConnection(namePrefix = "jira-fill-other", enabled = false)
        store.upsertIssue(otherConnId, issue(200, "\"other\""), now = 1_000)

        val fill = store.customFieldFill(connId)

        assertEquals(shapes.size + 2L, fill.total)
        // "" , {} , 0 and the filled value count; missing, null and [] do not; the tombstoned, moved-out and other-connection issues
        // are excluded, and the fields-less issues add nothing.
        assertEquals(4L, fill.nonNullByField[fieldId])
        assertEquals(shapes.size.toLong(), fill.nonNullByField["summary"])
        assertEquals(1L, store.customFieldFill(otherConnId).total)
    }

    @Test
    fun `a connection with no issues has an empty fill`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fill-empty", enabled = false)
        val fill = rawStore().customFieldFill(connId)
        assertEquals(0L, fill.total)
        assertNull(fill.nonNullByField[fieldId])
    }
}
