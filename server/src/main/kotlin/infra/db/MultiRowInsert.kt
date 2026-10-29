package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The largest number of bind parameters one [insertRows] statement carries. The PostgreSQL wire
 * protocol counts a Bind message's parameters in an Int16 (65,535); half of that leaves headroom,
 * and the row chunk is derived from it, so a wide table simply gets smaller chunks.
 */
private const val MAX_BIND_PARAMETERS = 32_000

/**
 * The most characters of `String` parameter values (the raw tables' `jsonb` payloads, any text) one statement carries: 1 MiB.
 * The bind cap alone bounds the PARAMETER COUNT, not the bytes: dense issues' worklogs (540 of ~15 KB per issue) were flushed
 * in statements capped only at the bind cap's ~3,555 rows, ~55 MB of jsonb each, and two such statements at once got a Postgres backend
 * OOM-killed (`build-times.md` WHY 48, case g).
 * A statement costs the backend several times its payload (the Bind message, the parsed value, the jsonb conversion, the tuple),
 * so the budget keeps that to a few MiB; it is far above every real per-issue batch (the largest real per-issue worklog total is
 * ~0.5 MB) and far below the point where one more round trip per ~1 MiB shows in a sync. The call is per FLUSH, not per issue (up to
 * 100 issues, or `BACKFILL_FLUSH_WORKLOGS` = 5,000 worklogs, `JiraWorklogStream.kt`), so a real multi-MB flush splits too: behaviourally
 * fine, the rows keep their order and their one transaction. Counted in `String.length` (UTF-16 units), which UNDER-counts the UTF-8
 * bytes of non-ASCII text by up to 3x, so such a statement can carry up to ~3 MiB; harmless at this budget (a memory guard, not a
 * quota). Only `String` values count. Every writer is bounded the same way, but the metrics rows
 * (short keys) and one issue's `norm` rows are far below it, so their statements are the ones the bind cap alone made.
 * A single row over the budget is written alone, never refused here (the stored-size ceilings refuse an oversized item
 * upstream, `jira-integration.md` "Stored-size ceilings"). The rows are built lazily against this same budget (see [insertRows]).
 */
internal const val MAX_PAYLOAD_CHARS = 1 shl 20

/** The most rows one statement of a table whose statement lists [columnCount] columns carries (its bind-parameter cap). */
internal fun maxRowsPerStatement(columnCount: Int): Int = (MAX_BIND_PARAMETERS / columnCount).coerceAtLeast(1)

/**
 * Test seam (`MultiRowInsertTest`, `JiraRawStoreTest`): a coroutine-context element, installed with
 * `withContext(StatementProbe { table, rows -> … }) { … }`, that [writeRows] calls with the table and the number of rows a statement
 * carries, just before it runs. Those are the only rows [insertRows] and [insertRowsIgnoringConflicts] have in flight (an upsert's are
 * all built first, see [upsertRows]). It sees only the writes of coroutines running under it, so a concurrent writer elsewhere in the
 * same JVM can neither reach it nor be miscounted by it (it replaced a process-wide mutable hook).
 */
internal class StatementProbe(val onStatement: (Table, Int) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<StatementProbe>
}

/** The row a [insertRows] body fills: `this[Column] = value`, the same DSL as `batchInsert`'s lambda. */
class InsertRow {
    internal val values = LinkedHashMap<Column<*>, Any?>()

    operator fun <S> set(column: Column<S>, value: S) {
        values[column] = value
    }

    // A `reference(…)` column is a `Column<EntityID<ID>>` that callers fill with the plain id, exactly
    // as `batchInsert`'s own setter takes it; it is bound through the referenced id column's own type.
    @JvmName("setEntityId")
    operator fun <ID : Comparable<ID>> set(column: Column<EntityID<ID>>, value: ID) {
        values[column] = value
    }

    @JvmName("setNullableEntityId")
    operator fun <ID : Comparable<ID>> set(column: Column<EntityID<ID>?>, value: ID?) {
        values[column] = value
    }
}

