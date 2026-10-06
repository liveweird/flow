package ch.nokillswit

import ch.nokillswit.jira.isBadValueError
import ch.nokillswit.jira.isDataError
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcTimeoutException
import org.jetbrains.exposed.v1.core.VarCharColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `jira/JiraProcessStream.kt`'s bad-row classifier (SQLSTATE class 22/23, or Exposed's varchar length
 * check, anywhere in the cause chain) in isolation.
 */
class JiraProcessFailureClassTest {
    @Test
    fun `data exceptions and integrity violations are bad rows, including behind wrappers`() {
        assertEquals(true, R2dbcDataIntegrityViolationException("duplicate key", "23505").isDataError())
        assertEquals(true, R2dbcBadGrammarException("value too long", "22001").isDataError())
        val wrapped = IllegalStateException("wrapped", RuntimeException("deeper", R2dbcDataIntegrityViolationException("x", "23502")))
        assertEquals(true, wrapped.isDataError())
    }

    @Test
    fun `Exposed's client-side varchar length rejection is a bad row - only that IllegalArgumentException`() {
        // Real Exposed, not a hand-typed message: a value over the column's length throws before any SQL is sent.
        val tooLong = assertFailsWith<IllegalArgumentException> { VarCharColumnType(3).validateValueBeforeUpdate("abcd") }
        assertEquals(true, tooLong.isDataError())
        assertEquals(true, RuntimeException("wrapped", tooLong).isDataError())
        assertEquals(false, IllegalArgumentException("something else").isDataError())
    }

    @Test
    fun `connection, timeout, grammar and unknown failures are not bad rows`() {
        assertEquals(false, R2dbcNonTransientResourceException("connection lost", "08006").isDataError())
        assertEquals(false, R2dbcTimeoutException("statement timeout", "57014").isDataError())
        assertEquals(false, R2dbcBadGrammarException("syntax error", "42601").isDataError())
        assertEquals(false, R2dbcNonTransientResourceException("no state").isDataError())
        assertEquals(false, IllegalStateException("not a database error").isDataError())
    }

    @Test
    fun `bad values are class 22 and Exposed's length check only - an integrity violation is a data error but not a bad value`() {
        assertEquals(true, R2dbcBadGrammarException("value too long", "22001").isBadValueError())
        assertEquals(true, IllegalStateException("wrapped", R2dbcBadGrammarException("nul", "22021")).isBadValueError())
        val tooLong = assertFailsWith<IllegalArgumentException> { VarCharColumnType(3).validateValueBeforeUpdate("abcd") }
        assertEquals(true, RuntimeException("wrapped", tooLong).isBadValueError())
        assertEquals(false, IllegalArgumentException("something else").isBadValueError())
        assertEquals(false, R2dbcDataIntegrityViolationException("duplicate key", "23505").isBadValueError())
        assertEquals(false, R2dbcDataIntegrityViolationException("not null", "23502").isBadValueError())
        assertEquals(false, R2dbcNonTransientResourceException("connection lost", "08006").isBadValueError())
        assertEquals(false, R2dbcTimeoutException("statement timeout", "57014").isBadValueError())
        assertEquals(true, R2dbcDataIntegrityViolationException("duplicate key", "23505").isDataError())
    }
}
