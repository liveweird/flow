package ch.nokillswit

import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The numeric config keys that used to be read with a bare `toInt()`/`toLong()` (a `NumberFormatException`
 * with no key in it, or worse a silently nonsensical value) now go through `requireConfigInt/Long`:
 * a malformed or out-of-range override refuses startup, naming the key — the `DurationConfigBoundsTest`
 * pattern, for `jwt.*ExpiresInSeconds` (`plugins/Security.kt`), the three `maxTracked` capacities and
 * `mfa.codeTtlSeconds` (`auth/AuthRoutes.kt`), `mail.smtp.port` (`infra/mail/Mail.kt`) and the per-IP
 * `security.rateLimit.*PerMinute` overrides (`plugins/RateLimits.kt`).
 */
class NumericConfigBoundsTest {

    private fun assertRefused(key: String, vararg values: String, extra: List<Pair<String, String>> = emptyList()) {
        values.forEach { value ->
            testApplication {
                configureApp(key to value, *extra.toTypedArray())
                assertStartupFails("Config \"$key\"") { startApplication() }
            }
        }
    }

    @Test
    fun `a malformed or out-of-range JWT lifetime refuses to start`() {
        assertRefused("jwt.accessExpiresInSeconds", "0", "-5", "abc", Long.MAX_VALUE.toString())
        assertRefused("jwt.refreshExpiresInSeconds", "0", "-5", "abc", Long.MAX_VALUE.toString())
    }

    @Test
    fun `a malformed or out-of-range throttle capacity refuses to start`() {
        assertRefused("security.lockout.maxTracked", "0", "-1", "abc", "1000001")
        assertRefused("security.passwordReset.maxTracked", "0", "-1", "abc", "1000001")
        assertRefused("security.mfa.maxTracked", "0", "-1", "abc", "1000001")
    }

    @Test
    fun `a malformed or overflow-sized MFA code ttl refuses to start`() {
        assertRefused("security.mfa.codeTtlSeconds", "-1", "abc", Long.MAX_VALUE.toString())
    }

    @Test
    fun `a zero MFA code ttl is refused outside development mode`() = testApplication {
        configureApp(
            "security.mfa.codeTtlSeconds" to "0",
            "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("security.mfa.codeTtlSeconds") { startApplication() }
        }
    }

    @Test
    fun `a malformed or out-of-range stored-size ceiling refuses to start`() {
        assertRefused("jira.maxStoredPayloadBytes", "262143", "8388609", "-1", "abc")
        assertRefused("jira.processPageBytes", "2097151", "67108865", "-1", "abc")
    }

    @Test
    fun `a malformed or out-of-range smtp port refuses to start`() {
        assertRefused(
            "mail.smtp.port", "0", "65536", "abc",
            extra = listOf("mail.transport" to "smtp", "mail.smtp.host" to "localhost"),
        )
    }

    @Test
    fun `a malformed or non-positive per-IP rate limit override refuses to start`() {
        assertRefused("security.rateLimit.loginPerMinute", "0", "-1", "abc")
        assertRefused("security.rateLimit.refreshPerMinute", "0", "abc")
        assertRefused("security.rateLimit.readyPerMinute", "0", "abc")
        assertRefused("security.rateLimit.reportsPerMinute", "0", "-1", "abc", "1000001")
        assertRefused("security.rateLimit.passwordResetPerMinute", "-5", "abc")
    }

    @Test
    fun `a malformed or out-of-range Jira changelog page size refuses to start`() {
        // Jira's documented changelog/bulkfetch maxResults is 1..10,000.
        assertRefused("jira.changelogPageSize", "0", "10001", "abc")
    }

    @Test
    fun `a malformed or out-of-range Jira users refresh refuses to start`() {
        // 0 = every SYNC; a week at most.
        assertRefused("jira.usersRefreshHours", "-1", "169", "abc")
    }

    @Test
    fun `the shipped Jira response cap default is 8 MiB`() {
        // Read from the file, not through the config loader: an exported JIRA_MAX_RESPONSE_BYTES of a developer's shell must not matter.
        // One response is held ~3-4x while read and parsed, 4 in flight, in a -Xmx512m worker (`jira-integration.md` "Response size cap").
        val yaml = checkNotNull(NumericConfigBoundsTest::class.java.getResource("/application.yaml")).readText()
        val default = Regex("maxResponseBytes:\\s*\"\\\$JIRA_MAX_RESPONSE_BYTES:(\\d+)\"").find(yaml)?.groupValues?.get(1)
        assertEquals((8L * 1024 * 1024).toString(), default)
    }

    @Test
    fun `a malformed or out-of-range ingest job time ceiling or pause threshold refuses to start`() {
        // Minutes: 5..1440 (below 5 the REFERENCE pass alone could eat the budget every run); trips: 1..100.
        assertRefused("ingest.maxJobMinutes", "0", "4", "1441", "abc")
        assertRefused("ingest.pauseAfterLimitTrips", "0", "101", "abc")
    }

    @Test
    fun `the shipped ingest job time ceiling is 60 minutes and the pause threshold 5 trips`() {
        // Read from the file, not through the config loader: an exported INGEST_* variable of a developer's shell must not matter.
        val yaml = checkNotNull(NumericConfigBoundsTest::class.java.getResource("/application.yaml")).readText()
        val minutes = Regex("maxJobMinutes:\\s*\"\\\$INGEST_MAX_JOB_MINUTES:(\\d+)\"").find(yaml)?.groupValues?.get(1)
        val trips = Regex("pauseAfterLimitTrips:\\s*\"\\\$INGEST_PAUSE_AFTER_LIMIT_TRIPS:(\\d+)\"").find(yaml)?.groupValues?.get(1)
        assertEquals("60", minutes)
        assertEquals("5", trips)
    }

    @Test
    fun `the largest documented Jira response cap still boots`() = testApplication {
        configureApp("jira.maxResponseBytes" to (128L * 1024 * 1024).toString())
        startApplication()
    }

    @Test
    fun `values at the ceilings still boot`() = testApplication {
        configureApp(
            "jwt.accessExpiresInSeconds" to (365L * 24 * 3600).toString(),
            "jwt.refreshExpiresInSeconds" to "1",
            "security.lockout.maxTracked" to "1000000",
            "security.mfa.codeTtlSeconds" to "0",
            "security.rateLimit.loginPerMinute" to "1000000",
            "jira.usersRefreshHours" to "168",
            "ingest.maxJobMinutes" to "1440",
            "ingest.pauseAfterLimitTrips" to "100",
        )
        startApplication() // must not throw
    }
}
