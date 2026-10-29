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
// The STORED text a stream later re-reads into trees is bounded the same way (the stored-size ceilings, `JiraStoredCeilings.kt`): a
// worker slot's PROCESS page (UTF-16 worst case, with its customFieldsJson copies), the CHANGELOGS answer text and the WORKLOGS text
// retained are the other terms a slot can pin (those two streams convert every page to text under the big-parse lock, so only ONE slot
// at a time has a tree on top of its text); every other slot is budgeted at its own largest term ([heapFitVerdict]). What stays outside
// it (named)
// is in `.claude/docs/jira-integration.md` "What the boot check does NOT bound yet". The `all` role is budgeted for its worker only: a
// Test connection's own tree (one big parse at a time) is not added to it.

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
 * under char-based ceilings) and parse each item inside the writer, whose stored-size ceilings ([denseTreeBytes]) are budgeted apart
 * ([processSlotBytes], [changelogSlotBytes]).
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
 * Person trend peer-cell cache (`reports.peerCellCache.maxEntries`, ~8 MiB at its default cap).
 */
internal const val WEB_RESERVE_BYTES = 40L * MIB

/** What a role can pin per slot and keep beside it: a worker stores responses ([STORED_RESPONSE_TREES]), a `web` role only parses one. */
internal enum class HeapFitRole(val treesPerSlot: Int, val reserveBytes: Long) {
    WORKER(STORED_RESPONSE_TREES, RESERVE_BYTES),
    WEB(1, WEB_RESERVE_BYTES),
}

/**
 * Heap bytes a DENSE stored document of [bytes] bytes can retain once parsed: [RESPONSE_BYTES_PER_NODE] bytes a node is what a
 * response may carry, and stored text (a canonical issue, a jsonb-formatted payload) can be 3x denser than that, at
 * [TREE_BYTES_PER_NODE] a node. The worst shape for the hostile case; a real 131 KiB issue is ~5 MiB of tree by it.
 */
internal fun denseTreeBytes(bytes: Long): Long = bytes * 3 / RESPONSE_BYTES_PER_NODE * TREE_BYTES_PER_NODE

/**
 * Copies of a PROCESS page's stored text a slot holds at once, each counted at UTF-16's worst case (2 bytes a character for text that
 * is 1 byte a character in UTF-8): the page's strings as read (2x) and the `customFieldsJson` copies the normalizer builds from them
 * while those strings are still referenced (at most the page again, 2x). A reference-kind chunk (`JiraEntityReads`, at most
 * [JiraStoredCeilings.processPageBytes] of text, one chunk at a time) is smaller than this and so covered by it.
 */
internal const val PROCESS_PAGE_TEXT_BYTES_PER_STORED_BYTE = 4L

/**
 * What one PROCESS slot can pin: a page of stored text claimed under [JiraStoredCeilings.processPageBytes] at
 * [PROCESS_PAGE_TEXT_BYTES_PER_STORED_BYTE] bytes of heap a stored byte, plus the dense trees of the one issue payload and the one
 * history being parsed at a time.
 */
internal fun processSlotBytes(stored: JiraStoredCeilings): Long =
    PROCESS_PAGE_TEXT_BYTES_PER_STORED_BYTE * stored.processPageBytes +
        denseTreeBytes(stored.maxPayloadBytes) + denseTreeBytes(MAX_STORED_HISTORY_BYTES)

/**
 * The answer text a CHANGELOGS slot accumulates until its transaction: [MAX_ANSWER_CHARS] characters at 2 bytes (UTF-16 worst case). The
 * stream keeps no page tree beside it - each page is converted to history rows inside the client call under the big-parse lock
 * (`JiraClient.changelogBulkAs`) - but while it converts, THAT tree exists on top of the text ([heapFitVerdict] adds it for one slot).
 */
internal fun changelogTextBytes(): Long = 2 * MAX_ANSWER_CHARS

/**
 * The worklog text a WORKLOGS slot has accumulated, at 2 bytes a character ([WorklogCeilings.retentionChars] for [window] =
 * `jira.maxConcurrentRequests` fetches ahead); like CHANGELOGS it holds no page tree, but the one under the lock is on top of it.
 */
internal fun worklogTextBytes(window: Int, ceilings: WorklogCeilings = WorklogCeilings()): Long = 2 * ceilings.retentionChars(window)

