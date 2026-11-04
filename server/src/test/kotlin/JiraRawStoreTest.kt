package ch.nokillswit

import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.db.StatementProbe
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawChangelogInput
import ch.nokillswit.jira.RawEntityInput
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.RawWorklogInput
import ch.nokillswit.jira.trimmedWorklogCanonicalJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `jira/JiraRawStore.kt` (v0.2.0 plan §7 "REFERENCE"/"ISSUES"): the sha256 diff rule shared by
 * `upsertIssue`/`upsertEntity`, resurrection of a tombstoned row, and the REFERENCE pass's
 * end-of-pass tombstone sweep.
 */
class JiraRawStoreTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())

    private suspend fun createConnection(): UInt {
        val ds = SyncedStubFixture.dataSources()
        return ds.create(
            DataSourceRequest(
                name = unique("jira-raw-store"),
                enabled = true,
                syncIntervalMinutes = 60,
                backfillFrom = defaultBackfillFrom(),
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = TEST_JIRA_SITE_URL,
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
        payload = Json.parseToJsonElement(
            """{"id":"$issueId","key":"ENG-$issueId","fields":{"summary":"$summary","project":{"id":"1","key":"ENG"}}}""",
        ),
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

    // A NUL is not storable in PostgreSQL jsonb: canonicalJson replaces it with U+FFFD before the text is hashed and stored.
    private fun nulPayload(issueId: Long) =
        """{"id":"$issueId","key":"ENG-$issueId","k\u0000ey":1,""" +
            """"fields":{"summary":"before\u0000after","project":{"id":"1","key":"ENG"}}}"""

    @Test
    fun `an issue whose payload holds a NUL is stored with U+FFFD, and the same payload again is UNCHANGED`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        val input = issue(30, 1_000).copy(payload = Json.parseToJsonElement(nulPayload(30)))

        assertEquals(RawUpsertOutcome.INSERTED, store.upsertIssue(connId, input, now = 1_000))
        assertEquals(RawUpsertOutcome.UNCHANGED, store.upsertIssue(connId, input, now = 2_000), "the stored hash is stable across fetches")

        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll()
                .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq 30L) }
                .toList().single()
        }
        val payload = row[JiraRawStore.Issues.payload]
        assertTrue(payload.contains("before�after"), payload)
        assertTrue(payload.contains("\"k�ey\""), "object keys are replaced too: $payload")
        // jsonb reformats what it stores, so the hash is checked against the canonical text it was taken from.
        val replacedForm = nulPayload(30).replace("\\u0000", "\\uFFFD")
        assertEquals(sha256Hex(canonicalJson(nulPayload(30))), row[JiraRawStore.Issues.sha256], "the stored text and its hash agree")
        assertEquals(sha256Hex(canonicalJson(replacedForm)), row[JiraRawStore.Issues.sha256], "a NUL hashes as its replacement")
        assertEquals(1_000L, row[JiraRawStore.Issues.changedAt])
    }

    @Test
    fun `an entity, a worklog and a changelog history with a NUL are stored, and re-writing them is a no-op`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        val entity = """{"id":"summary","name":"Sum\u0000mary"}"""
        assertEquals(RawUpsertOutcome.INSERTED, store.upsertEntity(connId, "FIELD", "summary", entity, now = 1_000))
        assertEquals(RawUpsertOutcome.UNCHANGED, store.upsertEntity(connId, "FIELD", "summary", entity, now = 2_000))

        val worklog = """{"id":"700300","issueId":"8","timeSpent":"a\u0000b"}"""
        assertEquals(RawUpsertOutcome.INSERTED, store.upsertWorklog(connId, 8L, 700_300L, 1_000, worklog, now = 1_000))
        assertEquals(RawUpsertOutcome.UNCHANGED, store.upsertWorklog(connId, 8L, 700_300L, 1_000, worklog, now = 2_000))

        val history = """{"id":"500300","items":[{"toString":"x\u0000y"}]}"""
        repeat(2) {
            store.insertChangelog(connId, 500_300L, 8L, 1_000L, "acc-1", history, now = 1_000)
        }
        assertEquals(1L, store.countChangelogs(connId))
        assertEquals(1L, store.countWorklogs(connId))
        val stored = store.changelogPayloadsForIssue(connId, 8L).single()
        assertTrue(stored.contains("x�y"), stored)
        assertTrue(store.worklogPayloadsForIssue(connId, 8L).single().contains("a�b"), "a kept key of a worklog holds the replacement")
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
        assertEquals(listOf(10L, 30L), store.staleChangelogIssueIds(connId, limit = 100, excluding = setOf(20L)), "a run-local exclusion")
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
        assertEquals(emptyList(), store.staleWorklogIssueIds(connId, limit = 100, excluding = setOf(60L)), "a run-local exclusion")
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

    // --- The batch writers (JiraRawBatchWrites): the rows and outcomes of the per-row path, in a handful of statements -------------

    /** Every row of [table] for [connId] but its connection id, as column-name maps, sorted — the comparison form of the batch tests. */
    private suspend fun rowsOf(table: Table, connectionColumn: Column<EntityID<UInt>>, connId: UInt): List<Map<String, Any?>> =
        suspendTransaction(sharedDatabaseForTests()) {
            table.selectAll().where { connectionColumn eq connId }.toList().map { row ->
                table.columns.filter { it != connectionColumn }.associate { it.name to row[it] }
            }
        }.sortedBy { it.toString() }

    @Test
    fun `upsertIssues leaves the rows and returns the outcomes the per-row path does - new, unchanged, changed, resurrected, repeated`() =
        runBlocking {
            val perRow = createConnection()
            val batch = createConnection()
            val store = rawStore()
            val issues = JiraRawStore.Issues
            val seed = listOf(issue(1, 1_000), issue(2, 1_000), issue(3, 1_000), issue(4, 1_000))
            for (connId in listOf(perRow, batch)) {
                seed.forEach { store.upsertIssue(connId, it, now = 1_000) }
                suspendTransaction(sharedDatabaseForTests()) {
                    // 3 was deleted, 4 moved out; every issue was worklog-synced (a resurrection must reset that stamp).
                    issues.update({ (issues.connectionId eq connId) and (issues.issueId eq 3L) }) { it[deletedAt] = 1_500L }
                    issues.update({ (issues.connectionId eq connId) and (issues.issueId eq 4L) }) { it[movedOutAt] = 1_500L }
                    issues.update({ issues.connectionId eq connId }) {
                        it[worklogsSyncedAt] = 1_200L
                        it[needsProcessing] = false
                    }
                }
            }
            val page = listOf(
                issue(1, 1_000), // unchanged
                issue(2, 2_000, summary = "edited"), // changed
                issue(3, 1_000), // same payload, but tombstoned: resurrected
                issue(4, 2_000, summary = "edited"), // moved out, edited: resurrected
                issue(5, 2_000), // new
                issue(6, 2_000).copy(payload = Json.parseToJsonElement(nulPayload(6))), // new, with a NUL
                issue(5, 3_000, summary = "edited again"), // repeats 5 within the page: judged against the first write
            )
            val expected = listOf(
                RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED, RawUpsertOutcome.RESURRECTED,
                RawUpsertOutcome.INSERTED, RawUpsertOutcome.INSERTED, RawUpsertOutcome.CHANGED,
            )

            val perRowOutcomes = page.map { store.upsertIssue(perRow, it, now = 2_000) }
            val batchOutcomes = store.upsertIssues(batch, page, now = 2_000)

            assertEquals(expected, perRowOutcomes)
            assertEquals(expected, batchOutcomes)
            val batchRows = rowsOf(issues, issues.connectionId, batch)
            assertEquals(rowsOf(issues, issues.connectionId, perRow), batchRows)
            assertEquals(6, batchRows.size)
            val resurrected = batchRows.first { it["issue_id"] == 3L }
            assertNull(resurrected["deleted_at"])
            assertNull(resurrected["worklogs_synced_at"], "a resurrected issue is worklog-backfilled again")
            assertEquals(1_200L, batchRows.first { it["issue_id"] == 1L }["worklogs_synced_at"], "an unchanged issue keeps its stamp")
            assertEquals(emptyList(), store.upsertIssues(batch, emptyList(), now = 2_000))
        }

    @Test
    fun `upsertIssues over more keys than one IN list carries inserts them all, then touches them all`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        val page = (1L..2_500L).map { issue(it, 1_000) }

        assertTrue(store.upsertIssues(connId, page, now = 1_000).all { it == RawUpsertOutcome.INSERTED })
        assertTrue(store.upsertIssues(connId, page, now = 2_000).all { it == RawUpsertOutcome.UNCHANGED })

        assertEquals(2_500L, store.countIssues(connId))
        val rows = rowsOf(JiraRawStore.Issues, JiraRawStore.Issues.connectionId, connId)
        assertTrue(rows.all { it["fetched_at"] == 2_000L && it["changed_at"] == 1_000L }, "touched, never changed")
    }

    @Test
    fun `upsertEntities leaves the rows and returns the outcomes the per-row path does`() = runBlocking {
        val perRow = createConnection()
        val batch = createConnection()
        val store = rawStore()
        val entities = JiraRawStore.Entities
        fun payload(id: String, name: String) = """{"id":"$id","name":"$name"}"""
        for (connId in listOf(perRow, batch)) {
            listOf("1", "2", "3").forEach { store.upsertEntity(connId, "STATUS", it, payload(it, "n$it"), now = 1_000) }
            // another kind with the same entity id must stay out of the batch's reach
            store.upsertEntity(connId, "FIELD", "1", payload("1", "field"), now = 1_000)
            suspendTransaction(sharedDatabaseForTests()) {
                entities.update({ (entities.connectionId eq connId) and (entities.kind eq "STATUS") and (entities.entityId eq "3") }) {
                    it[deletedAt] = 1_500L
                }
            }
        }
        val page = listOf(
            RawEntityInput("1", Json.parseToJsonElement(payload("1", "n1"))), // unchanged
            RawEntityInput("2", Json.parseToJsonElement(payload("2", "renamed"))), // changed
            RawEntityInput("3", Json.parseToJsonElement(payload("3", "n3"))), // tombstoned: resurrected
            RawEntityInput("4", Json.parseToJsonElement(payload("4", "n4"))), // new
            RawEntityInput("5", Json.parseToJsonElement("""{"id":"5","name":"Sum\u0000mary"}""")), // new, with a NUL
            RawEntityInput("4", Json.parseToJsonElement(payload("4", "n4 again"))), // repeats 4
        )
        val expected = listOf(
            RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED, RawUpsertOutcome.INSERTED,
            RawUpsertOutcome.INSERTED, RawUpsertOutcome.CHANGED,
        )

        val perRowOutcomes = page.map { store.upsertEntity(perRow, "STATUS", it.entityId, it.payload, now = 2_000) }
        val batchOutcomes = store.upsertEntities(batch, "STATUS", page, now = 2_000)

        assertEquals(expected, perRowOutcomes)
        assertEquals(expected, batchOutcomes)
        assertEquals(rowsOf(entities, entities.connectionId, perRow), rowsOf(entities, entities.connectionId, batch))
        assertEquals(6, rowsOf(entities, entities.connectionId, batch).size)
    }

    @Test
    fun `upsertWorklogs leaves the rows and returns the outcomes the per-row path does`() = runBlocking {
        val perRow = createConnection()
        val batch = createConnection()
        val store = rawStore()
        val worklogs = JiraRawStore.Worklogs
        for (connId in listOf(perRow, batch)) {
            listOf(1L, 2L, 3L).forEach {
                store.upsertWorklog(connId, 8L, 800_000L + it, 1_000, worklogPayload(800_000L + it, 8L), now = 1_000)
            }
            store.tombstoneWorklog(connId, 800_003L, now = 1_500)
        }
        val page = listOf(
            RawWorklogInput(8L, 800_001L, 1_000, worklogPayload(800_001L, 8L)), // unchanged
            RawWorklogInput(8L, 800_002L, 2_000, worklogPayload(800_002L, 8L, timeSpent = 7_200)), // changed
            RawWorklogInput(8L, 800_003L, 1_000, worklogPayload(800_003L, 8L)), // tombstoned: resurrected
            RawWorklogInput(9L, 800_004L, 2_000, worklogPayload(800_004L, 9L)), // new
            RawWorklogInput(9L, 800_005L, 2_000, """{"id":"800005","issueId":"9","timeSpent":"a\u0000b"}"""), // new, with a NUL
            RawWorklogInput(9L, 800_004L, 3_000, worklogPayload(800_004L, 9L, timeSpent = 60)), // repeats 800004
        )
        val expected = listOf(
            RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.CHANGED, RawUpsertOutcome.RESURRECTED, RawUpsertOutcome.INSERTED,
            RawUpsertOutcome.INSERTED, RawUpsertOutcome.CHANGED,
        )

        val perRowOutcomes = page.map {
            store.upsertWorklog(perRow, it.issueId, it.worklogId, it.worklogUpdatedAt, it.payloadJson, now = 2_000)
        }
        val batchOutcomes = store.upsertWorklogs(batch, page, now = 2_000)

        assertEquals(expected, perRowOutcomes)
        assertEquals(expected, batchOutcomes)
        assertEquals(rowsOf(worklogs, worklogs.connectionId, perRow), rowsOf(worklogs, worklogs.connectionId, batch))
        assertEquals(5L, store.countWorklogs(batch))
    }

    @Test
    fun `upsertWorklogs writes a big batch in bounded groups - the same rows the per-row path leaves`() = runBlocking {
        val perRow = createConnection()
        val batch = createConnection()
        val store = rawStore()
        val worklogs = JiraRawStore.Worklogs
        // 1,100 rows: a full 1,024-row group is ONE statement; the 76 left decompose (64 + 8 + 4).
        val page = (1L..1_100L).map { RawWorklogInput(8L, 900_000L + it, it, worklogPayload(900_000L + it, 8L, timeSpent = it)) }
        val sizes = CopyOnWriteArrayList<Int>()
        // Only this call's coroutine sees the probe, and only worklog statements are kept.
        withContext(StatementProbe { table, size -> if (table == worklogs) sizes += size }) {
            assertEquals(List(1_100) { RawUpsertOutcome.INSERTED }, store.upsertWorklogs(batch, page, now = 2_000))
        }
        assertEquals(listOf(1_024, 64, 8, 4), sizes)
        page.forEach { store.upsertWorklog(perRow, it.issueId, it.worklogId, it.worklogUpdatedAt, it.payloadJson, now = 2_000) }
        assertEquals(rowsOf(worklogs, worklogs.connectionId, perRow), rowsOf(worklogs, worklogs.connectionId, batch))
        assertEquals(1_100L, store.countWorklogs(batch))
    }

    @Test
    fun `upsertWorklogs bounds a group by its characters too - 100 worklogs of 12,000 characters go out in groups of at most 43 rows`() =
        runBlocking {
            val connId = createConnection()
            val store = rawStore()
            val padding = "x".repeat(12_000)
            val page = (1L..100L).map {
                RawWorklogInput(8L, 910_000L + it, it, """{"id":"${910_000L + it}","issueId":"8","comment":"$padding"}""")
            }
            val sizes = CopyOnWriteArrayList<Int>()
            withContext(StatementProbe { table, size -> if (table == JiraRawStore.Worklogs) sizes += size }) {
                store.upsertWorklogs(connId, page, now = 2_000)
            }
            // Groups of 43, 43 and 14 rows (43 x ~12,050 characters <= 512 Ki), each decomposed into power-of-two statements.
            assertEquals(listOf(32, 8, 2, 1, 32, 8, 2, 1, 8, 4, 2), sizes)
            assertEquals(100L, store.countWorklogs(connId))
        }

    /** The statements [tx] still keeps (Exposed lists every one it ran until the transaction ends); a rename on an upgrade fails here. */
    private fun executedStatements(tx: R2dbcTransaction): Int {
        val field = R2dbcTransaction::class.java.getDeclaredField("executedStatements").also { it.isAccessible = true }
        return (field.get(tx) as List<*>).size
    }

    @Test
    fun `a flush of changed worklogs keeps none of its per-row UPDATE statements and leaves the rows a fresh insert does`() = runBlocking {
        val changed = createConnection()
        val fresh = createConnection()
        val store = rawStore()
        val worklogs = JiraRawStore.Worklogs
        // The version moves `timeSpentSeconds` (a kept field; `comment` is trimmed away before the hash).
        fun page(version: Long) = (1L..1_200L).map {
            RawWorklogInput(8L, 920_000L + it, it, """{"id":"${920_000L + it}","issueId":"8","timeSpentSeconds":${it + version}}""")
        }
        store.upsertWorklogs(changed, page(0), now = 1_000)
        suspendTransaction(sharedDatabaseForTests()) {
            // The control: the single-row path keeps its statements (so the counter works and the writer below joins THIS transaction).
            store.upsertWorklog(changed, 8L, 920_001L, 1L, """{"id":"920001","issueId":"8","timeSpentSeconds":1}""", now = 1_500)
            assertTrue(executedStatements(this) > 0, "the control: a single-row write leaves its statements listed")
            val outcomes = store.upsertWorklogs(changed, page(10_000), now = 2_000)
            assertEquals(List(1_200) { RawUpsertOutcome.CHANGED }, outcomes)
            // 1,200 UPDATEs plus the key reads ran in this transaction; none of them may still be listed.
            assertTrue(executedStatements(this) <= 1, "the flush left ${executedStatements(this)} executed statements reachable")
        }
        store.upsertWorklogs(fresh, page(10_000), now = 2_000)
        // The same rows but for first_seen_at, which a change keeps from the insert (1,000) and a fresh insert sets (2,000).
        fun withoutFirstSeen(rows: List<Map<String, Any?>>) = rows.map { it - worklogs.firstSeenAt.name }
        assertEquals(
            withoutFirstSeen(rowsOf(worklogs, worklogs.connectionId, fresh)),
            withoutFirstSeen(rowsOf(worklogs, worklogs.connectionId, changed)),
        )
        assertEquals(1_200L, store.countWorklogs(changed))
    }

    @Test
    fun `a batch worklog is stored under the hash of the very text it is rendered to`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        val payload = """{"timeSpentSeconds":60,"id":"930001","issueId":"8","author":{"accountId":"a","emailAddress":"e"}}"""
        val input = RawWorklogInput(8L, 930_001L, 5L, payload)
        store.upsertWorklogs(connId, listOf(input), now = 1_000)
        val row = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq connId }.toList().single()
        }
        val rendered = trimmedWorklogCanonicalJson(input.payloadJson)
        assertEquals(sha256Hex(rendered), row[JiraRawStore.Worklogs.sha256])
        assertEquals(Json.parseToJsonElement(rendered), Json.parseToJsonElement(row[JiraRawStore.Worklogs.payload]))
    }

    @Test
    fun `insertChangelogs leaves the rows the per-row inserts do - a present history and a repeated one keep the first write`() =
        runBlocking {
            val perRow = createConnection()
            val batch = createConnection()
            val store = rawStore()
            val changelogs = JiraRawStore.Changelogs
            val authorJson = """{"accountId":"a","emailAddress":"e"}"""
            fun history(id: Long, issueId: Long, author: String?, marker: String = "x") =
                RawChangelogInput(id, issueId, id * 10, author, """{"id":"$id","marker":"$marker","author":$authorJson}""")
            for (connId in listOf(perRow, batch)) {
                store.insertChangelog(connId, 900_001L, 1L, 10, "acc-first", """{"id":"900001","marker":"present"}""", now = 1_000)
            }
            val page = listOf(
                history(900_001, 1, "acc-other", "again"), // already stored: dropped
                history(900_002, 1, "acc-1"),
                history(900_003, 2, null), // no author
                history(900_002, 1, "acc-2", "repeated"), // repeats 900002 within the page: dropped
                RawChangelogInput(900_004, 2, 5, "acc-1", """{"id":"900004","items":[{"toString":"x\u0000y"}]}"""), // a NUL
            )

            page.forEach {
                store.insertChangelog(perRow, it.historyId, it.issueId, it.createdAt, it.authorAccountId, it.payloadJson, now = 2_000)
            }
            store.insertChangelogs(batch, page, now = 2_000)
            store.insertChangelogs(batch, emptyList(), now = 2_000)

            val batchRows = rowsOf(changelogs, changelogs.connectionId, batch)
            assertEquals(rowsOf(changelogs, changelogs.connectionId, perRow), batchRows)
            assertEquals(4, batchRows.size)
            assertEquals("acc-first", batchRows.first { it["history_id"] == 900_001L }["author_account_id"])
            assertEquals("acc-1", batchRows.first { it["history_id"] == 900_002L }["author_account_id"])
            assertTrue(store.changelogPayloadsForIssue(batch, 2L).any { it.contains("x�y") })
            assertTrue(store.changelogPayloadsForIssue(batch, 1L).none { it.contains("emailAddress") }, "the author is slimmed")
        }

    @Test
    fun `markWorklogsSynced for a list stamps exactly those issues and flags them for processing`() = runBlocking {
        val connId = createConnection()
        val store = rawStore()
        (70L..72L).forEach { store.upsertIssue(connId, issue(it, 1_000), now = 1_000) }
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ JiraRawStore.Issues.connectionId eq connId }) { it[needsProcessing] = false }
        }

        store.markWorklogsSynced(connId, emptyList(), now = 4_000)
        assertEquals(listOf(70L, 71L, 72L), store.staleWorklogIssueIds(connId, limit = 10), "an empty list stamps nothing")
        store.markWorklogsSynced(connId, listOf(70L, 72L), now = 5_000)

        assertEquals(listOf(71L), store.staleWorklogIssueIds(connId, limit = 10))
        val flagged = rowsOf(JiraRawStore.Issues, JiraRawStore.Issues.connectionId, connId)
            .associate { it["issue_id"] as Long to (it["worklogs_synced_at"] to it["needs_processing"]) }
        assertEquals(mapOf(70L to (5_000L to true), 71L to (null to false), 72L to (5_000L to true)), flagged)
    }
}
