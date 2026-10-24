package ch.nokillswit.telemetry

import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import kotlin.math.roundToLong

/** The schemas whose size the storage collector reports. */
private val SIZED_SCHEMAS = listOf("raw", "norm", "metrics", "public", "ops")

/** The schemas whose tables the per-connection estimate scans. */
private val ESTIMATED_SCHEMAS = listOf("raw", "norm", "metrics")

/** The raw issue table: its LIVE rows per connection are `conn.issues`. */
internal const val ISSUES_TABLE = "raw.jira_issues"

private fun sqlList(values: List<String>) = values.joinToString(",") { "'$it'" }

private fun quoteIdentifier(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * `db.size` (`pg_database_size`) and `db.schema.size` per schema: the sum of `pg_total_relation_size` (heap, TOAST and indexes) over
 * its tables and materialized views. A schema without relations reads 0. Catalog reads only - it touches no table's rows - so it
 * needs no other package's service (`persistence.md` "Telemetry (V36)"). Two statements, each in a transaction of its own.
 */
internal suspend fun HeavyReader.storageReadings(): List<Reading> {
    val total = read { query("SELECT pg_database_size(current_database())") { it.get(0, Long::class.javaObjectType) ?: 0L }.single() }
    val bySchema = read {
        query(
            """
            SELECT n.nspname, COALESCE(SUM(pg_total_relation_size(c.oid)), 0)::bigint
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname IN (${sqlList(SIZED_SCHEMAS)}) AND c.relkind IN ('r', 'm')
            GROUP BY n.nspname
            """.trimIndent(),
        ) { it.get(0, String::class.java).orEmpty() to (it.get(1, Long::class.javaObjectType) ?: 0L) }.toMap()
    }
    return listOf(Reading(TelemetryMetrics.DB_SIZE, total.toDouble())) +
        SIZED_SCHEMAS.map { Reading(TelemetryMetrics.DB_SCHEMA_SIZE, (bySchema[it] ?: 0L).toDouble(), it) }
}

/**
 * One table that has a `connection_id` column: its total size ([sizeBytes], heap + TOAST + indexes) and its row count per
 * connection ([rowsByConnection]; the key `null` collects rows with no connection, which no connection owns but which dilute the share).
 */
internal class ConnectionTable(val name: String, val sizeBytes: Long, val rowsByConnection: Map<Long?, Long>)

/**
 * Every table of `raw`/`norm`/`metrics` with a `connection_id` column, DISCOVERED from the catalog (a table a later migration adds
 * is included with no code change), each with its size and its row counts per connection. The counts are `SELECT connection_id,
 * count(*) ... GROUP BY connection_id` - one statement per table, each in its own short transaction - and never read a payload
 * (`pg_column_size(t.*)` would de-TOAST every one). Rows of a soft-deleted but not yet purged connection are counted: they are
 * exactly the storage a purge would reclaim.
 */
internal suspend fun HeavyReader.connectionTables(): List<ConnectionTable> {
    val tables = read {
        query(
            """
            SELECT n.nspname, c.relname, pg_total_relation_size(c.oid)::bigint
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'connection_id' AND a.attnum > 0 AND NOT a.attisdropped
            WHERE n.nspname IN (${sqlList(ESTIMATED_SCHEMAS)}) AND c.relkind IN ('r', 'm')
            ORDER BY n.nspname, c.relname
            """.trimIndent(),
        ) {
            val size = it.get(2, Long::class.javaObjectType) ?: 0L
            Triple(it.get(0, String::class.java).orEmpty(), it.get(1, String::class.java).orEmpty(), size)
        }
    }
    return tables.map { (schema, table, size) ->
        val qualified = "${quoteIdentifier(schema)}.${quoteIdentifier(table)}"
        val rows = read { connectionCounts("SELECT connection_id::bigint, count(*)::bigint FROM $qualified GROUP BY connection_id") }
        ConnectionTable("$schema.$table", size, rows)
    }
}

private suspend fun R2dbcTransaction.connectionCounts(sql: String): Map<Long?, Long> =
    query(sql) { it.get(0, Long::class.javaObjectType) to (it.get(1, Long::class.javaObjectType) ?: 0L) }.toMap()

/**
 * The estimated bytes per connection: for every table, each connection's share of the rows times the table's size, summed over the
 * tables. Rows are weighed equally within a table (a wide changelog counts like a narrow one), so the figure is an estimate -
 * exact only where one connection owns the table - and the shares of one table always sum to its size together with the rows
 * that belong to no connection.
 */
internal fun apportion(tables: List<ConnectionTable>): Map<Long, Double> {
    val bytes = HashMap<Long, Double>()
    for (table in tables) {
        val rows = table.rowsByConnection.values.sum()
        if (rows == 0L) continue
        for ((connectionId, count) in table.rowsByConnection) {
            if (connectionId != null) bytes.merge(connectionId, table.sizeBytes.toDouble() * count / rows, Double::plus)
        }
    }
    return bytes
}

/**
 * `conn.issues` (the connection's LIVE rows of [ISSUES_TABLE]: not deleted and not moved out of scope, the issues the data profile
 * and the reports see) and `conn.bytes.est` ([apportion], which weighs every stored row, tombstones included) per connection id, for
 * every connection that owns a row in any scanned table.
 */
internal fun connectionEstimateReadings(tables: List<ConnectionTable>, liveIssues: Map<Long?, Long>): List<Reading> {
    val bytes = apportion(tables)
    return (bytes.keys + liveIssues.keys.filterNotNull()).toSortedSet().flatMap { id ->
        listOf(
            Reading(TelemetryMetrics.CONN_ISSUES, (liveIssues[id] ?: 0L).toDouble(), id.toString()),
            Reading(TelemetryMetrics.CONN_BYTES_EST, (bytes[id] ?: 0.0).roundToLong().toDouble(), id.toString()),
        )
    }
}

/** [connectionEstimateReadings] over a fresh scan: the tables, then the live issue counts. */
internal suspend fun HeavyReader.connectionEstimateReadings(): List<Reading> {
    val tables = connectionTables()
    val live = read {
        connectionCounts(
            "SELECT connection_id::bigint, count(*)::bigint FROM raw.jira_issues " +
                "WHERE deleted_at IS NULL AND moved_out_at IS NULL GROUP BY connection_id",
        )
    }
    return connectionEstimateReadings(tables, live)
}
