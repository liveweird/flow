package ch.nokillswit.infra.crypto

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*

// The columns parameter mixes NOT NULL (Column<String>) and nullable (Column<String?>) text
// columns; reads and writes both go through the nullable view, which only ever narrows.
@Suppress("UNCHECKED_CAST")
private fun Column<out String?>.asNullable(): Column<String?> = this as Column<String?>

/**
 * The shared encrypt-at-rest backfill body (see infra/db/Bootstrap.kt and "Encryption at
 * rest" in `.claude/docs/security.md`): rewrites [columns] of [table] as
 * `encrypt(decrypt(value))` — a one-time wrap for legacy plaintext, a re-wrap under the
 * current key during rotation. Runs INSIDE the caller's transaction (services with two
 * encrypted tables keep both backfills atomic), so callers wrap it in their own
 * `suspendTransaction`. Idempotent; returns the rewritten row count.
 *
 * Row selection: without [reencryptAll], only rows where some column still holds
 * non-enveloped plaintext (the registered content-neutral `notLike "enc:v1:%"` marker
 * predicate); with it (key rotation), every row that has anything to re-encrypt — all rows
 * when any column is NOT NULL, else rows with at least one non-null value (the goal-events
 * shape: rewriting an all-NULL row is a no-op, so it is skipped rather than counted).
 */
suspend fun FieldCipher.reencryptRows(
    table: UIntIdTable,
    columns: List<Column<out String?>>,
    reencryptAll: Boolean,
): Int {
    val enveloped = "${FieldCipher.PREFIX}%"
    val legacyOnly = columns
        .map { column ->
            val c = column.asNullable()
            if (c.columnType.nullable) c.isNotNull() and (c notLike enveloped) else c notLike enveloped
        }
        .reduce<Op<Boolean>, Op<Boolean>> { acc, op -> acc or op }
    val rotationScope =
        if (columns.any { !it.columnType.nullable }) {
            Op.TRUE
        } else {
            columns
                .map { it.asNullable().isNotNull() as Op<Boolean> }
                .reduce { acc, op -> acc or op }
        }
    val rows = table
        .select(listOf(table.id) + columns)
        .where { if (reencryptAll) rotationScope else legacyOnly }
        .toList()
    rows.forEach { row ->
        table.update({ table.id eq row[table.id] }) { statement ->
            columns.forEach { column ->
                val c = column.asNullable()
                row[c]?.let { value -> statement[c] = encrypt(decrypt(value)) }
            }
        }
    }
    return rows.size
}