/** Which of a slot's terms dominates it, so a refusal names the knobs that move it. */
internal enum class SlotTerm { RESPONSE, PROCESS, CHANGELOG, WORKLOG }

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
    /** The dominating term of the one slot that may be converting a page under the lock, and its size (text + that tree). */
    val slotTerm: SlotTerm = SlotTerm.RESPONSE,
    val slotBytes: Long = 0,
    /** What each OTHER slot is budgeted at: the largest of a slot's own terms (no converting tree on top). */
    val otherSlotBytes: Long = slotBytes,
) {
    val fits: Boolean get() = needBytes <= availableBytes
}

/**
 * The pure verdict. Each of [slots] concurrent jobs can pin its own largest ([SlotTerm]): [HeapFitRole.treesPerSlot] trees of
 * [maxResponseNodes] nodes on the response path (ISSUES, REFERENCE and RECONCILE keep their page tree past the big-parse lock), a PROCESS
 * page and the dense trees it parses ([processSlotBytes], no lock), or the accumulated text of a CHANGELOGS answer / the WORKLOGS
 * fetch-ahead ([changelogTextBytes], [worklogTextBytes]). One slot at a time may also be converting a page under the lock, which puts the
 * response tree on top of its text. So `need = (slots - 1) x the largest own term + max(own terms, text + tree)`, plus
 * [maxConcurrentRequests] bodies of [maxResponseBytes] in flight and the role's reserve, against the old generation; a `web` role stores
 * nothing and has the response term only. [stored] carries the two configurable stored-size figures.
 */
internal fun heapFitVerdict(
    slots: Int,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    heap: HeapSizes,
    maxResponseNodes: Long = defaultMaxResponseNodes(maxResponseBytes),
    role: HeapFitRole = HeapFitRole.WORKER,
    stored: JiraStoredCeilings = JiraStoredCeilings(),
): HeapFitVerdict {
    val responseTree = role.treesPerSlot * TREE_BYTES_PER_NODE * maxResponseNodes
    // What a slot pins on its own (a stored response tree, a PROCESS page, accumulated answer text) ...
    val own = buildList {
        add(SlotTerm.RESPONSE to responseTree)
        if (role == HeapFitRole.WORKER) {
            add(SlotTerm.PROCESS to processSlotBytes(stored))
            add(SlotTerm.CHANGELOG to changelogTextBytes())
            add(SlotTerm.WORKLOG to worklogTextBytes(maxConcurrentRequests))
        }
    }
    // ... and what ONE slot pins at the moment it is converting a page under the big-parse lock: its text AND that tree. The lock lets
    // only one tree be built at a time, but the other slots may be at their own largest (the ISSUES / REFERENCE / RECONCILE page trees
    // outlive the lock, and PROCESS takes none), so the other slots are NOT discounted: (slots - 1) at their own largest, one with a tree.
    val withTree = own.map { (term, bytes) ->
        term to if (term == SlotTerm.CHANGELOG || term == SlotTerm.WORKLOG) bytes + responseTree else bytes
    }
    val (slotTerm, slotBytes) = withTree.maxBy { it.second }
    val otherSlotBytes = own.maxOf { it.second }
    val need = (slots - 1).toLong() * otherSlotBytes + slotBytes + maxConcurrentRequests * maxResponseBytes + role.reserveBytes
    val old = heap.oldGenerationBytes
    val required = if (heap.youngScalesWithHeap && old > 0) {
        // old = heap x (old / (old + young)) here, so the heap that gives `need` of old generation is need x (old + young) / old.
        (need * (old + heap.youngGenerationBytes) + old - 1) / old
    } else {
        need + heap.youngGenerationBytes
    }
    return HeapFitVerdict(need, old, required, slotTerm, slotBytes, otherSlotBytes)
}

