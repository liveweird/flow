package ch.nokillswit.infra.db

import ch.nokillswit.auth.PasswordResetService
import ch.nokillswit.auth.PasswordResetServiceKey
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.crypto.FieldCipherKey
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncCursorsServiceKey
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.SyncJobsServiceKey
import ch.nokillswit.ingest.configuredScopeRules
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamServiceKey
import ch.nokillswit.flowusers.FlowUserService
import ch.nokillswit.flowusers.FlowUserServiceKey
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.time.Duration

/** The connected database itself — read by the readiness probe (plugins/Health.kt); services get
 *  it injected, and `ConnectionPoolTest` opens transactions directly against the SAME pool the
 *  app's services use to observe `pg_stat_activity` bounded by `postgres.pool.maxSize`. */
val R2dbcDatabaseKey = AttributeKey<R2dbcDatabase>("R2dbcDatabase")

/** One reading of the pooled connections (r2dbc-pool's `PoolMetrics`); see [PoolStatsKey]. */
data class PoolSnapshot(val acquired: Int, val idle: Int, val pendingAcquire: Int, val allocated: Int, val maxAllocated: Int)

/**
 * The pool's live figures, for the telemetry sampler (`telemetry/`, which sits above `infra/` and so reads them through this
 * key): a function, because the numbers change per call. Null when the pool reports no metrics (pinned present for
 * r2dbc-pool 1.0.2 by `PoolStatsTest`) - the sampler then simply records none.
 */
val PoolStatsKey = AttributeKey<() -> PoolSnapshot?>("PoolStats")

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
    val idleInTransactionTimeoutSeconds: Long,
    val applicationName: String,
)

private fun readPoolBounds(config: ApplicationConfig): PoolBounds {
    val maxSize = requireConfigInt(config, "postgres.pool.maxSize", min = 1, max = 1000)
    val initialSize = requireConfigInt(config, "postgres.pool.initialSize", min = 0, max = maxSize)
    val maxAcquireTimeSeconds = requireConfigLong(config, "postgres.pool.maxAcquireTimeSeconds", min = 1, max = 600)
    val maxIdleTimeSeconds = requireConfigLong(config, "postgres.pool.maxIdleTimeSeconds", min = 1, max = 86400)
    // 0 = off (PostgreSQL's own meaning); see DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT for the default and why.
    val idleInTransactionTimeoutSeconds = requireConfigLong(config, "postgres.pool.idleInTransactionTimeoutSeconds", min = 0, max = 86400)
    // application_name = flow on every pooled connection by default (deliberate — ops can count
    // this instance's own connections in pg_stat_activity, and ConnectionPoolTest relies on it);
    // overridable per test application instance so overlapping test apps sharing the
    // Testcontainer never share one count.
    val applicationName = config.propertyOrNull("postgres.pool.applicationName")?.getString() ?: "flow"
    return PoolBounds(maxSize, initialSize, maxAcquireTimeSeconds, maxIdleTimeSeconds, idleInTransactionTimeoutSeconds, applicationName)
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
    val (database, pool) = connectPooledDatabase(options, bounds)
    monitor.subscribe(ApplicationStopped) { pool.dispose() }
    attributes.put(PoolStatsKey) { pool.snapshot() }
    return database
}

internal fun ConnectionPool.snapshot(): PoolSnapshot? =
    metrics.orElse(null)?.let {
        PoolSnapshot(it.acquiredSize(), it.idleSize(), it.pendingAcquireSize(), it.allocatedSize(), it.maxAllocatedSize)
    }

/**
 * Distinct SQL texts each pooled connection keeps as a cached, named server-side prepared statement
 * (r2dbc-postgresql's `preparedStatementCacheQueries`; the driver's default, -1, is unbounded and never
 * closes one). Flow's statements are a few hundred fixed texts (Exposed's own plus the quantized multi-row
 * inserts of `MultiRowInsert.kt`); 256 keeps the hot ones and lets an LRU eviction close the rest, so a
 * long-lived connection's backend memory stays bounded.
 */
internal const val PREPARED_STATEMENT_CACHE_QUERIES = 256

