package ch.nokillswit.infra.db

import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.crypto.FieldCipherKey
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamServiceKey
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.time.Duration

/** The connected database itself — read by the readiness probe (plugins/Health.kt); services get
 *  it injected, and `ConnectionPoolTest` opens transactions directly against the SAME pool the
 *  app's services use to observe `pg_stat_activity` bounded by `postgres.pool.maxSize`. */
val R2dbcDatabaseKey = AttributeKey<R2dbcDatabase>("R2dbcDatabase")

/**
 * The bounded pool sizing read from `postgres.pool.*` (`.claude/docs/persistence.md`
 * "Connection pool") — boot-validated through the shared [requireConfigInt]/[requireConfigLong]
 * (`infra/config/`, ported from Lettuce), so a malformed or out-of-range bound refuses startup
 * with a config-error message naming the key, before any connection is attempted.
 */
private data class PoolBounds(
    val maxSize: Int,
    val initialSize: Int,
    val maxAcquireTimeSeconds: Long,
    val maxIdleTimeSeconds: Long,
    val applicationName: String,
)

private fun readPoolBounds(config: ApplicationConfig): PoolBounds {
    val maxSize = requireConfigInt(config, "postgres.pool.maxSize", min = 1, max = 1000)
    val initialSize = requireConfigInt(config, "postgres.pool.initialSize", min = 0, max = maxSize)
    val maxAcquireTimeSeconds = requireConfigLong(config, "postgres.pool.maxAcquireTimeSeconds", min = 1, max = 600)
    val maxIdleTimeSeconds = requireConfigLong(config, "postgres.pool.maxIdleTimeSeconds", min = 1, max = 86400)
    // application_name = flow on every pooled connection by default (deliberate — ops can count
    // this instance's own connections in pg_stat_activity, and ConnectionPoolTest relies on it);
    // overridable per test application instance so overlapping test apps sharing the
    // Testcontainer never share one count.
    val applicationName = config.propertyOrNull("postgres.pool.applicationName")?.getString() ?: "flow"
    return PoolBounds(maxSize, initialSize, maxAcquireTimeSeconds, maxIdleTimeSeconds, applicationName)
}

/**
 * Connects Exposed to a bounded R2DBC [ConnectionPool] instead of a raw per-transaction
 * connection factory (`.claude/docs/persistence.md` "Connection pool"): a plain
 * `r2dbc:postgresql://` connect opens one PostgreSQL backend per `suspendTransaction` with
 * nothing capping how many run at once — measured in Lettuce, v3.16.1: a 120-parallel burst
 * against one endpoint took ALL 100 backends of PostgreSQL's default `max_connections` (6 × 500
 * "too many clients", 7 × 401 because the JWT validation's blocklist read failed too — fixed
 * there in v3.16.2 by the same 500-not-401 change ported into `plugins/Security.kt` alongside
 * this pool); pooled, the same burst peaks at `maxSize` = 20.
 *
 * [ConnectionFactoryOptions.parse] plus the user/password/application-name mutations produce
 * [options], from which [ConnectionFactories.get] resolves the PLAIN (unpooled) PostgreSQL
 * factory that [ConnectionPool] then wraps. Exposed's
 * `R2dbcDatabase.connect(connectionFactory, databaseConfig, ...)` overload derives its SQL
 * dialect and reported URL from `databaseConfig.connectionFactoryOptions` alone — it only calls
 * `ConnectionFactories.get(options)` itself when the `connectionFactory` argument is null, and
 * otherwise reads `getDialectName`/`getUrlString` straight off `options` — so [options] (still
 * carrying `driver=postgresql`) is threaded into `databaseConfig` unchanged even though actual
 * traffic goes through the pool. The pool is disposed on [ApplicationStopped] so the hundreds of
 * `testApplication`s the suite boots each release their connections.
 */
private fun Application.connectPooled(): R2dbcDatabase {
    val config = environment.config
    val bounds = readPoolBounds(config)
    val options = ConnectionFactoryOptions.parse(config.property("postgres.r2dbcUrl").getString())
        .mutate()
        .option(ConnectionFactoryOptions.USER, config.property("postgres.user").getString())
        .option(ConnectionFactoryOptions.PASSWORD, config.property("postgres.password").getString())
        .option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME, bounds.applicationName)
        .build()
    val rawFactory = ConnectionFactories.get(options)
    val pool = ConnectionPool(
        ConnectionPoolConfiguration.builder(rawFactory)
            .maxSize(bounds.maxSize)
            .initialSize(bounds.initialSize)
            .maxAcquireTime(Duration.ofSeconds(bounds.maxAcquireTimeSeconds))
            .maxIdleTime(Duration.ofSeconds(bounds.maxIdleTimeSeconds))
            .build(),
    )
    monitor.subscribe(ApplicationStopped) { pool.dispose() }
    val databaseConfig = R2dbcDatabaseConfig.Builder().apply {
        connectionFactoryOptions = options
        // ONE attempt per suspendTransaction: Exposed's default of three retries any
        // R2dbcException, and the pool's acquire timeout is one — retrying a saturated pool
        // three times would turn the acquire budget into 3x the queueing per request exactly
        // when the pool is already full. Flow has no path relying on Exposed's retry.
        defaultMaxAttempts = 1
    }
    return R2dbcDatabase.connect(connectionFactory = pool, databaseConfig = databaseConfig)
}

/**
 * The DI composition root: connects the one R2DBC database (through the bounded pool above) and
 * publishes every service into [Application.attributes]. Feature modules read their services back
 * via the AttributeKey — application.yaml runs this module before any route module, so the keys
 * are always present.
 */
suspend fun Application.configureDatabase() {
    val database = connectPooled()
    val userService = UserService(database)
    attributes.put(R2dbcDatabaseKey, database)
    attributes.put(UserServiceKey, userService)
    val teamService = TeamService(database)
    attributes.put(TeamServiceKey, teamService)
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
    // The first EncryptedAtRest consumer (infra/crypto/EncryptedAtRest.kt) — the FieldCipher was
    // published by configureCrypto, which application.yaml runs before this module.
    attributes.put(DataSourceServiceKey, DataSourceService(database, attributes[FieldCipherKey]))
}
