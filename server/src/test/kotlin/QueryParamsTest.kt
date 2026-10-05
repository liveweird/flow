package ch.nokillswit

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.repeatedEnum
import ch.nokillswit.infra.paging.repeatedLongs
import ch.nokillswit.infra.paging.repeatedStrings
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

    @Test
    fun `repeatedLongs parses distinct non-negative ids in order and is empty when absent`() {
        val params = parametersOf("id" to listOf("7", " 3 ", "7", "", "0"), "missing" to emptyList())
        assertEquals(listOf(7L, 3L, 0L), params.repeatedLongs("id", maxCount = 5))
        assertEquals(emptyList(), params.repeatedLongs("missing", maxCount = 5))
    }

    @Test
    fun `repeatedLongs 400s a malformed or negative value`() {
        for (bad in listOf("x", "-1", "1.5", "99999999999999999999")) {
            val params = parametersOf("id" to listOf("1", bad))
            val failure = assertFailsWith<BadRequestException> { params.repeatedLongs("id", maxCount = 5) }
            assertEquals("Invalid id: $bad", failure.message)
        }
    }

    @Test
    fun `repeatedLongs bounds the distinct count - duplicates collapse first`() {
        assertEquals(listOf(1L, 2L), parametersOf("id" to listOf("1", "2", "1", "2")).repeatedLongs("id", maxCount = 2))
        val three = parametersOf("id" to listOf("1", "2", "3"))
        val tooMany = assertFailsWith<BadRequestException> { three.repeatedLongs("id", maxCount = 2) }
        assertEquals("Parameter 'id' takes at most 2 values", tooMany.message)
        val none = parametersOf("other" to listOf("1"))
        val tooFew = assertFailsWith<BadRequestException> { none.repeatedLongs("id", minCount = 1, maxCount = 2) }
        assertEquals("Parameter 'id' takes at least 1 value", tooFew.message)
        val one = parametersOf("id" to listOf("1"))
        val tooFewPlural = assertFailsWith<BadRequestException> { one.repeatedLongs("id", minCount = 2, maxCount = 3) }
        assertEquals("Parameter 'id' takes at least 2 values", tooFewPlural.message)
    }

    @Test
    fun `repeatedLongs minValue can require ids of at least 1`() {
        val params = parametersOf("id" to listOf("3", "0"))
        assertEquals(listOf(3L, 0L), params.repeatedLongs("id", maxCount = 5))
        val failure = assertFailsWith<BadRequestException> { params.repeatedLongs("id", maxCount = 5, minValue = 1) }
        assertEquals("Invalid id: 0", failure.message)
        assertEquals(listOf(3L), parametersOf("id" to listOf("3")).repeatedLongs("id", maxCount = 5, minValue = 1))
    }

    @Test
    fun `repeatedStrings trims, drops blanks and duplicates, and bounds the count`() {
        val params = parametersOf("k" to listOf(" FLO-1 ", "", "FLO-2", "FLO-1"))
        assertEquals(listOf("FLO-1", "FLO-2"), params.repeatedStrings("k", maxCount = 2))
        assertEquals(emptyList(), params.repeatedStrings("missing", maxCount = 2))
        assertFailsWith<BadRequestException> { params.repeatedStrings("k", maxCount = 1) }
        assertFailsWith<BadRequestException> { params.repeatedStrings("missing", minCount = 1, maxCount = 2) }
    }
}
