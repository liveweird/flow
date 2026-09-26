package ch.nokillswit

import ch.nokillswit.infra.catchingFailures
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatchingFailuresTest {
    @Test
    fun `an ordinary failure goes to onFailure, a success returns the block's value`() {
        assertEquals("ok", catchingFailures({ "ok" }) { "failed" })
        assertEquals("failed: boom", catchingFailures({ error("boom") }) { "failed: ${it.message}" })
    }

    @Test
    fun `cancellation and JVM errors propagate untouched`() {
        assertFailsWith<CancellationException> {
            catchingFailures<Unit>({ throw CancellationException("gone") }) { }
        }
        assertFailsWith<StackOverflowError> {
            catchingFailures<Unit>({ throw StackOverflowError() }) { }
        }
    }
}
