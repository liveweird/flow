package ch.nokillswit.telemetry

import ch.nokillswit.infra.db.insertRowsIgnoringConflicts
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * `ops.telemetry_samples.resolution` of an hourly rollup. A raw sample stores the sampler's REAL interval in seconds (60..1800, a
 * divisor of 3600), so a raw row is any row with `resolution < HOURLY_RESOLUTION`.
 */
const val HOURLY_RESOLUTION = 3600

/** Raw samples live 7 days, hourly rollups and silent processes 90 (`.claude/docs/persistence.md` "Telemetry (V36)"). */
internal const val RAW_RETENTION_MILLIS = 7 * MILLIS_PER_DAY
internal const val HOURLY_RETENTION_MILLIS = 90 * MILLIS_PER_DAY
internal const val PROCESS_RETENTION_MILLIS = 90 * MILLIS_PER_DAY

/** An hour is rolled up only once it ended at least this long ago, so a late tick of a slow process still lands in it. */
internal const val ROLLUP_SETTLE_MILLIS = 10 * MILLIS_PER_MINUTE

/** The settle time for a sampler ticking every [intervalMillis]: [ROLLUP_SETTLE_MILLIS], or half an interval when that is longer. */
internal fun rollupSettleMillis(intervalMillis: Long): Long = maxOf(ROLLUP_SETTLE_MILLIS, intervalMillis / 2)

/**
 * The advisory lock the cluster section of a tick takes (`pg_try_advisory_xact_lock`, the single-`bigint` form): the ASCII bytes of
 * "FlowTele". One process per tick holds it, so the web and worker JVMs (and any replica) never repeat the cluster work. A row lock
 * cannot do the job: the work is "whoever gets here first this interval", with no row to lock until it has been done.
 */
internal const val TELEMETRY_LOCK_KEY = 0x466C6F7754656C65L

/** One row of `ops.telemetry_samples`. */
data class SampleRow(
    val resolution: Int,
    val ts: Long,
    val processId: Int,
    val metric: String,
    val label: String,
    val n: Int,
    val avg: Double,
    val max: Double,
)

/**
 * The `ops` schema's access (V36): the process registry, the sample inserts, the cluster lock and the hourly rollup / prune.
 * Every statement runs in a transaction that carries [statementTimeoutSeconds] as its `queryTimeout` (the only way a statement
 * timeout may be set, `persistence.md` "Connection pool"). The tx-bound functions are extensions on [R2dbcTransaction] so the
 * sampler can compose them inside the one cluster transaction.
 */
class TelemetryStore(private val database: R2dbcDatabase, private val statementTimeoutSeconds: Int) {
    object Processes : IntIdTable("ops.telemetry_processes") {
        val role = varchar("role", 16)
        val instance = varchar("instance", MAX_INSTANCE_LENGTH)
        val startedAt = long("started_at")
        val lastSeen = long("last_seen")
        val buildVersion = varchar("build_version", 64).nullable()
        val buildCommit = varchar("build_commit", 64).nullable()
        val processors = integer("processors")
        val heapMaxBytes = long("heap_max_bytes")
        val containerLimitBytes = long("container_limit_bytes").nullable()
    }

    object Samples : Table("ops.telemetry_samples") {
        val resolution = integer("resolution")
        val ts = long("ts")
        val processId = integer("process_id")
        val metric = varchar("metric", MAX_METRIC_NAME_LENGTH)
        val label = varchar("label", MAX_LABEL_LENGTH).default("")
        val n = integer("n").default(1)
        val avg = double("avg")
        val max = double("max")
        override val primaryKey = PrimaryKey(resolution, metric, ts, processId, label)
    }

    /** A transaction under the telemetry statement budget ([timeoutSeconds] overrides it: the heavy collectors' own). */
    suspend fun <T> transaction(timeoutSeconds: Int = statementTimeoutSeconds, block: suspend R2dbcTransaction.() -> T): T =
        suspendTransaction(database) {
            queryTimeout = timeoutSeconds
            block()
        }

    /**
     * The id of this process run's row, inserted on first sight and found again by its identity afterwards (two samplers of one
     * JVM - a test - share a row). A row found is brought up to date.
     */
    suspend fun registerProcess(identity: ProcessIdentity, now: Long, containerLimitBytes: Long?): Int = transaction {
        val existing = Processes.select(Processes.id).where {
            (Processes.role eq identity.role) and (Processes.instance eq identity.instance) and (Processes.startedAt eq identity.startedAt)
        }.toList().firstOrNull()?.get(Processes.id)?.value
        if (existing != null) {
            touch(existing, now, containerLimitBytes)
            existing
        } else {
            Processes.insertAndGetId {
                it[Processes.role] = identity.role
                it[Processes.instance] = identity.instance
                it[Processes.startedAt] = identity.startedAt
                it[Processes.lastSeen] = now
                it[Processes.buildVersion] = identity.buildVersion
                it[Processes.buildCommit] = identity.buildCommit
                it[Processes.processors] = identity.processors
                it[Processes.heapMaxBytes] = identity.heapMaxBytes
                it[Processes.containerLimitBytes] = containerLimitBytes
            }.value
        }
    }

    /**
     * Stamps `last_seen` (and the limit, which a pod resize can change); false when the row is gone (pruned by hand) and the
     * process must register again.
     */
    suspend fun touchProcess(id: Int, now: Long, containerLimitBytes: Long?): Boolean = transaction { touch(id, now, containerLimitBytes) }

    private suspend fun touch(id: Int, now: Long, containerLimitBytes: Long?): Boolean =
        Processes.update({ Processes.id eq id }) {
            it[Processes.lastSeen] = now
            it[Processes.containerLimitBytes] = containerLimitBytes
        } > 0

