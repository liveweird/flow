package ch.nokillswit.infra.db

import io.ktor.server.application.*
import org.flywaydb.core.Flyway

/**
 * Reduces an operator-supplied JDBC URL to `host[:port]/database` for logging (ported verbatim
 * from Lettuce's `jdbcTarget` — `.claude/docs/observability.md`, "never log secrets"). Deliberately
 * string-based rather than `java.net.URI`: a `;`-delimited parameter block some JDBC URLs use
 * (e.g. `...flow;password=hunter2`) sails straight through `URI`'s path component untouched, and a
 * password containing an unescaped `@` or `/` can confuse `URI`'s authority parsing outright.
 * Everything after `?` or `;`, and any userinfo (`user:pass@` — taking the LAST `@` so a password
 * that itself embeds one is still stripped), is dropped before host is split from database. A URL
 * without a `//` authority logs as its scheme only — never throws on a malformed URL.
 */
internal fun jdbcUrlForLogging(url: String): String {
    if ("//" !in url) return url.substringBefore(':', url) + ":…"
    val rest = url.substringAfter("//").substringBefore('?').substringBefore(';').substringAfterLast('@')
    val host = rest.substringBefore('/')
    val database = rest.substringAfter('/', "")
    return if (database.isEmpty()) host else "$host/$database"
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
