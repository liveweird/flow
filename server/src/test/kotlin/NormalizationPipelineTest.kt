package ch.nokillswit

import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReconcileStream
import ch.nokillswit.jira.parseJiraInstantEpochMillis
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `customfield_10030` (`sample-data/README.md` "Phase 3 (v0.3.0) additions") — the Work category select field, on every epic. */
private const val WORK_CATEGORY_FIELD_ID = "customfield_10030"

/**
 * A hand-maintained, INDEPENDENT copy of `sample-data/jira/generate.mjs`'s STATUS_CATEGORIES map —
 * cross-checks PROCESS's own category resolution against a second source of truth, never reusing its code.
 */
private val EXPECTED_STATUS_CATEGORY = mapOf(
    "1" to StatusCategory.TODO,
    "3" to StatusCategory.IN_PROGRESS,
    "10001" to StatusCategory.IN_PROGRESS,
    "10002" to StatusCategory.DONE,
    "10003" to StatusCategory.IN_PROGRESS,
    "10004" to StatusCategory.IN_PROGRESS,
)

@Serializable
private data class ExpectedSprints(val perProjectSprintCounts: Map<String, Int>)

@Serializable
private data class ExpectedFixtureNorm(val sprints: ExpectedSprints)

private val EXPECTED_NORM_JSON = Json { ignoreUnknownKeys = true }

private val expectedNormFixture: ExpectedFixtureNorm by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile } ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    EXPECTED_NORM_JSON.decodeFromString(file.readText())
}

/**
 * The normalized layer's own pipeline test (v0.2.0 plan §8/§11, plan commit 8a) — `JiraConnector`'s
 * PROCESS stream, asserting invariants over the PERSISTED `norm.*` rows (not Tiling's in-memory
 * guarantees, which `TilingTest.kt` already covers exhaustively) — the DB is the thing that has to
 * be right.
 *
 * **Shared synced fixture** (`.claude/docs/testing.md` "Shared synced fixture"): every test that
 * only READS the result of a full backfill SYNC shares [SyncedStubFixture]'s ONE synced connection
 * rather than running its own from scratch. A test whose subject is REPROCESS/RECONCILE/a
 * `processing_version` simulation clones that connection's raw rows
 * ([SyncedStubFixture.cloneRawData]) into a connection of its own and drives only the stream under
 * test against the clone — the shared connection itself is never mutated (`SyncedStubFixtureTest`
 * is the tripwire).
 *
 * `sample-data/jira/expected.json`'s `reopens`/`flagged` counters are computed over the WHOLE
 * simulated dataset, including the out-of-scope `SEC` project (`.claude/docs/ingestion.md`'s "Jira
 * stub" caveat about `worklogs.*` applies identically here — the generator was never asked to
 * scope THESE counters either). This suite therefore cross-checks reopens against an INDEPENDENT
 * re-derivation from the same raw changelog rows PROCESS itself consumed (never trusting the
 * whole-dataset JSON number directly), and treats `flagged`/sprint-carryover counts as
 * plausibility bounds against it instead of exact equality.
 */
class NormalizationPipelineTest {
    private fun rawStore() = SyncedStubFixture.rawStore()
    private fun workItems() = SyncedStubFixture.workItems()

