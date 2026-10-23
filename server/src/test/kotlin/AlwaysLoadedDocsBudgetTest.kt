package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the always-loaded instruction set inside its size budget. `CLAUDE.md` imports `.claude/docs/testing.md` and
 * `.claude/docs/list-endpoints.md` with `@`, so those three files are in every session's context; together they must stay
 * under [BUDGET_BYTES] bytes (the promise `CLAUDE.md` makes in its "Cross-cutting conventions" section).
 */
class AlwaysLoadedDocsBudgetTest {
    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    @Test
    fun `CLAUDE_md plus its two imported docs stay under the always-loaded byte budget`() {
        val files = listOf(
            locate("CLAUDE.md", "../CLAUDE.md"),
            locate(".claude/docs/testing.md", "../.claude/docs/testing.md"),
            locate(".claude/docs/list-endpoints.md", "../.claude/docs/list-endpoints.md"),
        )
        val sizes = files.associate { it.name to it.length() }
        val total = sizes.values.sum()
        assertTrue(
            total < BUDGET_BYTES,
            "the always-loaded set is $total bytes ($sizes), the budget is $BUDGET_BYTES. Measure with " +
                "`wc -c CLAUDE.md .claude/docs/testing.md .claude/docs/list-endpoints.md`. Detail belongs in an " +
                "on-demand doc under .claude/docs/ (read on demand, not imported), leaving a one-line pointer here.",
        )
    }

    private companion object {
        const val BUDGET_BYTES = 36_000
    }
}
