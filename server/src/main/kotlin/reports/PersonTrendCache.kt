package ch.nokillswit.reports

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/*
 * Person trend's peer-cell cache (WHY 49, `.claude/docs/reports.md` "Report 20", Cost): a teammate's per-window cells -- the 14
 * [TREND_ROWS] cells and whether they were active -- kept for the next request, so the second person of a team fetches ONE person's rows
 * instead of the whole team's. Per process and per `Application` (the web tier runs one replica), no caller-shaped data (D12).
 *
 * GENERATION = the report data stamp (`dataStamp`, the ETag's own lines), read ONCE in the route BEFORE the report transaction. An entry is
 * therefore never OLDER than its generation promises; a write finishing in between only makes it newer than its key, and the next request
 * (a new stamp) misses. Each stamp read takes a TICKET (a monotonic counter) just BEFORE the read. A request whose stamp differs swaps a
 * fresh generation in (compare-and-set from the one it observed) only if the observed generation's ticket is older than its own; a
 * straggler that read its stamp earlier than the generation in place (a DERIVE committed in between) works on a private generation
 * instead, so it can never replace a newer one with older data. The replaced generation is garbage at once.
 *
 * KEY = (account, window end, `peerCellSlice`). Each input a cell depends on is covered:
 *   account, window end      the key itself (the window's bounds are the end day + the zone, a `settings:` line);
 *   connection scope         the key (the RESOLVED sorted ids; which exist/derived: `connection:`/`run:` lines);
 *   domain, domainView, activityType, workCategory   the key (null-safe);
 *   facts (`fact_task_delivery`, `fact_worklog`) and the DERIVE-written `metrics.task_assignee`/`item_stage`/`dim_task`/`dim_date` the
 *                            WIP statements read   `run:` lines (DERIVE commits the star and the run row in one transaction);
 *   minSampleSize, zone, weekend, hours, holidays, membership   `settings:` (every write bumps the revision);
 *   the backfill start       the `connection:` line (`backfill_from`; its PUT bumps only the connection's own revision);
 *   the DERIVE clock / coverage day                   `run:`; "today"                         `clock:` (the DAY token);
 *   team membership          no cell: the peers are re-read live per request; `tailPercent` never reaches a cell;
 *   the caller               nothing (D12; no capacity in Person trend).
 * An input added to the stamp later invalidates the cache for free (`ReportValidatorInputsTest` pins the stamp).
 *
 * BOUND: [maxEntries] entries per generation, every entry fixed-size, so the entry cap IS the byte cap (`0` = off). A full generation
 * REFUSES writes (the auth stores' rule, never evict live state): the request already did the work and answers correctly. A peer is
 * served only when EVERY window it is needed in is present, else its rows are fetched (and its cells computed and stored for exactly the
 * windows it is a peer in -- never more than the cache-off request computes); a half-written account (a cancelled request) is
 * therefore harmless. No single-flight: concurrent identical misses each pay today's cost and write idempotent values.
 */

private val log = LoggerFactory.getLogger("ch.nokillswit.reports.PersonTrendCache")

/** Most entries one generation holds; the knob's default (`reports.peerCellCache.maxEntries`), about 8 MiB (~0.4 KiB per entry). */
const val DEFAULT_PEER_CELL_CACHE_MAX_ENTRIES: Int = 20_000

/** The knob's upper bound (boot-validated): about 18 MiB of the web role's heap at the measured entry size. */
const val MAX_PEER_CELL_CACHE_MAX_ENTRIES: Int = 50_000

/** The slices of a request that change a cell: the resolved connections and the three slice filters (`domainView` applies to `domain`). */
internal fun peerCellSlice(connectionIds: List<UInt>, filter: ReportFilter): String {
    fun text(value: String?) = value?.let { "${it.length}:$it" } ?: "-"
    return "c=${connectionIds.sorted().joinToString(",")};v=${filter.domainView};d=${text(filter.domain)};" +
        "a=${text(filter.activityType)};w=${text(filter.workCategory)}"
}

/** One cache key; [slice] is the [peerCellSlice] string, built once per request and shared by that request's keys (not across requests). */
internal data class PeerCellKey(val account: String, val end: LocalDate, val slice: String)

/**
 * One account's [TREND_ROWS] cells over one window, in that order, as primitives: the values (with a presence mask), the sample sizes and
 * a hidden mask, plus whether the account was active ([hadActivity]). The fresh and the cached path both read cells through this class,
 * so the reference median sees the very same doubles either way.
 */
internal class PeerCellEntry private constructor(
    private val values: DoubleArray,
    private val counts: IntArray,
    private val present: Int,
    private val hiddenMask: Int,
    val active: Boolean,
) {
    /** The cell of the [row]-th [TREND_ROWS] row. */
    fun cell(row: Int): CompareCell =
        CompareCell(if (((present ushr row) and 1) == 1) values[row] else null, counts[row], ((hiddenMask ushr row) and 1) == 1)

    companion object {
        init {
            require(TREND_ROWS.size <= Int.SIZE_BITS - 1) { "the masks hold ${Int.SIZE_BITS - 1} rows" }
        }

        /** The entry of [cells] ([cellsOf] of one account over one window) and its [active] flag. */
        fun of(cells: Map<String, CompareCell>, active: Boolean): PeerCellEntry {
            val values = DoubleArray(TREND_ROWS.size)
            val counts = IntArray(TREND_ROWS.size)
            var present = 0
            var hidden = 0
            TREND_ROWS.forEachIndexed { row, spec ->
                val cell = cells.getValue(spec.key)
                cell.value?.let {
                    values[row] = it
                    present = present or (1 shl row)
                }
                counts[row] = cell.n
                if (cell.hidden) hidden = hidden or (1 shl row)
            }
            return PeerCellEntry(values, counts, present, hidden, active)
        }
    }
}

