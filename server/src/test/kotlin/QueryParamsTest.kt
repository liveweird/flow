package ch.nokillswit

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.repeatedEnum
import ch.nokillswit.infra.paging.repeatedValues
import ch.nokillswit.infra.paging.singleValue
import io.ktor.http.parametersOf
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests of the query-param parsing helpers (infra/paging/QueryParams.kt). The
 * paging pipeline itself (parsePaging + applyPaging) is covered by the list-endpoint route
 * tests against the real database.
 */
class QueryParamsTest {

    private enum class Fruit { APPLE, PEAR }

    @Test
    fun `singleValue returns the value, null when absent, and 400s a repeated key`() {
        val params = parametersOf("a" to listOf("x"), "b" to listOf("1", "2"))
        assertEquals("x", params.singleValue("a"))
        assertNull(params.singleValue("missing"))
        val failure = assertFailsWith<BadRequestException> { params.singleValue("b") }
        assertEquals("Parameter 'b' must not be repeated", failure.message)
    }

    @Test
    fun `optionalString treats blank as absent`() {
        val params = parametersOf("blank" to listOf("   "), "real" to listOf(" x "))
        assertNull(params.optionalString("blank"))
        assertEquals(" x ", params.optionalString("real"))
    }

    @Test
    fun `repeatedValues collects every non-blank value and is empty when absent`() {
        val params = parametersOf("v" to listOf("a", " ", "b"), "blank" to listOf("", "  "))
        assertEquals(listOf("a", "b"), params.repeatedValues("v"))
        assertEquals(emptyList(), params.repeatedValues("blank"))
        assertEquals(emptyList(), params.repeatedValues("missing"))
    }

    @Test
    fun `optionalEnum matches exact constant names and lists the allowed values on failure`() {
        val params = parametersOf("ok" to listOf("PEAR"), "junk" to listOf("pear"))
        assertEquals(Fruit.PEAR, params.optionalEnum<Fruit>("ok"))
        assertNull(params.optionalEnum<Fruit>("missing"))
        val failure = assertFailsWith<BadRequestException> { params.optionalEnum<Fruit>("junk") }
        assertTrue(failure.message!!.contains("APPLE, PEAR"))
    }

    @Test
    fun `repeatedEnum is case-insensitive, distinct, empty when absent, and 400s an unknown value`() {
        val params = parametersOf("v" to listOf("pear", "PEAR", "Apple"), "junk" to listOf("kiwi"))
        assertEquals(listOf(Fruit.PEAR, Fruit.APPLE), params.repeatedEnum<Fruit>("v"))
        assertEquals(emptyList(), params.repeatedEnum<Fruit>("missing"))
        val failure = assertFailsWith<BadRequestException> { params.repeatedEnum<Fruit>("junk") }
        assertTrue(failure.message!!.contains("APPLE, PEAR"))
    }
}
