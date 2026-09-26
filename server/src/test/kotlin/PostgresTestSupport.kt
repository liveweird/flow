package ch.nokillswit

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

object PostgresTestSupport {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName
                .parse("postgres:18.6-alpine@sha256:6c538e7206ea40ff740ef27883529390a690b6ead6ba96b44c67a9f7c638e8fd")
                .asCompatibleSubstituteFor("postgres"),
        ).apply {
            withDatabaseName("flow_test")
            withUsername("flow")
            withPassword("flow")
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val user: String get() = container.username
    val password: String get() = container.password
    val r2dbcUrl: String
        get() = "r2dbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"
}
