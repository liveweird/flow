package ch.nokillswit

import ch.nokillswit.infra.db.chunkSizes
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.upsertRows
import org.jetbrains.exposed.v1.core.Table
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `infra/db/MultiRowInsert.kt`'s `insertRows` writes exactly what Exposed's `batchInsert` writes,
 * only as multi-row `VALUES` statements (`.claude/docs/build-times.md` WHY 3). Every case inserts
 * the SAME rows through both into two private connections and compares what the database holds,
 * so a difference in value conversion, null handling, the reference (`EntityID`) columns, the
 * client-side defaults or the chunking shows up as a row difference, not a guess.
 */
class MultiRowInsertTest {
    private data class Stage(val issueId: Long, val stage: String, val statusId: String, val from: Long, val to: Long?)

    /** A private, DISABLED connection (no worker ever derives it) to write into. */
    private suspend fun connection(): UInt = SyncedStubFixture.createConnection(namePrefix = "multi-row-insert", enabled = false)

    private suspend fun cleanUp(connIds: List<UInt>) = suspendTransaction(sharedDatabaseForTests()) {
        for (connId in connIds) {
            MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
            MetricsTables.DimTask.deleteWhere { MetricsTables.DimTask.connectionId eq connId }
            MetricsTables.DimDomain.deleteWhere { MetricsTables.DimDomain.connectionId eq connId }
        }
    }

