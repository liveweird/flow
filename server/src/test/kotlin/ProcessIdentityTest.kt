package ch.nokillswit

import ch.nokillswit.plugins.AppRole
import ch.nokillswit.telemetry.ProcessIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `telemetry/ProcessIdentity.kt` through its injectable environment: only `HOSTNAME`, `FLOW_BUILD_VERSION` and `FLOW_BUILD_COMMIT` are
 * ever read (never JVM arguments, other variables or system properties), control characters are stripped and the fields bounded.
 */
class ProcessIdentityTest {
    private class RecordingEnv(private val values: Map<String, String>) : (String) -> String? {
        val asked = mutableSetOf<String>()
        override fun invoke(name: String): String? = values[name].also { asked += name }
    }

    @Test
    fun `only the hostname and the two build variables are read`() {
        val env = RecordingEnv(
            mapOf(
                "HOSTNAME" to "flow-web-7d9f", "FLOW_BUILD_VERSION" to "1.17.0", "FLOW_BUILD_COMMIT" to "abc1234",
                "POSTGRES_PASSWORD" to "secret", "JAVA_TOOL_OPTIONS" to "-Dsecret=1",
            ),
        )
        val identity = ProcessIdentity.detect(AppRole.WORKER, env)
        assertEquals(setOf("HOSTNAME", "FLOW_BUILD_VERSION", "FLOW_BUILD_COMMIT"), env.asked)
        assertEquals("worker", identity.role)
        assertEquals("flow-web-7d9f", identity.instance)
        assertEquals("1.17.0", identity.buildVersion)
        assertEquals("abc1234", identity.buildCommit)
        assertTrue(identity.processors >= 1 && identity.heapMaxBytes > 0 && identity.startedAt > 0)
    }

    @Test
    fun `control characters are stripped and the fields are truncated`() {
        val env = RecordingEnv(
            mapOf(
                "HOSTNAME" to "  pod\u0000-\n1" + "x".repeat(200),
                "FLOW_BUILD_VERSION" to "1.\t2" + "9".repeat(100),
                "FLOW_BUILD_COMMIT" to "a\u0007b" + "c".repeat(100),
            ),
        )
        val identity = ProcessIdentity.detect(AppRole.WEB, env)
        assertEquals(("pod-1" + "x".repeat(200)).take(100), identity.instance)
        assertEquals(("1.2" + "9".repeat(100)).take(64), identity.buildVersion)
        assertEquals(("ab" + "c".repeat(100)).take(64), identity.buildCommit)
        assertEquals("unknown", ProcessIdentity.instanceName { "\u0001\u0002" }, "nothing left of a name made of control characters")
    }

    @Test
    fun `absent or blank variables are null build fields and a hostname fallback`() {
        val env = RecordingEnv(mapOf("HOSTNAME" to "  ", "FLOW_BUILD_VERSION" to "", "FLOW_BUILD_COMMIT" to " "))
        val identity = ProcessIdentity.detect(AppRole.ALL, env)
        assertNull(identity.buildVersion)
        assertNull(identity.buildCommit)
        assertTrue(identity.instance.isNotBlank() && identity.instance.length <= 100, "the resolver's host name, or \"unknown\"")
        assertEquals("all", identity.role)
    }
}
