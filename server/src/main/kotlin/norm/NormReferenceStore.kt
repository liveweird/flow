package ch.nokillswit.norm

import ch.nokillswit.infra.json.parseStringArray
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.norm.WorkItemStore.BoardColumns
import ch.nokillswit.norm.WorkItemStore.Boards
import ch.nokillswit.norm.WorkItemStore.People
import ch.nokillswit.norm.WorkItemStore.Sprints
import ch.nokillswit.norm.WorkItemStore.Statuses
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

// Deliberately the `WorkItemStore` logger name: the skip warning predates the split and operators/tests filter on it.
private val log = LoggerFactory.getLogger(WorkItemStore::class.java)

/**
 * True when [value] fits this `varchar(n)` column (or is null / the column is unbounded). Exposed checks the
 * length CLIENT-side before any SQL runs, so an over-long value would throw out of the whole rebuild — the
 * reference rebuilds ([NormReferenceStore.replaceStatuses] and friends) use this to SKIP such a row instead.
 * `String.length` is never smaller than the code-point count, so it can only be stricter than either check.
 */
private fun Column<out String?>.fits(value: String?): Boolean {
    val limit = (columnType as? VarCharColumnType)?.colLength
    return value == null || limit == null || value.length <= limit
}

/** One structured warn naming what a reference rebuild skipped; [ids] stay out of the line for people (account ids). */
private fun logSkippedReferenceRows(table: String, skipped: Int, ids: List<Any>?) {
    if (skipped == 0) return
    val firstIds = ids?.take(REFERENCE_SKIP_LOGGED_IDS)?.joinToString(prefix = ", first ids ").orEmpty()
    log.warn("{} rebuild skipped {} row(s) whose key/enum value exceeds its column length{}", table, skipped, firstIds)
}

private const val REFERENCE_SKIP_LOGGED_IDS = 5

/** A rebuilt reference row (plan §8: "reference rows ... rebuilt per connection each PROCESS") — one per `norm.statuses` row. */
data class StatusRef(val statusId: String, val name: String, val category: StatusCategory)
data class PersonRef(val accountId: String, val displayName: String, val email: String?, val active: Boolean)
data class BoardColumnRef(val name: String, val statusIds: List<String>)
data class BoardRef(val boardId: Long, val name: String, val boardType: String, val projectKey: String?, val columns: List<BoardColumnRef>)
data class SprintRef(
    val sprintId: Long,
    val boardId: Long?,
    val name: String,
    val state: String,
    val startAtMs: Long?,
    val endAtMs: Long?,
    val goal: String?,
    /** `completeDate` (v0.3.0 M1 commit 2) — the metrics layer keys sprint periods on completion, not `endAt`. */
    val completeAtMs: Long? = null,
)

/**
 * The wholesale-rebuilt reference tables (`norm.statuses`/`people`/`boards`/`board_columns`/`sprints`, plan §8): the
 * per-connection REPLACE the PROCESS step runs once per run (never diffed row-by-row), and the read-back of the
 * status/board/sprint rows for the data profile. An over-long key/enum value is SKIPPED and counted, never thrown.
 */
internal class NormReferenceStore(private val database: R2dbcDatabase) {

    /** Reference rows are rebuilt WHOLESALE per connection, once per PROCESS run (plan §8) — never diffed. */
    suspend fun replaceStatuses(connectionId: UInt, statuses: List<StatusRef>): Int {
        val (rows, skipped) = statuses.partition { Statuses.statusId.fits(it.statusId) }
        suspendTransaction(database) {
            Statuses.deleteWhere { Statuses.connectionId eq connectionId }
            if (rows.isNotEmpty()) {
                Statuses.batchInsert(rows) {
                    this[Statuses.connectionId] = connectionId
                    this[Statuses.statusId] = it.statusId
                    this[Statuses.name] = it.name
                    this[Statuses.category] = it.category.name
                }
            }
        }
        logSkippedReferenceRows("norm.statuses", skipped.size, skipped.map { it.statusId })
        return skipped.size
    }

    suspend fun replacePeople(connectionId: UInt, people: List<PersonRef>): Int {
        val (rows, skipped) = people.partition { People.accountId.fits(it.accountId) }
        suspendTransaction(database) {
            People.deleteWhere { People.connectionId eq connectionId }
            if (rows.isNotEmpty()) {
                People.batchInsert(rows) {
                    this[People.connectionId] = connectionId
                    this[People.accountId] = it.accountId
                    this[People.displayName] = it.displayName
                    this[People.email] = it.email
                    this[People.active] = it.active
                }
            }
        }
        logSkippedReferenceRows("norm.people", skipped.size, null)
        return skipped.size
    }

