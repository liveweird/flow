package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * V25's literal seed against an independent re-derivation ([PolishHolidays]: Meeus/Jones/Butcher Easter plus the fixed dates), so a
 * wrong literal cannot hide in the migration. The table is shared and tests add far-off or single-day holidays and remove them again,
 * so the comparison is over the seeded years only and ignores nothing the generator names.
 */
class PublicHolidaySeedTest {

    private suspend fun seededYearRows(): Map<String, String> {
        SyncedStubFixture.ensureMigrated()
        return TestHolidays.service.list().filter { LocalDate.parse(it.date).year in PolishHolidays.SEEDED_YEARS }
            .associate { it.date to it.name }
    }

    @Test
    fun `the seeded rows equal the generated Polish statutory holidays for 2017 to 2030`() = runBlocking {
        val expected = PolishHolidays.SEEDED_YEARS.flatMap(PolishHolidays::of).associate { it.first.toString() to it.second }
        assertEquals(expected.toSortedMap(), seededYearRows().toSortedMap())
        assertEquals(8 * 13 + 6 * 14, expected.size, "13 days a year before 2025 (Christmas Eve is statutory since), 14 from 2025")
    }

    @Test
    fun `spot checks - movable feasts and the 2025 Christmas Eve`() = runBlocking {
        val rows = seededYearRows()
        assertEquals("Poniedziałek Wielkanocny", rows["2026-04-06"])
        assertEquals("Boże Ciało", rows["2026-06-04"])
        assertEquals("Wigilia Bożego Narodzenia", rows["2025-12-24"])
        assertNull(rows["2024-12-24"], "Christmas Eve was not statutory before 2025")
        assertEquals("Wielkanoc", rows["2027-03-28"])
    }
}