/**
 * `batchInsert`'s call shape — `table.insertRows(rows) { this[Col] = it.value }` — executed as ONE
 * multi-row `INSERT … VALUES (…), (…)` per chunk instead of one statement execution per row.
 *
 * Why it exists (`.claude/docs/build-times.md` WHY 3): `exposed-r2dbc`'s `batchInsert` runs every
 * row as its own bound execution of the prepared statement — ~0.16-0.19 ms per row however small
 * the row — so a DERIVE of the 1,200-issue stub (~15,000 bridge/fact rows) spent ~2.3 s of its ~3.3 s
 * in it. The same rows as multi-row `VALUES` statements take ~10 x less (3,600 `item_stage` rows:
 * 620 ms `batchInsert`, 54 ms here; the database's own cost with its indexes and foreign-key check
 * is ~40 ms). Nothing else differs: the rows, their order and their column values are the ones
 * `batchInsert` writes (every value still goes through its column's own type, every value is
 * still a bound parameter, never string-built) and it runs inside the caller's current transaction.
 *
 * Contract (checked, not assumed): every row sets the SAME columns. The statement lists the table's
 * columns in declaration order: each column a row sets, plus each column carrying an Exposed
 * client-side `default(…)` that no row sets (filled with that default, as `batchInsert` does). Any
 * other unset column is omitted, so its database default applies (a `SERIAL` id, a NULL). Generated
 * values are never returned (no caller reads the surrogate id). An empty [rows] is a no-op. Every set
 * column must belong to this table, and the table must have no sequence-backed `autoIncrement("seq")`
 * column (Exposed would insert `nextval(...)` for it; plain `SERIAL` ids, which the database fills, are fine).
 * Exposed's client-side value validation (a `varchar` length check) is NOT run: an overflow fails
 * server-side (SQLSTATE 22001) instead of in the client.
 *
 * **Statements are bounded in rows and in payload** ([payloadChunkSizes]): at most the bind cap's rows ([MAX_BIND_PARAMETERS]) and
 * at most [MAX_PAYLOAD_CHARS] characters of `String` values (a lone heavier row goes alone). Upsert's last-per-key collapse sees the whole
 * batch BEFORE this split, and the rows keep their order and their one transaction.
 *
 * **Statement texts are quantized** ([payloadChunkSizes]): the SQL text depends on the row count, and every
 * distinct text becomes one named server-side prepared statement that the driver caches per connection,
 * so a chunk is either the table's maximum rows per statement or a power of two — a handful of texts per
 * table, however many rows a batch has.
 *
 * **Rows are built per statement, and the plan is the same.** [insertRows] and [insertRowsIgnoringConflicts] run `body` for a row only
 * while the statement that carries it is assembled ([lazyStatements]): rows are built until the next statement is decided (the row cap
 * reached, or the payload budget overflowed by one row, the payload of a row being known once it is built) and the rest wait for the
 * next one, so the rows in flight are one statement's cap ([maxRowsPerStatement]), not the call's: the `InsertRow` maps, the boxed
 * numbers and the payload texts `body` renders for the rest of a large call do not exist yet (WORKLOGS batches of up to ~30,000 rows,
 * `build-times.md` WHY 51). The statements are EXACTLY [payloadChunkSizes]' over the full list, power-of-two rounding included. `body`
 * therefore runs lazily, exactly once per row, and must be pure (the first row, built to read the columns, is handed to the first
 * statement, not built again, and is not kept for the call). A row that sets other columns than the first fails the call when its statement
 * is assembled, after the earlier statements ran: the caller's transaction rolls them back. [upsertRows] still builds every row first,
 * because its collapse of repeated keys needs them all, then plans with [payloadChunkSizes]. A writer may bound its own groups tighter
 * (the worklog writer hands in groups of at most 1,024 rows / 512 Ki characters, `JiraWorklogCeilings.kt`, to cap what it holds beside
 * the batch); a group is under [MAX_PAYLOAD_CHARS], so it normally becomes one statement (a group of a non-power-of-two row count
 * becomes its binary decomposition, as any batch does) and the two bounds never contradict.
 *
 * **Memory (`build-times.md` WHY 14, `MultiRowInsertRetentionTest`):** every chunk's executed statement is released
 * as soon as it ran (`clearExecutedStatements`), so a transaction's heap does not grow with the rows it writes.
 * `clearExecutedStatements()` empties the transaction's WHOLE list — the caller's earlier statements too (DERIVE's
 * per-batch `inList` SELECTs, ~80 MB at scale 20 if kept) — and resets `openResultRowsCount`. DERIVE's per-batch reads
 * are therefore bounded only because every batch ends in an `insertRows`: a step that reads per batch but writes via
 * `batchInsert`/`exec` would bring the linear retention back.
 */
