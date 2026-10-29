package ch.nokillswit.jira

import ch.nokillswit.ingest.readWorkerSlots
import ch.nokillswit.plugins.runsWorker
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

// The boot-time check that the JVM heap can hold what the Jira ceilings allow (`.claude/docs/jira-integration.md` "Node and depth
// ceiling", "The residual"). The ceilings bound ONE response's tree CONSTRUCTION; this bounds how many such trees the configuration
// lets a worker keep alive at once on the RESPONSE path (one tree per slot for the ISSUES, REFERENCE and RECONCILE pages, big parses
// serialised), so an operator who raises a knob without the heap is refused at boot instead of meeting an `OutOfMemoryError` (a worker
// EXIT under `-XX:+ExitOnOutOfMemoryError`) the first time a hostile host sends a dense document.
//
// NOT yet bounded by it (the named residual, BACKLOG "being closed by a per-item stored-size ceiling and a PROCESS byte budget"):
// PROCESS re-loading a page of 50 issues' stored text, the changelog/worklog writers' per-item parse of text, WORKLOGS' fetch-ahead
// overlap, REFERENCE's PROJECT_FIELDS and the data PROFILE parsing whole entity sets, and the web role's raw-issue inspector. The
// `all` role is budgeted for its worker only: a Test connection's own tree (one big parse at a time) is not added to it.

private const val MIB = 1024L * 1024

/**
 * Heap bytes ONE parsed JSON tree retains per node (a structural token, [RESPONSE_BYTES_PER_NODE]) in the worst shape found, the
 * `[{"a":0},...]` document. Measured on the flow-perf stack against `sample-data/jira/hostile.mjs` (build-times WHY 48): a hostile
 * `GET /field` of 1,048,510 tokens parsed by the shipped web JVM raised the live set (after a full GC) by 114 MiB, ~108 MiB of it the
 * tree and ~6 MiB the body bytes and their decoded string; a `--pad page` ISSUES page the same in the worker. 112 B is the measured
 * 108 B a node plus ~4 %.
 */
internal const val TREE_BYTES_PER_NODE = 112L

/**
 * Trees of a response a worker slot holds at once when the response is STORED (an issue page, `GET /field`): after the no-reparse fix
 * ONE, PROVISIONALLY - measured as 3 before the fix, set to 1 by it and not yet re-measured (build-times WHY 48). The raw writers
 * canonicalize the very tree the stream parsed (`canonicalJson(JsonElement)` writes it
 * straight to text, sorting only each object's key list), where they used to re-parse its text (a second tree) and build a sorted
 * copy (a third): that was 3, measured as one hostile SYNC peaking 324 MiB above the idle live set, i.e. 2.9-3.0 trees of
 * [TREE_BYTES_PER_NODE]. This is the RESPONSE path only: the changelog and worklog writers still take TEXT (their batches accumulate
 * under char-based ceilings) and parse each item inside the writer, which no ceiling bounds yet (the residual in the file header).
 */
internal const val STORED_RESPONSE_TREES = 1

/**
 * Old generation a WORKER keeps beside the slots' trees (WHY 48): the idle live set (19-23 MiB) plus the slack above the live set the
 * collector needs to keep running at the two-slot peak (the old generation's used peak was 67-72 MiB over the live set at -Xmx864m and
 * -Xmx1024m), ~95 MiB, rounded up. It also covers a scale-20 DERIVE's own live set over idle (45-62 MiB), which can only run in a slot
 * a hostile tree then does not hold.
 */
internal const val RESERVE_BYTES = 96L * MIB

/**
 * The same reserve for a `web` role, which runs no job and stores no Jira response (it parses only the Test connection probes, behind
 * the one big-parse lock, so one tree at a time): the idle live set (21-24 MiB) plus ~16 MiB for concurrent API requests and the
 * collector's slack; the bodies in flight are counted apart (a call waiting for the big-parse lock keeps its body). Measured (WHY 48):
 * three Test connections at once against a hostile `GET /field` (2.8 MB bodies) peaked at a 143 MiB live set in the shipped
 * `-Xmx256m -Xmn64m` (192 MiB old), against this formula's 112 + 4 x 8 + 40 = 184 MiB (~160 MiB for the same peak with four 8 MiB
 * bodies). It does NOT cover heavy report
 * concurrency (WHY 46: eight 15-person trends filled the old generation before that fix), which is the web heap's own budget -- as is the
 * Person trend peer-cell cache (`reports.peerCellCache.maxEntries`, ~9 MiB at its default cap).
 */
