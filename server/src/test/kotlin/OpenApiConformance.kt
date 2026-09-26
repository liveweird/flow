package ch.nokillswit

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.SimpleValidationReportFormat
import com.atlassian.oai.validator.report.ValidationReport
import io.ktor.client.call.save
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.bodyAsText
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime OpenAPI conformance: the [OpenApiConformance] client plugin (installed by
 * [flowTestClientDefaults], i.e. in every `jsonClient()`/`authedClient()`) validates each
 * `/api/` interaction the test suite produces against `openapi/documentation.yaml` and fails the
 * exercising test on drift — undeclared endpoints/methods/status codes, response bodies that
 * don't match their declared schema, wrong content types.
 *
 * Scope decisions:
 *  - **Response side is fully validated.** Request-side validation is ignored except the two
 *    routing signals ([LevelResolver] below): a request hitting a path/method not in the spec is
 *    an error (catches undocumented endpoints), but deliberately malformed payloads and missing
 *    bearer tokens — which many tests send to assert 400/401 behavior — are not flagged. The
 *    error *responses* those probes provoke are still validated against `ProblemDetail`.
 *  - Non-`/api/` paths (`/openapi`, the SPA) are skipped.
 *  - Mode switch: `-Dopenapi.conformance=fail|warn|off` (default `fail`; `warn` prints instead of
 *    throwing — for one-shot drift triage only).
 *
 * Known blind spots (documented, accepted):
 *  - Tests using `testApplication`'s default `client` (not the shared factories) bypass the plugin.
 *  - `autoHeadResponse` answers HEAD on GET routes but the spec declares no HEAD operations; no
 *    test sends HEAD today — one that does through a validated client will (correctly) trip
 *    `validation.request.operation.notAllowed`.
 *
 * The spec is fed to the validator relabeled as OpenAPI 3.0.3 (in memory only — the committed
 * file stays 3.1 for `web`'s openapi-typescript): swagger-request-validator's 3.1 handling is
 * unreliable (draft-04 schema engine; 3.1 parse mode can drop `nullable:`), and the document uses
 * only 3.0-style constructs — pinned by `OpenApiSpecTest`'s 3.0-compat guard.
 */
object OpenApiSpec {
    /** The committed spec text, 3.1-labeled — what `OpenApiSpecTest`'s guards assert on. */
    val rawYaml: String by lazy {
        checkNotNull(OpenApiSpec::class.java.classLoader.getResource("openapi/documentation.yaml")) {
            "openapi/documentation.yaml not on the test classpath"
        }.readText()
    }

    /** The in-memory 3.0.3 relabel fed to the validator and the parser (see class KDoc). */
    val validatorYaml: String by lazy {
        rawYaml.replaceFirst("openapi: 3.1.0", "openapi: 3.0.3")
            .also { check(it != rawYaml) { "expected 'openapi: 3.1.0' as the spec's version line" } }
    }

    /** Parsed swagger model — drives the coverage report and the static sanity tests. */
    val parsed: OpenAPI by lazy {
        val result = OpenAPIV3Parser().readContents(validatorYaml, null, ParseOptions().apply { isResolve = true })
        checkNotNull(result.openAPI) { "spec failed to parse: ${result.messages}" }
    }

    val validator: OpenApiInteractionValidator by lazy {
        OpenApiInteractionValidator.createForInlineApiSpecification(validatorYaml)
            .withLevelResolver(
                LevelResolver.create()
                    // Ignore request-side validation (payload/parameter/security) — tests probe
                    // error paths with deliberately invalid requests...
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    // ...but an /api/ request outside the spec is exactly the drift we hunt.
                    .withLevel("validation.request.path.missing", ValidationReport.Level.ERROR)
                    .withLevel("validation.request.operation.notAllowed", ValidationReport.Level.ERROR)
                    .withLevel("validation.response", ValidationReport.Level.ERROR)
                    .build()
            )
            // Whitelist deliberately absent: fix drift in the spec or the code, don't suppress it.
            // If an entry ever becomes unavoidable, add `.withWhitelist(...)` with a justification.
            .build()
    }
}

/**
 * Ktor client plugin validating every `/api/` request/response pair against [OpenApiSpec].
 * Installed once per test client via [flowTestClientDefaults]. Throws [AssertionError] from
 * inside the exercising test on a conformance violation (see [OpenApiSpec] KDoc for modes/scope).
 */
val OpenApiConformance = createClientPlugin("OpenApiConformance") {
    on(Send) { request ->
        val call = proceed(request)
        val path = call.request.url.encodedPath
        if (!path.startsWith("/api/")) return@on call

        val saved = call.save() // buffer the body so the test can still read it after us
        val bodyText = saved.response.bodyAsText()

        val simpleRequest = SimpleRequest.Builder(call.request.method.value, path).apply {
            call.request.url.parameters.forEach { name, values -> values.forEach { withQueryParam(name, it) } }
            call.request.headers.forEach { name, values -> values.forEach { withHeader(name, it) } }
            // No request body on purpose: request-side validation is ignored (see LevelResolver).
        }.build()
        val simpleResponse = SimpleResponse.Builder(saved.response.status.value).apply {
            saved.response.headers.forEach { name, values -> values.forEach { withHeader(name, it) } }
            if (bodyText.isNotEmpty()) withBody(bodyText)
        }.build()

        val report = OpenApiSpec.validator.validate(simpleRequest, simpleResponse)
        // Record before any throw so failing interactions still show in the coverage report.
        OpenApiCoverage.record(call.request.method.value, path, saved.response.status.value)

        if (report.hasErrors()) {
            val message = "OpenAPI conformance violation: ${call.request.method.value} $path -> " +
                "${saved.response.status.value}\n" +
                SimpleValidationReportFormat.getInstance().apply(report) +
                "\nresponse body: $bodyText"
            when (System.getProperty("openapi.conformance", "fail")) {
                "off" -> {}
                "warn" -> System.err.println(message)
                else -> throw AssertionError(message)
            }
        }
        saved
    }
}

/**
 * Records the (method, path-template, status) triples the suite exercised and writes
 * `build/reports/openapi-conformance/coverage.md` at JVM exit — a report, not a gate.
 * Single test JVM today (Gradle default forks); with parallel forks the last one wins.
 */
object OpenApiCoverage {
    private data class Template(val path: String, val regex: Regex, val paramCount: Int)

    /** Spec path templates, most-literal-first so `/teams/members` beats `/teams/{id}`. */
    private val templates: List<Template> by lazy {
        OpenApiSpec.parsed.paths.keys.map { template ->
            val paramCount = Regex("""\{[^}]+}""").findAll(template).count()
            val pattern = template.split("/").joinToString("/") { segment ->
                if (segment.startsWith("{") && segment.endsWith("}")) "[^/]+" else Regex.escape(segment)
            }
            Template(template, Regex("^$pattern$"), paramCount)
        }.sortedWith(compareBy({ it.paramCount }, { -it.path.length }))
    }

    private val exercised = ConcurrentHashMap.newKeySet<Triple<String, String, Int>>()

    /**
     * Statuses a SHARED plugin or interceptor produces for every operation alike, each pinned once by
     * its own test rather than per route: `400` (a malformed id or body — ErrorHandling's negative-segment
     * intercept and the converter vocabulary; `PayloadValidationTest`), `401` (the JWT challenge —
     * `AnonymousAccessTest` sweeps it), `413` (`RequestBodyLimit` — `PayloadValidationTest`), `429` (the
     * per-IP buckets — `RateLimitResponseTest` + one case per bucket), `500`/`default` (the catch-all —
     * no honest way to force one through the public API).
     */
    private val CROSS_CUTTING_STATUSES = setOf("400", "401", "413", "415", "429", "500", "default")

    fun record(method: String, path: String, status: Int) {
        val template = templates.firstOrNull { it.regex.matches(path) }?.path ?: "(no template: $path)"
        exercised.add(Triple(method.uppercase(), template, status))
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread { writeReport() })
    }

    private fun writeReport() {
        if (exercised.isEmpty()) return
        val lines = mutableListOf("# OpenAPI conformance coverage", "")
        var operations = 0
        var operationsHit = 0
        var pairs = 0
        var pairsHit = 0
        OpenApiSpec.parsed.paths.forEach { (path, item) ->
            item.readOperationsMap().forEach { (method, operation) ->
                operations++
                val declared = operation.responses.keys
                val hits = exercised.filter { it.first == method.name && it.second == path }.map { it.third }
                if (hits.isNotEmpty()) operationsHit++
                lines += "### ${method.name} $path — ${operation.operationId ?: "(no operationId)"}"
                declared.forEach { statusKey ->
                    pairs++
                    val hit = hits.any { it.toString() == statusKey }
                    if (hit) pairsHit++
                    lines += "- [${if (hit) "x" else " "}] $statusKey"
                }
                val undeclared = hits.filter { h -> declared.none { it == h.toString() } && "default" !in declared }
                if (undeclared.isNotEmpty()) lines += "- ⚠ exercised but undeclared: ${undeclared.sorted().joinToString()}"
                lines += ""
            }
        }
        val unresolved = exercised.filter { it.second.startsWith("(no template") }
        if (unresolved.isNotEmpty()) {
            lines += "## Requests matching no spec path"
            unresolved.sortedBy { it.second }.forEach { lines += "- ${it.first} ${it.second} -> ${it.third}" }
            lines += ""
        }
        lines.add(2, "")
        lines.add(2, "$operationsHit of $operations operations exercised; $pairsHit of $pairs declared (operation, status) pairs.")
        val target = Paths.get("build/reports/openapi-conformance/coverage.md")
        Files.createDirectories(target.parent)
        Files.write(target, lines)
        Files.write(target.resolveSibling("gaps.txt"), gaps())
    }

    /**
     * The GATE's input (server/build.gradle.kts fails the full-suite `test` task on a non-empty file):
     * every declared (operation, status) pair the suite never produced, minus [CROSS_CUTTING_STATUSES].
     * A new operation therefore needs a test per declared feature status (2xx/3xx, 403, 404, 409, 502,
     * 503 …), or its status list trimmed to what the route can actually answer.
     */
    private fun gaps(): List<String> = OpenApiSpec.parsed.paths.flatMap { (path, item) ->
        item.readOperationsMap().flatMap { (method, operation) ->
            val hits = exercised.filter { it.first == method.name && it.second == path }.map { it.third.toString() }.toSet()
            operation.responses.keys
                .filterNot { it in CROSS_CUTTING_STATUSES }
                .filterNot { it in hits }
                .map { "${method.name} $path $it (${operation.operationId})" }
        }
    }
}
