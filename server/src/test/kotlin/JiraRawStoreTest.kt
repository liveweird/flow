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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `jira/JiraRawStore.kt` (v0.2.0 plan §7 "REFERENCE"/"ISSUES"): the sha256 diff rule shared by
 * `upsertIssue`/`upsertEntity`, resurrection of a tombstoned row, and the REFERENCE pass's
 * end-of-pass tombstone sweep.
 */
class JiraRawStoreTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())

    private suspend fun createConnection(): UInt {
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

    // --- V11: raw.jira_changelogs / raw.jira_worklogs (commit 7a) --------------------------------

    @Test
    fun `insertChangelog is append-only - the same history id is a no-op the second time`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.insertChangelog(
            connId, historyId = 500_001L, issueId = 1L, createdAt = 1_000L, authorAccountId = "acc-1",
            payloadJson = """{"id":"500001"}""", now = 1_000,
        )
        // A different payload/author for the SAME (connection, history_id) — still a no-op: history rows are immutable.
        store.insertChangelog(
            connId, historyId = 500_001L, issueId = 1L, createdAt = 9_999L, authorAccountId = "acc-2",
            payloadJson = """{"id":"500001","changed":true}""", now = 2_000,
        )

        assertEquals(1L, store.countChangelogs(connId))
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Changelogs.selectAll()
                .where { (JiraRawStore.Changelogs.connectionId eq connId) and (JiraRawStore.Changelogs.historyId eq 500_001L) }
                .toList().single()
        }
        assertEquals(1_000L, row[JiraRawStore.Changelogs.createdAt], "the FIRST insert wins — dedup, not overwrite")
        assertEquals("acc-1", row[JiraRawStore.Changelogs.authorAccountId])
    }

    @Test
    fun `staleChangelogIssueIds returns never-synced and changed-since-sync issues, ascending, never tombstoned`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(20, 1_000), now = 1_000) // never synced
        store.upsertIssue(connId, issue(10, 1_000), now = 1_000) // never synced, lower id
        store.upsertIssue(connId, issue(30, 1_000), now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            // 30 was synced, then changed again afterward — stale.
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 30L) }) {
                it[changelogSyncedAt] = 500L
                it[changedAt] = 1_500L
            }
        }
        store.upsertIssue(connId, issue(40, 1_000), now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            // 40 was synced AFTER its last change — up to date, must not appear.
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 40L) }) {
                it[changelogSyncedAt] = 2_000L
            }
        }
        store.upsertIssue(connId, issue(5, 1_000), now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            // 5 is tombstoned — must never appear.
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 5L) }) {
                it[deletedAt] = 999L
            }
        }

        val stale = store.staleChangelogIssueIds(connId, limit = 100)
        assertEquals(listOf(10L, 20L, 30L), stale)
    }

    @Test
    fun `markChangelogSynced sets changelog_synced_at and flags needs_processing for every id`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(6, 1_000), now = 1_000)
        store.upsertIssue(connId, issue(7, 1_000), now = 1_000)

        store.markChangelogSynced(connId, listOf(6L, 7L), now = 5_000)

        val rows = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll()
                .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId inList listOf(6L, 7L)) }
                .toList()
        }
        rows.forEach {
            assertEquals(5_000L, it[JiraRawStore.Issues.changelogSyncedAt])
            assertEquals(true, it[JiraRawStore.Issues.needsProcessing])
        }
    }

    private fun worklogPayload(worklogId: Long, issueId: Long, timeSpent: Long = 3_600) =
        """{"id":"$worklogId","issueId":"$issueId","timeSpentSeconds":$timeSpent}"""

    @Test
    fun `a new worklog is inserted`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        val outcome = store.upsertWorklog(
            connId, issueId = 8L, worklogId = 700_100L, worklogUpdatedAt = 1_000, worklogPayload(700_100L, 8L), now = 1_000,
        )
        assertEquals(RawUpsertOutcome.INSERTED, outcome)
        assertEquals(1L, store.countWorklogs(connId))
    }

    @Test
    fun `an unchanged worklog re-fetch only bumps fetched_at`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertWorklog(connId, 8L, 700_101L, 1_000, worklogPayload(700_101L, 8L), now = 1_000)
        val outcome = store.upsertWorklog(connId, 8L, 700_101L, 1_000, worklogPayload(700_101L, 8L), now = 2_000)
        assertEquals(RawUpsertOutcome.UNCHANGED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Worklogs.selectAll()
                .where { (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq 700_101L) }
                .toList().single()
        }
        assertEquals(2_000L, row[JiraRawStore.Worklogs.fetchedAt])
        assertEquals(1_000L, row[JiraRawStore.Worklogs.changedAt])
    }

    @Test
    fun `a genuine worklog change refreshes payload and changed_at`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertWorklog(connId, 8L, 700_102L, 1_000, worklogPayload(700_102L, 8L, timeSpent = 3_600), now = 1_000)
        val outcome = store.upsertWorklog(connId, 8L, 700_102L, 2_000, worklogPayload(700_102L, 8L, timeSpent = 7_200), now = 2_000)
        assertEquals(RawUpsertOutcome.CHANGED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Worklogs.selectAll()
                .where { (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq 700_102L) }
                .toList().single()
        }
        assertEquals(2_000L, row[JiraRawStore.Worklogs.changedAt])
        assertEquals(2_000L, row[JiraRawStore.Worklogs.worklogUpdatedAt])
    }

    @Test
    fun `tombstoneWorklog sets deleted_at, and is a no-op for a worklog never stored`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertWorklog(connId, 8L, 700_103L, 1_000, worklogPayload(700_103L, 8L), now = 1_000)

        val affected = store.tombstoneWorklog(connId, 700_103L, now = 5_000)
        assertEquals(1, affected)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Worklogs.selectAll()
                .where { (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq 700_103L) }
                .toList().single()
        }
        assertEquals(5_000L, row[JiraRawStore.Worklogs.deletedAt])

        val noopAffected = store.tombstoneWorklog(connId, worklogId = 999_999L, now = 6_000)
        assertEquals(0, noopAffected, "a worklog Flow never stored (out of scope, A1) is a silent no-op")
    }

    @Test
    fun `a resurrected worklog seen again after being tombstoned clears deleted_at`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertWorklog(connId, 8L, 700_104L, 1_000, worklogPayload(700_104L, 8L), now = 1_000)
        store.tombstoneWorklog(connId, 700_104L, now = 2_000)

        val outcome = store.upsertWorklog(connId, 8L, 700_104L, 3_000, worklogPayload(700_104L, 8L, timeSpent = 9_999), now = 3_000)
        assertEquals(RawUpsertOutcome.RESURRECTED, outcome)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Worklogs.selectAll()
                .where { (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq 700_104L) }
                .toList().single()
        }
        assertNull(row[JiraRawStore.Worklogs.deletedAt])
    }

    @Test
    fun `knownInScopeIssueIds excludes ids not stored and tombstoned issues`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(50, 1_000), now = 1_000)
        store.upsertIssue(connId, issue(51, 1_000), now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 51L) }) {
                it[deletedAt] = 999L
            }
        }

        val known = store.knownInScopeIssueIds(connId, listOf(50L, 51L, 999_999L))
        assertEquals(setOf(50L), known)
    }

    @Test
    fun `staleWorklogIssueIds returns never-worklog-synced, never tombstoned issues`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.upsertIssue(connId, issue(60, 1_000), now = 1_000)
        store.upsertIssue(connId, issue(61, 1_000), now = 1_000)
        store.markWorklogsSynced(connId, 61L, now = 5_000)

        val stale = store.staleWorklogIssueIds(connId, limit = 100)
        assertEquals(listOf(60L), stale)
    }

    @Test
    fun `purgeChangelogsBatch and purgeWorklogsBatch drain a connection's V11 rows`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        store.insertChangelog(connId, 500_200L, 9L, 1_000, "acc", """{"id":"500200"}""", now = 1_000)
        store.upsertWorklog(connId, 9L, 700_200L, 1_000, worklogPayload(700_200L, 9L), now = 1_000)

        var deletedChangelogs = 0
        while (true) {
            val n = store.purgeChangelogsBatch(connId, batchSize = 10)
            deletedChangelogs += n
            if (n == 0) break
        }
        var deletedWorklogs = 0
        while (true) {
            val n = store.purgeWorklogsBatch(connId, batchSize = 10)
            deletedWorklogs += n
            if (n == 0) break
        }
        assertEquals(1, deletedChangelogs)
        assertEquals(1, deletedWorklogs)
        assertEquals(0L, store.countChangelogs(connId))
        assertEquals(0L, store.countWorklogs(connId))
    }
}
