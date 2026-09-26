package ch.nokillswit.infra.db

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * The soft-delete convention as a type (persistence.md "Soft delete"): every business table carries
 * `marked_as_deleted`, and every read filters on it through ONE [active] predicate instead of a private
 * copy per service (seven copies before the checkup).
 */
interface SoftDeletable {
    val markedAsDeleted: Column<Boolean>
}

/** The rows that still exist, in the business sense. */
fun SoftDeletable.active(): Op<Boolean> = markedAsDeleted eq false

/** The wall clock every write stamps (`created_at`/`updated_at`, epoch millis) — one name to grep for. */
fun nowMillis(): Long = System.currentTimeMillis()

/** Materializes and locks an active row before a delete path runs its separate child-count query. */
suspend fun <T> T.lockActiveForUpdate(rowId: UInt): Boolean
    where T : UIntIdTable, T : SoftDeletable =
    select(id).where { (id eq rowId) and active() }.forUpdate().toList().isNotEmpty()
