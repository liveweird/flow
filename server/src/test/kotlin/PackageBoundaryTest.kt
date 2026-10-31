package ch.nokillswit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one-way package layering (CLAUDE.md "Package layout", `.claude/docs/ingestion.md` "The DERIVE job kind", checkup D5):
 * the connector side (`ingest`, `norm`, `jira`, `gitlab`) never references `metrics` or `reports` (and `jira` and `gitlab` never
 * reference each other), and `metrics` never references
 * `reports`. `metrics/` reaches the worker only by registering on `ingest/JobHandlers.kt`'s `JobHandlerRegistry`, so the
 * ingestion core stays connector- and metrics-agnostic. A plain source scan (no Docker, no classpath tricks): any non-comment
 * line of a file declaring the lower package that mentions an upper package by its qualified name (an import, a wildcard
 * import or an inline reference) is a violation.
 *
 * The same rule holds from the bottom (checkup-3 D2-13/D9-6): everything under `infra/` is the foundation every feature
 * builds on, so it never references a feature package (`ingest`, `norm`, `jira`, `gitlab`, `metrics`, `reports`, `teams`, `flowusers`,
 * `auth`, `authz`). `audit` (the cross-cutting audit trail) and `plugins` (Ktor wiring: `respondProblem`) are not features and
 * stay open to `infra`. The composition root is the one documented exception ([infraCompositionRoot]): the DB/bootstrap
 * wiring MUST construct the feature services it publishes on `Application.attributes`, so exactly those two files may name
 * exactly those packages.
 *
 * `fetch/` is the hardened HTTP transport every connector shares (`SourceHttp`, the backoff, the call tally, the ceilings). It sits
 * between `infra` and the connectors: it may use `infra` (the outbound guard's `BlockedHostException`, `sha256Hex`) and nothing above
 * it, `ingest` included (a connector hands it everything it needs as plain parameters, and `ingest` already imports `jira`, so an
 * `ingest` import would close a cycle). The two connectors, `jira` and the later `gitlab`, never reference each other: each reaches
 * the shared machinery only through `fetch` and the registries of `ingest`.
 */
class PackageBoundaryTest {
    private val mainSources: List<File> by lazy {
        val root = File(System.getProperty("repo.root") ?: "..", "server/src/main/kotlin")
        assertTrue(root.isDirectory, "${root.absolutePath} not found")
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * Composition-root exceptions to "infra never references a feature": `infra/db/Database.kt` builds and publishes the feature
     * services (`ingest`, `teams`, `flowusers`, `auth`) and `infra/db/Bootstrap.kt` seeds the admin through `flowusers`/`auth`
     * and reads the `ingest` data-source service. Keyed by file name, valued by the feature packages that file may import;
     * a listed package the file no longer uses fails the test (a stale exception is dead permission).
     */
    private val infraCompositionRoot: Map<String, Set<String>> = mapOf(
        "Database.kt" to setOf("auth", "ingest", "teams", "flowusers"),
        "Bootstrap.kt" to setOf("auth", "ingest", "flowusers"),
    )

    private val featurePackages = listOf(
        "ingest", "norm", "fetch", "jira", "gitlab", "metrics", "reports", "teams", "flowusers", "auth", "authz", "telemetry",
        "identities",
    )

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
    fun `ingest, norm, fetch, jira and gitlab never reference metrics or reports`() {
        val lower = setOf(
            "ch.nokillswit.ingest", "ch.nokillswit.norm", "ch.nokillswit.fetch", "ch.nokillswit.jira", "ch.nokillswit.gitlab",
        )
        // The scan must actually see those packages, or an empty result proves nothing (a moved directory, a renamed package).
        for (pkg in lower) assertTrue(mainSources.any { packageOf(it.readText()) == pkg }, "no source declares $pkg")
        assertEquals(emptyList(), violations(lower, listOf("metrics", "reports")))
    }

    @Test
    fun `fetch references no connector and no feature package`() {
        val lower = setOf("ch.nokillswit.fetch")
        // The scan must actually see the package, or an empty result proves nothing (a moved directory, a renamed package).
        assertTrue(mainSources.any { packageOf(it.readText()) in lower }, "no source declares ch.nokillswit.fetch")
        assertEquals(emptyList(), violations(lower, featurePackages - "fetch"))
    }

    @Test
    fun `jira and gitlab never reference each other`() {
        // Each connector reaches the other's world only through ingest/ (the registry) and the shared infra; the scan must see both.
        for (pkg in listOf("ch.nokillswit.jira", "ch.nokillswit.gitlab")) {
            assertTrue(mainSources.any { packageOf(it.readText()) == pkg }, "no source declares $pkg")
        }
        assertEquals(emptyList(), violations(setOf("ch.nokillswit.jira"), listOf("gitlab")))
        assertEquals(emptyList(), violations(setOf("ch.nokillswit.gitlab"), listOf("jira")))
    }

    @Test
    fun `norm never references jira or gitlab`() {
        // The normalized layer is connector-agnostic: a connector writes into it, never the other way round (`norm/` imports `infra/` and
        // `ingest/` only). The scan must see the package, or an empty result proves nothing.
        val lower = setOf("ch.nokillswit.norm")
        assertTrue(mainSources.any { packageOf(it.readText()) in lower }, "no source declares ch.nokillswit.norm")
        assertEquals(emptyList(), violations(lower, listOf("jira", "gitlab")))
    }

    @Test
    fun `identities references only infra, norm, ingest, authz, audit and plugins`() {
        // The identity mapping sits above the connectors and `norm/` and BELOW `metrics/`/`reports/` (which will read the resolver): it
        // must not reach up, sideways into the other features, or into a connector. The scan must see the package.
        val lower = setOf("ch.nokillswit.identities")
        assertTrue(mainSources.any { packageOf(it.readText()) in lower }, "no source declares ch.nokillswit.identities")
        val forbidden = listOf("metrics", "reports", "jira", "gitlab", "fetch", "teams", "flowusers", "auth", "telemetry")
        assertEquals(emptyList(), violations(lower, forbidden))
    }

    @Test
    fun `no connector or norm package references identities`() {
        val lower = setOf(
            "ch.nokillswit.ingest", "ch.nokillswit.norm", "ch.nokillswit.fetch", "ch.nokillswit.jira", "ch.nokillswit.gitlab",
        )
        for (pkg in lower) assertTrue(mainSources.any { packageOf(it.readText()) == pkg }, "no source declares $pkg")
        assertEquals(emptyList(), violations(lower, listOf("identities")))
    }

    @Test
    fun `metrics never references reports`() {
        val lower = setOf("ch.nokillswit.metrics")
        assertTrue(mainSources.any { packageOf(it.readText()) in lower }, "no source declares ch.nokillswit.metrics")
        assertEquals(emptyList(), violations(lower, listOf("reports")))
    }

    @Test
    fun `no package outside telemetry references telemetry`() {
        // telemetry/ is the top layer (an observer of every lower one): it reaches `plugins` and `infra/db` through attribute
        // keys, so those seams never name it. The scan must see the package, or an empty result proves nothing.
        assertTrue(mainSources.any { packageOf(it.readText()) == "ch.nokillswit.telemetry" }, "no source declares ch.nokillswit.telemetry")
        val violations = mainSources.flatMap { file ->
            val text = file.readText()
            if (packageOf(text) == "ch.nokillswit.telemetry") {
                emptyList()
            } else {
                forbiddenReferences(text, listOf("telemetry")).map { "${file.name} $it" }
            }
        }
        assertEquals(emptyList(), violations)
    }

    private fun infraSources(): List<Pair<File, String>> = mainSources.mapNotNull { file ->
        val text = file.readText()
        if (packageOf(text)?.startsWith("ch.nokillswit.infra") == true) file to text else null
    }

    @Test
    fun `infra never references a feature package outside the composition root`() {
        val infra = infraSources()
        assertTrue(infra.size > 20, "the scan saw only ${infra.size} infra sources")
        val violations = infra.flatMap { (file, text) ->
            val allowed = infraCompositionRoot[file.name].orEmpty()
            forbiddenReferences(text, featurePackages - allowed).map { "${file.name} $it" }
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `every composition-root exception is still used`() {
        val infra = infraSources().associate { (file, text) -> file.name to text }
        for ((name, allowed) in infraCompositionRoot) {
            val text = infra[name] ?: error("composition-root file $name is not under infra/")
            for (pkg in allowed) {
                assertTrue(forbiddenReferences(text, listOf(pkg)).isNotEmpty(), "$name no longer references $pkg: drop the exception")
            }
        }
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
