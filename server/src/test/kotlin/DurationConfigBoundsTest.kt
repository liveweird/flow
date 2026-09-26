package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * `security.lockout.durationSeconds` and `security.passwordReset.minIntervalSeconds`
 * (`auth/AuthRoutes.kt`) are read through `requireConfigLong(..., min = 1)` and then multiplied
 * by 1000 to get millis. Without an upper bound, a value near `Long.MAX_VALUE` overflows that
 * multiplication into a NEGATIVE millis figure — which would silently disable the lockout/reset
 * throttle instead of refusing to start. `MAX_DURATION_SECONDS` (30 days) closes that gap.
 */
class DurationConfigBoundsTest {

    @Test
    fun `an overflow-sized lockout duration refuses to start`() = testApplication {
        configureApp("security.lockout.durationSeconds" to Long.MAX_VALUE.toString())
        assertStartupFails("security.lockout.durationSeconds") { startApplication() }
    }

    @Test
    fun `an overflow-sized password-reset interval refuses to start`() = testApplication {
        configureApp("security.passwordReset.minIntervalSeconds" to Long.MAX_VALUE.toString())
        assertStartupFails("security.passwordReset.minIntervalSeconds") { startApplication() }
    }

    @Test
    fun `a duration at the 30-day ceiling still boots`() = testApplication {
        configureApp(
            "security.lockout.durationSeconds" to (30L * 24 * 3600).toString(),
            "security.passwordReset.minIntervalSeconds" to (30L * 24 * 3600).toString(),
        )
        startApplication() // must not throw
    }
}
