package ch.nokillswit.ingest

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * A resumable phase within a sync job (v0.2.0 plan §7): REFERENCE/ISSUES land in plan commit 6;
 * CHANGELOGS/WORKLOGS/RECONCILE/PROCESS/PROFILE arrive in later commits — every one of them
 * implements this same shape. [name] is the `sync_cursors.stream` key that owns its cursor's shape
 * (`ingest/SyncCursors.kt`).
 */
interface Stream {
    val name: String
    suspend fun run(context: StreamContext)
}

/**
 * What a stream needs from its enclosing job — cursor read/write scoped to [connectionId], a
 * [heartbeat] that throws [LeaseLostException] the moment the lease is gone (a stream never has to
 * check a `Boolean` itself and risk writing a cursor afterward), a [transaction] wrapper so a
 * page's raw-store writes and its cursor advance land in ONE transaction (`.claude/docs/
 * persistence.md` "Sync cursors" — Exposed reuses an enclosing `suspendTransaction` for nested
 * calls against the SAME [R2dbcDatabase], so [JiraRawStore]/[SyncCursorsService]'s own
 * `suspendTransaction` calls join this one), and an injectable [clock].
 */
class StreamContext(
    val connectionId: UInt,
    private val jobId: UInt,
    private val database: R2dbcDatabase,
    private val cursors: SyncCursorsService,
    private val jobHeartbeat: suspend () -> Boolean,
    val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun cursor(stream: String): SyncCursorRow? = cursors.get(connectionId, stream)

    suspend fun putCursor(
        stream: String,
        cursor: String,
        watermarkAt: Long? = null,
        lastCompletedAt: Long? = null,
        now: Long = clock(),
    ) = cursors.put(connectionId, stream, cursor, watermarkAt, lastCompletedAt, now)

    suspend fun clearCursor(stream: String) = cursors.clear(connectionId, stream)

    /** Wraps [block] in one transaction against the SAME [database] every raw-store/cursor call in [block] uses. */
    suspend fun <T> transaction(block: suspend () -> T): T = suspendTransaction(database) { block() }

    /**
     * Call once per page, AFTER that page's writes are committed (so a lease loss here never
     * un-writes anything) — throws [LeaseLostException] once the lease is gone, which every stream
     * lets propagate rather than catching (`ingest/IngestWorker.kt`'s `runJob` handles it).
     */
    suspend fun heartbeat() {
        if (!jobHeartbeat()) throw LeaseLostException(jobId)
    }
}