    suspend fun replaceBoards(connectionId: UInt, boards: List<BoardRef>): Int {
        val (rows, skipped) = boards.partition { Boards.boardType.fits(it.boardType) && Boards.projectKey.fits(it.projectKey) }
        suspendTransaction(database) {
            Boards.deleteWhere { Boards.connectionId eq connectionId }
            BoardColumns.deleteWhere { BoardColumns.connectionId eq connectionId }
            if (rows.isNotEmpty()) {
                Boards.batchInsert(rows) {
                    this[Boards.connectionId] = connectionId
                    this[Boards.boardId] = it.boardId
                    this[Boards.name] = it.name
                    this[Boards.boardType] = it.boardType
                    this[Boards.projectKey] = it.projectKey
                }
            }
            rows.forEach { board ->
                if (board.columns.isNotEmpty()) {
                    BoardColumns.batchInsert(board.columns.withIndex().toList()) { (index, column) ->
                        this[BoardColumns.connectionId] = connectionId
                        this[BoardColumns.boardId] = board.boardId
                        this[BoardColumns.seq] = index + 1
                        this[BoardColumns.name] = column.name
                        this[BoardColumns.statusIds] = stringArrayJson(column.statusIds)
                    }
                }
            }
        }
        logSkippedReferenceRows("norm.boards", skipped.size, skipped.map { it.boardId })
        return skipped.size
    }

    suspend fun replaceSprints(connectionId: UInt, sprints: List<SprintRef>): Int {
        val (rows, skipped) = sprints.partition { Sprints.state.fits(it.state) }
        suspendTransaction(database) {
            Sprints.deleteWhere { Sprints.connectionId eq connectionId }
            if (rows.isNotEmpty()) {
                Sprints.batchInsert(rows) {
                    this[Sprints.connectionId] = connectionId
                    this[Sprints.sprintId] = it.sprintId
                    this[Sprints.boardId] = it.boardId
                    this[Sprints.name] = it.name
                    this[Sprints.state] = it.state
                    this[Sprints.startAt] = it.startAtMs
                    this[Sprints.endAt] = it.endAtMs
                    this[Sprints.goal] = it.goal
                    this[Sprints.completeAt] = it.completeAtMs
                }
            }
        }
        logSkippedReferenceRows("norm.sprints", skipped.size, skipped.map { it.sprintId })
        return skipped.size
    }

    /**
     * Every `norm.sprints` reference row for a connection (v0.2.0 plan §8/§12 item 9) — rebuilt
     * wholesale per PROCESS run, read back as-is.
     */
    suspend fun allSprintRefs(connectionId: UInt): List<SprintRef> = suspendTransaction(database) {
        Sprints.selectAll().where { Sprints.connectionId eq connectionId }.toList().map { row ->
            SprintRef(
                sprintId = row[Sprints.sprintId],
                boardId = row[Sprints.boardId],
                name = row[Sprints.name],
                state = row[Sprints.state],
                startAtMs = row[Sprints.startAt],
                endAtMs = row[Sprints.endAt],
                goal = row[Sprints.goal],
                completeAtMs = row[Sprints.completeAt],
            )
        }
    }

    /** Every `norm.boards`/`norm.board_columns` reference row for a connection, joined back into [BoardRef] shape. */
    suspend fun allBoardRefs(connectionId: UInt): List<BoardRef> = suspendTransaction(database) {
        val columnsByBoard = BoardColumns.selectAll().where { BoardColumns.connectionId eq connectionId }
            .toList().sortedBy { it[BoardColumns.seq] }
            .groupBy({ it[BoardColumns.boardId] }) { BoardColumnRef(it[BoardColumns.name], parseStringArray(it[BoardColumns.statusIds])) }
        Boards.selectAll().where { Boards.connectionId eq connectionId }.toList().map { row ->
            val boardId = row[Boards.boardId]
            BoardRef(
                boardId = boardId,
                name = row[Boards.name],
                boardType = row[Boards.boardType],
                projectKey = row[Boards.projectKey],
                columns = columnsByBoard[boardId] ?: emptyList(),
            )
        }
    }

    /** Every `norm.statuses` reference row for a connection — the data profile's status-id-to-name lookup for board columns. */
    suspend fun allStatusRefs(connectionId: UInt): List<StatusRef> = suspendTransaction(database) {
        Statuses.selectAll().where { Statuses.connectionId eq connectionId }.toList().map { row ->
            StatusRef(row[Statuses.statusId], row[Statuses.name], StatusCategory.valueOf(row[Statuses.category]))
        }
    }
}
