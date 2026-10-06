package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** Batch size for the PURGE step's connector-cleanup (plan §0 A2) — bounds one DELETE's row count. */
internal const val JIRA_PURGE_BATCH_SIZE = 500

/** The PURGE step's batched deletes over the four `raw.jira_*` tables (plan §0 A2); the drain loop is [purgeAll] below. */
internal class JiraRawPurge(private val database: R2dbcDatabase) {

    /** One batch of a connection's `raw.jira_issues` rows, oldest-inserted-order-free (the PURGE step, plan §0 A2). */
    suspend fun purgeIssuesBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Issues.select(Issues.issueId).where { Issues.connectionId eq connectionId }.limit(batchSize)
            .map { it[Issues.issueId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Issues.deleteWhere { (Issues.connectionId eq connectionId) and (Issues.issueId inList ids) }
    }

    /** One batch of a connection's `raw.jira_entities` rows (the PURGE step, plan §0 A2). */
    suspend fun purgeEntitiesBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val rows = Entities.select(Entities.kind, Entities.entityId).where { Entities.connectionId eq connectionId }
            .limit(batchSize).toList()
        if (rows.isEmpty()) return@suspendTransaction 0
        rows.groupBy({ it[Entities.kind] }, { it[Entities.entityId] })
            .entries.sumOf { (kind, entityIds) ->
                Entities.deleteWhere {
                    (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and (Entities.entityId inList entityIds)
                }
            }
    }

    /** One batch of a connection's `raw.jira_changelogs` rows (the PURGE step, plan §0 A2, V11). */
    suspend fun purgeChangelogsBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Changelogs.select(Changelogs.historyId).where { Changelogs.connectionId eq connectionId }.limit(batchSize)
            .map { it[Changelogs.historyId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Changelogs.deleteWhere { (Changelogs.connectionId eq connectionId) and (Changelogs.historyId inList ids) }
    }

    /** One batch of a connection's `raw.jira_worklogs` rows (the PURGE step, plan §0 A2, V11). */
    suspend fun purgeWorklogsBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Worklogs.select(Worklogs.worklogId).where { Worklogs.connectionId eq connectionId }.limit(batchSize)
            .map { it[Worklogs.worklogId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId inList ids) }
    }
}

/** Drains a connection's Jira raw rows in batches — the PURGE step (`ingest/Connector.kt`'s `PurgeStep`, plan §0 A2). */
suspend fun JiraRawStore.purgeAll(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE) {
    while (purgeChangelogsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeWorklogsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeIssuesBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeEntitiesBatch(connectionId, batchSize) > 0) { /* drain */ }
}
