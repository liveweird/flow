package ch.nokillswit

import ch.nokillswit.jira.HeapFitRole
import ch.nokillswit.jira.HeapSizes
import ch.nokillswit.jira.JiraStoredCeilings
import ch.nokillswit.jira.MAX_ANSWER_CHARS
import ch.nokillswit.jira.MAX_STORED_HISTORY_BYTES
import ch.nokillswit.jira.SlotTerm
import ch.nokillswit.jira.WorklogCeilings
import ch.nokillswit.jira.requireProcessPageHoldsAnIssue
import ch.nokillswit.jira.retentionChars
import ch.nokillswit.jira.worklogTextBytes
import ch.nokillswit.jira.changelogTextBytes
import ch.nokillswit.jira.denseTreeBytes
import ch.nokillswit.jira.processSlotBytes
import ch.nokillswit.jira.RESERVE_BYTES
import ch.nokillswit.jira.STORED_RESPONSE_TREES
import ch.nokillswit.jira.TREE_BYTES_PER_NODE
import ch.nokillswit.jira.WEB_RESERVE_BYTES
import ch.nokillswit.jira.heapFitMessage
import ch.nokillswit.jira.heapFitVerdict
import ch.nokillswit.jira.youngIsPinned
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The boot-time heap fit check (`jira/JiraHeapFit.kt`, `.claude/docs/jira-integration.md` "The residual"): the Jira ceilings x
 * the worker slots must fit the old generation, else a role that runs the worker refuses to start (a `web` role only warns).
 * The arithmetic is tested as the pure `heapFitVerdict`; ONE boot per role proves the wiring, so no test needs a big heap.
 */
class JiraHeapFitTest {
    private val mib = 1024L * 1024

    /** The worker as shipped: `-Xmx512m -Xmn64m` (`JAVA_OPTS` over `applicationDefaultJvmArgs`) = a 448 MiB old generation. */
    private val shippedWorker = HeapSizes(oldGenerationBytes = 448 * mib, youngGenerationBytes = 64 * mib)

    private fun shippedDefault(key: String, name: String): Long {
        // Read from the file, not through the config loader: an exported INGEST_*/JIRA_* variable of a developer's shell must not matter.
        val yaml = checkNotNull(JiraHeapFitTest::class.java.getResource("/application.yaml")).readText()
        return checkNotNull(Regex("$name:\\s*\"\\\$$key:(\\d+)\"").find(yaml)).groupValues[1].toLong()
    }

    private fun repoFile(path: String) = File(System.getProperty("repo.root") ?: "..", path).readText()

    @Test
    fun `the shipped defaults fit the shipped worker heap`() {
        val slots = shippedDefault("INGEST_WORKER_SLOTS", "workerSlots").toInt()
        val bytes = shippedDefault("JIRA_MAX_RESPONSE_BYTES", "maxResponseBytes")
        val requests = shippedDefault("JIRA_MAX_CONCURRENT_REQUESTS", "maxConcurrentRequests").toInt()
        // The shipped worker heap is read from the files that set it, so a change there is checked against the ceilings.
        assertTrue("value: \"-Xmx512m\"" in repoFile("k8s/worker-deployment.yaml"))
        assertTrue("\"-Xmn64m\"" in repoFile("server/build.gradle.kts"))
        // The other worker-role heaps the check is judged against: compose's `all`, the perf stack's worker and `:server:run`.
        assertTrue("JAVA_OPTS: \"-Xmx512m\"" in repoFile("docker-compose.yaml"))
        assertTrue("JAVA_OPTS: \"-Xmx512m\"" in repoFile("docker-compose.perf.yaml"))
        assertTrue("+ \"-Xmx512m\"" in repoFile("server/build.gradle.kts"), "the :server:run override")
        val verdict = heapFitVerdict(slots, bytes, requests, shippedWorker)
        // (2 - 1) slots at their own largest (PROCESS, 137 MiB: the response tree is 112, the CHANGELOGS text 32, the WORKLOGS text 48)
        // + the
        // one slot that may be converting a page under the big-parse lock (WORKLOGS: 24 Mi characters of text as UTF-16 = 48 MiB + the
        // 112 MiB tree = 160 MiB) + 4 x 8 MiB in flight + 96 MiB reserve = 425 MiB of old generation, i.e. -Xmx489m (WHY 48).
        assertEquals(425 * mib, verdict.needBytes)
        assertEquals(SlotTerm.WORKLOG, verdict.slotTerm)
        assertEquals(160 * mib, verdict.slotBytes)
        assertEquals(137 * mib, verdict.otherSlotBytes)
        assertEquals(489 * mib, verdict.requiredMaxHeapBytes)
        assertTrue(verdict.fits, "the shipped defaults must fit the shipped worker heap (needs -Xmx${verdict.requiredMaxHeapBytes / mib}m)")
    }

