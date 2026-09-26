package ch.nokillswit

import ch.nokillswit.infra.db.orVanished
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure unit tests of the small SQL-adjacent helpers in infra/db/Sql.kt; the SQL rendering
 * (containsNormalized) is covered by the list-endpoint filter tests against the real database.
 */
class SqlHelpersTest {

    @Test
    fun `orVanished returns the value when present and errors when it is gone`() {
        assertEquals("here", ("here" as String?).orVanished("Contract", 1u))
        val failure = assertFailsWith<IllegalStateException> {
            (null as String?).orVanished("Contract", 7u, "after opening")
        }
        assertEquals("Contract 7 vanished after opening", failure.message)
    }
}
