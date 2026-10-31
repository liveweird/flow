package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceService
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID

/**
 * A `GITLAB` row in `source_connections`, inserted straight into the shared container (V40 admits the kind; no route creates one before the
 * GitLab data-source release). `ConnectorSeamTest` and `KindLeakTest` use it to prove a second connector kind never reaches a Jira-only
 * path. Every row has a unique name and is hard-deleted by its test ([remove]) - the shared table is never left with one.
 */
object GitLabRowFixture {
    /** The minimal GitLab settings: exactly what `GitLabConnectionSettings` declares, no Jira key in it. */
    const val MINIMAL_SETTINGS: String = """{"baseUrl":"https://gitlab.example.com","groupPaths":["acme/platform"]}"""

    suspend fun insert(
        namePrefix: String,
        enabled: Boolean = false,
        settings: String = MINIMAL_SETTINGS,
    ): UInt = suspendTransaction(sharedDatabaseForTests()) {
        val stamp = System.currentTimeMillis()
        DataSourceService.Connections.insert {
            it[kind] = DataSourceKind.GITLAB.name
            it[name] = "$namePrefix-${UUID.randomUUID().toString().take(8)}"
            it[DataSourceService.Connections.enabled] = enabled
            it[secret] = ""
            it[DataSourceService.Connections.settings] = settings
            it[syncIntervalMinutes] = 60
            it[backfillFrom] = "2025-01-01"
            it[reconcileHourUtc] = 3
            it[configRevision] = 1
            it[consecutiveFailures] = 0
            it[createdAt] = stamp
            it[updatedAt] = stamp
            it[markedAsDeleted] = false
        }[DataSourceService.Connections.id].value
    }

    /** Hard delete (jobs first: the queue's FK), so no GITLAB row outlives its test in the shared table. */
    fun remove(connectionId: UInt) {
        PostgresTestSupport.connect().use { c ->
            for (sql in listOf("DELETE FROM sync_jobs WHERE connection_id = ?", "DELETE FROM source_connections WHERE id = ?")) {
                c.prepareStatement(sql).use {
                    it.setInt(1, connectionId.toInt())
                    it.executeUpdate()
                }
            }
        }
    }
}
