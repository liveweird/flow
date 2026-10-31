package ch.nokillswit

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import java.io.File

/**
 * The shared in-JVM WireMock fixture over `sample-data/gitlab-stub` (gitlab-integration.md "Stub"): the SAME
 * mappings/`__files` the compose `gitlab-stub` service serves (`sample-data/README.md` "GitLab stub"), so the
 * GitLab connector's tests run against the exact generator-fixed contract. Lazily started once and SHARED across the
 * whole suite, like [JiraStubServer].
 */
object GitLabStubServer {
    /** The synthetic instance's host name (what the stub's own absolute URLs in bodies carry). */
    const val HOST = "gitlab.flow-sample.example"

    /** The WireMock scenario the day-2 deltas hang off: `Started` -> `day2`. */
    const val SCENARIO = "gitlab-day2"

    /** The scenario state after one simulated day (`sample-data/gitlab/expected.json` `day2`). */
    const val DAY2 = "day2"

    private val stubRoot: File by lazy {
        listOf(File("sample-data/gitlab-stub"), File("../sample-data/gitlab-stub"))
            .firstOrNull { it.isDirectory }
            ?: error("sample-data/gitlab-stub not found from ${File(".").absolutePath}")
    }

    private val server: WireMockServer by lazy {
        WireMockServer(
            WireMockConfiguration.options()
                .usingFilesUnderDirectory(stubRoot.absolutePath)
                .dynamicPort()
                // No test reads the request journal, and WireMock keeps it unbounded by default (every served request WITH its
                // response body, for the suite's lifetime in this shared singleton): see JiraStubServer.
                .disableRequestJournal(),
        ).apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    private val client: WireMock by lazy { WireMock(server.port()) }

    /** Starts the server (idempotent - a `by lazy` singleton) and returns its base URL. */
    fun start(): String {
        server.isRunning
        return baseUrl
    }

    val baseUrl: String get() = server.baseUrl()

    /** Resets every scenario back to `Started` - call between tests that touch `day2`. */
    fun resetScenarios() = client.resetScenarios()

    fun setScenarioState(scenarioName: String, state: String) = client.setSingleScenarioState(scenarioName, state)

    /** Registers a one-off override mapping (e.g. forcing a 403/429 on one path) and returns it for later removal. */
    fun addOverride(stub: MappingBuilder): StubMapping = client.register(stub)

    fun removeOverride(mapping: StubMapping) = client.removeStubMapping(mapping)
}
