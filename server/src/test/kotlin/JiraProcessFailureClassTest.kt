package ch.nokillswit

import ch.nokillswit.jira.isDataError
import io.r2dbc.spi.R2dbcBadGrammarException
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals

/** `jira/JiraProcessStream.kt`'s bad-row classifier (SQLSTATE class 22/23 anywhere in the cause chain) in isolation. */
class JiraProcessFailureClassTest {
    @Test
    fun `data exceptions and integrity violations are bad rows, including behind wrappers`() {
        assertEquals(true, R2dbcDataIntegrityViolationException("duplicate key", "23505").isDataError())
        assertEquals(true, R2dbcBadGrammarException("value too long", "22001").isDataError())
        val wrapped = IllegalStateException("wrapped", RuntimeException("deeper", R2dbcDataIntegrityViolationException("x", "23502")))
        assertEquals(true, wrapped.isDataError())
    }

    @Test
    fun `connection, timeout, grammar and unknown failures are not bad rows`() {
        assertEquals(false, R2dbcNonTransientResourceException("connection lost", "08006").isDataError())
        assertEquals(false, R2dbcTimeoutException("statement timeout", "57014").isDataError())
        assertEquals(false, R2dbcBadGrammarException("syntax error", "42601").isDataError())
        assertEquals(false, R2dbcNonTransientResourceException("no state").isDataError())
        assertEquals(false, IllegalStateException("not a database error").isDataError())
    }
}
