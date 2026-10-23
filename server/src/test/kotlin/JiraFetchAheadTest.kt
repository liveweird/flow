package ch.nokillswit

import ch.nokillswit.jira.fetchAheadInOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Counts the calls running right now and the most that ever ran together. */
internal class InFlight {
    val now = AtomicInteger()
    val peak = AtomicInteger()

    /**
     * Waits (bounded) until every tracked call has ended. Ktor runs a call's engine work in a call context that is ATTACHED to the
     * caller's job, not its child, so cancelling the caller cancels the call asynchronously: `cancelAndJoin` can return while a
     * mock handler's `finally` has not run yet. A call that is never cancelled still fails the test, after [timeoutMillis].
     */
    suspend fun awaitDrained(what: String, timeoutMillis: Long = 10_000) {
        withTimeoutOrNull(timeoutMillis) { while (now.get() != 0) delay(5) }
        assertEquals(0, now.get(), "$what (still in flight after $timeoutMillis ms)")
    }

    suspend fun <T> track(block: suspend () -> T): T {
        peak.accumulateAndGet(now.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            return block()
        } finally {
            now.decrementAndGet()
        }
    }
}

/**
 * `fetchAheadInOrder` (`jira/JiraFetchAhead.kt`), the fetch-concurrently / settle-in-order helper under the WORKLOGS backfill
 * (`JiraWorklogConcurrencyTest` drives the whole stream): order of consumption, the look-ahead bound, a failure at its own
 * position, and structured cancellation. No database, no HTTP.
 */
class JiraFetchAheadTest {
    @Test
    fun `outcomes are consumed in list order whatever order the fetches finish in`() = runBlocking {
        val consumed = mutableListOf<Int>()
        fetchAheadInOrder(
            items = listOf(1, 2, 3, 4, 5),
            window = 5,
            fetch = { item -> delay((6 - item) * 15L); item * 10 },
            consume = { item, outcome ->
                consumed += item
                assertEquals(item * 10, outcome.getOrThrow())
            },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), consumed)
    }

    @Test
    fun `no more fetches than the window are ever in flight, and a window of 1 is the sequential loop`() = runBlocking {
        for (window in listOf(1, 3)) {
            val inFlight = InFlight()
            fetchAheadInOrder(
                items = (1..12).toList(),
                window = window,
                fetch = { inFlight.track { delay(10) } },
                consume = { _, outcome -> outcome.getOrThrow() },
            )
            assertEquals(window, inFlight.peak.get(), "window $window")
            assertEquals(0, inFlight.now.get())
        }
    }

    @Test
    fun `no fetch starts while the finished but unconsumed results already outweigh the budget`() = runBlocking {
        // The children run on this single-threaded loop, so none finishes until the loop first awaits the head: deterministic.
        val started = mutableListOf<Int>()
        val startedWhenConsuming = mutableMapOf<Int, List<Int>>()
        fetchAheadInOrder(
            items = (1..6).toList(),
            window = 3,
            fetch = { item -> started += item; item },
            weight = { 100 },
            budget = 150,
            consume = { item, outcome ->
                outcome.getOrThrow()
                startedWhenConsuming[item] = started.toList()
            },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), started, "everything is fetched in the end")
        assertEquals(listOf(1, 2, 3), startedWhenConsuming[1])
        assertEquals(
            listOf(1, 2, 3), startedWhenConsuming[2],
            "items 2 and 3 were finished (200 > 150): a fourth fetch must wait, though the window has room",
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), startedWhenConsuming[4], "once the held results are within the budget the window refills")
    }

    @Test
    fun `a failed fetch reaches the consumer at its own position, and a throw there cancels the fetches in flight`() = runBlocking {
        val inFlight = InFlight()
        val consumed = mutableListOf<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            fetchAheadInOrder(
                items = listOf(1, 2, 3, 4),
                window = 4,
                fetch = { item ->
                    when (item) {
                        2 -> error("item 2 failed")
                        1 -> item
                        else -> inFlight.track { awaitCancellation() }
                    }
                },
                consume = { item, outcome ->
                    outcome.getOrThrow()
                    consumed += item
                },
            )
        }
        assertEquals("item 2 failed", failure.message)
        assertEquals(listOf(1), consumed, "item 1, ahead of the failure, was consumed; nothing after it")
        assertEquals(0, inFlight.now.get(), "items 3 and 4 were cancelled and awaited before the exception left the call")
    }

    @Test
    fun `an earlier failure in list order wins over a later one that happens first`() = runBlocking {
        val failure = assertFailsWith<IllegalStateException> {
            fetchAheadInOrder(
                items = listOf(1, 2),
                window = 2,
                fetch = { item ->
                    if (item == 1) delay(50)
                    error(if (item == 1) "first in order" else "first in time")
                },
                consume = { _, outcome -> outcome.getOrThrow() },
            )
        }
        assertEquals("first in order", failure.message)
    }

    @Test
    fun `cancelling the caller cancels the in-flight fetches and is never handed to the consumer as an outcome`() = runBlocking {
        val inFlight = InFlight()
        val allStarted = CompletableDeferred<Unit>()
        var consumeCalls = 0
        val job = launch(Dispatchers.Default) {
            fetchAheadInOrder(
                items = listOf(1, 2, 3),
                window = 3,
                fetch = {
                    inFlight.track {
                        if (inFlight.now.get() == 3) allStarted.complete(Unit)
                        awaitCancellation()
                    }
                },
                consume = { _, _ -> consumeCalls++ },
            )
        }
        withTimeout(10_000) { allStarted.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, consumeCalls)
        assertEquals(0, inFlight.now.get())
    }
}
