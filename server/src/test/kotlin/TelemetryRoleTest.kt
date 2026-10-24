package ch.nokillswit

import ch.nokillswit.plugins.AppRole
import ch.nokillswit.telemetry.ProcessIdentity
import ch.nokillswit.telemetry.TelemetryOverrides
import ch.nokillswit.telemetry.TelemetryOverridesKey
import ch.nokillswit.telemetry.TelemetrySampler
import ch.nokillswit.telemetry.TelemetrySamplerKey
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sampler runs in EVERY role (`telemetry/Telemetry.kt`): the web and the worker JVMs are separate processes with their own
 * heaps. With `telemetry.enabled` on, each role's application starts the sampler, its first tick (made immediate here) registers
 * the process under that role, and its samples land; with it off (the suite default) nothing starts. Once the application stops,
 * the sampling loop is completed, not left running against a disposed pool.
 */
class TelemetryRoleTest {
    private val store = telemetryStore()

    @Before
    fun emptyOps() = runBlocking { wipeTelemetry(store) }

    /**
     * Boots one application in [role]; [overridden] pins a unique identity (the process row is then findable by it), otherwise the
     * sampler detects its own, as in production. Returns the sampler the application ran, after the application has stopped.
     */
    private fun bootWith(role: String, enabled: Boolean, overridden: Boolean = true): TelemetrySampler? = runBlocking {
        val identity = if (overridden) testIdentity(role = role) else ProcessIdentity.detect(AppRole.valueOf(role.uppercase()))
        var sampler: TelemetrySampler? = null
        lateinit var app: Application
        testApplication {
            configureApp("app.role" to role, "telemetry.enabled" to enabled.toString())
            application {
                app = this
                attributes.put(
                    TelemetryOverridesKey,
                    TelemetryOverrides(identity = identity.takeIf { overridden }, firstTickDelayMillis = 0),
                )
            }
            startApplication()
            if (enabled) {
                sampler = assertNotNull(app.attributes.getOrNull(TelemetrySamplerKey), "the sampler is published once started")
                val pid = awaitProcess { store.processId(identity) }
                val sample = awaitProcess { store.samples(pid, metric = "mem.heap_used").firstOrNull() }
                assertEquals("mem.heap_used", sample.metric, "its samples land")
                assertTrue(assertNotNull(sampler.loop).isActive, "the loop is running")
            } else {
                delay(300)
                assertFalse(app.attributes.contains(TelemetrySamplerKey), "telemetry.enabled=false starts no sampler")
                assertEquals(null, store.processId(identity))
            }
        }
        sampler
    }

    private suspend fun <T : Any> awaitProcess(read: suspend () -> T?): T {
        repeat(150) {
            read()?.let { return it }
            delay(100)
        }
        error("the sampler never wrote its first tick")
    }

    @Test
    fun `the web role samples itself`() {
        bootWith("web", enabled = true)
    }

    @Test
    fun `the worker role samples itself`() {
        bootWith("worker", enabled = true)
    }

    @Test
    fun `without an identity override the stored process is the detected one, under the role it runs as`() {
        for (role in listOf("web", "worker")) {
            bootWith(role, enabled = true, overridden = false) // awaits the row under ProcessIdentity.detect(role)
            val detected = ProcessIdentity.detect(AppRole.valueOf(role.uppercase()))
            val pid = assertNotNull(runBlocking { store.processId(detected) }, "$role registered under its detected identity")
            val row = runBlocking { store.processView(pid) }
            assertEquals(role, row.role)
            assertEquals(detected.instance, row.instance)
            assertEquals(detected.startedAt, row.startedAt)
        }
    }

    @Test
    fun `the sampling loop is completed once the application has stopped`() {
        val sampler = assertNotNull(bootWith("web", enabled = true))
        val loop = assertNotNull(sampler.loop)
        assertTrue(loop.isCompleted, "ApplicationStopping cancelled the loop and joined it")
        assertTrue(loop.isCancelled)
    }

    @Test
    fun `a disabled sampler starts nothing`() {
        assertEquals(null, bootWith("web", enabled = false))
    }
}
