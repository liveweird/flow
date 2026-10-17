package ch.nokillswit

import ch.nokillswit.metrics.PublicHolidayCreateRequest
import ch.nokillswit.metrics.PublicHolidayService
import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate

/**
 * The public-holiday dictionary (`metrics.public_holidays`) from the test side. The table is GLOBAL, shared state (like `dim_date`
 * and `metrics.settings`) and V25 seeds the Polish statutory holidays 2017-2030, so a test that needs a holiday on a particular day
 * adds it for the duration of [with] and removes it in a `finally`; a test that needs "no holiday" picks days the seed leaves alone.
 * Both mutations bump the shared revision (like a settings PUT) — harmless, every revision-sensitive test reads the revision it needs.
 */
object TestHolidays {
    val service: PublicHolidayService by lazy { PublicHolidayService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings()) }

    /** Runs [block] with a holiday on [date] (ISO) and removes it afterwards. */
    suspend fun <T> with(date: String, name: String = "Test holiday", block: suspend () -> T): T {
        val created = service.create(PublicHolidayCreateRequest(date, name))
        return try {
            block()
        } finally {
            service.delete(created.id)
        }
    }

    /** Every date the dictionary holds right now. */
    suspend fun dates(): Set<LocalDate> = service.list().map { LocalDate.parse(it.date) }.toSet()

    /** The working calendar exactly as DERIVE and the reports read it now. */
    suspend fun calendar(): WorkingCalendar = DerivedStubFixture.metricsSettings().readCalendar()
}
