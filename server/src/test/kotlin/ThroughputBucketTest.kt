package ch.nokillswit

import ch.nokillswit.reports.ThroughputBucket
import ch.nokillswit.reports.bucketStart
import ch.nokillswit.reports.bucketStarts
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure bucket math behind `GET /api/v1/reports/throughput` (`reports/ThroughputReport.kt`,
 * `.claude/docs/reports.md` "Report 2"): weeks start Monday and buckets are cut in the CONFIGURED
 * zone, never UTC. Europe/Warsaw's 2026 spring-forward is Sunday 2026-03-29 (02:00 -> 03:00), so the
 * week containing it is 23h shorter than a normal one.
 */
class ThroughputBucketTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    private fun ms(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `weeks start on Monday in the configured zone across a DST change`() {
        // Monday 00:30 CEST is still SUNDAY 22:30 in UTC — a UTC cut would file it in the previous week.
        val mondayJustAfterMidnight = ms(warsaw, 2026, 3, 30, 0, 30)
        assertEquals(LocalDate.of(2026, 3, 30), bucketStart(mondayJustAfterMidnight, warsaw, ThroughputBucket.WEEK))
        assertEquals(LocalDate.of(2026, 3, 23), bucketStart(ms(warsaw, 2026, 3, 29, 23, 30), warsaw, ThroughputBucket.WEEK))
        // The DST day itself (a Sunday) belongs to the week that started the Monday before.
        assertEquals(LocalDate.of(2026, 3, 23), bucketStart(ms(warsaw, 2026, 3, 29, 12), warsaw, ThroughputBucket.WEEK))
        // A Monday instant is its own bucket start.
        assertEquals(LocalDate.of(2026, 3, 23), bucketStart(ms(warsaw, 2026, 3, 23), warsaw, ThroughputBucket.WEEK))
    }

    @Test
    fun `months start on the first in the configured zone`() {
        assertEquals(LocalDate.of(2026, 3, 1), bucketStart(ms(warsaw, 2026, 3, 31, 23, 59), warsaw, ThroughputBucket.MONTH))
        // 00:00 on April 1st in Warsaw is still March 31st 22:00 UTC.
        assertEquals(LocalDate.of(2026, 4, 1), bucketStart(ms(warsaw, 2026, 4, 1), warsaw, ThroughputBucket.MONTH))
    }

    @Test
    fun `bucketStarts zero-fills every bucket the window touches and treats the end as exclusive`() {
        val weeks = bucketStarts(ms(warsaw, 2026, 3, 25), ms(warsaw, 2026, 4, 6), warsaw, ThroughputBucket.WEEK)
        // [03-25, 04-06) ends at midnight Monday 04-06, so that week is NOT touched.
        assertEquals(listOf(LocalDate.of(2026, 3, 23), LocalDate.of(2026, 3, 30)), weeks)

        val months = bucketStarts(ms(warsaw, 2026, 1, 15), ms(warsaw, 2026, 3, 10), warsaw, ThroughputBucket.MONTH)
        assertEquals(listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)), months)

        assertEquals(emptyList(), bucketStarts(ms(warsaw, 2026, 3, 1), ms(warsaw, 2026, 3, 1), warsaw, ThroughputBucket.WEEK))
    }
}