suspend fun <T : Table, E> T.insertRows(rows: Iterable<E>, body: InsertRow.(E) -> Unit) {
    writeRows("insertRows", rows, body, conflictKeys = null)
}

/**
 * [insertRows]' append-only sibling — `table.insertRowsIgnoringConflicts(rows) { … }` is ONE multi-row
 * `INSERT … VALUES (…), (…) ON CONFLICT DO NOTHING` per chunk (the same chunking, quantized statement texts, bind-parameter cap
 * and contract as [insertRows]; the suffix is constant). It is the batch form of Exposed's `insertIgnore`: a row that violates
 * any unique constraint is silently dropped, the first of two rows sharing a key winning (PostgreSQL skips a duplicate inside
 * one `DO NOTHING` statement — unlike `DO UPDATE`, which could not touch a row twice). Nothing is returned, so the number of
 * rows actually written is unknown to the caller.
 */
suspend fun <T : Table, E> T.insertRowsIgnoringConflicts(rows: Iterable<E>, body: InsertRow.(E) -> Unit) {
    writeRows("insertRowsIgnoringConflicts", rows, body, conflictKeys = null, ignoreConflicts = true)
}

/**
 * [insertRows]' `ON CONFLICT` sibling — `table.upsertRows(rows, keys = listOf(Col, …)) { this[Col] = it.value }` replaces
 * `batchUpsert(rows, *keys)` with ONE multi-row
 * `INSERT … VALUES (…), (…) ON CONFLICT (<keys>) DO UPDATE SET c = EXCLUDED.c, …` per chunk (the same chunking, the same
 * quantized statement texts, the same bind-parameter cap, the same contract as [insertRows]; the `ON CONFLICT` suffix
 * is constant, so it adds no statement texts). Why: `batchUpsert` is one bound execution per row, ~0.2 ms each
 * (`.claude/docs/build-times.md`, the `norm.work_items` follow-up).
 *
 * `keys` are the conflict target (a unique index/PK of the table, every one set by every row). The `DO UPDATE SET`
 * list is every OTHER column of the statement — each column a row sets plus each unset client-side `default(…)`
 * column, exactly what `batchUpsert` updates — and it must not be empty (use [insertRows] for a table that is all
 * key). A single `INSERT … ON CONFLICT DO UPDATE` cannot touch one row twice (`cardinality_violation`), so rows
 * sharing a key are collapsed to the LAST one before chunking: the end state `batchUpsert`'s sequential
 * executions leave. No `WHERE` clause, nothing returned.
 */
suspend fun <T : Table, E> T.upsertRows(rows: Iterable<E>, keys: List<Column<*>>, body: InsertRow.(E) -> Unit) {
    require(keys.isNotEmpty()) { "upsertRows: the conflict target needs at least one key column" }
    writeRows("upsertRows", rows, body, conflictKeys = keys)
}

