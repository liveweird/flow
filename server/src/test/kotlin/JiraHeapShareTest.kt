package ch.nokillswit

import ch.nokillswit.jira.JiraHeapShare
import ch.nokillswit.jira.jiraHeapShareCapacityBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `jira/JiraHeapShare.kt`: the weighted FIFO semaphore the web-side Jira consumers share (`.claude/docs/jira-integration.md` "The
 * boot-time heap fit check"). Pure coroutines over the virtual-time `runTest`; the wiring into the inspector is `RawIssueInspectorTest`.
 */
// runCurrent / testScheduler are the test scheduler's experimental stepping API, the only way to hold a coroutine mid-queue.
@OptIn(ExperimentalCoroutinesApi::class)
class JiraHeapShareTest {
    private val mib = 1024L * 1024

    /** Starts a coroutine that takes [bytes], logs its start, and holds the share until [release] completes. */
    private fun TestScope.holder(
        share: JiraHeapShare,
        bytes: Long,
        name: String,
        started: MutableList<String>,
        release: CompletableDeferred<Unit> = CompletableDeferred(),
    ): Pair<Job, CompletableDeferred<Unit>> {
        val job = launch {
            share.withShare(bytes) {
                started += name
                release.await()
            }
        }
        return job to release
    }

    @Test
    fun `the shipped capacity is one 112 MiB response tree at the default cap`() {
        assertEquals(112 * mib, jiraHeapShareCapacityBytes(8 * mib))
    }

    @Test
    fun `the sum of the holders never exceeds the capacity and everyone completes`() = runTest {
        val share = JiraHeapShare(100)
        val random = Random(7)
        var held = 0L
        var peak = 0L
        var done = 0
        repeat(40) {
            val weight = random.nextLong(1, 101)
            launch {
                share.withShare(weight) {
                    held += weight
                    peak = maxOf(peak, held)
                    assertTrue(held <= 100, "held $held")
                    delay(random.nextLong(1, 20))
                    held -= weight
                }
                done++
            }
        }
        runCurrent()
        testScheduler.advanceUntilIdle()
        assertEquals(40, done)
        assertEquals(0, held)
        assertTrue(peak in 50..100, "the share was really shared: peak $peak")
    }

    @Test
    fun `waiters are admitted in arrival order, a big head is not starved by small ones behind it`() = runTest {
        val share = JiraHeapShare(100)
        val started = mutableListOf<String>()
        val (_, releaseFirst) = holder(share, 50, "first", started)
        runCurrent()
        val (_, releaseBig) = holder(share, 95, "big", started)
        val (_, releaseSmall) = holder(share, 10, "small", started)
        runCurrent()
        assertEquals(listOf("first"), started, "the small one would fit beside first, but the big one is ahead of it")
        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "big"), started, "big (95) fits once first is gone; small (10) does not fit beside it")
        releaseBig.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "big", "small"), started)
        releaseSmall.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a request over the capacity is clamped to it and admitted alone`() = runTest {
        val share = JiraHeapShare(100)
        val started = mutableListOf<String>()
        val (_, releaseSmall) = holder(share, 10, "small", started)
        runCurrent()
        val (_, releaseHuge) = holder(share, 1_000, "huge", started)
        val (_, releaseLater) = holder(share, 1, "later", started)
        runCurrent()
        assertEquals(listOf("small"), started, "huge waits for the share to empty")
        releaseSmall.complete(Unit)
        runCurrent()
        assertEquals(listOf("small", "huge"), started)
        releaseHuge.complete(Unit)
        runCurrent()
        assertEquals(listOf("small", "huge", "later"), started, "nothing ran beside the huge request")
        releaseLater.complete(Unit)
        runCurrent()
    }

    @Test
    fun `the share is released when the block throws`() = runTest {
        val share = JiraHeapShare(100)
        assertFailsWith<IllegalStateException> { share.withShare(100) { error("boom") } }
        var ran = false
        val job = launch { share.withShare(100) { ran = true } }
        runCurrent()
        assertTrue(job.isCompleted && ran, "the full share was free again")
    }

    @Test
    fun `a cancelled waiter leaves the queue without leaking or blocking the next`() = runTest {
        val share = JiraHeapShare(100)
        val started = mutableListOf<String>()
        val (_, releaseHolder) = holder(share, 100, "holder", started)
        runCurrent()
        val (waiterA, _) = holder(share, 50, "a", started)
        val (_, releaseB) = holder(share, 50, "b", started)
        runCurrent()
        waiterA.cancel()
        runCurrent()
        releaseHolder.complete(Unit)
        runCurrent()
        assertEquals(listOf("holder", "b"), started, "a never ran; b was not blocked behind it")
        releaseB.complete(Unit)
        runCurrent()
        val full = launch { share.withShare(100) { } }
        runCurrent()
        assertTrue(full.isCompleted, "no capacity leaked")
    }

    @Test
    fun `a head waiter that is cancelled lets the small ones behind it in at once`() = runTest {
        val share = JiraHeapShare(100)
        val started = mutableListOf<String>()
        val (_, releaseHolder) = holder(share, 60, "holder", started)
        runCurrent()
        val (head, _) = holder(share, 80, "head", started)
        val (_, releaseSmall) = holder(share, 30, "small", started)
        runCurrent()
        assertEquals(listOf("holder"), started)
        head.cancel()
        runCurrent()
        assertEquals(listOf("holder", "small"), started, "small fits beside the holder once the head is gone")
        releaseHolder.complete(Unit)
        releaseSmall.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a cancelled holder hands its share to the waiter`() = runTest {
        val share = JiraHeapShare(100)
        val started = mutableListOf<String>()
        val (holderJob, _) = holder(share, 100, "holder", started)
        runCurrent()
        val (_, releaseWaiter) = holder(share, 100, "waiter", started)
        runCurrent()
        assertEquals(listOf("holder"), started)
        holderJob.cancel()
        runCurrent()
        assertEquals(listOf("holder", "waiter"), started)
        releaseWaiter.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a waiter cancelled just as it is granted gives the share back`() = runTest {
        val share = JiraHeapShare(100)
        val started = mutableListOf<String>()
        val (holderJob, _) = holder(share, 100, "holder", started)
        runCurrent()
        val (waiter, _) = holder(share, 50, "waiter", started)
        runCurrent()
        // Both resumptions are queued before either runs: the holder's release grants the waiter, whose own resumption is already a
        // cancellation. The share it was just granted must not stay taken.
        holderJob.cancel()
        waiter.cancel()
        runCurrent()
        assertEquals(listOf("holder"), started, "the cancelled waiter never ran its block")
        val full = launch(start = CoroutineStart.DEFAULT) { share.withShare(100) { } }
        runCurrent()
        assertTrue(full.isCompleted, "no capacity leaked")
        assertFalse(waiter.isActive)
    }
}
