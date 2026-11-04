package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.fetch.SkipBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `SkipBudget` (lifted from Jira to `fetch/`): the per-run oversized-skip fail-safe, parameterised by the connector's source. */
class SkipBudgetTest {

    private val other = FetchSource("Other", "other.maxResponseBytes")

    @Test
    fun `the skip budget allows ten skips, then a tenth of the ids attempted`() {
        val small = SkipBudget(other, "CHANGELOGS", "issues")
        repeat(10) { small.skip() }
        val failure = assertFailsWith<FetchException> { small.skip() }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("CHANGELOGS skipped 11 of 11 issues" in failure.message.orEmpty())

        val large = SkipBudget(other, "WORKLOGS", "issues")
        large.settled(200)
        repeat(22) { large.skip() } // 22 of 222 is under a tenth (22.2): still fine
        val over = assertFailsWith<FetchException> { large.skip() } // 23 of 223 is over the 22 allowed
        assertTrue("WORKLOGS skipped 23 of 223 issues" in over.message.orEmpty(), over.message)
    }

    @Test
    fun `the failure names the source and its response-size setting`() {
        val budget = SkipBudget(other, "COMMIT_FILES", "commits")
        repeat(10) { budget.skip() }
        val failure = assertFailsWith<FetchException> { budget.skip() }
        val message = failure.message.orEmpty()
        assertTrue(message.startsWith("Other fetch failed"), message)
        assertTrue("over other.maxResponseBytes" in message, message)
    }

    @Test
    fun `settled ids count once and raise the allowance with the default of one`() {
        val budget = SkipBudget(other, "WORKLOGS", "issues")
        repeat(100) { budget.settled() } // the default count is one id
        repeat(11) { budget.skip() } // 11 of 111 attempted: over 10 but not over a tenth (11.1)
        val over = assertFailsWith<FetchException> { budget.skip() } // 12 of 112 is over the 11 allowed
        assertTrue("WORKLOGS skipped 12 of 112 issues" in over.message.orEmpty(), over.message)
    }
}