/** What a request found: the peers [served] from the cache (account, then window end) and the accounts whose rows [fetch] must read. */
internal class PeerCellPlan(val served: Map<String, Map<LocalDate, PeerCellEntry>>, val fetch: List<String>)

/** The counters of one cache (tests and the DEBUG line read them; no meter -- the registry dump is a look-back aid nobody reads). */
internal class PeerCellStats {
    val servedPeers = AtomicLong()
    val fetchedPeers = AtomicLong()
    val writes = AtomicLong()
    val refused = AtomicLong()
    val swaps = AtomicLong()
}

/** One generation's entries (see the header). [stats] is `null` for the shared disabled generation, which stores and counts nothing. */
internal class PeerCellGeneration(
    val stamp: List<String>,
    /** The ticket taken just before this generation's stamp was read: a later ticket may replace it, an earlier one may not. */
    val ticket: Long,
    private val limit: Int,
    private val stats: PeerCellStats?,
    val swapped: Boolean,
) {
    private val entries = ConcurrentHashMap<PeerCellKey, PeerCellEntry>()
    private val size = AtomicInteger()

    val enabled: Boolean get() = stats != null

    val entryCount: Int get() = size.get()

    /**
     * Splits [peersByWindow] (per window, the person's reference peers; [ends] the windows' end days) into the peers served from the cache
     * and the accounts to fetch: the [person] always, then every peer missing from ANY window it is needed in, in order of appearance.
     */
    fun plan(person: String, slice: String, ends: List<LocalDate>, peersByWindow: List<List<String>>): PeerCellPlan {
        val neededIn = LinkedHashMap<String, MutableList<LocalDate>>()
        peersByWindow.forEachIndexed { i, peers -> peers.forEach { neededIn.getOrPut(it) { ArrayList() } += ends[i] } }
        val served = LinkedHashMap<String, Map<LocalDate, PeerCellEntry>>()
        val fetch = arrayListOf(person)
        for ((peer, windows) in neededIn) {
            val found = HashMap<LocalDate, PeerCellEntry>(windows.size * 2)
            for (end in windows) entries[PeerCellKey(peer, end, slice)]?.let { found[end] = it }
            if (enabled && found.size == windows.size) served[peer] = found else fetch += peer
        }
        stats?.servedPeers?.addAndGet(served.size.toLong())
        stats?.fetchedPeers?.addAndGet((fetch.size - 1).toLong())
        return PeerCellPlan(served, fetch)
    }

    /** Stores [entry] unless the key is present or the generation is full (a refusal is counted; the cap is exact under concurrency). */
    fun store(key: PeerCellKey, entry: PeerCellEntry) {
        val counters = stats ?: return
        if (entries.containsKey(key)) return
        while (true) {
            val now = size.get()
            if (now >= limit) {
                counters.refused.incrementAndGet()
                return
            }
            if (size.compareAndSet(now, now + 1)) break
        }
        if (entries.putIfAbsent(key, entry) == null) counters.writes.incrementAndGet() else size.decrementAndGet()
    }

    /** One DEBUG line per request: what was served, what was fetched, how full the generation is. */
    fun report(plan: PeerCellPlan) {
        if (!enabled || !log.isDebugEnabled) return
        log.debug(
            "served {} of {} peers, fetched {} accounts, {} entries, generation swapped: {}",
            plan.served.size, plan.served.size + plan.fetch.size - 1, plan.fetch.size, size.get(), if (swapped) "yes" else "no",
        )
    }
}

/**
 * The cache of one `Application` (a constructor collaborator of [ReportService]; never a process-wide object). [maxEntries] `0` turns it
 * off: every request then fetches every account, exactly as before the cache.
 */
class PeerCellCache(private val maxEntries: Int = DEFAULT_PEER_CELL_CACHE_MAX_ENTRIES) {
    init {
        require(maxEntries in 0..MAX_PEER_CELL_CACHE_MAX_ENTRIES) { "maxEntries must be 0..$MAX_PEER_CELL_CACHE_MAX_ENTRIES" }
    }

    internal val stats = PeerCellStats()

    /** How many entries the current generation holds (for tests). */
    internal val entryCount: Int get() = current.get()?.entryCount ?: 0

    private val current = AtomicReference<PeerCellGeneration?>(null)
    private val disabled = PeerCellGeneration(emptyList(), 0L, 0, null, swapped = false)

    private val tickets = AtomicLong()

    /** The next ticket; a route takes it just BEFORE it reads the data stamp, so a larger ticket never read its stamp earlier. */
    internal fun nextTicket(): Long = tickets.incrementAndGet()

    /**
     * The generation of [stamp] (read with [ticket]): the current one when it is that stamp, else a fresh one swapped in from the one
     * OBSERVED here (a compare and set, so a request never replaces a generation it did not see) -- but only over a generation whose
     * ticket is older than [ticket]. A straggler (an older ticket, so possibly an older stamp), a lost race against a different stamp
     * and a request that finds a newer generation work on a private generation that nobody shares: misses only, never a wrong answer.
     */
    internal fun open(stamp: List<String>, ticket: Long): PeerCellGeneration {
        if (maxEntries == 0) return disabled
        val observed = current.get()
        if (observed != null && observed.stamp == stamp) return observed
        if (observed != null && observed.ticket > ticket) return PeerCellGeneration(stamp, ticket, maxEntries, stats, swapped = false)
        val fresh = PeerCellGeneration(stamp, ticket, maxEntries, stats, swapped = true)
        if (current.compareAndSet(observed, fresh)) {
            stats.swaps.incrementAndGet()
            return fresh
        }
        return current.get()?.takeIf { it.stamp == stamp } ?: fresh
    }
}
