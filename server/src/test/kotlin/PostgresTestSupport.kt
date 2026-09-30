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
    val user: String get() = container.username
    val password: String get() = container.password
    val r2dbcUrl: String
        get() = "r2dbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
}
