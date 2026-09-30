package ch.nokillswit

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One (HTTP method, spec path template, response status) the suite produced. */
data class ExercisedHit(val method: String, val path: String, val status: Int)

/** One declared spec operation and the response keys (`"200"`, `"default"`, …) it lists. */
data class DeclaredOperation(val method: String, val path: String, val operationId: String?, val statuses: List<String>)

/**
 * The pure half of the OpenAPI coverage gate, kept free of the spec parser so [OpenApiCoverageMergeTest]
 * can drive it with tiny fixtures. With parallel test forks each JVM records only what IT exercised, so
 * coverage is computed on the UNION of every fork's hits — equivalent to intersecting the forks' gap sets
 * (a pair exercised by any fork is covered) — and never on one fork's view.
 */
object OpenApiCoverageMerge {
    /**
     * Statuses a SHARED plugin or interceptor produces for every operation alike, each pinned once by
     * its own test rather than per route: `400` (a malformed id or body — ErrorHandling's negative-segment
     * intercept and the converter vocabulary; `PayloadValidationTest`), `401` (the JWT challenge —
     * `AnonymousAccessTest` sweeps it), `413` (`RequestBodyLimit` — `PayloadValidationTest`), `429` (the
     * per-IP buckets — `RateLimitResponseTest` + one case per bucket), `500`/`default` (the catch-all —
     * no honest way to force one through the public API).
     */
    val CROSS_CUTTING_STATUSES = setOf("400", "401", "413", "415", "429", "500", "default")

    /** `METHOD path status` — the path may itself contain spaces (`(no template: …)`), so split at the ends. */
    fun encode(hit: ExercisedHit): String = "${hit.method} ${hit.path} ${hit.status}"

    fun decode(line: String): ExercisedHit = ExercisedHit(
        line.substringBefore(' '),
        line.substringAfter(' ').substringBeforeLast(' '),
        line.substringAfterLast(' ').toInt(),
    )

    /** The union of every fork's hits; a fork's blank lines (or a fork that wrote nothing) add nothing. */
    fun union(perFork: Collection<Collection<String>>): Set<ExercisedHit> =
        perFork.flatMapTo(linkedSetOf()) { lines -> lines.filter { it.isNotBlank() }.map(::decode) }

    /**
     * Every declared (operation, status) pair no fork produced, minus [CROSS_CUTTING_STATUSES]. The GATE's
     * input: a new operation needs a test per declared feature status (2xx/3xx, 403, 404, 409, 502, 503 …),
     * or its status list trimmed to what the route can actually answer.
     */
    fun gaps(declared: List<DeclaredOperation>, exercised: Set<ExercisedHit>): List<String> = declared.flatMap { op ->
        val hits = exercised.filter { it.method == op.method && it.path == op.path }.map { it.status.toString() }.toSet()
        op.statuses
            .filterNot { it in CROSS_CUTTING_STATUSES }
            .filterNot { it in hits }
            .map { "${op.method} ${op.path} $it (${op.operationId})" }
    }

    /** The human-readable `coverage.md` over the union — a report; [gaps] is the gate. */
    fun report(declared: List<DeclaredOperation>, exercised: Set<ExercisedHit>): List<String> {
        val lines = mutableListOf("# OpenAPI conformance coverage", "")
        var operationsHit = 0
        var pairs = 0
        var pairsHit = 0
        declared.forEach { op ->
            val hits = exercised.filter { it.method == op.method && it.path == op.path }.map { it.status }
            if (hits.isNotEmpty()) operationsHit++
            lines += "### ${op.method} ${op.path} — ${op.operationId ?: "(no operationId)"}"
            op.statuses.forEach { statusKey ->
                pairs++
                val hit = hits.any { it.toString() == statusKey }
                if (hit) pairsHit++
                lines += "- [${if (hit) "x" else " "}] $statusKey"
            }
            val undeclared = hits.filter { h -> op.statuses.none { it == h.toString() } && "default" !in op.statuses }
            if (undeclared.isNotEmpty()) lines += "- ⚠ exercised but undeclared: ${undeclared.sorted().joinToString()}"
            lines += ""
        }
        val unresolved = exercised.filter { it.path.startsWith("(no template") }
        if (unresolved.isNotEmpty()) {
            lines += "## Requests matching no spec path"
            unresolved.sortedBy { it.path }.forEach { lines += "- ${it.method} ${it.path} -> ${it.status}" }
            lines += ""
        }
        lines.add(2, "")
        lines.add(2, "$operationsHit of ${declared.size} operations exercised; $pairsHit of $pairs declared (operation, status) pairs.")
        return lines
    }

    /**
     * Writes THIS fork's `exercised-<forkId>.txt` (pid plus a random suffix, so a recycled pid can never
     * overwrite another fork's file), then merges every fork's file in [dir] into `coverage.md` +
     * `gaps.txt` — all under an exclusive lock on `.merge.lock`, so concurrent fork exits serialize. The last
     * fork to exit sees every file, so the final `gaps.txt` is complete; an earlier one leaves a superset.
     */
    fun publish(dir: Path, forkId: String, exercised: Set<ExercisedHit>, declared: List<DeclaredOperation>) {
        Files.createDirectories(dir)
        FileChannel.open(dir.resolve(".merge.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                Files.write(dir.resolve("exercised-$forkId.txt"), exercised.map(::encode).sorted())
                mergeInto(dir, declared)
            }
        }
    }

    /** Merge every `exercised-*.txt` in [dir] (no lock — callers hold it) into `coverage.md` and `gaps.txt`. */
    fun mergeInto(dir: Path, declared: List<DeclaredOperation>) {
        val perFork = Files.list(dir).use { files ->
            files.filter { it.fileName.toString().let { n -> n.startsWith("exercised-") && n.endsWith(".txt") } }
                .map { Files.readAllLines(it) }.toList()
        }
        val exercised = union(perFork)
        Files.write(dir.resolve("coverage.md"), report(declared, exercised))
        Files.write(dir.resolve("gaps.txt"), gaps(declared, exercised))
    }
}
