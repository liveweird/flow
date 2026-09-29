package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps `.claude/docs/measures.md` honest: every "Pinned by" cell (the LAST column of each
 * measure table) may only name test classes that exist in this source set, and every quoted test
 * name fragment in it must appear in some test source here (`…` separates fragments of one quoted
 * name). A renamed or deleted test therefore breaks this test instead of leaving the contract
 * claiming a pin that no longer exists.
 */
class MeasureContractTest {
    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    @Test
    fun `every Pinned-by reference in measures_md names an existing test`() {
        val doc = locate(".claude/docs/measures.md", "../.claude/docs/measures.md").readText()
        val testDir = locate("src/test/kotlin", "server/src/test/kotlin")
        val sources = testDir.listFiles { file -> file.extension == "kt" }.orEmpty()
            .associate { it.nameWithoutExtension to it.readText() }
        val allSources = sources.values.joinToString("\n")

        val pinnedCells = doc.lines()
            .filter { it.startsWith("|") && !it.startsWith("|---") }
            .map { row -> row.trim().trim('|').split("|").last().trim() }
            .filterNot { it == "Pinned by" }
        assertTrue(pinnedCells.size > MIN_ROWS, "expected the measure tables, found ${pinnedCells.size} rows")

        val missing = pinnedCells.flatMap { cell ->
            val classes = CLASS_REF.findAll(cell).map { it.groupValues[1] }.filterNot { it in sources }
                .map { "class $it" }
            val fragments = QUOTED.findAll(cell).map { it.groupValues[1] }
                .flatMap { quoted -> quoted.split("…").map { it.trim(' ', '.') } }
                .filter { it.isNotEmpty() && it !in allSources }
                .map { "test name fragment \"$it\"" }
            (classes + fragments).toList()
        }
        assertTrue(missing.isEmpty(), "measures.md \"Pinned by\" names tests that do not exist: $missing")
    }

    private companion object {
        val CLASS_REF = Regex("`([A-Z][A-Za-z]*Test)`")
        val QUOTED = Regex("\"([^\"]+)\"")
        const val MIN_ROWS = 40
    }
}