internal fun heapFitMessage(
    verdict: HeapFitVerdict,
    slots: Int,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    role: HeapFitRole = HeapFitRole.WORKER,
    stored: JiraStoredCeilings = JiraStoredCeilings(),
): String {
    val nodes = "${defaultMaxResponseNodes(maxResponseBytes)} nodes (jira.maxResponseBytes $maxResponseBytes / $RESPONSE_BYTES_PER_NODE)"
    // A web role has no slots: it parses one Test connection tree at a time, so only the Jira knobs it really uses are named.
    val slotMib = verdict.slotBytes.ceilMib()
    val otherMib = verdict.otherSlotBytes.ceilMib()
    val trees = when {
        role == HeapFitRole.WEB -> "one tree at a time (big parses are serialised) of up to $nodes x $TREE_BYTES_PER_NODE B"
        verdict.slotTerm == SlotTerm.PROCESS ->
            "ingest.workerSlots ($slots) slots, the largest a PROCESS slot of $slotMib MiB " +
                "($PROCESS_PAGE_TEXT_BYTES_PER_STORED_BYTE x jira.processPageBytes ${stored.processPageBytes} + " +
                "dense trees of one jira.maxStoredPayloadBytes (${stored.maxPayloadBytes}) item and one " +
                "$MAX_STORED_HISTORY_BYTES-byte history), the others up to $otherMib MiB each"
        verdict.slotTerm == SlotTerm.CHANGELOG ->
            "ingest.workerSlots ($slots) slots, the largest a CHANGELOGS slot of $slotMib MiB " +
                "(a $MAX_ANSWER_CHARS-character answer held as UTF-16 text + the big response tree being converted), " +
                "the others up to $otherMib MiB each"
        verdict.slotTerm == SlotTerm.WORKLOG ->
            "ingest.workerSlots ($slots) slots, the largest a WORKLOGS slot of $slotMib MiB " +
                "(the worklog text of ${maxConcurrentRequests + 1} issue answers and a batch held as UTF-16 + the big response tree " +
                "being converted), the others up to $otherMib MiB each"
        else -> "ingest.workerSlots ($slots) x ${role.treesPerSlot} tree(s) of up to $nodes x $TREE_BYTES_PER_NODE B"
    }
    val knobs = when {
        role == HeapFitRole.WEB -> "lower jira.maxResponseBytes or jira.maxConcurrentRequests"
        verdict.slotTerm == SlotTerm.PROCESS ->
            "lower ingest.workerSlots, jira.processPageBytes, jira.maxStoredPayloadBytes or jira.maxConcurrentRequests"
        verdict.slotTerm == SlotTerm.CHANGELOG || verdict.slotTerm == SlotTerm.WORKLOG ->
            "lower ingest.workerSlots, jira.maxResponseBytes or jira.maxConcurrentRequests"
        else -> "lower ingest.workerSlots, jira.maxResponseBytes or jira.maxConcurrentRequests"
    }
    return "The Jira response ceilings do not fit the heap: $trees + jira.maxConcurrentRequests ($maxConcurrentRequests) bodies of " +
        "jira.maxResponseBytes ($maxResponseBytes) in flight + ${role.reserveBytes / MIB} MiB reserve need " +
        "${verdict.needBytes.ceilMib()} MiB of old generation, but only ${verdict.availableBytes.ceilMib()} MiB is available: " +
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
 * worker only (425 MiB at the shipped defaults against the 448 MiB of `-Xmx512m`): adding a Test connection's tree (537 MiB) would
 * refuse the shipped compose and `:server:run` configurations, so that tree is the residual of the file header. Called from
 * [configureJira], whose
 * `ingest.workerSlots` read goes through `ingest/IngestWorkerConfig.kt`'s `readWorkerSlots` so the key's bounds have one owner.
 */
internal fun Application.requireHeapFitsJiraCeilings(
    config: ApplicationConfig,
    maxResponseBytes: Long,
    maxConcurrentRequests: Int,
    stored: JiraStoredCeilings,
    heap: HeapSizes = currentHeapSizes(),
) {
    val worker = runsWorker()
    val slots = if (worker) readWorkerSlots(config) else 1
    val role = if (worker) HeapFitRole.WORKER else HeapFitRole.WEB
    // Both roles hold up to maxConcurrentRequests bodies: a web call waiting for the big-parse lock keeps its body too.
    val verdict = heapFitVerdict(slots, maxResponseBytes, maxConcurrentRequests, heap, role = role, stored = stored)
    if (verdict.fits) return
    val message = heapFitMessage(verdict, slots, maxResponseBytes, maxConcurrentRequests, role, stored)
    check(!worker) { message }
    log.warn("$message (this web role only parses Test connection responses, so it keeps running)")
}
