package ch.nokillswit

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestSupport {
    /** Tag + digest — must equal `docker-compose.yaml`'s image (pinned by `PostgresImagePinTest`). */
    const val IMAGE = "postgres:18.6-alpine@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873"

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName
                .parse(IMAGE)
                .asCompatibleSubstituteFor("postgres"),
        ).apply {
            val startedAt = System.nanoTime()
            withDatabaseName("flow_test")
            withUsername("flow")
            withPassword("flow")
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
            // Migrate before ANYONE gets the container: Gradle forks change class order, so the first class of
            // a JVM may touch the DB (a fixture, a raw JDBC helper) before any test booted the app's Flyway
            // module. Same call as `infra/db/Flyway.kt`; idempotent — the app's own migrate() then no-ops.
            org.flywaydb.core.Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            // The suite's baseline is "no history horizon": V39 wakes the settings row at 24 months, and a long-standing test reads fixed
            // old dates (2020, 2024) on the real clock, which the horizon would clamp or refuse. A test of the horizon sets its own through
            // `withMetricsSettings`, which restores this baseline (`ReportHistoryHorizonTest`).
            createConnection("").use { connection ->
                connection.createStatement().use { it.executeUpdate("UPDATE metrics.settings SET history_horizon_months = NULL") }
            }
            // Fixture-cost marker (read by `scripts/timings/local-times.mjs`, which subtracts it from the suite that
            // paid it): `fixture-cost<TAB><name><TAB><millis>`; Gradle captures it in that suite's <system-out>.
            println("fixture-cost\tpostgres\t${(System.nanoTime() - startedAt) / 1_000_000L}")
        }
    }

    /**
     * Starts the container and applies every migration (once per JVM, `by lazy` is synchronized). Every
     * accessor below already triggers this; call it explicitly where a test only needs "a migrated database".
     */
    fun ensureMigrated() {
        container.jdbcUrl
    }

    val jdbcUrl: String get() = container.jdbcUrl

    /**
     * Every test-side raw JDBC connection comes from here, never from a bare `DriverManager.getConnection`: pgjdbc's
     * defaults are "wait forever" for the connect, the login and every read, and a call blocked in a socket read
     * cannot be cancelled, so one half-dead connection hung a CI job to its 25-minute timeout (the 2026-10-17
     * `DimDateContentionTest` stall). The bounds sit far above any legitimate statement here (the slowest is a
     * DERIVE-blocked row lock, tens of seconds on a slow runner) and BELOW the test task's stall watchdog
     * (`-PtestStallMinutes`, default 5 min in `server/build.gradle.kts`), so a dead socket fails the one test with
     * its own stack trace instead of being killed as a hung fork.
     */
    fun connect(): java.sql.Connection = connectTo(jdbcUrl)

    /** [connect] to another database of the same container (a scratch one a test created), with the same bounds. */
    fun connect(database: String): java.sql.Connection = connectTo(jdbcUrlFor(database))

    /** The container's JDBC URL naming [database], the query string (pgjdbc options) kept. */
    fun jdbcUrlFor(database: String): String = jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$database$1")

    private fun connectTo(url: String): java.sql.Connection =
        java.sql.DriverManager.getConnection(
            url,
            java.util.Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("connectTimeout", JDBC_CONNECT_TIMEOUT_S.toString())
                setProperty("loginTimeout", JDBC_CONNECT_TIMEOUT_S.toString())
                setProperty("socketTimeout", JDBC_SOCKET_TIMEOUT_S.toString())
                setProperty("tcpKeepAlive", "true")
            },
        )

    private const val JDBC_CONNECT_TIMEOUT_S = 30
    private const val JDBC_SOCKET_TIMEOUT_S = 180
    val user: String get() = container.username
    val password: String get() = container.password
    val r2dbcUrl: String
        get() = "r2dbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
}
