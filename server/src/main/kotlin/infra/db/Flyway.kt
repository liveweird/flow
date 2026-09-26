package ch.nokillswit.infra.db

import io.ktor.server.application.*
import org.flywaydb.core.Flyway

fun Application.configureFlyway() {
    val url = environment.config.property("postgres.jdbcUrl").getString()
    val user = environment.config.property("postgres.user").getString()
    val password = environment.config.property("postgres.password").getString()

    log.info("Running Flyway migrations against $url")
    val result = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    log.info("Flyway applied ${result.migrationsExecuted} migration(s); schema at version ${result.targetSchemaVersion}")
}