/**
 * PostgreSQL's `idle_in_transaction_session_timeout` for every pooled connection (`postgres.pool.idleInTransactionTimeoutSeconds`,
 * sent as a startup parameter, so it is the session default — it also overrides an `ALTER ROLE/DATABASE … SET` of the same
 * setting — and no `SET` ever touches it): the server ends a session that sits idle INSIDE a transaction for longer, rolling the
 * transaction back and releasing its locks. It is the backstop for a session whose client stopped talking to it with a
 * transaction open — the r2dbc-postgresql socket leak `LeakedSocketGuard.kt` closes is the one seen (build-times WHY 24), a
 * frozen worker or a coroutine stuck mid-transaction are the others. An idle gap inside a transaction can never be longer than
 * the transaction, and the longest one Flow runs is a DERIVE (`metrics.md` "Performance", scale 20: 18-29 s warm, 38 s for a
 * cold first derive; no transaction spans an outbound call or a mail send), so 5 minutes is ~8x the longest legitimate gap.
 * The DERIVE lock timeout is never below it (`metrics/MetricsDeriver.kt` `deriveLockTimeoutMs`): a DERIVE queued behind such a
 * session outlives it and proceeds. It ends only an IDLE session — one stuck `active` (in `ClientWrite`) is the guard's job.
 */
internal val DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT: Duration = Duration.ofMinutes(5)

private const val IDLE_IN_TRANSACTION_TIMEOUT = "idle_in_transaction_session_timeout"

/**
 * [options] plus the `idle_in_transaction_session_timeout` startup parameter, unless the URL already set one (an operator's
 * `options=` wins). The driver takes `options` as a map or as its `key=value;key=value` string form.
 */
internal fun withIdleInTransactionTimeout(options: ConnectionFactoryOptions, timeout: Duration): ConnectionFactoryOptions {
    val millis = timeout.toMillis().toString()
    val existing = if (options.hasOption(STARTUP_OPTIONS)) options.getValue(STARTUP_OPTIONS) else null
    val alreadySet = when (existing) {
        null -> false
        is Map<*, *> -> existing.containsKey(IDLE_IN_TRANSACTION_TIMEOUT)
        else -> IDLE_IN_TRANSACTION_TIMEOUT in existing.toString()
    }
    if (alreadySet) return options
    val merged: Any = when (existing) {
        null -> mapOf(IDLE_IN_TRANSACTION_TIMEOUT to millis)
        is Map<*, *> -> existing + (IDLE_IN_TRANSACTION_TIMEOUT to millis)
        else -> "$existing;$IDLE_IN_TRANSACTION_TIMEOUT=$millis"
    }
    return options.mutate().option(STARTUP_OPTIONS, merged).build()
}

// The driver's `options` option is typed Map<String, String>, but a URL's `?options=` arrives as its string form.
@Suppress("UNCHECKED_CAST") // reading and writing the one option whose declared type the URL form does not honour
private val STARTUP_OPTIONS = PostgresqlConnectionFactoryProvider.OPTIONS as Option<Any>

/**
 * The pool construction itself, shared by [connectPooled] (production and every `testApplication`)
 * and the test harness's own direct-access database (`sharedDatabaseForTests()`, which had been an
 * UNPOOLED connect paying a fresh PostgreSQL backend, ~4 ms, per transaction). The caller owns
 * disposing the returned [ConnectionPool].
 */
