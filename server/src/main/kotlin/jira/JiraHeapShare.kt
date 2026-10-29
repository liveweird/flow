package ch.nokillswit.jira

import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Heap bytes the raw issue inspector (`ingest/RawIssueInspectorRoutes.kt`) holds per STORED byte of the issue it serves, at the
 * worst case: the stored text as read (UTF-16 worst case, 2 bytes a character that is 1 byte in UTF-8: 2x), the response's encoded JSON
 * text built from it (the serializer's `String`/`StringBuilder`, again 2x) and the bytes the engine writes from that (1x) = 5x.
 * `jira.processPageBytes` (8 MiB) therefore costs up to 40 MiB, which [requireInspectionFitsHeapShare] pins to fit the share.
 */
const val INSPECTION_BYTES_PER_STORED_BYTE = 5L

/** The capacity of the web role's [JiraHeapShare]: the ONE parsed response tree the web heap fit already counts ([TREE_BYTES_PER_NODE]). */
internal fun jiraHeapShareCapacityBytes(maxResponseBytes: Long): Long = TREE_BYTES_PER_NODE * defaultMaxResponseNodes(maxResponseBytes)

/** The share of the booted app (published by `configureJira`): the web-side Jira consumers draw their heap from it. */
val JiraHeapShareKey = AttributeKey<JiraHeapShare>("JiraHeapShare")

/**
 * One heap budget the web-side Jira consumers share (`.claude/docs/jira-integration.md` "The boot-time heap fit check"): the heap fit
 * counts ONE response tree for the `web` role, so whatever else a web request holds for the Jira data (the raw issue inspector today)
 * is admitted against that same figure, [capacityBytes], instead of beside it. A weighted FIFO semaphore: a request of `bytes` waits
 * until the sum of the holders plus its weight fits; strict FIFO, so a large waiter at the head is never starved by small ones behind
 * it. A request heavier than the capacity is clamped to it (so it is admitted alone, never refused forever).
 */
class JiraHeapShare(val capacityBytes: Long) {
    private class Waiter(val weight: Long) {
        val gate = CompletableDeferred<Unit>()
        var granted = false
    }

    // The critical sections never suspend (they only move numbers and complete deferreds), so a plain monitor is the whole lock.
    private val lock = Any()
    private val waiters = ArrayDeque<Waiter>()
    private var inUse = 0L

    /** Runs [block] holding [bytes] of the share (clamped to the capacity); suspends first while the share cannot admit it. */
    suspend fun <T> withShare(bytes: Long, block: suspend () -> T): T {
        val weight = bytes.coerceIn(0L, capacityBytes)
        acquire(weight)
        try {
            return block()
        } finally {
            release(weight)
        }
    }

    private fun fits(weight: Long) = inUse + weight <= capacityBytes

    private suspend fun acquire(weight: Long) {
        val waiter = synchronized(lock) {
            if (waiters.isEmpty() && fits(weight)) {
                inUse += weight
                return
            }
            Waiter(weight).also { waiters.addLast(it) }
        }
        try {
            waiter.gate.await()
        } catch (e: CancellationException) {
            // Cancelled while queued (it leaves, so the next waiter is not blocked behind it) or just after being granted (the weight
            // is handed back): either way nothing leaks.
            synchronized(lock) {
                if (waiter.granted) inUse -= weight else waiters.remove(waiter)
                admitWaiters()
            }
            throw e
        }
    }

    private fun release(weight: Long) = synchronized(lock) {
        inUse -= weight
        admitWaiters()
    }

    /** Admits waiters from the head while they fit; stops at the first that does not (strict FIFO). Caller holds [lock]. */
    private fun admitWaiters() {
        while (true) {
            val head = waiters.firstOrNull()?.takeIf { fits(it.weight) } ?: return
            waiters.removeFirst()
            inUse += head.weight
            head.granted = true
            head.gate.complete(Unit)
        }
    }
}