    /**
     * Runs [block] in ONE transaction that holds the cluster lock ([TELEMETRY_LOCK_KEY], transaction-scoped, released at commit or
     * rollback), or returns null without running it when another process holds it right now.
     */
    suspend fun <T> clusterSection(block: suspend R2dbcTransaction.() -> T): T? = transaction {
        if (tryLockCluster()) block() else null
    }

    private suspend fun R2dbcTransaction.tryLockCluster(): Boolean =
        exec("SELECT pg_try_advisory_xact_lock($TELEMETRY_LOCK_KEY)", explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, Boolean::class.javaObjectType) == true
        }?.toList()?.single() == true
}

/** Inserts [rows] in the caller's transaction; a row that already exists (same resolution, metric, ts, process, label) is kept. */
internal suspend fun insertSampleRows(rows: List<SampleRow>) {
    TelemetryStore.Samples.insertRowsIgnoringConflicts(rows) {
        this[TelemetryStore.Samples.resolution] = it.resolution
        this[TelemetryStore.Samples.ts] = it.ts
        this[TelemetryStore.Samples.processId] = it.processId
        this[TelemetryStore.Samples.metric] = it.metric
        this[TelemetryStore.Samples.label] = it.label
        this[TelemetryStore.Samples.n] = it.n
        this[TelemetryStore.Samples.avg] = it.avg
        this[TelemetryStore.Samples.max] = it.max
    }
}

/** Sets the figure of the raw row ([metric], [ts], [processId], no label) that a heavy collector claimed with 0 and now completes. */
internal suspend fun R2dbcTransaction.completeSample(resolution: Int, metric: MetricDef, ts: Long, processId: Int, value: Double) {
    val samples = TelemetryStore.Samples
    samples.update({
        (samples.resolution eq resolution) and (samples.metric eq metric.name) and (samples.ts eq ts) and
            (samples.processId eq processId) and (samples.label eq "")
    }) {
        it[samples.avg] = value
        it[samples.max] = value
    }
}

/** The newest raw `ts` of [metric], or null: the cluster section's "this bucket was already served" check reads it. */
internal suspend fun R2dbcTransaction.newestRawTs(metric: MetricDef): Long? {
    val samples = TelemetryStore.Samples
    return samples.select(samples.ts.max()).where { (samples.resolution less HOURLY_RESOLUTION) and (samples.metric eq metric.name) }
        .toList().singleOrNull()?.get(samples.ts.max())
}

/** The start of the hour containing [ts]. */
internal fun hourFloor(ts: Long): Long = ts - Math.floorMod(ts, MILLIS_PER_HOUR)

/**
 * Folds the raw samples of every COMPLETE hour (ended at least [settleMillis] before [now]) after the newest hourly row into
 * hourly rows: `n` = the samples folded, `avg` their n-weighted mean, `max` the largest max. Idempotent (`ON CONFLICT DO NOTHING`),
 * one statement, whose inserted-row count it returns: true only when it wrote at least one hourly row, which is what makes the
 * caller prune - once per hour in steady state, and never while the rollup keeps failing (a failure throws before the prune).
 */
internal suspend fun R2dbcTransaction.rollupHourly(now: Long, settleMillis: Long = ROLLUP_SETTLE_MILLIS): Boolean {
    val samples = TelemetryStore.Samples
    val end = hourFloor(now - settleMillis)
    val newestHourly = samples.select(samples.ts.max()).where { samples.resolution eq HOURLY_RESOLUTION }
        .toList().singleOrNull()?.get(samples.ts.max())
    val from = if (newestHourly != null) {
        newestHourly + MILLIS_PER_HOUR
    } else {
        samples.select(samples.ts.min()).where { samples.resolution less HOURLY_RESOLUTION }
            .toList().singleOrNull()?.get(samples.ts.min())?.let(::hourFloor) ?: return false
    }
    if (from >= end) return false
    val inserted = exec(
        """
        WITH folded AS (
            INSERT INTO ops.telemetry_samples (resolution, ts, process_id, metric, label, n, avg, max)
            SELECT $HOURLY_RESOLUTION, ts - ts % $MILLIS_PER_HOUR, process_id, metric, label,
                   SUM(n)::int, SUM(avg * n) / SUM(n), MAX(max)
            FROM ops.telemetry_samples
            WHERE resolution < $HOURLY_RESOLUTION AND ts >= $from AND ts < $end
            GROUP BY ts - ts % $MILLIS_PER_HOUR, process_id, metric, label
            ON CONFLICT DO NOTHING
            RETURNING 1
        )
        SELECT count(*) FROM folded
        """.trimIndent(),
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.get(0, Long::class.javaObjectType) ?: 0L }?.toList()?.single() ?: 0L
    return inserted > 0
}

/**
 * The hard-delete prune (persistence.md "Telemetry (V36)"): raw samples older than 7 days, hourly rows older than 90, and
 * processes silent for 90 (their samples go with them, `ON DELETE CASCADE`). A row exactly at its cutoff stays.
 */
internal suspend fun R2dbcTransaction.pruneTelemetry(now: Long) {
    val samples = TelemetryStore.Samples
    samples.deleteWhere { (resolution less HOURLY_RESOLUTION) and (ts less now - RAW_RETENTION_MILLIS) }
    samples.deleteWhere { (resolution eq HOURLY_RESOLUTION) and (ts less now - HOURLY_RETENTION_MILLIS) }
    TelemetryStore.Processes.deleteWhere { lastSeen less now - PROCESS_RETENTION_MILLIS }
}