internal fun connectPooledDatabase(
    options: ConnectionFactoryOptions,
    maxSize: Int,
    initialSize: Int,
    maxAcquireTime: Duration,
    maxIdleTime: Duration,
    idleInTransactionTimeout: Duration = DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT,
): Pair<R2dbcDatabase, ConnectionPool> {
    // Bound the driver's prepared-statement cache (its default is unbounded, and every cached text stays a
    // named server-side statement for the connection's life): see PREPARED_STATEMENT_CACHE_QUERIES.
    val cached = if (options.hasOption(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES)) {
        options
    } else {
        options.mutate()
            .option(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES, PREPARED_STATEMENT_CACHE_QUERIES)
            .build()
    }
    LeakedSockets.checkSupported()
    // Both decorators sit below the pool: the remembered statement timeout belongs to one physical connection
    // (StatementTimeoutCache.kt), and the pool's destroy of a broken connection must reach the socket guard (LeakedSocketGuard.kt).
    val rawFactory = StatementTimeoutCachingConnectionFactory(
        LeakedSocketClosingConnectionFactory(ConnectionFactories.get(withIdleInTransactionTimeout(cached, idleInTransactionTimeout))),
    )
    val pool = ConnectionPool(
        ConnectionPoolConfiguration.builder(rawFactory)
            .maxSize(maxSize)
            .initialSize(initialSize)
            // r2dbc-pool 1.0.2 builds create() as `Mono.defer { acquire.timeout(maxAcquireTime) }.retry(acquireRetry)`
            // with acquireRetry defaulting to 1, so the effective acquire deadline is ~2x maxAcquireTime (the
            // retried waiter re-queues). Kept deliberately: the same retry transparently replaces a connection that
            // fails LOCAL validation (closed by a Postgres/pod restart). `.claude/docs/persistence.md`.
            .maxAcquireTime(maxAcquireTime)
            .maxIdleTime(maxIdleTime)
            .build(),
    )
    val databaseConfig = R2dbcDatabaseConfig.Builder().apply {
        connectionFactoryOptions = options
        // ONE attempt per suspendTransaction: Exposed's default of three retries any
        // R2dbcException, and the pool's acquire timeout is one — retrying a saturated pool
        // three times would turn the acquire budget into 3x the queueing per request exactly
        // when the pool is already full. Flow has no path relying on Exposed's retry.
        defaultMaxAttempts = 1
    }
    return R2dbcDatabase.connect(connectionFactory = pool, databaseConfig = databaseConfig) to pool
}

private fun connectPooledDatabase(options: ConnectionFactoryOptions, bounds: PoolBounds) = connectPooledDatabase(
    options,
    maxSize = bounds.maxSize,
    initialSize = bounds.initialSize,
    maxAcquireTime = Duration.ofSeconds(bounds.maxAcquireTimeSeconds),
    maxIdleTime = Duration.ofSeconds(bounds.maxIdleTimeSeconds),
    idleInTransactionTimeout = Duration.ofSeconds(bounds.idleInTransactionTimeoutSeconds),
)

/**
 * The DI composition root: connects the one R2DBC database (through the bounded pool above) and
 * publishes every service into [Application.attributes]. Feature modules read their services back
 * via the AttributeKey — application.yaml runs this module before any route module, so the keys
 * are always present.
 */
suspend fun Application.configureDatabase() {
    val scopeRules = configuredScopeRules() // an unknown or production-`off` value fails startup here, before the pool opens
    val database = connectPooled()
    val userService = FlowUserService(database)
    attributes.put(R2dbcDatabaseKey, database)
    attributes.put(FlowUserServiceKey, userService)
    val teamService = TeamService(database)
    attributes.put(TeamServiceKey, teamService)
    attributes.put(
        TokenBlocklistServiceKey,
        TokenBlocklistService(
            database,
            replayGraceMillis = requireConfigLong(
                environment.config, "security.refresh.replayGraceSeconds", min = 0, max = 600,
            ) * 1000,
        ),
    )
    // Persisted single-use reset links (V35): the lifetime is boot-validated, 1 second .. 1 hour.
    attributes.put(
        PasswordResetServiceKey,
        PasswordResetService(
            database,
            ttlMillis = requireConfigLong(environment.config, "security.passwordReset.tokenTtlSeconds", min = 1, max = 3600) * 1000,
        ),
    )
    // The first EncryptedAtRest consumer (infra/crypto/EncryptedAtRest.kt) — the FieldCipher was
    // published by configureCrypto, which application.yaml runs before this module.
    attributes.put(DataSourceServiceKey, DataSourceService(database, attributes[FieldCipherKey], scopeRules))
    // The sync-job queue (v0.2.0 plan §5/§9): published here (not gated by role) since the web
    // role's job API (ingest/SyncJobRoutes.kt) enqueues/lists/cancels jobs too — only the actual
    // claim/scan loop (ingest/IngestWorker.kt) is worker-only.
    val maxAttempts = requireConfigInt(environment.config, "ingest.maxAttempts", min = 1, max = 10)
    // The DERIVE debounce window (V27 `not_before`; 0 = off): a configuration-caused DERIVE is scheduled that long after the
    // last edit. Validated on every boot, like every ingest.* setting.
    val deriveQuietSeconds = requireConfigInt(environment.config, "ingest.deriveQuietSeconds", min = 0, max = 3600)
    attributes.put(SyncJobsServiceKey, SyncJobsService(database, maxAttempts, deriveQuietMillis = deriveQuietSeconds * 1000L))
    attributes.put(SyncCursorsServiceKey, SyncCursorsService(database))
}
