package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.RawUpsertOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `jira/JiraRawStore.kt` (v0.2.0 plan §7 "REFERENCE"/"ISSUES"): the sha256 diff rule shared by
 * `upsertIssue`/`upsertEntity`, resurrection of a tombstoned row, and the REFERENCE pass's
 * end-of-pass tombstone sweep.
 */
private val migrated = AtomicBoolean(false)

// See IngestWorkerTest.kt's identical note: this suite constructs services directly against
// sharedDatabaseForTests() without booting a testApplication, so nothing else in this JVM fork is
// guaranteed to have run Flyway first (Gradle test forking, `--tests` filtering).
private fun ensureMigrated() {
    if (migrated.compareAndSet(false, true)) {
        org.flywaydb.core.Flyway.configure()
            .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

class JiraRawStoreTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())

    private suspend fun createConnection(): UInt {
        ensureMigrated()
        val ds = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
        return ds.create(
            DataSourceRequest(
                name = unique("jira-raw-store"),
                enabled = true,
                syncIntervalMinutes = 60,
                backfillFrom = defaultBackfillFrom(),
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                    email = "svc-${unique("acct")}@example.com",
                    apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = listOf("ENG"),
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )
    }

    private fun issue(issueId: Long, updatedAt: Long, summary: String = "sample") = RawIssueInput(
        issueId = issueId,
        issueKey = "ENG-$issueId",
        projectId = 1L,
        projectKey = "ENG",
        issueUpdatedAt = updatedAt,
        payloadJson = """{"id":"$issueId","key":"ENG-$issueId","fields":{"summary":"$summary","project":{"id":"1","key":"ENG"}}}""",
    )

    @Test
    fun `a new issue is inserted and flagged for processing`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        val outcome = store.upsertIssue(connId, issue(1, 1_000), now = 1_000)
        assertEquals(RawUpsertOutcome.INSERTED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll()
                .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 1L) }
                .toList().single()
        }
        assertEquals(true, row[JiraRawStore.Issues.needsProcessing])
        assertEquals(1_000L, row[JiraRawStore.Issues.firstSeenAt])
        assertEquals(1_000L, row[JiraRawStore.Issues.changedAt])
    }

    @Test
    fun `an unchanged re-fetch only bumps fetched_at, never changed_at or payload`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(2, 1_000), now = 1_000)
        val outcome = store.upsertIssue(connId, issue(2, 1_000), now = 2_000)
        assertEquals(RawUpsertOutcome.UNCHANGED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll()
                .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 2L) }
                .toList().single()
        }
        assertEquals(2_000L, row[JiraRawStore.Issues.fetchedAt])
        assertEquals(1_000L, row[JiraRawStore.Issues.changedAt], "an unchanged sha must never bump changed_at")
    }

    @Test
    fun `a genuine change refreshes payload, changed_at and needs_processing`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(3, 1_000, summary = "before"), now = 1_000)
        // Clear needs_processing to prove the change flags it again (PROCESS, plan commit 8, would have cleared it).
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 3L) }) {
                it[needsProcessing] = false
            }
        }
        val outcome = store.upsertIssue(connId, issue(3, 2_000, summary = "after"), now = 2_000)
        assertEquals(RawUpsertOutcome.CHANGED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll()
                .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 3L) }
                .toList().single()
        }
        assertEquals(2_000L, row[JiraRawStore.Issues.changedAt])
        assertEquals(2_000L, row[JiraRawStore.Issues.fetchedAt])
        assertEquals(true, row[JiraRawStore.Issues.needsProcessing])
        assertEquals(2_000L, row[JiraRawStore.Issues.issueUpdatedAt])
    }

    @Test
    fun `a tombstoned issue seen again is resurrected - deleted_at and moved_out_at are cleared`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(4, 1_000), now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 4L) }) {
                it[deletedAt] = 1_500L
            }
        }
        val outcome = store.upsertIssue(connId, issue(4, 2_000), now = 2_000)
        assertEquals(RawUpsertOutcome.RESURRECTED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll()
                .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 4L) }
                .toList().single()
        }
        assertNull(row[JiraRawStore.Issues.deletedAt])
        assertNull(row[JiraRawStore.Issues.movedOutAt])
    }

    @Test
    fun `an entity's unchanged re-fetch only bumps last_seen_at`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertEntity(connId, "FIELD", "summary", """{"id":"summary","name":"Summary"}""", now = 1_000)
        val outcome = store.upsertEntity(connId, "FIELD", "summary", """{"id":"summary","name":"Summary"}""", now = 2_000)
        assertEquals(RawUpsertOutcome.UNCHANGED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Entities.selectAll()
                .where {
                    (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq "FIELD") and
                        (JiraRawStore.Entities.entityId eq "summary")
                }.toList().single()
        }
        assertEquals(2_000L, row[JiraRawStore.Entities.lastSeenAt])
        assertEquals(1_000L, row[JiraRawStore.Entities.changedAt])
    }

    @Test
    fun `entities not seen since the pass start are tombstoned, others are not`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertEntity(connId, "STATUS", "1", """{"id":"1","name":"To Do"}""", now = 1_000)
        store.upsertEntity(connId, "STATUS", "2", """{"id":"2","name":"Done"}""", now = 1_000)
        val passStartedAt = 5_000L
        // Only status "2" is seen again in the new pass.
        store.upsertEntity(connId, "STATUS", "2", """{"id":"2","name":"Done"}""", now = passStartedAt + 100)

        val tombstoned = store.markEntitiesDeletedNotSeenSince(connId, "STATUS", passStartedAt, now = passStartedAt + 200)
        assertEquals(1, tombstoned)

        val rows = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Entities.selectAll()
                .where { (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq "STATUS") }
                .toList().associate { it[JiraRawStore.Entities.entityId] to it[JiraRawStore.Entities.deletedAt] }
        }
        assertNotNull(rows["1"], "status 1 row must still exist")
        assertEquals(passStartedAt + 200, rows["1"])
        assertNull(rows["2"], "status 2 was seen again this pass and must not be tombstoned")
    }

    @Test
    fun `a resurrected entity clears deleted_at`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertEntity(connId, "USER", "acc-1", """{"accountId":"acc-1","displayName":"A"}""", now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Entities.update({
                (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq "USER") and
                    (JiraRawStore.Entities.entityId eq "acc-1")
            }) { it[deletedAt] = 1_500L }
        }
        val outcome = store.upsertEntity(connId, "USER", "acc-1", """{"accountId":"acc-1","displayName":"A"}""", now = 2_000)
        assertEquals(RawUpsertOutcome.RESURRECTED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Entities.selectAll()
                .where {
                    (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq "USER") and
                        (JiraRawStore.Entities.entityId eq "acc-1")
                }.toList().single()
        }
        assertNull(row[JiraRawStore.Entities.deletedAt])
    }

    @Test
    fun `purgeIssuesBatch and purgeEntitiesBatch drain a connection's rows`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(5, 1_000), now = 1_000)
        store.upsertEntity(connId, "FIELD", "summary", """{"id":"summary"}""", now = 1_000)

        var deletedIssues = 0
        while (true) {
            val n = store.purgeIssuesBatch(connId, batchSize = 10)
            deletedIssues += n
            if (n == 0) break
        }
        var deletedEntities = 0
        while (true) {
            val n = store.purgeEntitiesBatch(connId, batchSize = 10)
            deletedEntities += n
            if (n == 0) break
        }
        assertEquals(1, deletedIssues)
        assertEquals(1, deletedEntities)
        assertEquals(0L, store.countIssues(connId))
    }
}