    @Test
    fun `item_stage rows - including a second chunk and NULL valid_to - equal what batchInsert writes`() = runBlocking {
        val viaBatch = connection()
        val viaRows = connection()
        val table = MetricsTables.ItemStage
        // 6 columns x 6,000 rows = 36,000 bind parameters: above the 32,000 cap, so two statements.
        val rows = (1..6_000).map {
            Stage(it.toLong(), "IN_PROGRESS", "1000${it % 7}", it * 1_000L, if (it % 5 == 0) null else it * 1_000L + 500)
        }
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                rows.chunked(500).forEach { chunk ->
                    table.batchInsert(chunk) {
                        this[table.connectionId] = viaBatch
                        this[table.issueId] = it.issueId
                        this[table.stage] = it.stage
                        this[table.statusId] = it.statusId
                        this[table.validFrom] = it.from
                        this[table.validTo] = it.to
                    }
                }
                table.insertRows(rows) {
                    this[table.connectionId] = viaRows
                    this[table.issueId] = it.issueId
                    this[table.stage] = it.stage
                    this[table.statusId] = it.statusId
                    this[table.validFrom] = it.from
                    this[table.validTo] = it.to
                }
            }
            fun ResultRow.asStage() =
                Stage(this[table.issueId], this[table.stage], this[table.statusId], this[table.validFrom], this[table.validTo])
            val (expected, actual) = suspendTransaction(sharedDatabaseForTests()) {
                fun read(connId: UInt) = table.selectAll().where { table.connectionId eq connId }.orderBy(table.issueId to SortOrder.ASC)
                read(viaBatch).toList().map { it.asStage() } to read(viaRows).toList().map { it.asStage() }
            }
            assertEquals(rows, expected, "the reference write must round-trip the input")
            assertEquals(expected, actual, "insertRows must store the same rows as batchInsert")
            assertTrue(actual.any { it.to == null } && actual.any { it.to != null }, "both NULL and non-NULL valid_to were exercised")
        } finally {
            cleanUp(listOf(viaBatch, viaRows))
        }
    }

    @Test
    fun `a client-side default column no row sets is filled like batchInsert and nullable columns stay NULL`() = runBlocking {
        val viaBatch = connection()
        val viaRows = connection()
        val table = MetricsTables.DimTask
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                // is_subtask (client default false) is NOT set; work_category/domain_key/epic_id are left to NULL.
                table.batchInsert(listOf(1L, 2L, 3L)) { id ->
                    this[table.connectionId] = viaBatch
                    this[table.issueId] = id
                    this[table.issueKey] = "MRI-$id"
                    this[table.issueType] = "Task"
                    this[table.activityType] = "DEVELOPMENT"
                    this[table.workCategorySource] = "NONE"
                    this[table.parentTaskId] = if (id == 2L) 1L else null
                    this[table.configRevision] = 7L
                }
                table.insertRows(listOf(1L, 2L, 3L)) { id ->
                    this[table.connectionId] = viaRows
                    this[table.issueId] = id
                    this[table.issueKey] = "MRI-$id"
                    this[table.issueType] = "Task"
                    this[table.activityType] = "DEVELOPMENT"
                    this[table.workCategorySource] = "NONE"
                    this[table.parentTaskId] = if (id == 2L) 1L else null
                    this[table.configRevision] = 7L
                }
            }
            suspend fun read(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
                table.selectAll().where { table.connectionId eq connId }.orderBy(table.issueId to SortOrder.ASC).toList().map { row ->
                    listOf(
                        row[table.issueId], row[table.issueKey], row[table.issueType], row[table.activityType], row[table.workCategory],
                        row[table.workCategorySource], row[table.isSubtask], row[table.parentTaskId], row[table.domainKey],
                        row[table.epicId], row[table.configRevision],
                    )
                }
            }
            val expected = read(viaBatch)
            assertEquals(3, expected.size)
            assertEquals(false, expected.first()[6], "the client-side default applies when no row sets is_subtask")
            assertEquals(expected, read(viaRows))
        } finally {
            cleanUp(listOf(viaBatch, viaRows))
        }
    }

    @Test
    fun `a jsonb column and a nullable reference column - set and NULL - equal what batchInsert writes`() = runBlocking {
        val viaBatch = connection()
        val viaRows = connection()
        val table = MetricsTables.DimDomain
        val teamId = TestTeams.seed(SyncedStubFixture.unique("multi-row-team"))
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                val domains = listOf(Triple("ALPHA", listOf("A1", "A2"), teamId), Triple("BETA", listOf("B1"), null))
                table.batchInsert(domains) {
                    this[table.connectionId] = viaBatch
                    this[table.domainKey] = it.first
                    this[table.name] = "Domain ${it.first}"
                    this[table.projectKeys] = stringArrayJson(it.second)
                    this[table.ownerTeamId] = it.third
                    this[table.configRevision] = 3L
                }
                table.insertRows(domains) {
                    this[table.connectionId] = viaRows
                    this[table.domainKey] = it.first
                    this[table.name] = "Domain ${it.first}"
                    this[table.projectKeys] = stringArrayJson(it.second)
                    this[table.ownerTeamId] = it.third
                    this[table.configRevision] = 3L
                }
            }
            suspend fun read(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
                table.selectAll().where { table.connectionId eq connId }.orderBy(table.domainKey to SortOrder.ASC).toList().map { row ->
                    listOf(
                        row[table.domainKey], row[table.name], row[table.projectKeys], row[table.ownerTeamId]?.value,
                        row[table.configRevision],
                    )
                }
            }
            val expected = read(viaBatch)
            assertEquals(listOf(teamId, null), expected.map { it[3] }, "the reference write round-trips the team id and NULL")
            assertEquals(expected, read(viaRows))
        } finally {
            cleanUp(listOf(viaBatch, viaRows))
        }
    }

    @Test
    fun `norm field_changes rows - NULLs in each nullable column and a long TEXT field - equal batchInsert's`() = runBlocking {
        data class Change(
            val issueId: Long,
            val seq: Int,
            val field: String,
            val changedAt: Long,
            val fromValue: String?,
            val fromText: String?,
            val toValue: String?,
            val toText: String?,
            val fieldId: String?,
        )
        val viaBatch = connection()
        val viaRows = connection()
        val table = WorkItemStore.FieldChanges
        // `field` is TEXT (V19): a 5,000-character display name must round-trip; the middle rows null each nullable column in turn.
        val rows = listOf(
            Change(1, 1, "Status", 1_000, "10000", "To Do", "10001", "In Progress", "status"),
            Change(1, 2, "L".repeat(5_000), 2_000, null, null, null, null, null),
            Change(2, 1, "Start date", 3_000, null, "2024-01-01", "x".repeat(3_000), null, "customfield_10015"),
            Change(2, 2, "Assignee", 4_000, "a", null, null, "b", null),
            Change(3, 1, "Résumé — \u00e9\u4e2d", 5_000, "", "", "", "", "customfield_${"9".repeat(80)}"),
        )
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                table.batchInsert(rows, shouldReturnGeneratedValues = false) {
                    this[table.connectionId] = viaBatch
                    this[table.issueId] = it.issueId
                    this[table.seq] = it.seq
                    this[table.field] = it.field
                    this[table.changedAt] = it.changedAt
                    this[table.fromValue] = it.fromValue
                    this[table.fromText] = it.fromText
                    this[table.toValue] = it.toValue
                    this[table.toText] = it.toText
                    this[table.fieldId] = it.fieldId
                }
                table.insertRows(rows) {
                    this[table.connectionId] = viaRows
                    this[table.issueId] = it.issueId
                    this[table.seq] = it.seq
                    this[table.field] = it.field
                    this[table.changedAt] = it.changedAt
                    this[table.fromValue] = it.fromValue
                    this[table.fromText] = it.fromText
                    this[table.toValue] = it.toValue
                    this[table.toText] = it.toText
                    this[table.fieldId] = it.fieldId
                }
            }
            suspend fun read(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
                table.selectAll().where { table.connectionId eq connId }
                    .orderBy(table.issueId to SortOrder.ASC, table.seq to SortOrder.ASC).toList()
                    .map { row -> table.columns.filter { it != table.id && it != table.connectionId }.map { row[it] } }
            }
            val expected = read(viaBatch)
            assertEquals(rows.size, expected.size)
            assertEquals(rows.map { it.field }, expected.map { it[2] }, "the reference write must round-trip the long TEXT field")
            assertEquals(expected, read(viaRows), "insertRows must store the same rows (every non-id column) as batchInsert")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                for (connId in listOf(viaBatch, viaRows)) table.deleteWhere { table.connectionId eq connId }
            }
        }
    }

    /** One `norm.work_items` row's values, in the shape both writers take; [variant] decides which nullable columns are NULL. */
    private fun workItemValues(issueId: Long, variant: Int): List<Pair<Column<*>, Any?>> {
        val t = WorkItemStore.WorkItems
        val odd = (issueId + variant) % 2 == 0L
        return listOf(
            t.issueId to issueId,
            t.issueKey to "MRU-$issueId",
            t.projectKey to "MRU",
            t.issueType to if (variant == 1) "Bug" else "Story \u00e9\u4e2d",
            t.isSubtask to odd,
            t.parentIssueId to if (odd) issueId + 1 else null,
            t.summary to if (variant == 1 && issueId % 3 == 0L) null else "summary $variant ${"s".repeat((issueId % 50).toInt())}",
            t.statusId to "1000${variant}",
            t.statusName to "Status $variant",
            t.statusCategory to if (odd) "DONE" else "IN_PROGRESS",
            t.resolution to if (odd) "Done" else null,
            t.priority to if (variant == 1) null else "High",
            t.assigneeAccountId to if (odd) "acc-$issueId" else null,
            t.reporterAccountId to if (variant == 1) null else "rep-$issueId",
            t.createdAt to 1_000L + issueId,
            t.updatedAt to 2_000L + issueId * variant,
            t.resolvedAt to if (odd) 3_000L + issueId else null,
            t.storyPoints to if (odd) 2.5 + variant else null,
            t.originalEstimateSeconds to if (variant == 1) null else 3_600L * issueId,
            t.timeSpentSeconds to 60L * variant,
            t.labels to stringArrayJson(listOf("a$variant", "b")),
            t.components to stringArrayJson(emptyList()),
            t.fixVersions to stringArrayJson(listOf("v$issueId")),
            t.currentSprintIds to "[$issueId, ${issueId + 1}]",
            t.teamValue to if (odd) """{"id":"t$variant"}""" else null,
            t.flagged to (variant == 1),
            t.rank to if (odd) "0|i$issueId:" else null,
            t.hierarchyLevel to if (variant == 1) null else 0,
            t.dueAt to if (odd) 9_000L else null,
            t.customFields to """{"customfield_1":"x$variant"}""",
            t.anomalies to "[]",
            t.deletedAt to if (variant == 1 && issueId % 7 == 0L) 5_000L else null,
            t.movedOutAt to if (variant == 0 && issueId % 7 == 0L) 6_000L else null,
            t.processedAt to 10_000L * (variant + 1),
            t.processingVersion to 3 + variant,
        )
    }

    private suspend fun readWorkItems(connId: UInt): List<List<Any?>> = suspendTransaction(sharedDatabaseForTests()) {
        val t = WorkItemStore.WorkItems
        t.selectAll().where { t.connectionId eq connId }.orderBy(t.issueId to SortOrder.ASC).toList()
            .map { row -> t.columns.filter { it != t.connectionId }.map { row[it] } }
    }

    private suspend fun cleanUpWorkItems(connIds: List<UInt>) = suspendTransaction(sharedDatabaseForTests()) {
        for (connId in connIds) WorkItemStore.WorkItems.deleteWhere { WorkItemStore.WorkItems.connectionId eq connId }
    }

    /** Writes [ids] at [variant] through `batchUpsert` into [connId] — the reference `upsertRows` must match. */
    private suspend fun batchUpsertWorkItems(connId: UInt, ids: List<Long>, variant: Int) = suspendTransaction(sharedDatabaseForTests()) {
        val t = WorkItemStore.WorkItems
        t.batchUpsert(ids, t.connectionId, t.issueId, shouldReturnGeneratedValues = false) { id ->
            this[t.connectionId] = connId
            @Suppress("UNCHECKED_CAST") // every pair's value comes from its own column's declared type
            for ((column, value) in workItemValues(id, variant)) this[column as Column<Any?>] = value
        }
    }

    private suspend fun upsertRowsWorkItems(connId: UInt, ids: List<Long>, variant: Int) = suspendTransaction(sharedDatabaseForTests()) {
        val t = WorkItemStore.WorkItems
        t.upsertRows(ids, listOf(t.connectionId, t.issueId)) { id ->
            this[t.connectionId] = connId
            @Suppress("UNCHECKED_CAST") // every pair's value comes from its own column's declared type
            for ((column, value) in workItemValues(id, variant)) this[column as Column<Any?>] = value
        }
    }

    @Test
    fun `work_items upsert - insert path then update path over several chunks, NULLs both ways - equals batchUpsert`() = runBlocking {
        val viaBatch = connection()
        val viaRows = connection()
        try {
            // 36 columns -> 888 rows per statement: 1,000 rows are two full-or-quantized statements; the second write
            // overwrites ids 500..1000 (update path, every nullable column flips between NULL and a value) and adds 1001..1500.
            val first = (1L..1_000L).toList()
            val second = (500L..1_500L).toList()
            batchUpsertWorkItems(viaBatch, first, variant = 0)
            upsertRowsWorkItems(viaRows, first, variant = 0)
            val afterInsert = readWorkItems(viaBatch)
            assertEquals(1_000, afterInsert.size)
            assertEquals(afterInsert, readWorkItems(viaRows), "insert path: upsertRows must store what batchUpsert stores")

            batchUpsertWorkItems(viaBatch, second, variant = 1)
            upsertRowsWorkItems(viaRows, second, variant = 1)
            val afterUpdate = readWorkItems(viaBatch)
            assertEquals(1_500, afterUpdate.size)
            assertEquals(afterUpdate, readWorkItems(viaRows), "update path: upsertRows must store what batchUpsert stores")
            val t = WorkItemStore.WorkItems
            val columns = t.columns.filter { it != t.connectionId }
            val summary = columns.indexOf(t.summary)
            val summaries = afterUpdate.map { it[summary] }
            assertTrue(summaries.any { it == null } && summaries.any { it != null }, "NULL and non-NULL both exercised")
            assertTrue(afterUpdate.first()[columns.indexOf(t.processingVersion)] == 3, "rows outside the second write are untouched")
            assertTrue(afterUpdate[700][columns.indexOf(t.processingVersion)] == 4, "rows inside the second write were updated")
        } finally {
            cleanUpWorkItems(listOf(viaBatch, viaRows))
        }
    }

    @Test
    fun `upsertRows collapses a repeated key to its last row like batchUpsert's sequential executions`() = runBlocking {
        val viaBatch = connection()
        val viaRows = connection()
        try {
            // id 5 appears twice with different values: a single ON CONFLICT statement would otherwise raise
            // "cannot affect row a second time"; batchUpsert executes in order, so the last one wins.
            val t = WorkItemStore.WorkItems
            suspend fun write(connId: UInt, viaUpsertRows: Boolean) = suspendTransaction(sharedDatabaseForTests()) {
                val rows = listOf(5L to 0, 6L to 0, 5L to 1, 7L to 0)
                @Suppress("UNCHECKED_CAST") // every pair's value comes from its own column's declared type
                if (viaUpsertRows) {
                    t.upsertRows(rows, listOf(t.connectionId, t.issueId)) { (id, variant) ->
                        this[t.connectionId] = connId
                        for ((column, value) in workItemValues(id, variant)) this[column as Column<Any?>] = value
                    }
                } else {
                    t.batchUpsert(rows, t.connectionId, t.issueId, shouldReturnGeneratedValues = false) { (id, variant) ->
                        this[t.connectionId] = connId
                        for ((column, value) in workItemValues(id, variant)) this[column as Column<Any?>] = value
                    }
                }
            }
            write(viaBatch, viaUpsertRows = false)
            write(viaRows, viaUpsertRows = true)
            val expected = readWorkItems(viaBatch)
            assertEquals(3, expected.size)
            val statusName = t.columns.filter { it != t.connectionId }.indexOf(t.statusName)
            assertEquals("Status 1", expected.first()[statusName], "the reference: the last row for id 5 wins")
            assertEquals(expected, readWorkItems(viaRows))
        } finally {
            cleanUpWorkItems(listOf(viaBatch, viaRows))
        }
    }

    @Test
    fun `upsertRows refuses an empty or unset conflict target and a table that is all key`() = runBlocking {
        val connId = connection()
        val t = MetricsTables.ItemStage
        assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) { t.upsertRows(listOf(1L), emptyList()) { this[t.connectionId] = connId } }
        }
        val unset = assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) {
                t.upsertRows(listOf(1L), listOf(t.connectionId, t.issueId)) { this[t.connectionId] = connId }
            }
        }
        assertTrue(unset.message!!.contains("conflict key"), unset.message)
        val allKey = assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) {
                t.upsertRows(listOf(1L), listOf(t.issueId)) { this[t.issueId] = it }
            }
        }
        assertTrue(allKey.message!!.contains("nothing to update"), allKey.message)
        Unit
    }

    @Test
    fun `no rows is a no-op and rows that set different columns are refused`() = runBlocking {
        val connId = connection()
        val table = MetricsTables.ItemStage
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                table.insertRows(emptyList<Stage>()) { this[table.connectionId] = connId }
                assertEquals(0L, table.selectAll().where { table.connectionId eq connId }.count())
            }
            assertFailsWith<IllegalArgumentException> {
                suspendTransaction(sharedDatabaseForTests()) {
                    table.insertRows(listOf(1L, 2L)) {
                        this[table.connectionId] = connId
                        this[table.issueId] = it
                        if (it == 1L) this[table.stage] = "DONE"
                    }
                }
            }
            assertFailsWith<IllegalArgumentException> {
                suspendTransaction(sharedDatabaseForTests()) { table.insertRows(listOf(1L)) { } }
            }
            Unit // the last expression of a runBlocking test body must be Unit (JUnit4 wants a void method)
        } finally {
            cleanUp(listOf(connId))
        }
    }

    @Test
    fun `a column of another table and a sequence-backed autoIncrement table are refused`() = runBlocking {
        val table = MetricsTables.ItemStage
        val foreign = assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) {
                table.insertRows(listOf(1L)) { this[MetricsTables.DimTask.issueId] = it }
            }
        }
        assertTrue(foreign.message!!.contains("issue_id") && foreign.message!!.contains("dim_task"), foreign.message)
        val sequenced = assertFailsWith<IllegalArgumentException> {
            suspendTransaction(sharedDatabaseForTests()) {
                SequencedStage.insertRows(listOf(1L)) { this[SequencedStage.issueId] = it }
            }
        }
        assertTrue(sequenced.message!!.contains("sequence-backed"), sequenced.message)
    }

    /** The shape of a table whose id comes from a named sequence (`autoIncrement("…")`) — never inserted into. */
    private object SequencedStage : Table("metrics.item_stage") {
        val id = integer("id").autoIncrement("metrics.item_stage_id_seq")
        val issueId = long("issue_id")
    }

    @Test
    fun `chunk sizes are full chunks then a binary decomposition - a handful of distinct statement texts`() {
        assertEquals(emptyList(), chunkSizes(0, 100))
        assertEquals(listOf(8, 4, 1), chunkSizes(13, 100))
        assertEquals(listOf(100, 100, 32, 8, 2, 1), chunkSizes(243, 100))
        assertEquals(listOf(100), chunkSizes(100, 100))
        assertEquals(listOf(1), chunkSizes(1, 5_333))
        val max = 5_333
        val seen = mutableSetOf<Int>()
        for (total in 0..12_000) {
            val sizes = chunkSizes(total, max)
            assertEquals(total, sizes.sum(), "every row is written exactly once (total $total)")
            assertTrue(sizes.all { it == max || Integer.bitCount(it) == 1 }, "only full or power-of-two chunks (total $total)")
            seen += sizes
        }
        // the max itself plus the 13 powers of two below it: the whole vocabulary of a 5,333-row-wide table
        assertTrue(seen.size <= 14, "distinct chunk sizes: ${seen.size}")
    }
}
