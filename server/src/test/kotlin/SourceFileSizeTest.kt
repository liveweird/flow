package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Size guard for every Kotlin file under `server/src/main/kotlin`: no file may exceed [MAX_LINES] lines, so a split like
 * `norm/WorkItemStore.kt` (1028 lines -> a thin facade plus one collaborator per concern) does not regrow. Lines are
 * counted exactly like `wc -l` (the number of `\n` characters), so `wc -l <file>` is the number to put in [OVERSIZED].
 *
 * Detekt has no file-length rule (`LargeClass` measures one class, and `TooManyFunctions` is off on purpose — see
 * `config/detekt/detekt.yml`), hence this test.
 *
 * Policy: a NEW file stays at or under [MAX_LINES]. Today's oversized files are grandfathered in [OVERSIZED] with their
 * CURRENT size as the ceiling, and the ratchet is exact: a listed file that grew fails, a listed file that SHRANK fails
 * too ("lower the ceiling to N"), and one at or under [MAX_LINES] must be deleted from the list — so every ceiling always
 * equals the file's present size and can only go down. Raising a ceiling (or listing a new file) is a deliberate,
 * reviewed exception that the PR must justify; the default answer is to split the file by concern first.
 */
class SourceFileSizeTest {
    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("none of ${candidates.toList()} found from ${File(".").absolutePath}")

    private fun sizes(): Map<String, Int> {
        val root = locate("src/main/kotlin", "server/src/main/kotlin")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().count { b -> b == NEWLINE } }
    }

    @Test
    fun `no main source file exceeds the size threshold outside the grandfathered list`() {
        val sizes = sizes()
        assertTrue(sizes.size > MIN_FILES, "expected the server sources, found ${sizes.size} files")
        val tooBig = sizes.filter { (file, lines) -> lines > (OVERSIZED[file] ?: MAX_LINES) }
            .map { (file, lines) -> "$file has $lines lines (limit ${OVERSIZED[file] ?: MAX_LINES})" }
        assertTrue(tooBig.isEmpty(), "split these by concern instead of growing them: $tooBig")
    }

    @Test
    fun `every grandfathered ceiling equals its file's current size`() {
        val sizes = sizes()
        val stale = OVERSIZED.keys.filter { (sizes[it] ?: 0) <= MAX_LINES }
        assertTrue(stale.isEmpty(), "delete these entries from OVERSIZED (missing or now <= $MAX_LINES lines): $stale")
        val loose = OVERSIZED.filter { (file, ceiling) -> sizes.getValue(file) < ceiling }
            .map { (file, ceiling) -> "$file: lower the ceiling from $ceiling to ${sizes.getValue(file)}" }
        assertTrue(loose.isEmpty(), "the ratchet must tighten: $loose")
    }

    private companion object {
        const val MAX_LINES = 400
        const val MIN_FILES = 100
        const val NEWLINE = '\n'.code.toByte()

        /** Path under `server/src/main/kotlin` -> its line count when the guard was added (a ceiling, never a target). */
        val OVERSIZED = mapOf(
            "jira/JiraRawStore.kt" to 879,
            "metrics/MetricsStore.kt" to 811,
            "metrics/DeriveKernels.kt" to 790,
            "metrics/MetricsDeriver.kt" to 668,
            "reports/DeepDiveReport.kt" to 614,
            "auth/AuthRoutes.kt" to 519,
            "reports/ReportSupport.kt" to 504,
            "jira/JiraNormalizer.kt" to 470,
            "metrics/DeriveTaskRows.kt" to 439,
            "metrics/MetricsConfigService.kt" to 432,
            "ingest/DataSourceService.kt" to 421,
            "metrics/MetricsTables.kt" to 406,
        )
    }
}