private suspend fun <T : Table, E> T.writeRows(
    caller: String,
    rows: Iterable<E>,
    body: InsertRow.(E) -> Unit,
    conflictKeys: List<Column<*>>?,
    ignoreConflicts: Boolean = false,
) {
    val source = rows as? List<E> ?: rows.toList()
    if (source.isEmpty()) return
    fun build(element: E): Map<Column<*>, Any?> = InsertRow().also { it.body(element) }.values
    // An upsert collapses repeated keys over the WHOLE call, which needs every row's key first, so it materialises them (every row
    // validated before the collapse); the other two build each row only while its statement is being assembled (see "Memory").
    val eager = if (conflictKeys == null) null else source.map(::build)
    val firstRow = eager?.first() ?: build(source.first())
    // A copy: the key view would keep the first row alive for the whole call.
    val setColumns = LinkedHashSet(firstRow.keys)
    require(setColumns.isNotEmpty()) { "$caller: a row that sets no column cannot be inserted" }
    fun checked(row: Map<Column<*>, Any?>) = row.also { require(it.keys == setColumns) { "$caller: every row must set the same columns" } }
    eager?.forEach(::checked)
    requireWritable(caller, setColumns)
    val collected = if (eager != null && conflictKeys != null) lastPerKey(caller, eager, conflictKeys) else null
    val columns = this.columns.filter { it in setColumns || it.defaultValueFun != null }
    val tx = TransactionManager.current()
    val header = "INSERT INTO ${tx.identity(this)} (${columns.joinToString(", ") { tx.identity(it) }}) VALUES "
    val suffix = when {
        ignoreConflicts -> " ON CONFLICT DO NOTHING"
        conflictKeys == null -> ""
        else -> conflictSuffix(caller, tx, columns, conflictKeys)
    }
    val maxRows = maxRowsPerStatement(columns.size)
    // The lazy plan's first row is the one built above; the rest are built as their statement is assembled (`head` is dropped once used).
    var head: Map<Column<*>, Any?>? = if (eager == null) firstRow else null
    var next = 1
    val statements = if (collected != null) {
        // The whole batch is built, so its plan is the pure planner's over the materialised payloads.
        var offset = 0
        payloadChunkSizes(collected.map(::payloadChars), maxRows, MAX_PAYLOAD_CHARS.toLong()).asSequence()
            .map { size -> collected.subList(offset, offset + size).also { offset += size } }
    } else {
        lazyStatements(source.size, maxRows, MAX_PAYLOAD_CHARS.toLong(), ::payloadChars) {
            head?.also { head = null } ?: checked(build(source[next++]))
        }
    }
    for (chunk in statements) {
        val size = chunk.size
        val arguments = ArrayList<Pair<IColumnType<*>, Any?>>(size * columns.size)
        val sql = StringBuilder(header)
        chunk.forEachIndexed { index, row ->
            if (index > 0) sql.append(", ")
            sql.append('(')
            columns.forEachIndexed { columnIndex, column ->
                val value = if (column in row) row[column] else column.defaultValueFun?.invoke()
                if (columnIndex > 0) sql.append(", ")
                val bound = bindable(column, value)
                sql.append(marker(bound.first, bound.second))
                arguments += bound
            }
            sql.append(')')
        }
        sql.append(suffix)
        coroutineContext[StatementProbe]?.onStatement?.invoke(this, size)
        tx.exec(sql.toString(), arguments)
        // Exposed keeps every executed statement until the transaction ends (commit or rollback; `closeStatementsAndConnection`
        // only clears the list, closing nothing, and `executeIn` itself clears it when `!supportsMultipleResultSets`, so this
        // is a sanctioned operation): a 32,000-parameter statement is ~8 MB of parsed tokens + encoded parameters, and DERIVE
        // is ONE transaction.
        tx.clearExecutedStatements()
    }
}

/** The checks on the table and the columns a call sets that do not depend on any row's value (see the contract on [insertRows]). */
private fun Table.requireWritable(caller: String, setColumns: Set<Column<*>>) {
    setColumns.firstOrNull { it.table != this }?.let { foreign ->
        throw IllegalArgumentException("$caller: column ${foreign.name} belongs to ${foreign.table.tableName}, not ${this.tableName}")
    }
    require(this.columns.none { (it.columnType as? AutoIncColumnType<*>)?.sequence != null }) {
        "$caller: ${this.tableName} has a sequence-backed autoIncrement column; use batchInsert"
    }
}

/** [rows] with every key collapsed to its LAST row (first-seen order), see [upsertRows]. */
private fun lastPerKey(caller: String, rows: List<Map<Column<*>, Any?>>, keys: List<Column<*>>): List<Map<Column<*>, Any?>> {
    require(keys.all { it in rows.first() }) { "$caller: every conflict key column must be set by every row" }
    val byKey = LinkedHashMap<List<Any?>, Map<Column<*>, Any?>>(rows.size * 2)
    for (row in rows) byKey[keys.map { key -> row[key].let { if (it is EntityID<*>) it.value else it } }] = row
    return byKey.values.toList()
}

private fun conflictSuffix(caller: String, tx: R2dbcTransaction, columns: List<Column<*>>, keys: List<Column<*>>): String {
    val updated = columns.filter { it !in keys }
    require(updated.isNotEmpty()) { "$caller: every column is a conflict key, nothing to update" }
    return " ON CONFLICT (${keys.joinToString(", ") { tx.identity(it) }}) DO UPDATE SET " +
        updated.joinToString(", ") { "${tx.identity(it)} = EXCLUDED.${tx.identity(it)}" }
}

