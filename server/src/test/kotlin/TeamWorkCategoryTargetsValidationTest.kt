package ch.nokillswit

import ch.nokillswit.metrics.MAX_WORK_CATEGORY_TARGETS
import ch.nokillswit.metrics.TeamWorkCategoryTarget
import ch.nokillswit.metrics.TeamWorkCategoryTargetsRequest
import ch.nokillswit.metrics.sanitizedTeamWorkCategoryTargets
import ch.nokillswit.metrics.validateTeamWorkCategoryTargets
import io.ktor.server.plugins.BadRequestException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pure shape rules of a work-category target set, one at a time: every rejected body here breaks exactly ONE rule (the others
 * hold), and the message names it, so no rule can hide behind another. Which keys a team may choose needs the database and is
 * `TeamWorkCategoryTargetsTest`'s.
 */
class TeamWorkCategoryTargetsValidationTest {

    private fun set(vararg pairs: Pair<String, Int>) =
        TeamWorkCategoryTargetsRequest(pairs.map { TeamWorkCategoryTarget(it.first, it.second) })

    /** [count] distinct keys, in range, summing to 100 (the first takes it all): only the count can be wrong. */
    private fun many(count: Int) =
        TeamWorkCategoryTargetsRequest(List(count) { TeamWorkCategoryTarget("K$it", if (it == 0) 100 else 0) })

    private fun rejects(expectedPart: String, request: TeamWorkCategoryTargetsRequest) {
        val failure = assertFailsWith<BadRequestException> { validateTeamWorkCategoryTargets(request) }
        assertContains(failure.message.orEmpty(), expectedPart)
    }

    @Test
    fun `valid sets pass - empty, one, zero rows, and the count and key length bounds`() {
        validateTeamWorkCategoryTargets(set())
        validateTeamWorkCategoryTargets(set("A" to 100))
        validateTeamWorkCategoryTargets(set("A" to 100, "B" to 0))
        validateTeamWorkCategoryTargets(set("A" to 40, "B" to 35, "C" to 25))
        validateTeamWorkCategoryTargets(set("K".repeat(100) to 100))
        validateTeamWorkCategoryTargets(many(MAX_WORK_CATEGORY_TARGETS))
    }

    @Test
    fun `more than 100 targets are rejected on the count alone`() {
        rejects("At most", many(MAX_WORK_CATEGORY_TARGETS + 1))
    }

    @Test
    fun `a blank key and an over-long key are rejected on the key alone`() {
        rejects("key must be", set("  " to 100))
        rejects("key must be", set("" to 100))
        rejects("key must be", set("K".repeat(101) to 100))
    }

    @Test
    fun `a percent outside 0 to 100 is rejected on the percent alone`() {
        rejects("targetPercent", set("A" to 101))
        rejects("targetPercent", set("A" to -1))
        rejects("targetPercent", set("A" to 100, "B" to 1, "C" to -1)) // sums to 100: only the range is wrong
    }

    @Test
    fun `a duplicate key is rejected on the duplicate alone`() {
        rejects("Duplicate work category: A", set("A" to 50, "A" to 50)) // sums to 100
    }

    @Test
    fun `a non-empty set must sum to exactly 100`() {
        rejects("add up to exactly 100 percent (got 99)", set("A" to 60, "B" to 39))
        rejects("add up to exactly 100 percent (got 101)", set("A" to 60, "B" to 41))
        rejects("add up to exactly 100 percent (got 0)", set("A" to 0))
    }

    @Test
    fun `sanitizing trims keys and a control character is a 400`() {
        assertEquals("A", sanitizedTeamWorkCategoryTargets(set("  A " to 100)).targets.single().key)
        assertFailsWith<BadRequestException> { sanitizedTeamWorkCategoryTargets(set("A\u0001" to 100)) }
    }
}
