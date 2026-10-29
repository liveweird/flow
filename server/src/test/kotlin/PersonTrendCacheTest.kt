package ch.nokillswit

import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.MAX_PEER_CELL_CACHE_MAX_ENTRIES
import ch.nokillswit.reports.PeerCellCache
import ch.nokillswit.reports.PeerCellEntry
import ch.nokillswit.reports.PeerCellKey
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.TREND_ROWS
import ch.nokillswit.reports.peerCellSlice
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The peer-cell cache's own mechanics (`reports/PersonTrendCache.kt`), no database: the key's canonical slice string, the compact entry's
 * round trip, the generation swap, the plan ("every window or fetch"), the exact entry cap under concurrency, and the retained size of one
 * entry (the `in-test peer-cell-bytes` line of `build-times.md`).
 */
class PersonTrendCacheTest {

    private fun filter(
        domain: String? = null,
        view: DomainView = DomainView.TASK,
        activity: String? = null,
        category: String? = null,
    ) = ReportFilter(
        period = ReportPeriod.DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), 0L, 1L),
        level = ReportLevel.USER,
        teamId = null,
        accountIds = listOf("p"),
        domainView = view,
        domain = domain,
        activityType = activity,
        workCategory = category,
        connectionId = null,
    )

    private fun cells(seed: Int, hidden: Set<Int> = emptySet(), missing: Set<Int> = emptySet()): Map<String, CompareCell> =
        TREND_ROWS.mapIndexed { row, spec ->
            spec.key to CompareCell(if (row in missing) null else seed + row / 7.0, seed + row, row in hidden)
        }.toMap()

    private fun entry(seed: Int = 1, active: Boolean = true) = PeerCellEntry.of(cells(seed), active)

    private fun key(account: String, end: String, slice: String = "s") = PeerCellKey(account, LocalDate.parse(end), slice)

    @Test
    fun `the slice string is canonical - connection order is irrelevant, every slice and its null are distinct`() {
        assertEquals(peerCellSlice(listOf(3u, 1u, 2u), filter()), peerCellSlice(listOf(1u, 2u, 3u), filter()))
        assertNotEquals(peerCellSlice(listOf(1u), filter()), peerCellSlice(listOf(1u, 2u), filter()))
        val variants = listOf(
            filter(), filter(domain = "web"), filter(domain = "web", view = DomainView.EPIC), filter(activity = "BUG"),
            filter(category = "UNCATEGORIZED"), filter(category = "null"), filter(domain = ""), filter(domain = "w", activity = "eb"),
            filter(domain = "we", activity = "b"),
        )
        val slices = variants.map { peerCellSlice(listOf(1u), it) }
        assertEquals(variants.size, slices.toSet().size, "no two slices share a string: $slices")
        assertEquals(
            peerCellSlice(listOf(1u), filter(domain = "web")), peerCellSlice(listOf(1u), filter(domain = "web", view = DomainView.TASK)),
            "TASK is the default view",
        )
    }

    @Test
    fun `an entry gives back exactly the cells it was made from - null, hidden, zero and negative zero included`() {
        val tricky = TREND_ROWS.mapIndexed { row, spec ->
            val value = when (row % 5) {
                0 -> null
                1 -> -0.0
                2 -> 0.0
                3 -> Double.MIN_VALUE
                else -> 1.0 / 3.0 + row
            }
            spec.key to CompareCell(value, row * 11, hidden = row % 3 == 0)
        }.toMap()
        for (active in listOf(true, false)) {
            val made = PeerCellEntry.of(tricky, active)
            assertEquals(active, made.active)
            TREND_ROWS.forEachIndexed { row, spec ->
                assertEquals(tricky.getValue(spec.key), made.cell(row), "row ${spec.key}")
            }
        }
        val plain = cells(7)
        val round = PeerCellEntry.of(plain, true)
        TREND_ROWS.forEachIndexed { row, spec -> assertEquals(plain.getValue(spec.key), round.cell(row)) }
    }

    @Test
    fun `a generation is reused for its stamp, swapped from the one observed for another, and shared by racing requests`() {
        val cache = PeerCellCache(100)
        val first = cache.open(listOf("a", "b"))
        assertTrue(first.swapped)
        assertSame(first, cache.open(listOf("a", "b")), "the same stamp is the same generation")
        assertEquals(1L, cache.stats.swaps.get())
        val second = cache.open(listOf("a", "c"))
        assertNotSame(first, second)
        assertTrue(second.swapped)
        assertEquals(2L, cache.stats.swaps.get())

        // Eight requests with ONE new stamp race: exactly one swap wins the compare-and-set, the losers adopt its generation.
        val racing = PeerCellCache(100)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val opened = (1..8).map { pool.submit<Any> { start.await(); racing.open(listOf("same")) } }
            start.countDown()
            val generations = opened.map { it.get(30, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, generations.size, "one shared generation")
            assertEquals(1L, racing.stats.swaps.get(), "one swap")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `racing requests with different stamps never get another stamp's generation`() {
        val cache = PeerCellCache(100)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val opened = (1..8).map { n ->
                pool.submit<Pair<String, List<String>>> { start.await(); "s$n" to cache.open(listOf("s$n")).stamp }
            }
            start.countDown()
            opened.map { it.get(30, TimeUnit.SECONDS) }.forEach { (asked, got) -> assertEquals(listOf(asked), got) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the plan serves a peer only when every window it is needed in is cached, the person is always fetched`() {
        val ends = listOf("2026-01-01", "2026-01-15", "2026-01-29").map(LocalDate::parse)
        val generation = PeerCellCache(100).open(listOf("g"))
        // Peers: x in all three windows, y in the last two, z in the first only.
        val peers = listOf(listOf("x", "z"), listOf("x", "y"), listOf("x", "y"))
        for (end in ends) generation.store(PeerCellKey("x", end, "s"), entry())
        generation.store(PeerCellKey("y", ends[2], "s"), entry()) // y: only one of its two windows
        generation.store(PeerCellKey("z", ends[0], "s"), entry(2))
        generation.store(PeerCellKey("p", ends[0], "s"), entry()) // the person is cached too, and still fetched

        val plan = generation.plan("p", "s", ends, peers)
        assertEquals(setOf("x", "z"), plan.served.keys, "x and z are complete, y misses a window")
        assertEquals(ends.toSet(), plan.served.getValue("x").keys)
        assertEquals(setOf(ends[0]), plan.served.getValue("z").keys)
        assertEquals(listOf("p", "y"), plan.fetch, "the person first, then the incomplete peer")
        assertEquals(listOf("p", "x", "z", "y"), generation.plan("p", "other-slice", ends, peers).fetch, "another slice shares nothing")
        val noTeam = generation.plan("p", "s", ends, listOf(emptyList(), emptyList(), emptyList()))
        assertEquals(listOf("p"), noTeam.fetch, "no team: the person only")
    }

    @Test
    fun `a disabled cache stores and serves nothing and counts nothing`() {
        val cache = PeerCellCache(0)
        val generation = cache.open(listOf("g"))
        assertFalse(generation.enabled)
        val end = LocalDate.parse("2026-01-01")
        generation.store(PeerCellKey("x", end, "s"), entry())
        val plan = generation.plan("p", "s", listOf(end), listOf(listOf("x", "y")))
        assertEquals(emptySet(), plan.served.keys)
        assertEquals(listOf("p", "x", "y"), plan.fetch)
        assertEquals(0, generation.entryCount)
        with(cache.stats) {
            val all = listOf(servedPeers, fetchedPeers, writes, refused, swaps).map { it.get() }
            assertEquals(listOf(0L, 0L, 0L, 0L, 0L), all)
        }
    }

    @Test
    fun `the cap refuses writes and never evicts, exactly, even under concurrency`() {
        val cache = PeerCellCache(5)
        val generation = cache.open(listOf("g"))
        val end = LocalDate.parse("2026-01-01")
        (1..20).forEach { generation.store(PeerCellKey("a$it", end, "s"), entry(it)) }
        assertEquals(5, generation.entryCount)
        assertEquals(5L, cache.stats.writes.get())
        assertEquals(15L, cache.stats.refused.get())
        generation.store(PeerCellKey("a1", end, "s"), entry(99)) // present: neither a write nor a refusal
        assertEquals(15L, cache.stats.refused.get())
        val kept = generation.plan("p", "s", listOf(end), listOf((1..20).map { "a$it" }))
        assertEquals((1..5).map { "a$it" }.toSet(), kept.served.keys, "the first five stay: nothing was evicted")

        val racing = PeerCellCache(100)
        val shared = racing.open(listOf("g"))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val done = (1..8).map { t ->
                pool.submit { start.await(); (1..500).forEach { shared.store(PeerCellKey("t$t-$it", end, "s"), entry(it)) } }
            }
            start.countDown()
            done.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(100, shared.entryCount, "the cap is exact under concurrent writers")
        assertEquals(100L, racing.stats.writes.get())
        assertEquals(8L * 500 - 100, racing.stats.refused.get())
    }

    @Test
    fun `the cache refuses a size outside 0 to the maximum`() {
        assertFailsWith<IllegalArgumentException> { PeerCellCache(-1) }
        assertFailsWith<IllegalArgumentException> { PeerCellCache(MAX_PEER_CELL_CACHE_MAX_ENTRIES + 1) }
        PeerCellCache(MAX_PEER_CELL_CACHE_MAX_ENTRIES)
    }

    /**
     * An in-test target (`build-times.md`), not a gate: the retained heap per entry (key + map node + the compact entry) by the WHY 48
     * method -- `System.gc()` and `Runtime` deltas over 10,000 inserts of realistic ~45-character account ids (retained: the key, its
     * account string, the map node and the entry; the window ends and the slice string are shared, as in a request).
     */
    @Test
    fun `the retained size of one cached entry stays near the sizing estimate`() {
        val runtime = Runtime.getRuntime()
        fun used(): Long {
            repeat(3) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }
        val count = 10_000
        // The real keys share their window ends (79 at most) and the request's slice string; only the account string is the key's own.
        val ends = (0L until 79L).map { LocalDate.parse("2026-01-01").plusDays(it) }
        val slice = peerCellSlice(listOf(1u), filter())
        val sample = cells(1)
        val before = used()
        val generation = PeerCellCache(MAX_PEER_CELL_CACHE_MAX_ENTRIES).open(listOf("g"))
        repeat(count) {
            generation.store(PeerCellKey("712020:" + "%038d".format(it), ends[it % 79], slice), PeerCellEntry.of(sample, true))
        }
        val after = used()
        val perEntry = (after - before) / count
        println("in-test\tpeer-cell-bytes\t$perEntry")
        assertEquals(count, generation.entryCount, "every key was distinct and under the cap")
        assertTrue(perEntry in 100..2000, "about 0.45 KiB per entry was the estimate, measured $perEntry")
    }
}
