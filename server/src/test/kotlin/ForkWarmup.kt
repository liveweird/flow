package ch.nokillswit

import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue

/**
 * The fork-level warm-up: the first class of every test JVM pays the Testcontainers Postgres + Flyway and the first Ktor
 * boot, and when that class runs inside `testApplication` it pays them under Ktor's hard 60 s limit (14-15 s cold in the
 * 2026-10 flake sweep, far more on a slow runner). JUnit 4 does not time a `@BeforeClass`, so [warm] moves the one-time cost
 * there: the `test` task (`server/build.gradle.kts`) pins `ForkWarmup<k>Test` first in fork k for k <= `-Pforks`, and
 * whichever class comes next finds a migrated database and a JVM that has already booted the app once.
 *
 * Why eight classes (`ForkWarmup1Test`..`ForkWarmup8Test`, `-Pforks` is 1..8): Gradle deals one class FILE to one fork and
 * collapses a repeated class name, so N forks need N distinct classes; `TestClassNamingTest` allows one concrete `*Test`
 * class per file and forbids an abstract `*Test` base, so each is a small file delegating here. A surplus class is kept out
 * of the run by the build's candidate rules (`.claude/docs/test-fixtures.md` "Balanced fork order").
 *
 * Must never touch `SyncedStubFixture`/`DerivedStubFixture` (`StubFixtureWarmupGuardTest`): the stub SYNC is the heavy
 * classes' own `warmStubFixtures()`, and a forks-wide SYNC here would cost every fork the SYNC even when it draws none.
 */
object ForkWarmup {
    @Volatile
    private var booted = false

    /** Idempotent per JVM: migrate the container, boot the app once and throw it away; prints the `app-boot` marker. */
    @Synchronized
    fun warm() {
        if (booted) return
        PostgresTestSupport.ensureMigrated()
        val startedAt = System.nanoTime()
        runBlocking {
            TestApplication { configureApp() }.run {
                start()
                stop()
            }
        }
        // Fixture-cost marker (read by `scripts/timings/local-times.mjs`, which subtracts it from the suite that
        // paid it): `fixture-cost<TAB><name><TAB><millis>`; Gradle captures it in that suite's <system-out>.
        println("fixture-cost\tapp-boot\t${(System.nanoTime() - startedAt) / 1_000_000L}")
        booted = true
    }

    /** The body of every `ForkWarmup<k>Test`: the fork's database holds the migrations and the throwaway boot ran. */
    fun assertWarm() {
        assertTrue(booted, "ForkWarmup.warm() did not run in this JVM")
        PostgresTestSupport.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from flyway_schema_history where success").use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getInt(1) >= 1, "no successful Flyway migration in this fork's database")
                }
            }
        }
    }
}
