package ch.nokillswit.infra.db

import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import java.net.URI
import java.net.URISyntaxException

/**
 * Reduces an operator-supplied JDBC URL to `host:port/db` for logging — no userinfo, no query
 * string, so a `jdbc:postgresql://host/db?user=...&password=...` form never lands a credential
 * in the log (`.claude/docs/observability.md`, "never log secrets"). Falls back to a fixed
 * placeholder rather than crashing the log line on a malformed URL.
 */
internal fun jdbcUrlForLogging(url: String): String = try {
    val uri = URI(url.removePrefix("jdbc:"))
    val host = uri.host ?: return "<jdbc url without a host>"
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    "$host$port${uri.path}"
} catch (_: URISyntaxException) {
    "<unparseable jdbc url>"
} catch (_: IllegalArgumentException) {
    "<unparseable jdbc url>"
}

fun Application.configureFlyway() {
    val url = environment.config.property("postgres.jdbcUrl").getString()
    val user = environment.config.property("postgres.user").getString()
    val password = environment.config.property("postgres.password").getString()

    log.info("Running Flyway migrations against ${jdbcUrlForLogging(url)}")
    val result = Flyway.configure()
        .dataSource(url, user, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    log.info("Flyway applied ${result.migrationsExecuted} migration(s); schema at version ${result.targetSchemaVersion}")
}
