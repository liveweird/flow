package ch.nokillswit.jira

import ch.nokillswit.fetch.defaultMaxResponseNodes
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * Heap bytes the raw issue inspector (`ingest/RawIssueInspectorRoutes.kt`) holds per STORED byte of the issue it serves (its payload,
 * histories, live worklogs and the work item's `custom_fields` text), at the worst case, from the first read until the encoded body
 * exists (the share is released before the body is written):
 *
 *  - the stored text as read: UTF-16 `String`s, 2 bytes a character that is 1 byte in UTF-8 = 2x (the driver's row buffer is
 *    transient and on top of this while the last text is read);
 *  - the response encoded by `Json.encodeToStream`: the serializer's chunk buffer for the largest string, a `CharArray` of up to 2x
 *    that string, and the `ByteArrayOutputStream` sized from the stored bytes (1x, it never grows) = up to 3x;
 *  - the final `toByteArray()` copy = 1x, taken while the stream buffer is still live.
 *
 * That peaks at 2x + 3x + 1x = 6x, and the driver's row buffer and the library's chunking are not under this code's control, so the
 * figure is 8x rather than the derived 6x (the previous, `encodeToString` + engine write, path was 5x counted and ~8x real).
 * `jira.processPageBytes` (8 MiB) plus a same-size `custom_fields` therefore costs up to 128 MiB, more than the share: the inspector
 * answers 413 for an issue whose weight is over the share (`capacityBytes / this`), so no inspection is larger than the share.
 */
const val INSPECTION_BYTES_PER_STORED_BYTE = 8L

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

    // The waiters admitted under the lock are completed AFTER it is left: completing may resume a waiter inline on this thread
    // (Ktor's dispatcher can), and its continuation must never run inside the monitor.
    private fun complete(granted: List<Waiter>) = granted.forEach { it.gate.complete(Unit) }

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
            complete(
                synchronized(lock) {
                    if (waiter.granted) inUse -= weight else waiters.remove(waiter)
                    admitWaiters()
                },
            )
            throw e
        }
    }

    private fun release(weight: Long) = complete(
        synchronized(lock) {
            inUse -= weight
            admitWaiters()
        },
    )

    /**
     * Admits waiters from the head while they fit (marking them granted and counting their weight); stops at the first that does not
     * (strict FIFO). Returns them for the caller to [complete] once it has left [lock], which it holds here.
     */
    private fun admitWaiters(): List<Waiter> {
        val granted = mutableListOf<Waiter>()
        while (true) {
            val head = waiters.firstOrNull()?.takeIf { fits(it.weight) } ?: return granted
            waiters.removeFirst()
            inUse += head.weight
            head.granted = true
            granted += head
        }
    }
}