    @Test
    fun `the shipped web heap fits one parsed tree`() {
        // The web role as shipped: the image's baked -Xmx256m -Xmn64m = a 192 MiB old generation (k8s/web-deployment.yaml sets
        // no JAVA_OPTS).
        assertTrue("\"-Xmx256m\"" in repoFile("server/build.gradle.kts"))
        val requests = shippedDefault("JIRA_MAX_CONCURRENT_REQUESTS", "maxConcurrentRequests").toInt()
        val verdict = heapFitVerdict(1, 8 * mib, requests, HeapSizes(192 * mib, 64 * mib), role = HeapFitRole.WEB)
        // 1 tree x 112 MiB + 4 x 8 MiB bodies (a call waiting for the big-parse lock keeps its body) + 40 MiB web reserve = 184 MiB
        // against 192 MiB: a shipped web boot does not warn.
        assertEquals(184 * mib, verdict.needBytes)
        assertTrue(verdict.fits)
        // The worker's reserve (96 MiB) would not, which is why the role is part of the verdict.
        assertFalse(heapFitVerdict(1, 8 * mib, requests, HeapSizes(192 * mib, 64 * mib)).fits)
    }

    @Test
    fun `the node cost is one tree of 1 Mi nodes at the default cap`() {
        // The WHY 48 calibration: one tree 112 MiB at the default 1 Mi-node ceiling, a stored response one of them (the writers
        // canonicalize the tree they are given; they used to hold three).
        assertEquals(112 * mib, TREE_BYTES_PER_NODE * 1_048_576)
        assertEquals(1, STORED_RESPONSE_TREES)
        assertEquals(96 * mib, RESERVE_BYTES)
        assertEquals(40 * mib, WEB_RESERVE_BYTES)
    }

    @Test
    fun `more slots, a bigger cap or more requests without more heap fail and the message names every knob and the -Xmx needed`() {
        val cases = listOf(
            Triple(4, 8 * mib, 4), // twice the slots
            Triple(2, 16 * mib, 4), // twice the cap (twice the nodes)
        )
        cases.forEach { (slots, bytes, requests) ->
            val verdict = heapFitVerdict(slots, bytes, requests, shippedWorker)
            assertFalse(verdict.fits, "$slots slots, $bytes bytes, $requests requests")
            val message = heapFitMessage(verdict, slots, bytes, requests)
            listOf("ingest.workerSlots", "jira.maxResponseBytes", "jira.maxConcurrentRequests", "-Xmx").forEach {
                assertTrue(it in message, "'$it' in: $message")
            }
            // The -Xmx quoted holds the need on top of the young generation, so it is NOT what is already too small.
            assertTrue("-Xmx to at least ${verdict.requiredMaxHeapBytes / mib}m" in message, message)
            assertTrue(verdict.requiredMaxHeapBytes > shippedWorker.oldGenerationBytes + shippedWorker.youngGenerationBytes)
        }
        // 4 slots: 3 x 137 (PROCESS) + 160 (WORKLOGS under the lock) + 32 + 96 = 699 MiB of old generation, so -Xmx763m with the 64 MiB
        // young generation.
        assertEquals(763 * mib, heapFitVerdict(4, 8 * mib, 4, shippedWorker).requiredMaxHeapBytes)
    }

    @Test
    fun `the -Xmx asked for gives the old generation the need whether the young generation is fixed or scales with the heap`() {
        // Fixed young generation (Serial -Xmn64m): every extra byte of -Xmx is old generation, so need + young.
        assertEquals(489 * mib, heapFitVerdict(2, 8 * mib, 4, shippedWorker).requiredMaxHeapBytes)
        // Scaling young generation (Parallel: old = 2/3 of the heap, the two-thirds fallback): need x (old + young) / old.
        val scaling = HeapSizes(oldGenerationBytes = 256 * mib, youngGenerationBytes = 128 * mib, youngScalesWithHeap = true)
        val verdict = heapFitVerdict(2, 8 * mib, 4, scaling)
        assertFalse(verdict.fits)
        assertEquals(425 * mib * 3 / 2, verdict.requiredMaxHeapBytes, "425 MiB of old generation is 2/3 of 637.5 MiB")
        // Following the advice fits: a heap of that size has an old generation (2/3) of the need.
        assertTrue(heapFitVerdict(2, 8 * mib, 4, HeapSizes(426 * mib, 213 * mib, youngScalesWithHeap = true)).fits)
        // G1 reports the whole heap as old and no young cap: the ratio is 1.
        assertEquals(425 * mib, heapFitVerdict(2, 8 * mib, 4, HeapSizes(512 * mib, 0, youngScalesWithHeap = true)).requiredMaxHeapBytes)
    }

