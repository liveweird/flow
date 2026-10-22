package ch.nokillswit.infra.db

import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
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

/** The rows that were soft-deleted (the purge sweep's candidates). */
fun SoftDeletable.deleted(): Op<Boolean> = markedAsDeleted eq true

/** The wall clock every write stamps (`created_at`/`updated_at`, epoch millis) — one name to grep for. */
fun nowMillis(): Long = System.currentTimeMillis()

/**
 * Materializes and locks an active row for the rest of the caller's transaction (a delete path's separate child-count
 * query, a write that must not race a soft delete). The lock is `FOR NO KEY UPDATE`, never `FOR UPDATE`: it still
 * conflicts with itself and with `UPDATE`, so writers of the row serialize, but it does NOT conflict with the
 * `FOR KEY SHARE` an FK check takes on the referenced row — so a long transaction inserting children (DERIVE) does not
 * stall the write. Only for writes that leave the row's key (`id`) alone; persistence.md "Row locks vs. FK locks".
 */
suspend fun <T> T.lockActiveForWrite(rowId: UInt): Boolean
    where T : UIntIdTable, T : SoftDeletable =
    select(id).where { (id eq rowId) and active() }.forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().isNotEmpty()

/** Whether an active row with [rowId] exists — the read-only twin of [lockActiveForWrite] (no row lock). */
suspend fun <T> T.existsActive(rowId: UInt): Boolean
    where T : UIntIdTable, T : SoftDeletable =
    select(id).where { (id eq rowId) and active() }.toList().isNotEmpty()
