package ch.nokillswit

import ch.nokillswit.telemetry.readTelemetryConfig
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `telemetry.*` (`application.yaml`, `telemetry/TelemetryConfig.kt`, `plugins/Monitoring.kt`): shipped defaults, the inclusive
 * range edges, and a boot refused for any value outside them - validated even when the sampler is off.
 */
class TelemetryConfigTest {
    private fun parsed(vararg overrides: Pair<String, String>) =
        readTelemetryConfig(ApplicationConfig("application.yaml").mergeWith(MapApplicationConfig(*overrides)))

    @Test
    fun `the shipped defaults`() {
        val config = parsed()
        assertTrue(config.enabled)
        assertEquals(300, config.sampleIntervalSeconds)
        assertEquals(3600, config.storageIntervalSeconds)
        assertEquals(21_600, config.connectionEstimateIntervalSeconds)
        assertEquals(60, config.statementTimeoutSeconds)
    }

    @Test
    fun `the range edges are accepted`() {
        assertEquals(60, parsed("telemetry.sampleIntervalSeconds" to "60").sampleIntervalSeconds)
        assertEquals(15, parsed("telemetry.sampleIntervalSeconds" to "60").statementTimeoutSeconds)
        assertEquals(1800, parsed("telemetry.sampleIntervalSeconds" to "1800").sampleIntervalSeconds)
        assertEquals(60, parsed("telemetry.sampleIntervalSeconds" to "1800").statementTimeoutSeconds)
        for (divisor in listOf(60, 90, 120, 180, 300, 600, 900, 1200, 1800)) {
            assertEquals(divisor, parsed("telemetry.sampleIntervalSeconds" to "$divisor").sampleIntervalSeconds)
        }
        assertEquals(900, parsed("telemetry.storageIntervalSeconds" to "900").storageIntervalSeconds)
        assertEquals(86_400, parsed("telemetry.storageIntervalSeconds" to "86400").storageIntervalSeconds)
        assertEquals(3_600, parsed("telemetry.connectionEstimateIntervalSeconds" to "3600").connectionEstimateIntervalSeconds)
        assertEquals(604_800, parsed("telemetry.connectionEstimateIntervalSeconds" to "604800").connectionEstimateIntervalSeconds)
        assertFalse(parsed("telemetry.enabled" to "false").enabled)
    }

    @Test
    fun `values outside the bounds are refused naming the key`() {
        val bad = mapOf(
            // 3600 is the hour itself, 70 / 700 / 1000 / 1500 do not divide it, 59 / 3601 are out of range
            "telemetry.sampleIntervalSeconds" to listOf("59", "1801", "3600", "0", "-1", "abc", "", "70", "700", "1000", "1500"),
            "telemetry.storageIntervalSeconds" to listOf("899", "86401", "abc"),
            "telemetry.connectionEstimateIntervalSeconds" to listOf("3599", "604801", "abc"),
        )
        for ((key, values) in bad) {
            for (value in values) {
                val failure = assertFailsWith<IllegalStateException>("$key=$value") { parsed(key to value) }
                assertTrue("Config \"$key\"" in failure.message.orEmpty(), failure.message)
            }
        }
        for (value in listOf("yes", "1", "", "TRUE ")) {
            val failure = assertFailsWith<IllegalStateException>("enabled=$value") { parsed("telemetry.enabled" to value) }
            assertTrue("Config \"telemetry.enabled\"" in failure.message.orEmpty())
        }
    }

    @Test
    fun `a bad value refuses the boot, also with the sampler off`() {
        listOf(
            "telemetry.sampleIntervalSeconds" to "30",
            "telemetry.storageIntervalSeconds" to "10",
            "telemetry.connectionEstimateIntervalSeconds" to "10",
            "telemetry.enabled" to "maybe",
        ).forEach { (key, value) ->
            testApplication {
                configureApp("telemetry.enabled" to "false", key to value)
                assertStartupFails("Config \"$key\"") { startApplication() }
            }
        }
    }
}