    @Test
    fun `only an explicit argument pins the young generation`() {
        assertTrue(youngIsPinned(listOf("-Xmx256m", "-Xmn64m")))
        assertTrue(youngIsPinned(listOf("-XX:MaxNewSize=64m")))
        assertTrue(youngIsPinned(listOf("-XX:NewSize=64m")))
        assertFalse(youngIsPinned(listOf("-Xmx512m", "-XX:+UseSerialGC", "-XX:NewRatio=2")))
    }

    @Test
    fun `the web message names the knobs a web role uses and no worker slots`() {
        val verdict = heapFitVerdict(1, 8 * mib, 4, HeapSizes(100 * mib, 64 * mib), role = HeapFitRole.WEB)
        val message = heapFitMessage(verdict, 1, 8 * mib, 4, HeapFitRole.WEB)
        listOf("jira.maxResponseBytes", "jira.maxConcurrentRequests (4)", "-Xmx").forEach {
            assertTrue(it in message, "'$it' in: $message")
        }
        assertFalse("workerSlots" in message, message)
    }

    @Test
    fun `a heap exactly as big as the need fits`() {
        val verdict = heapFitVerdict(2, 8 * mib, 4, HeapSizes(425 * mib, 64 * mib))
        assertTrue(verdict.fits)
        assertFalse(heapFitVerdict(2, 8 * mib, 4, HeapSizes(425 * mib - 1, 64 * mib)).fits)
    }

    @Test
    fun `the stored-text terms - dense tree arithmetic, the PROCESS, CHANGELOGS and WORKLOGS slots`() {
        // 3 / 8 of a node a byte (3x denser than a response may be) at 112 B a node.
        assertEquals(84 * mib, denseTreeBytes(2 * mib))
        assertEquals(21 * mib, denseTreeBytes(512 * 1024))
        assertEquals(512L * 1024, MAX_STORED_HISTORY_BYTES)
        // PROCESS: 4 x 8 MiB page (UTF-16 strings 2x + the customFieldsJson copies 2x) + 84 MiB (a 2 MiB item) + 21 MiB (a history).
        assertEquals(137 * mib, processSlotBytes(JiraStoredCeilings()))
        // CHANGELOGS: the 16 Mi-character answer as UTF-16 (32 MiB) of text; the big tree is added for the one slot converting a page.
        assertEquals(16 * mib, MAX_ANSWER_CHARS)
        assertEquals(32 * mib, changelogTextBytes())
        // WORKLOGS: (4 in flight + the batch being settled) x 4 Mi chars + the 4 Mi flush bound = 24 Mi chars = 48 MiB.
        assertEquals(24 * mib, WorklogCeilings().retentionChars(4))
        assertEquals(48 * mib, worklogTextBytes(4))
        // A smaller look-ahead window shrinks it: 2 in flight = 3 x 4 + 4 = 16 Mi chars, 32 MiB.
        assertEquals(32 * mib, worklogTextBytes(2))
        // The knobs move the PROCESS term: a bigger page or a bigger item grows it.
        assertEquals(153 * mib, processSlotBytes(JiraStoredCeilings(processPageBytes = 12 * mib)))
        assertEquals(221 * mib, processSlotBytes(JiraStoredCeilings(maxPayloadBytes = 4 * mib)))
    }

