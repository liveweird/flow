package ch.nokillswit

import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.WorklogFact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The DERIVE reads' narrowed shapes (`norm/NormDerivationReads.kt`): `workItemsForDerivation` keeps only the listed
 * `custom_fields` keys INSIDE its SELECT (what `MetricsDeriver.trimCustomFields` used to do after the read), and
 * `worklogsByIssue` scoped to some issue ids returns exactly the same per-issue lists as the whole-connection read. The end-to-end
 * guarantee (output byte-identical) is the digest tests'; these pin the read contracts on a hand-built item with extra fields.
 */
class NormDerivationReadsTest {
    private val workItems = WorkItemStore(sharedDatabaseForTests())

    // A Rank-shaped string, an ADF-shaped document, a number, an explicit null and a list value: what a real tenant's custom_fields holds.
    private val customFieldsJson = """
        {"customfield_10016": 5.5, "customfield_10019": "0|i0000f:",
         "customfield_10400": {"type": "doc", "content": [{"type": "text", "text": "x"}]},
         "customfield_10500": null, "customfield_10600": [{"id": "1", "value": "Run"}], "customfield_10700": "keep me"}
    """.trimIndent()

    private fun item(
        issueId: Long,
        customFields: String,
        worklogIds: List<Long> = emptyList(),
        tombstone: TombstoneKind = TombstoneKind.NONE,
    ) =
        NormalizedIssue(
            issueId = issueId,
            facts = WorkItemFacts(
                issueKey = "NDR-$issueId", projectKey = "NDR", issueType = "Story", isSubtask = false, parentIssueId = null,
                summary = "Summary of NDR-$issueId", currentStatusId = "1", resolution = null, priority = null, assigneeAccountId = "acc-1",
                reporterAccountId = null, createdAtMs = 1_000L, updatedAtMs = 2_000L, resolvedAtMs = null, storyPoints = null,
                originalEstimateSeconds = null, timeSpentSeconds = 0, labels = listOf("l1"), components = emptyList(),
                fixVersions = emptyList(), teamValueJson = null, rank = null, hierarchyLevel = 0, dueAtMs = null,
                customFieldsJson = customFields, tombstone = tombstone,
            ),
            currentStatusName = "To Do",
            currentStatusCategory = StatusCategory.TODO,
            statusIntervals = listOf(NormalizedStatusInterval(1, "1", "To Do", StatusCategory.TODO, 1_000L, null, IntervalSource.CREATED)),
            fieldIntervals = emptyList(), fieldChanges = emptyList(),
            worklogs = worklogIds.map { WorklogFact(it, "acc-$it", 3_000L + it, 3_600L * it, 4_000L, 5_000L) },
            currentSprintIds = listOf(7L, 8L), flagged = false, anomalies = emptyList(),
        )

    private suspend fun <T> withConnection(seed: List<NormalizedIssue>, body: suspend (UInt) -> T): T {
        val connId = SyncedStubFixture.createConnection(namePrefix = "norm-derive-reads", enabled = false)
        try {
            workItems.replaceWorkItems(connId, seed, now = 5_000L)
            return body(connId)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                WorkItemStore.Worklogs.deleteWhere { WorkItemStore.Worklogs.connectionId eq connId }
                WorkItemStore.StatusIntervals.deleteWhere { WorkItemStore.StatusIntervals.connectionId eq connId }
                WorkItemStore.WorkItems.deleteWhere { WorkItemStore.WorkItems.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
    }

    /** The old `trimCustomFields`, verbatim: `JsonObject(customFields.filterKeys { it in ids })`. */
    private fun oldTrim(customFields: JsonObject, ids: Set<String>) = JsonObject(customFields.filterKeys { it in ids })

    @Test
    fun `workItemsForDerivation keeps exactly the listed custom fields, as the old read-then-trim did`() = runBlocking {
        withConnection(
            listOf(
                item(1, customFieldsJson),
                item(2, "{}"),
                item(3, """{"customfield_10019": "only rank"}"""),
                item(4, customFieldsJson, tombstone = TombstoneKind.DELETED),
            ),
        ) { connId ->
            val whole = workItems.workItemsForDerivation(connId).associateBy { it.issueId }
            assertEquals(setOf(1L, 2L, 3L), whole.keys, "live rows only")
            val expectedWhole = Json.parseToJsonElement(customFieldsJson).jsonObject
            assertEquals(expectedWhole, whole.getValue(1).customFields, "null ids = the whole object")

            for (ids in listOf(
                setOf("customfield_10016"),
                // a number, an explicit null, an ADF doc and an id the object lacks
                setOf("customfield_10016", "customfield_10500", "customfield_10400", "customfield_9999"),
                setOf("customfield_10019", "customfield_10600", "customfield_10700"),
                emptySet(),
            )) {
                val trimmed = workItems.workItemsForDerivation(connId, ids).associateBy { it.issueId }
                assertEquals(whole.keys, trimmed.keys, "the id set never changes which rows come back: $ids")
                for ((issueId, row) in trimmed) {
                    assertEquals(oldTrim(whole.getValue(issueId).customFields, ids), row.customFields, "item $issueId, ids $ids")
                    // Every other column is read untouched.
                    assertEquals(whole.getValue(issueId).copy(customFields = row.customFields), row, "item $issueId, ids $ids")
                }
            }
            val one = workItems.workItemsForDerivation(connId, setOf("customfield_10016", "customfield_10500")).first { it.issueId == 1L }
            assertEquals(setOf("customfield_10016", "customfield_10500"), one.customFields.keys, "an explicit JSON null is kept")
            assertEquals(listOf(7L, 8L), one.currentSprintIds)
            assertEquals("acc-1", one.assigneeAccountId)
            assertTrue(workItems.workItemsForDerivation(connId, emptySet()).all { it.customFields.isEmpty() })
        }
    }

    @Test
    fun `worklogsByIssue scoped to some issues returns the same per-issue lists as the whole-connection read`() = runBlocking {
        withConnection(
            listOf(
                item(1, "{}", worklogIds = listOf(30, 10, 20)),
                item(2, "{}", worklogIds = listOf(11)),
                item(3, "{}"),
                item(4, "{}", worklogIds = listOf(41, 40)),
            ),
        ) { connId ->
            val whole = workItems.worklogsByIssue(connId)
            assertEquals(setOf(1L, 2L, 4L), whole.keys)
            assertEquals(listOf(10L, 20L, 30L), whole.getValue(1).map { it.worklogId }, "ordered by worklog id within an issue")
            assertEquals(3_600L * 20, whole.getValue(1)[1].timeSpentSeconds)
            assertEquals(4_000L, whole.getValue(1)[1].createdAt)

            assertEquals(whole, workItems.worklogsByIssue(connId, whole.keys + 3L + 99L), "ids with no worklog (or no issue) add nothing")
            assertEquals(whole.filterKeys { it in setOf(1L, 4L) }, workItems.worklogsByIssue(connId, listOf(1L, 4L)))
            assertEquals(whole.filterKeys { it == 2L }, workItems.worklogsByIssue(connId, setOf(2L, 3L)))
            assertEquals(emptyMap(), workItems.worklogsByIssue(connId, emptyList()))
            // The aggregate the worklog step uses to pick its items has a key for exactly the issues the whole read has.
            assertEquals(whole.keys, workItems.worklogSecondsByIssue(connId).keys)
            assertEquals(whole.mapValues { (_, rows) -> rows.sumOf { it.timeSpentSeconds } }, workItems.worklogSecondsByIssue(connId))
        }
    }
}