internal const val WEB_RESERVE_BYTES = 40L * MIB

/** What a role can pin per slot and keep beside it: a worker stores responses ([STORED_RESPONSE_TREES]), a `web` role only parses one. */
internal enum class HeapFitRole(val treesPerSlot: Int, val reserveBytes: Long) {
    WORKER(STORED_RESPONSE_TREES, RESERVE_BYTES),
    WEB(1, WEB_RESERVE_BYTES),
}

/**
 * The old generation and the young generation, in bytes, of the running JVM (or of a test's chosen heap). [youngScalesWithHeap] is
 * true when the young generation grows with `-Xmx` (no `-Xmn`/`MaxNewSize` pins it: Parallel's `PS Old Gen` is `-Xmx` less
 * `MaxNewSize`, and the two-thirds fallback is proportional), so more heap buys only its old share; false when it is fixed
 * (Serial with `-Xmn`: every extra byte of `-Xmx` is old generation).
 */
internal data class HeapSizes(
    val oldGenerationBytes: Long,
    val youngGenerationBytes: Long,
    val youngScalesWithHeap: Boolean = false,
)

internal data class HeapFitVerdict(
    val needBytes: Long,
    val availableBytes: Long,
    /**
     * The `-Xmx` that would hold [needBytes] in the old generation: need + the young generation when that is fixed, need scaled by the
     * current heap-to-old ratio when the young generation grows with the heap.
     */
    val requiredMaxHeapBytes: Long,
) {
    val fits: Boolean get() = needBytes <= availableBytes
}

/**
 * The pure verdict: [slots] concurrent jobs each holding [HeapFitRole.treesPerSlot] trees of [maxResponseNodes] nodes, plus
 * [maxConcurrentRequests] bodies of [maxResponseBytes] in flight, plus the role's reserve, against the old generation.
 * One response per slot on the RESPONSE path: ISSUES, REFERENCE and RECONCILE keep at most one big page alive and `bigParses` builds
 * one tree at a time across the slots. It does not bound what stored text is re-read into trees (PROCESS pages, the changelog and
 * worklog per-item parse, WORKLOGS' fetch-ahead overlap, PROJECT_FIELDS and PROFILE entity sets): the residual in the file header.
 */
internal fun heapFitVerdict(
    slots: Int,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    heap: HeapSizes,
    maxResponseNodes: Long = defaultMaxResponseNodes(maxResponseBytes),
    role: HeapFitRole = HeapFitRole.WORKER,
): HeapFitVerdict {
    val trees = slots.toLong() * role.treesPerSlot * TREE_BYTES_PER_NODE * maxResponseNodes
    val need = trees + maxConcurrentRequests * maxResponseBytes + role.reserveBytes
    val old = heap.oldGenerationBytes
    val required = if (heap.youngScalesWithHeap && old > 0) {
        // old = heap x (old / (old + young)) here, so the heap that gives `need` of old generation is need x (old + young) / old.
        (need * (old + heap.youngGenerationBytes) + old - 1) / old
    } else {
        need + heap.youngGenerationBytes
    }
    return HeapFitVerdict(need, old, required)
}

internal fun heapFitMessage(
    verdict: HeapFitVerdict,
    slots: Int,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    role: HeapFitRole = HeapFitRole.WORKER,
): String {
    val nodes = "${defaultMaxResponseNodes(maxResponseBytes)} nodes (jira.maxResponseBytes $maxResponseBytes / $RESPONSE_BYTES_PER_NODE)"
    // A web role has no slots: it parses one Test connection tree at a time, so only the Jira knobs it really uses are named.
    val trees = when (role) {
        HeapFitRole.WORKER -> "ingest.workerSlots ($slots) x ${role.treesPerSlot} tree(s) of up to $nodes x $TREE_BYTES_PER_NODE B"
        HeapFitRole.WEB -> "one tree at a time (big parses are serialised) of up to $nodes x $TREE_BYTES_PER_NODE B"
    }
    val knobs = when (role) {
        HeapFitRole.WORKER -> "lower ingest.workerSlots, jira.maxResponseBytes or jira.maxConcurrentRequests"
        HeapFitRole.WEB -> "lower jira.maxResponseBytes or jira.maxConcurrentRequests"
    }
    return "The Jira response ceilings do not fit the heap: $trees + jira.maxConcurrentRequests ($maxConcurrentRequests) bodies in " +
        "flight + ${role.reserveBytes / MIB} MiB reserve need ${verdict.needBytes.ceilMib()} MiB of old generation, " +
        "but only ${verdict.availableBytes.ceilMib()} MiB is available: " +
        "raise -Xmx to at least ${verdict.requiredMaxHeapBytes.ceilMib()}m (JAVA_OPTS), or $knobs"
}

