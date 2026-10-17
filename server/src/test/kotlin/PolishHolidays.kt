package ch.nokillswit

import java.time.LocalDate

/**
 * An independent re-derivation of the Polish statutory public holidays, ported from Lettuce's `perf/SeedHolidays.kt`. V25
 * (`metrics.public_holidays`) seeds the same set as LITERAL rows for 2017-2030; `PublicHolidaySeedTest` compares the table with this
 * generator row by row, so the literals are provably right and a wrong Easter date cannot hide in a hand-typed list.
 */
object PolishHolidays {
    /** Easter Sunday by the anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** The statutory holidays of [year] with the names V25 uses (Christmas Eve is statutory since 2025). */
    fun of(year: Int): List<Pair<LocalDate, String>> {
        val easter = easter(year)
        return buildList {
            add(LocalDate.of(year, 1, 1) to "Nowy Rok")
            add(LocalDate.of(year, 1, 6) to "Święto Trzech Króli")
            add(easter to "Wielkanoc")
            add(easter.plusDays(1) to "Poniedziałek Wielkanocny")
            add(LocalDate.of(year, 5, 1) to "Święto Pracy")
            add(LocalDate.of(year, 5, 3) to "Święto Konstytucji 3 Maja")
            add(easter.plusDays(49) to "Zielone Świątki")
            add(easter.plusDays(60) to "Boże Ciało")
            add(LocalDate.of(year, 8, 15) to "Wniebowzięcie Najświętszej Maryi Panny")
            add(LocalDate.of(year, 11, 1) to "Wszystkich Świętych")
            add(LocalDate.of(year, 11, 11) to "Narodowe Święto Niepodległości")
            if (year >= 2025) add(LocalDate.of(year, 12, 24) to "Wigilia Bożego Narodzenia")
            add(LocalDate.of(year, 12, 25) to "Boże Narodzenie (pierwszy dzień)")
            add(LocalDate.of(year, 12, 26) to "Boże Narodzenie (drugi dzień)")
        }
    }

    /** The years V25 seeds. */
    val SEEDED_YEARS = 2017..2030
}
