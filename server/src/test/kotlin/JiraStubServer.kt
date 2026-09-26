package ch.nokillswit

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import java.io.File

/**
 * The shared in-JVM WireMock fixture over `sample-data/jira-stub` (v0.2.0 plan §11): the SAME
 * mappings/`__files` the compose `jira-stub` service serves (`sample-data/README.md`), so the
 * server test suite exercises the client against the exact generator-fixed contract rather than a
 * second, hand-rolled fixture that could drift from it. Lazily started once and SHARED across the
 * whole suite, like [PostgresTestSupport].
 */
object JiraStubServer {
    /** The generator's fixed `cloudId` (`sample-data/README.md`). */
    const val CLOUD_ID = "b3f1a2c4-5d6e-4f7a-8b9c-0d1e2f3a4b5c"

    private val stubRoot: File by lazy {
        listOf(File("sample-data/jira-stub"), File("../sample-data/jira-stub"))
            .firstOrNull { it.isDirectory }
            ?: error("sample-data/jira-stub not found from ${File(".").absolutePath}")
    }

    private val server: WireMockServer by lazy {
        WireMockServer(
            WireMockConfiguration.options()
                .usingFilesUnderDirectory(stubRoot.absolutePath)
                .dynamicPort(),
        ).apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    private val client: WireMock by lazy { WireMock(server.port()) }

    /** Starts the server (idempotent — a `by lazy` singleton) and returns its base URL. */
    fun start(): String {
        server.isRunning
        return baseUrl
    }

    val baseUrl: String get() = server.baseUrl()

    /** Resets every scenario back to `Started` — call between tests that touch `day2`. */
    fun resetScenarios() = client.resetScenarios()

    fun setScenarioState(scenarioName: String, state: String) = client.setSingleScenarioState(scenarioName, state)

    /** Registers a one-off override mapping (e.g. forcing a 403/401 on one path) and returns it for later removal. */
    fun addOverride(stub: com.github.tomakehurst.wiremock.client.MappingBuilder): com.github.tomakehurst.wiremock.stubbing.StubMapping =
        client.register(stub)

    fun removeOverride(mapping: com.github.tomakehurst.wiremock.stubbing.StubMapping) = client.removeStubMapping(mapping)
}