private fun Long.ceilMib(): Long = (this + MIB - 1) / MIB

/**
 * Reads the running JVM's pools: the old generation is the pool named `Tenured Gen`/`...Old Gen` (SerialGC's exact cap, here `-Xmx`
 * less `-Xmn`; Parallel's `PS Old Gen` is `-Xmx` less `MaxNewSize`; G1's old pool reports the whole heap), the young generation is
 * every other heap pool with a defined cap (Serial: eden + two survivor spaces). A collector whose heap is ONE pool without "Old" in
 * its name (Shenandoah, non-generational ZGC) exposes no old pool and takes the two-thirds fallback of `Runtime.maxMemory()`, which
 * errs towards refusing. The young generation is fixed only when `-Xmn`/`MaxNewSize`/`NewSize` pins it ([youngIsPinned]).
 */
internal fun currentHeapSizes(
    inputArguments: List<String> = ManagementFactory.getRuntimeMXBean().inputArguments,
): HeapSizes {
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
    val (old, young) = pools.partition { "Old" in it.name || "Tenured" in it.name }
    val oldBytes = old.sumOf { it.usage.max.coerceAtLeast(0) }
    if (oldBytes <= 0) {
        return HeapSizes(Runtime.getRuntime().maxMemory() / 3 * 2, Runtime.getRuntime().maxMemory() / 3, youngScalesWithHeap = true)
    }
    val youngBytes = young.sumOf { pool -> pool.usage.max.coerceAtLeast(0) * if ("Survivor" in pool.name) 2 else 1 }
    return HeapSizes(oldBytes, youngBytes, youngScalesWithHeap = !youngIsPinned(inputArguments))
}

/** True when a JVM argument fixes the young generation's size (`-Xmn64m`, `-XX:MaxNewSize=`, `-XX:NewSize=`). */
internal fun youngIsPinned(inputArguments: List<String>): Boolean =
    inputArguments.any { it.startsWith("-Xmn") || it.startsWith("-XX:MaxNewSize=") || it.startsWith("-XX:NewSize=") }

/**
 * Fails startup of a role that runs the worker when [heapFitVerdict] does not fit; a `web` role (which parses only the Test connection
 * responses, one at a time) is judged as [HeapFitRole.WEB] (one tree, `jira.maxConcurrentRequests` bodies, [WEB_RESERVE_BYTES]: the
 * shipped `-Xmx256m -Xmn64m` fits it) and logs ONE WARN instead of failing when it does not fit. The `all` role is judged as the
 * worker only (352 MiB at the shipped defaults against the 448 MiB of `-Xmx512m`): adding a Test connection's tree (464 MiB) would
 * refuse the shipped compose and `:server:run` configurations, so that tree is the residual of the file header. Called from
 * [configureJira], whose
 * `ingest.workerSlots` read goes through `ingest/IngestWorkerConfig.kt`'s `readWorkerSlots` so the key's bounds have one owner.
 */
internal fun Application.requireHeapFitsJiraCeilings(
    config: ApplicationConfig,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    heap: HeapSizes = currentHeapSizes(),
) {
    val worker = runsWorker()
    val slots = if (worker) readWorkerSlots(config) else 1
    val role = if (worker) HeapFitRole.WORKER else HeapFitRole.WEB
    // Both roles hold up to maxConcurrentRequests bodies: a web call waiting for the big-parse lock keeps its body too.
    val verdict = heapFitVerdict(slots, maxResponseBytes, maxConcurrentRequests, heap, role = role)
    if (verdict.fits) return
    val message = heapFitMessage(verdict, slots, maxResponseBytes, maxConcurrentRequests, role)
    check(!worker) { message }
    log.warn("$message (this web role only parses Test connection responses, so it keeps running)")
}
