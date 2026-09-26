package ch.nokillswit

import ch.nokillswit.infra.db.containsPattern
import kotlin.test.Test
import kotlin.test.assertEquals

/** Direct tests of the pure LIKE-pattern builder (infra/db/Sql.kt); the SQL-side folding
 *  (`LOWER(unaccent(col)) LIKE LOWER(unaccent(pattern))`) is covered by the route filter
 *  tests against the real Testcontainers database. */
class ContainsPatternTest {

    @Test
    fun `wraps the value in wildcards and lowercases it`() {
        val pattern = containsPattern("Alice")
        assertEquals("%alice%", pattern.pattern)
        assertEquals('\\', pattern.escapeChar)
    }

    @Test
    fun `escapes LIKE metacharacters percent underscore and backslash`() {
        assertEquals("%100\\%%", containsPattern("100%").pattern)
        assertEquals("%snake\\_case%", containsPattern("snake_case").pattern)
        assertEquals("%back\\\\slash%", containsPattern("back\\slash").pattern)
    }

    @Test
    fun `escapes the escape character before the metacharacters it guards`() {
        // A literal "\%" in the input must become escaped-backslash + escaped-percent,
        // not an accidentally-valid escape sequence.
        assertEquals("%\\\\\\%%", containsPattern("\\%").pattern)
    }

    @Test
    fun `leaves diacritics for the SQL side and lowercases them correctly in Kotlin`() {
        // Folding to ASCII happens in SQL (unaccent, both sides); the Kotlin half only
        // lowercases — including Polish uppercase diacritics, which the JVM handles
        // locale-independently (unlike LOWER() in a C-locale database).
        assertEquals("%żółw%", containsPattern("ŻÓŁW").pattern)
        assertEquals("%ąćęńśźż%", containsPattern("ĄĆĘŃŚŹŻ").pattern)
    }
}