    /** A fresh connection with [sharedConnId]'s raw rows cloned in — the substrate a mutating test drives its own stream against. */
    private suspend fun clonedConnection(sharedConnId: UInt): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-norm-clone")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        return connId
    }

    @Test
    fun `PROCESS after backfill produces work items whose persisted intervals satisfy every invariant`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val items = workItems()

        assertEquals(1200L, items.countWorkItems(connId), "one norm.work_items row per in-scope raw issue")

        val intervalsByIssue = items.statusIntervalsByIssue(connId)
        assertEquals(1200, intervalsByIssue.size, "every work item must have its own status-interval timeline")

        intervalsByIssue.forEach { (issueId, intervals) ->
            val workItem = assertNotNull(items.workItemRow(connId, issueId), "issue $issueId")
            assertEquals(intervals.indices.map { it + 1 }, intervals.map { it.seq }, "issue $issueId: seq must be contiguous from 1")
            assertEquals(
                workItem[WorkItemStore.WorkItems.createdAt], intervals.first().fromAtMs,
                "issue $issueId: the first interval must start at created_at",
            )
            intervals.zipWithNext().forEach { (a, b) ->
                assertEquals(a.toAtMs, b.fromAtMs, "issue $issueId: interval $a -> $b must be contiguous")
            }
            assertEquals(1, intervals.count { it.toAtMs == null }, "issue $issueId: exactly one interval must be open")
        }
    }

    @Test
    fun `reopen count from persisted intervals matches an independent re-derivation from the raw changelog`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val items = workItems()
        val store = rawStore()

        val fromPersistedIntervals = items.statusIntervalsByIssue(connId).values.sumOf { intervals ->
            intervals.zipWithNext().count { (a, b) -> a.category == StatusCategory.DONE && b.category != StatusCategory.DONE }
        }

        // Independent re-derivation straight from raw.jira_issues/raw.jira_changelogs — the SAME
        // rows PROCESS itself read — using the hand-maintained EXPECTED_STATUS_CATEGORY map above,
        // never Tiling/Normalization's own code path.
        val rawIssueIds = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
                .toList().map { it[JiraRawStore.Issues.issueId] }
        }
        var independentReopens = 0
        rawIssueIds.forEach { issueId -> independentReopens += statusTransitionReopens(store, connId, issueId) }

        assertEquals(
            independentReopens, fromPersistedIntervals,
            "reopens computed from norm.* must match an independent re-derivation from raw.*",
        )
        assertTrue(
            fromPersistedIntervals > 0,
            "the synthetic dataset's ~5% reopen rate must produce at least one reopen among 1200 in-scope issues",
        )
        // A sanity bound against the WHOLE-DATASET figure in expected.json (57, including the
        // out-of-scope SEC project's own issues) — the in-scope subset can never exceed it.
        assertTrue(fromPersistedIntervals <= 57, "the in-scope reopen count can never exceed the whole-dataset figure in expected.json")
    }

    @Test
    fun `flagged and sprint reference counts are plausible against expected_json`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()

        val flaggedCount = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.WorkItems.selectAll()
                .where { (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.flagged eq true) }
                .count()
        }
        assertTrue(
            flaggedCount in 1..61,
            "sample-data/jira/expected.json flagged.count (61, whole-dataset) is the in-scope subset's upper bound",
        )

        val sprintCount = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.Sprints.selectAll().where { WorkItemStore.Sprints.connectionId eq connId }.count()
        }
        val expectedSprintCount = expectedNormFixture.sprints.perProjectSprintCounts.values.sum().toLong()
        assertEquals(
            expectedSprintCount, sprintCount,
            "sample-data/jira/expected.json sprints.perProjectSprintCounts (scrum-only, already in-scope)",
        )

        // Assignee: an EXACT cross-check between the raw issue's current `fields.assignee` and the
        // normalized row's `assignee_account_id` — a current-snapshot fact, not history, so this
        // must match one-for-one.
        val assignedInWorkItems = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.WorkItems.selectAll()
                .where { (WorkItemStore.WorkItems.connectionId eq connId) and WorkItemStore.WorkItems.assigneeAccountId.isNotNull() }
                .count()
        }
        val assignedInRaw = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList()
        }.count { row ->
            val payload = Json { ignoreUnknownKeys = true }.parseToJsonElement(row[JiraRawStore.Issues.payload]).jsonObject
            payload.getValue("fields").jsonObject["assignee"]?.let { it.toString() != "null" } == true
        }
        assertEquals(
            assignedInRaw.toLong(), assignedInWorkItems,
            "assignee_account_id must be non-null exactly when the raw issue currently has an assignee",
        )
        Unit
    }

    @Test
    fun `REPROCESS leaves the normalized digest unchanged`() = runBlocking {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = clonedConnection(sharedConnId)
        val store = rawStore()
        val items = workItems()
        val context = SyncedStubFixture.freshContext(connId)

        // The clone reproduces the shared connection's own POST-process raw state (needs_processing
        // = false), so — exactly like a real REPROCESS job (`JiraConnector.runReprocess`) — every
        // issue must be flagged again before PROCESS has anything to rebuild.
        store.markAllNeedsProcessing(connId)
        JiraProcessStream(store, items).run(context)
        val digestBefore = normalizedDigest(items, connId)

        store.markAllNeedsProcessing(connId)
        JiraProcessStream(store, items).run(context)
        val digestAfter = normalizedDigest(items, connId)

        assertEquals(digestBefore, digestAfter, "a REPROCESS must rebuild byte-for-byte identical normalized rows")
    }

    @Test
    fun `a processing_version mismatch makes an issue eligible for the next PROCESS pass`() = runBlocking {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = clonedConnection(sharedConnId)
        val store = rawStore()

        val issueId = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
                .toList().first()[JiraRawStore.Issues.issueId]
        }
        // A freshly-synced issue's stored `processing_version` matches the CURRENT constant
        // (v0.3.0 M1 commit 2 bumped it to 2) — never a stale literal, or a later bump breaks this
        // premise the moment the connector itself starts writing the new value. The clone carries
        // this state verbatim from the shared connection's own completed backfill.
        assertEquals(
            emptyList(),
            store.issuesToProcess(connId, currentProcessingVersion = PROCESSING_VERSION, limit = 10).filter { it == issueId },
        )

        // Simulate a PROCESSING_VERSION bump: downgrade one issue's stored version directly.
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }) {
                it[processingVersion] = 0
            }
        }
        val eligible = store.issuesToProcess(connId, currentProcessingVersion = PROCESSING_VERSION, limit = 2_000)
        assertTrue(issueId in eligible, "a stale processing_version must make the issue eligible for the next PROCESS pass")
    }

    @Test
    fun `RECONCILE's tombstone is mirrored onto norm work_items in the same job`() = runBlocking {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = clonedConnection(sharedConnId)
        val store = rawStore()
        val items = workItems()
        val context = SyncedStubFixture.freshContext(connId)

        JiraStubServer.setScenarioState("jira-day2", "day2")
        try {
            val client = SyncedStubFixture.buildClient()
            JiraReconcileStream(client, store, SyncedStubFixture.IN_SCOPE_PROJECT_KEYS).run(context)
            JiraProcessStream(store, items).run(context)

            val deletedId = expectedFixtureDay2Deleted()
            val movedId = expectedFixtureDay2Moved()
            val deletedWorkItem = assertNotNull(items.workItemRow(connId, deletedId))
            assertNotNull(deletedWorkItem[WorkItemStore.WorkItems.deletedAt], "norm.work_items must mirror raw.jira_issues.deleted_at")
            val movedWorkItem = assertNotNull(items.workItemRow(connId, movedId))
            assertNotNull(movedWorkItem[WorkItemStore.WorkItems.movedOutAt], "norm.work_items must mirror raw.jira_issues.moved_out_at")
            Unit
        } finally {
            JiraStubServer.resetScenarios()
        }
    }

    @Test
    fun `PARENT intervals exist for the moved tasks and each one's last interval matches the current parent`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val items = workItems()

        val parentMoveIssueIds = expectedParentMoveIssueIds()
        assertTrue(parentMoveIssueIds.isNotEmpty(), "sample-data/jira/expected.json parentMoves.issueIds must list at least one move")

        parentMoveIssueIds.forEach { issueId ->
            val parentIntervals = items.fieldIntervalsForIssue(connId, issueId).filter { it.field == TrackedField.PARENT }
            assertTrue(parentIntervals.size >= 2, "issue $issueId: a parent move must tile into at least two PARENT intervals")
            val lastInterval = parentIntervals.maxBy { it.seq }
            assertTrue(lastInterval.toAtMs == null, "issue $issueId: the LAST PARENT interval must be the open one")
            val workItem = assertNotNull(items.workItemRow(connId, issueId), "issue $issueId")
            assertEquals(
                workItem[WorkItemStore.WorkItems.parentIssueId], lastInterval.valueId?.toLongOrNull(),
                "issue $issueId: the LAST PARENT interval must match norm.work_items.parent_issue_id",
            )
        }
    }

    @Test
    fun `custom_fields carries the work-category field for every epic, and hierarchy_level is resolved from ISSUE_TYPE`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()

        val epicRows = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.WorkItems.selectAll()
                .where { (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.issueType eq "Epic") }
                .toList()
        }
        assertTrue(epicRows.isNotEmpty(), "the in-scope dataset must contain at least one epic")
        epicRows.forEach { row ->
            val customFields = Json.parseToJsonElement(row[WorkItemStore.WorkItems.customFields]).jsonObject
            assertTrue(
                customFields.containsKey(WORK_CATEGORY_FIELD_ID),
                "issue ${row[WorkItemStore.WorkItems.issueId]}: every epic must carry the work-category field in custom_fields",
            )
            assertEquals(
                1, row[WorkItemStore.WorkItems.hierarchyLevel],
                "issue ${row[WorkItemStore.WorkItems.issueId]}: an epic's hierarchy_level must be 1, from the ISSUE_TYPE reference entity",
            )
        }
    }

    @Test
    fun `every persisted field_changes row carries a non-null field_id`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()

        val rows = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.FieldChanges.selectAll().where { WorkItemStore.FieldChanges.connectionId eq connId }.toList()
        }
        assertTrue(rows.isNotEmpty(), "the in-scope dataset must have produced at least one field change")
        assertTrue(
            rows.all { it[WorkItemStore.FieldChanges.fieldId] != null },
            "every norm.work_item_field_changes row must carry its own field_id (v0.3.0 M1 commit 2)",
        )
    }

    @Test
    fun `a field_id=parent row exists per moved issue, readable via fieldChangesByFieldIds and fieldIntervalsByIssue`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val items = workItems()

        val parentMoveIssueIds = expectedParentMoveIssueIds().toSet()
        val parentChanges = items.fieldChangesByFieldIds(connId, listOf("parent"))
        val parentChangeIssueIds = parentChanges.map { it.issueId }.toSet()
        assertTrue(
            parentMoveIssueIds.all { it in parentChangeIssueIds },
            "every moved issue must have a field_id='parent' row readable via fieldChangesByFieldIds",
        )
        // Ordered by (issue_id, changed_at, seq) in SQL — assert the returned list is already sorted.
        assertEquals(parentChanges.sortedWith(compareBy({ it.issueId }, { it.changedAt })), parentChanges)

        val parentIntervalsByIssue = items.fieldIntervalsByIssue(connId, TrackedField.PARENT)
        parentMoveIssueIds.forEach { issueId ->
            assertTrue(
                (parentIntervalsByIssue[issueId]?.size ?: 0) >= 2,
                "issue $issueId: fieldIntervalsByIssue(PARENT) must carry at least two tiled intervals",
            )
        }
    }

    @Test
    fun `at least one non-tracked customfield_ change row exists (the widened tracked-field set)`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val items = workItems()

        // customfield_10015 ("Start date") is never in the OLD explicit tracked-field-id list
        // (status/assignee/priority/resolution/issuetype/project/issuekey plus the discovered
        // sprint/flagged/rank/story-points ids) — its presence here proves the widened "every
        // customfield_*" capture actually works, not just the fields already special-cased before.
        val startDateChanges = items.fieldChangesByFieldIds(connId, listOf("customfield_10015"))
        assertTrue(startDateChanges.isNotEmpty(), "sample-data's epic Start-date changelog entries must be captured (v0.3.0 M1 commit 2)")
    }

    @Test
    fun `a reprocess preserves the raw tombstone time instead of resetting it to the reprocess time`() = runBlocking {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = clonedConnection(sharedConnId)
        val store = rawStore()
        val items = workItems()
        val context = SyncedStubFixture.freshContext(connId)

        JiraStubServer.setScenarioState("jira-day2", "day2")
        try {
            val client = SyncedStubFixture.buildClient()
            JiraReconcileStream(client, store, SyncedStubFixture.IN_SCOPE_PROJECT_KEYS).run(context)
            JiraProcessStream(store, items).run(context)

            val deletedId = expectedFixtureDay2Deleted()
            val rawDeletedAt = assertNotNull(store.issueForProcessing(connId, deletedId)).deletedAt
            val normDeletedAtAfterReconcile = assertNotNull(items.workItemRow(connId, deletedId))[WorkItemStore.WorkItems.deletedAt]
            assertEquals(rawDeletedAt, normDeletedAtAfterReconcile, "the mirrored deleted_at must equal the raw tombstone time")

            // A REPROCESS run happens strictly later — if deleted_at were reset to "now" on
            // reprocess (the bug this test guards against), it would move past rawDeletedAt.
            store.markAllNeedsProcessing(connId)
            JiraProcessStream(store, items).run(context)
            val normDeletedAtAfterReprocess = assertNotNull(items.workItemRow(connId, deletedId))[WorkItemStore.WorkItems.deletedAt]
            assertEquals(
                rawDeletedAt, normDeletedAtAfterReprocess,
                "REPROCESS must preserve the raw tombstone time, not reset it to the reprocess time",
            )
        } finally {
            JiraStubServer.resetScenarios()
        }
    }

    @Test
    fun `worklog created and updated skew matches an independent re-derivation from the raw worklog payload`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        val store = rawStore()

        val persistedCreatedLater = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.Worklogs.selectAll().where { WorkItemStore.Worklogs.connectionId eq connId }.toList()
                .count { it[WorkItemStore.Worklogs.createdAt]!! > it[WorkItemStore.Worklogs.startedAt] }
        }
        val persistedUpdatedLater = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.Worklogs.selectAll().where { WorkItemStore.Worklogs.connectionId eq connId }.toList()
                .count { it[WorkItemStore.Worklogs.updatedAt]!! > it[WorkItemStore.Worklogs.createdAt]!! }
        }

        // Independent re-derivation straight from raw.jira_worklogs (already in-scope-filtered, A1) —
        // the SAME rows PROCESS itself read, never JiraNormalizer's own code path.
        val json = Json { ignoreUnknownKeys = true }
        val rawIssueIds = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
                .toList().map { it[JiraRawStore.Issues.issueId] }
        }
        var independentCreatedLater = 0
        var independentUpdatedLater = 0
        rawIssueIds.forEach { issueId ->
            store.worklogPayloadsForIssue(connId, issueId).forEach { payload ->
                val worklog = json.parseToJsonElement(payload).jsonObject
                val started = parseJiraInstantEpochMillis(worklog.getValue("started").jsonPrimitive.content)
                val created = parseJiraInstantEpochMillis(worklog.getValue("created").jsonPrimitive.content)
                val updated = parseJiraInstantEpochMillis(worklog.getValue("updated").jsonPrimitive.content)
                if (created > started) independentCreatedLater++
                if (updated > created) independentUpdatedLater++
            }
        }

        assertEquals(
            independentCreatedLater, persistedCreatedLater,
            "created_at > started_at count must match an independent re-derivation from the raw worklog payloads",
        )
        assertEquals(
            independentUpdatedLater, persistedUpdatedLater,
            "updated_at > created_at count must match an independent re-derivation from the raw worklog payloads",
        )
        assertTrue(persistedCreatedLater > 0, "the synthetic dataset's ~20% created-later skew must produce at least one in-scope worklog")
        // Sanity bounds against the WHOLE-DATASET figures in expected.json (240/147, incl. the
        // out-of-scope SEC project) — the in-scope subset can never exceed them.
        assertTrue(persistedCreatedLater <= 240, "in-scope createdLaterCount can never exceed the whole-dataset expected.json figure")
        assertTrue(persistedUpdatedLater <= 147, "in-scope updatedLaterCount can never exceed the whole-dataset expected.json figure")
    }

    @Test
    fun `complete_at is set on every closed sprint`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()

        val closedSprints = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.Sprints.selectAll()
                .where { (WorkItemStore.Sprints.connectionId eq connId) and (WorkItemStore.Sprints.state eq "closed") }
                .toList()
        }
        assertTrue(closedSprints.isNotEmpty(), "the in-scope dataset must contain at least one closed sprint")
        assertTrue(
            closedSprints.all { it[WorkItemStore.Sprints.completeAt] != null },
            "every closed sprint must carry complete_at (`completeDate`, v0.3.0 M1 commit 2)",
        )
    }

    private fun expectedParentMoveIssueIds(): List<Long> {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
        return json.getValue("parentMoves").jsonObject.getValue("issueIds").jsonArray.map { it.jsonPrimitive.content.toLong() }
    }

    private suspend fun statusTransitionReopens(store: JiraRawStore, connId: UInt, issueId: Long): Int {
        val json = Json { ignoreUnknownKeys = true }
        val issue = json.parseToJsonElement(assertNotNull(store.issueForProcessing(connId, issueId)).payloadJson).jsonObject
        val currentStatusId = issue.getValue("fields").jsonObject.getValue("status").jsonObject.getValue("id").jsonPrimitive.content
        val events = store.changelogPayloadsForIssue(connId, issueId)
            .map { json.parseToJsonElement(it).jsonObject }
            .flatMap { history -> history.getValue("items").jsonArray.map { it.jsonObject } }
            .filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == "status" }
            .map { it.getValue("from").jsonPrimitive.content to it.getValue("to").jsonPrimitive.content }
        val categories = (events.map { it.first } + events.map { it.second } + currentStatusId)
            .distinct()
            .associateWith { EXPECTED_STATUS_CATEGORY[it] ?: StatusCategory.UNKNOWN }
        var reopens = 0
        events.forEach { (from, to) ->
            if (categories.getValue(from) == StatusCategory.DONE && categories.getValue(to) != StatusCategory.DONE) reopens++
        }
        return reopens
    }

    /**
     * MD5 over every persisted status interval, field interval (all four `TrackedField` kinds,
     * PARENT included — v0.3.0 M1 commit 2 review fix widens this beyond status intervals alone)
     * and field-change row, ordered deterministically — the REPROCESS idempotence proof needs to
     * cover every table `replaceWorkItem` rewrites, not just `norm.work_item_status_intervals`.
     */
    private suspend fun normalizedDigest(items: WorkItemStore, connId: UInt): String {
        val digest = MessageDigest.getInstance("MD5")
        items.statusIntervalsByIssue(connId).toSortedMap().forEach { (issueId, intervals) ->
            intervals.forEach { interval ->
                val line = "S|$issueId|${interval.seq}|${interval.statusId}|${interval.fromAtMs}|${interval.toAtMs}|${interval.source}\n"
                digest.update(line.toByteArray())
            }
        }
        TrackedField.entries.forEach { field ->
            items.fieldIntervalsByIssue(connId, field).toSortedMap().forEach { (issueId, intervals) ->
                intervals.forEach { interval ->
                    val line = "F|$field|$issueId|${interval.seq}|${interval.valueId}|${interval.valueText}|" +
                        "${interval.fromAtMs}|${interval.toAtMs}\n"
                    digest.update(line.toByteArray())
                }
            }
        }
        val fieldChangeRows = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.FieldChanges.selectAll().where { WorkItemStore.FieldChanges.connectionId eq connId }.toList()
        }.sortedWith(compareBy({ it[WorkItemStore.FieldChanges.issueId] }, { it[WorkItemStore.FieldChanges.seq] }))
        fieldChangeRows.forEach { row ->
            val line = "C|${row[WorkItemStore.FieldChanges.issueId]}|${row[WorkItemStore.FieldChanges.seq]}|" +
                "${row[WorkItemStore.FieldChanges.fieldId]}|${row[WorkItemStore.FieldChanges.field]}|" +
                "${row[WorkItemStore.FieldChanges.changedAt]}|${row[WorkItemStore.FieldChanges.fromValue]}|" +
                "${row[WorkItemStore.FieldChanges.toValue]}\n"
            digest.update(line.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectedFixtureDay2Deleted(): Long {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
        return json.getValue("day2").jsonObject.getValue("deletedIssueId").jsonPrimitive.content.toLong()
    }

    private fun expectedFixtureDay2Moved(): Long {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
        return json.getValue("day2").jsonObject.getValue("movedIssueId").jsonPrimitive.content.toLong()
    }
}
