package ch.nokillswit

import ch.nokillswit.plugins.reporterIntervalSeconds
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `monitoring.reporterIntervalSeconds` (`plugins/Monitoring.kt`, env `METRICS_REPORT_INTERVAL_SECONDS`): `0` turns the
 * Dropwizard `Slf4jReporter` off, otherwise 30..3600 s; anything else refuses startup naming the key (the
 * `NumericConfigBoundsTest` pattern). The default ships as 300 in `application.yaml`.
 */
class MonitoringReporterConfigTest {

    private val key = "monitoring.reporterIntervalSeconds"

    private fun parsed(value: String) = reporterIntervalSeconds(MapApplicationConfig(key to value))

    @Test
    fun `zero turns the reporter off and the range edges are accepted`() {
        assertNull(parsed("0"))
        assertEquals(30, parsed("30"))
        assertEquals(300, parsed("300"))
        assertEquals(3600, parsed("3600"))
    }

    @Test
    fun `the shipped default is five minutes`() = testApplication {
        configureApp()
        startApplication()
        assertEquals(300, reporterIntervalSeconds(application.environment.config))
    }

    @Test
    fun `a zero interval boots without a reporter`() = testApplication {
        configureApp(key to "0")
        startApplication() // must not throw
        assertNull(reporterIntervalSeconds(application.environment.config))
    }

    @Test
    fun `a malformed or out-of-range interval refuses to start`() {
        listOf("1", "10", "29", "3601", "-1", "abc", "").forEach { value ->
            testApplication {
                configureApp(key to value)
                assertStartupFails("Config \"$key\"") { startApplication() }
            }
        }
    }
}
