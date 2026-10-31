package ch.nokillswit

import ch.nokillswit.gitlab.NormalizationFault
import ch.nokillswit.gitlab.ProcessFailure
import ch.nokillswit.gitlab.describe
import ch.nokillswit.gitlab.isRowFault
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import io.r2dbc.spi.R2dbcNonTransientResourceException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What PROCESS counts as a row fault of a page, and that its description never quotes a value (`gitlab/GitLabProcessFailure.kt`). */
class GitLabProcessFailureTest {
    @Test
    fun `a constraint violation is a row fault and neither it nor the failure around it quotes the value`() {
        val violation = R2dbcDataIntegrityViolationException("duplicate key value violates (identity_key)=(a@b.example)", "23505")
        val wrapped = RuntimeException("page", violation)
        assertTrue(wrapped.isRowFault())
        assertEquals("RuntimeException, SQLSTATE 23505", wrapped.describe())
        val failure = ProcessFailure("commit page", listOf("1/abc"), wrapped)
        assertFalse("@" in failure.message.orEmpty(), failure.message)
        assertTrue("SQLSTATE 23505" in failure.message.orEmpty() && "1/abc" in failure.message.orEmpty())
    }

    @Test
    fun `a normalizer throw and a raw decoder failure are row faults, a connection error and a cancellation-free outage are not`() {
        assertTrue(NormalizationFault(IllegalStateException("x@y.example")).isRowFault())
        val described = NormalizationFault(IllegalStateException("x@y.example")).describe()
        assertEquals("IllegalStateException", described, "the fault unwrapped, no message")
        assertTrue(RuntimeException("page", SerializationException("Unexpected JSON token at offset 3: a@b.example")).isRowFault())
        assertTrue(RuntimeException("page", NumberFormatException("For input string: \"a@b.example\"")).isRowFault())
        assertFalse(RuntimeException("page", R2dbcNonTransientResourceException("connection closed", "08006")).isRowFault())
        assertFalse(IllegalStateException("anything else").isRowFault())
        val state = R2dbcDataIntegrityViolationException("too long", "22001")
        assertTrue(state.isRowFault(), "SQLSTATE class 22 (data exception) is a row fault too")
    }
}
