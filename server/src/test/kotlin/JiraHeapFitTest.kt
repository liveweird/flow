package ch.nokillswit

import ch.nokillswit.jira.HeapFitRole
import ch.nokillswit.jira.HeapSizes
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
        // 2 slots x 1 tree x 112 MiB + 4 x 8 MiB in flight + 96 MiB reserve = 352 MiB of old generation, i.e. -Xmx416m (WHY 48; one tree
        // per slot since the no-reparse fix, provisional until re-measured).
        assertEquals(352 * mib, verdict.needBytes)
        assertEquals(416 * mib, verdict.requiredMaxHeapBytes)
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
        // 4 slots: 4 x 112 + 32 + 96 = 576 MiB of old generation, so -Xmx640m with the 64 MiB young generation.
        assertEquals(640 * mib, heapFitVerdict(4, 8 * mib, 4, shippedWorker).requiredMaxHeapBytes)
    }

    @Test
    fun `the -Xmx asked for gives the old generation the need whether the young generation is fixed or scales with the heap`() {
        // Fixed young generation (Serial -Xmn64m): every extra byte of -Xmx is old generation, so need + young.
        assertEquals(416 * mib, heapFitVerdict(2, 8 * mib, 4, shippedWorker).requiredMaxHeapBytes)
        // Scaling young generation (Parallel: old = 2/3 of the heap, the two-thirds fallback): need x (old + young) / old.
        val scaling = HeapSizes(oldGenerationBytes = 256 * mib, youngGenerationBytes = 128 * mib, youngScalesWithHeap = true)
        val verdict = heapFitVerdict(2, 8 * mib, 4, scaling)
        assertFalse(verdict.fits)
        assertEquals(528 * mib, verdict.requiredMaxHeapBytes, "352 MiB of old generation is 2/3 of 528 MiB")
        // Following the advice fits: a heap of that size has an old generation (2/3) of the need.
        assertTrue(heapFitVerdict(2, 8 * mib, 4, HeapSizes(352 * mib, 176 * mib, youngScalesWithHeap = true)).fits)
        // G1 reports the whole heap as old and no young cap: the ratio is 1.
        assertEquals(352 * mib, heapFitVerdict(2, 8 * mib, 4, HeapSizes(512 * mib, 0, youngScalesWithHeap = true)).requiredMaxHeapBytes)
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
        val verdict = heapFitVerdict(2, 8 * mib, 4, HeapSizes(352 * mib, 64 * mib))
        assertTrue(verdict.fits)
        assertFalse(heapFitVerdict(2, 8 * mib, 4, HeapSizes(352 * mib - 1, 64 * mib)).fits)
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