/**
 * The row counts of the statements that write rows weighing [payloads] (each row's `String` characters, in order), each statement
 * at most [maxRows] rows and, unless it is a single row, at most [maxPayload] characters. A statement is [maxRows] rows or a
 * power of two, so the SQL texts stay a handful per table; when no row is heavy the result is the full chunks of [maxRows] and then
 * the remainder's binary decomposition (13 -> 8, 4, 1; pinned against an oracle in `MultiRowInsertPayloadTest`). A statement is the
 * longest fitting run, rounded DOWN to a power of two unless it hit [maxRows]; a run that stopped on the payload budget leaves its
 * remainder for the next statement. A row heavier than
 * [maxPayload] alone is a statement of one row. The budget is inclusive (a run summing to exactly [maxPayload] fits).
 */
internal fun payloadChunkSizes(payloads: List<Int>, maxRows: Int, maxPayload: Long): List<Int> {
    val sizes = ArrayList<Int>()
    var start = 0
    while (start < payloads.size) {
        var fit = 0
        var sum = 0L
        while (start + fit < payloads.size && fit < maxRows) {
            sum += payloads[start + fit]
            if (fit > 0 && sum > maxPayload) break
            fit++
        }
        val size = if (fit == maxRows) maxRows else Integer.highestOneBit(fit)
        sizes += size
        start += size
    }
    return sizes
}

/** The characters of `String` values a row binds (the payload the budget counts; defaults a row leaves unset are not counted). */
private fun payloadChars(row: Map<Column<*>, Any?>): Int = row.values.sumOf { (it as? String)?.length ?: 0 }

/**
 * The statements [payloadChunkSizes] would plan over [total] rows, but built lazily: [next] builds the next row ([payloadOf] is its
 * payload) only when the statement being decided needs it, so a row exists from its building until its statement is handed out and the
 * rows in flight are at most [maxRows] (one statement's cap), never the call's. The plan is exactly [payloadChunkSizes]' over the full
 * list, power-of-two rounding included: a statement is [maxRows] rows when that many fit; otherwise a power of two, and then the
 * rows still unemitted decide how far to look. With `left` rows unemitted at most `min(maxRows, left)` can join, so a statement is
 * decided as soon as that many are built and fit (a [maxRows] run when `left >= maxRows`, else the highest power of two not above
 * `left`), or as soon as the budget overflows at row `k` (then the highest power of two not above `k`). Rows built past the statement
 * (the overflowing row, a power-of-two remainder) stay for the next one. Pinned against [payloadChunkSizes] in `MultiRowInsertPayloadTest`.
 */
internal fun <R> lazyStatements(
    total: Int,
    maxRows: Int,
    maxPayload: Long,
    payloadOf: (R) -> Int,
    next: () -> R,
): Sequence<List<R>> = sequence {
    val pending = ArrayDeque<Pair<R, Int>>()
    var left = total
    while (left > 0) {
        val reach = minOf(maxRows, left)
        val target = if (reach == maxRows) maxRows else Integer.highestOneBit(reach)
        var fit = 0
        var sum = 0L
        var size = 0
        while (size == 0) {
            if (fit == target) {
                size = target
            } else {
                if (fit == pending.size) next().let { pending.addLast(it to payloadOf(it)) }
                sum += pending[fit].second
                if (fit > 0 && sum > maxPayload) size = Integer.highestOneBit(fit) else fit++
            }
        }
        yield(List(size) { pending.removeFirst().first })
        left -= size
    }
}

/**
 * The type and value to bind for [column]. An `EntityID` (reference / id) column is bound as its
 * underlying id column (`EntityIDColumnType.notNullValueToDB` insists on an `EntityID` wrapper,
 * which a plain-id caller does not have and the wire never needs).
 */
private fun bindable(column: Column<*>, value: Any?): Pair<IColumnType<*>, Any?> {
    val type = column.columnType
    if (type !is EntityIDColumnType<*>) return type to value
    return type.idColumn.columnType to (if (value is EntityID<*>) value.value else value)
}

// The column type's own placeholder (`?` for every type the callers use; a type that needs a cast
// says so here, exactly as Exposed's single-row insert asks it).
@Suppress("UNCHECKED_CAST") // the value comes from the same column's own `set`, so its type always matches
private fun marker(type: IColumnType<*>, value: Any?): String = (type as IColumnType<Any?>).parameterMarker(value)
