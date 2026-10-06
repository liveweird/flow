package ch.nokillswit

import ch.nokillswit.infra.db.chunkSizes
import ch.nokillswit.infra.db.insertRows
import org.jetbrains.exposed.v1.core.Table
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchInsert
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
