package ch.nokillswit.infra.db

import ch.nokillswit.infra.config.requireConfigInt
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
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

/** The cap, in seconds, on Flyway's doubling wait between connect attempts (1, 2, 4, 8, 8, … s). */
private const val CONNECT_RETRY_MAX_INTERVAL_SECONDS = 8

/**
 * `postgres.connectRetries` (`POSTGRES_CONNECT_RETRIES`, 0..15, default 10) — boot-validated like every
 * numeric key. Flyway is the FIRST thing to touch the database at boot, so on a cold start (compose,
 * Kubernetes, a Postgres restart) it used to fail immediately and the process exited, crash-looping until
 * Postgres accepted connections. With retries the boot simply waits: 10 retries under the 8 s interval cap
 * is about 63 s in total before the original connection error is finally raised. `0` restores fail-fast.
 */
internal fun readFlywayConnectRetries(config: ApplicationConfig): Int =
    requireConfigInt(config, "postgres.connectRetries", min = 0, max = 15)

internal fun buildFlyway(url: String, user: String, password: String, connectRetries: Int): Flyway = Flyway.configure()
    .dataSource(url, user, password)
    .locations("classpath:db/migration")
    .connectRetries(connectRetries)
    .connectRetriesInterval(CONNECT_RETRY_MAX_INTERVAL_SECONDS)
    .load()

fun Application.configureFlyway() {
    val url = environment.config.property("postgres.jdbcUrl").getString()
    val user = environment.config.property("postgres.user").getString()
    val password = environment.config.property("postgres.password").getString()
    val connectRetries = readFlywayConnectRetries(environment.config)

    log.info("Running Flyway migrations against ${jdbcUrlForLogging(url)} (connect retries: $connectRetries)")
    val result = buildFlyway(url, user, password, connectRetries).migrate()
    log.info("Flyway applied ${result.migrationsExecuted} migration(s); schema at version ${result.targetSchemaVersion}")
}
