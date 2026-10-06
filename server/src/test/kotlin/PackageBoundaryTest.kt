package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-way package layering (CLAUDE.md "Package layout", `.claude/docs/ingestion.md` "The DERIVE job kind", checkup D5):
 * the connector side (`ingest`, `norm`, `jira`) never references `metrics` or `reports`, and `metrics` never references
 * `reports`. `metrics/` reaches the worker only by registering on `ingest/JobHandlers.kt`'s `JobHandlerRegistry`, so the
 * ingestion core stays connector- and metrics-agnostic. A plain source scan (no Docker, no classpath tricks): any non-comment
 * line of a file declaring the lower package that mentions an upper package by its qualified name (an import, a wildcard
 * import or an inline reference) is a violation.
 */
class PackageBoundaryTest {
    private val mainSources: List<File> by lazy {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun packageOf(text: String): String? =
        text.lineSequence().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()

    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** `line number: line` of every code line in [text] that names one of [forbidden] (`metrics`, `reports`, ...) by qualified package. */
    private fun forbiddenReferences(text: String, forbidden: List<String>): List<String> {
        val pattern = Regex("""\bch\.nokillswit\.(${forbidden.joinToString("|")})\b""")
        return text.lineSequence().withIndex()
            .filter { (_, line) -> !isComment(line) && pattern.containsMatchIn(line) }
            .map { (index, line) -> "${index + 1}: ${line.trim()}" }
            .toList()
    }

    private fun violations(lowerPackages: Set<String>, forbidden: List<String>): List<String> = mainSources.flatMap { file ->
        val text = file.readText()
        val declared = packageOf(text)
        if (declared !in lowerPackages) emptyList() else forbiddenReferences(text, forbidden).map { "${file.name} ($declared) $it" }
    }

    @Test
    fun `ingest, norm and jira never reference metrics or reports`() {
        val lower = setOf("ch.nokillswit.ingest", "ch.nokillswit.norm", "ch.nokillswit.jira")
        // The scan must actually see those packages, or an empty result proves nothing (a moved directory, a renamed package).
        for (pkg in lower) assertTrue(mainSources.any { packageOf(it.readText()) == pkg }, "no source declares $pkg")
        assertEquals(emptyList(), violations(lower, listOf("metrics", "reports")))
    }

    @Test
    fun `metrics never references reports`() {
        val lower = setOf("ch.nokillswit.metrics")
        assertTrue(mainSources.any { packageOf(it.readText()) in lower }, "no source declares ch.nokillswit.metrics")
        assertEquals(emptyList(), violations(lower, listOf("reports")))
    }

    @Test
    fun `the scan flags imports, wildcard imports and inline references but not comments`() {
        val source = """
            package ch.nokillswit.ingest

            import ch.nokillswit.metrics.MetricsStore
            import ch.nokillswit.reports.*
            // import ch.nokillswit.metrics.InComment
             * ch.nokillswit.reports.InKdoc
            val x = ch.nokillswit.metrics.Other()
            import ch.nokillswit.metricsx.NotThePackage
        """.trimIndent()
        assertEquals(
            listOf(
                "3: import ch.nokillswit.metrics.MetricsStore",
                "4: import ch.nokillswit.reports.*",
                "7: val x = ch.nokillswit.metrics.Other()",
            ),
            forbiddenReferences(source, listOf("metrics", "reports")),
        )
    }
}
