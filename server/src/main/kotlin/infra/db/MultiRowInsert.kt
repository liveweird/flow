package ch.nokillswit.infra.db

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/**
 * The largest number of bind parameters one [insertRows] statement carries. The PostgreSQL wire
 * protocol counts a Bind message's parameters in an Int16 (65,535); half of that leaves headroom,
 * and the row chunk is derived from it, so a wide table simply gets smaller chunks.
 */
private const val MAX_BIND_PARAMETERS = 32_000

/** The most rows one statement of a table whose statement lists [columnCount] columns carries (its bind-parameter cap). */
internal fun maxRowsPerStatement(columnCount: Int): Int = (MAX_BIND_PARAMETERS / columnCount).coerceAtLeast(1)

/**
 * Test seam (`MultiRowInsertTest`): called with the number of rows a statement carries, just before it runs. Those are the only
 * rows [insertRows] and [insertRowsIgnoringConflicts] have in flight (an upsert's are all built first, see [upsertRows]).
 */
@Volatile
internal var rowsInStatementProbe: ((Int) -> Unit)? = null

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
 * **Statement texts are quantized** ([chunkSizes]): the SQL text depends on the row count, and every
 * distinct text becomes one named server-side prepared statement that the driver caches per connection,
 * so a chunk is either the table's maximum rows per statement or a power of two — a handful of texts per
 * table, however many rows a batch has.
 *
 * **Rows are built per statement.** [insertRows] and [insertRowsIgnoringConflicts] run `body` for a row only while the statement that
 * carries it is assembled, so the rows in flight are one statement's (at most [maxRowsPerStatement]), not the call's: the
 * `InsertRow` maps, the boxed numbers and the payload texts `body` renders for the rest of a large call do not exist yet (WORKLOGS
 * batches of up to ~30,000 rows, `build-times.md` WHY 51). `body` therefore runs lazily, once per row, and must be pure. A row that
 * sets other columns than the first fails the call when its statement is assembled, after the earlier statements ran: the caller's
 * transaction rolls them back. [upsertRows] still builds every row first, because its collapse of repeated keys needs them all.
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
    val setColumns = (eager?.first() ?: build(source.first())).keys
    require(setColumns.isNotEmpty()) { "$caller: a row that sets no column cannot be inserted" }
    fun checked(row: Map<Column<*>, Any?>) = row.also { require(it.keys == setColumns) { "$caller: every row must set the same columns" } }
    eager?.forEach(::checked)
    setColumns.firstOrNull { it.table != this }?.let { foreign ->
        throw IllegalArgumentException("$caller: column ${foreign.name} belongs to ${foreign.table.tableName}, not ${this.tableName}")
    }
    require(this.columns.none { (it.columnType as? AutoIncColumnType<*>)?.sequence != null }) {
        "$caller: ${this.tableName} has a sequence-backed autoIncrement column; use batchInsert"
    }
    val collected = if (eager != null && conflictKeys != null) lastPerKey(caller, eager, conflictKeys) else null
    val total = collected?.size ?: source.size
    val columns = this.columns.filter { it in setColumns || it.defaultValueFun != null }
    val tx = TransactionManager.current()
    val header = "INSERT INTO ${tx.identity(this)} (${columns.joinToString(", ") { tx.identity(it) }}) VALUES "
    val suffix = when {
        ignoreConflicts -> " ON CONFLICT DO NOTHING"
        conflictKeys == null -> ""
        else -> conflictSuffix(caller, tx, columns, conflictKeys)
    }
    var offset = 0
    for (size in chunkSizes(total, maxRowsPerStatement(columns.size))) {
        val arguments = ArrayList<Pair<IColumnType<*>, Any?>>(size * columns.size)
        val sql = StringBuilder(header)
        for (index in offset until offset + size) {
            val row = collected?.get(index) ?: checked(build(source[index]))
            if (index > offset) sql.append(", ")
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
        offset += size
        sql.append(suffix)
        rowsInStatementProbe?.invoke(size)
        tx.exec(sql.toString(), arguments)
        // Exposed keeps every executed statement until the transaction ends (commit or rollback; `closeStatementsAndConnection`
        // only clears the list, closing nothing, and `executeIn` itself clears it when `!supportsMultipleResultSets`, so this
        // is a sanctioned operation): a 32,000-parameter statement is ~8 MB of parsed tokens + encoded parameters, and DERIVE
        // is ONE transaction.
        tx.clearExecutedStatements()
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
 * The row counts of the statements that write [total] rows: as many full chunks of [maxRows] as fit, then the
 * remainder as its binary decomposition (13 -> 8, 4, 1) — at most `log2(maxRows) + 1` distinct sizes, so a
 * table ever produces only a handful of SQL texts (see [insertRows]).
 */
internal fun chunkSizes(total: Int, maxRows: Int): List<Int> {
    val sizes = ArrayList<Int>()
    var left = total
    while (left >= maxRows) {
        sizes += maxRows
        left -= maxRows
    }
    var bit = Integer.highestOneBit(left)
    while (left > 0) {
        if (left >= bit) {
            sizes += bit
            left -= bit
        }
        bit = bit shr 1
    }
    return sizes
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