    @Test
    fun `the largest term of a slot is the one budgeted, and the message names ITS knobs`() {
        val heap = HeapSizes(200 * mib, 64 * mib)
        // WORKLOGS dominates at the shipped figures (160 MiB under the lock against 137 PROCESS, 112 the tree and 144 CHANGELOGS).
        val worklog = heapFitVerdict(2, 8 * mib, 4, heap)
        assertEquals(SlotTerm.WORKLOG, worklog.slotTerm)
        val worklogMessage = heapFitMessage(worklog, 2, 8 * mib, 4)
        listOf("WORKLOGS slot", "ingest.workerSlots", "jira.maxConcurrentRequests", "-Xmx").forEach {
            assertTrue(it in worklogMessage, "'$it' in: $worklogMessage")
        }
        assertFalse("jira.processPageBytes" in worklogMessage, worklogMessage)
        // A bigger page makes PROCESS the largest term.
        val bigPageStored = JiraStoredCeilings(processPageBytes = 64 * mib)
        val process = heapFitVerdict(2, 8 * mib, 4, heap, stored = bigPageStored)
        assertEquals(SlotTerm.PROCESS, process.slotTerm)
        val processMessage = heapFitMessage(process, 2, 8 * mib, 4, stored = bigPageStored)
        val named = listOf("jira.processPageBytes", "jira.maxStoredPayloadBytes", "ingest.workerSlots", "jira.maxConcurrentRequests")
        (named + listOf("PROCESS slot", "-Xmx")).forEach {
            assertTrue(it in processMessage, "'$it' in: $processMessage")
        }
        // A bigger response cap (16 MiB = 224 MiB a tree) makes the tree the largest term of the OTHER slots, and the slot under the
        // lock (text + tree) is still WORKLOGS: 48 + 224 = 272 MiB.
        val response = heapFitVerdict(2, 16 * mib, 4, heap)
        assertEquals(SlotTerm.WORKLOG, response.slotTerm)
        assertEquals(272 * mib, response.slotBytes)
        assertEquals(224 * mib, response.otherSlotBytes)
        assertTrue("jira.maxResponseBytes" in heapFitMessage(response, 2, 16 * mib, 4))
        assertFalse("jira.processPageBytes" in heapFitMessage(response, 2, 16 * mib, 4))
        // CHANGELOGS (32 MiB of answer text) is never the largest: WORKLOGS retains more text beside the same tree.
        assertTrue(changelogTextBytes() < worklogTextBytes(4))
        // A web role stores nothing: the stored-text terms never enter its verdict.
        val web = heapFitVerdict(1, 8 * mib, 4, HeapSizes(192 * mib, 64 * mib), role = HeapFitRole.WEB, stored = bigPageStored)
        assertEquals(184 * mib, web.needBytes)
        assertEquals(SlotTerm.RESPONSE, web.slotTerm)
    }

    @Test
    fun `slots are not discounted for the one big tree - the others are budgeted at their own largest while one converts under the lock`() {
        val verdict = heapFitVerdict(2, 8 * mib, 4, shippedWorker)
        // Slot A is in PROCESS (137 MiB, no lock) while slot B converts a worklog page under the lock (48 MiB of text + the 112 MiB tree):
        // 137 + 160 + 32 in flight + 96 reserve = 425 MiB. The share-based formula it replaced (2 x (48 + 56) + 128 = 336 MiB) passed this
        // mix with 89 MiB to spare it did not have.
        assertEquals(425 * mib, verdict.needBytes)
        assertTrue(verdict.needBytes > (2 * (48 + 56) + 4 * 8 + 96) * mib)
        // With one slot there is no "other": text + tree + the rest.
        assertEquals((160 + 32 + 96) * mib, heapFitVerdict(1, 8 * mib, 4, shippedWorker).needBytes)
        // Each further slot costs its own largest term (PROCESS, 137), not the converting slot's 160.
        assertEquals(137 * mib, heapFitVerdict(3, 8 * mib, 4, shippedWorker).needBytes - verdict.needBytes)
    }

    @Test
    fun `a process page that cannot hold the largest storable issue fails startup`() {
        val ok = JiraStoredCeilings(maxPayloadBytes = 2 * mib, processPageBytes = 2 * mib + MAX_STORED_HISTORY_BYTES)
        requireProcessPageHoldsAnIssue(ok)
        val failure = assertFailsWith<IllegalArgumentException> {
            requireProcessPageHoldsAnIssue(ok.copy(processPageBytes = ok.processPageBytes - 1))
        }
        assertTrue("jira.processPageBytes" in failure.message.orEmpty() && "jira.maxStoredPayloadBytes" in failure.message.orEmpty())
        requireProcessPageHoldsAnIssue(JiraStoredCeilings()) // the shipped 8 MiB page holds 2 MiB + 512 KiB
    }

    @Test
    fun `a worker role whose ceilings cannot fit any heap refuses to start`() = testApplication {
        // 16 slots at the 128 MiB maximum cap is ~27 GiB of tree: no test fork's heap holds it.
        configureApp("app.role" to "worker", "ingest.workerSlots" to "16", "jira.maxResponseBytes" to (128L * mib).toString())
        assertStartupFails("ingest.workerSlots (16)") { startApplication() }
    }

    @Test
    fun `the all role refuses too`() = testApplication {
        configureApp("app.role" to "all", "ingest.workerSlots" to "16", "jira.maxResponseBytes" to (128L * mib).toString())
        assertStartupFails("jira.maxConcurrentRequests") { startApplication() }
    }

    @Test
    fun `a web role with ceilings no heap holds only warns and boots`() = testApplication {
        val log = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
        try {
            configureApp("app.role" to "web", "jira.maxResponseBytes" to (128L * mib).toString())
            startApplication()
            val warnings = log.events.filter { "The Jira response ceilings do not fit the heap" in it.formattedMessage }
            assertEquals(1, warnings.size, "exactly one WARN")
            assertEquals("WARN", warnings.single().level.toString())
        } finally {
            log.detach()
        }
    }
}
