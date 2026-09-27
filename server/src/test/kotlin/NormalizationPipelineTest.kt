package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.WorkItemStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val normMigrated = AtomicBoolean(false)

// Same rationale as JiraSyncPipelineTest.kt's identical helper: this suite drives JiraConnector
// directly against sharedDatabaseForTests(), so nothing else in this JVM fork is guaranteed to have
// run Flyway first.
private fun ensureNormMigrated() {
    if (normMigrated.compareAndSet(false, true)) {
        org.flywaydb.core.Flyway.configure()
            .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

private val IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")

/**
 * A hand-maintained, INDEPENDENT copy of `sample-data/jira/generate.mjs`'s STATUS_CATEGORIES map —
 * cross-checks PROCESS's own category resolution against a second source of truth, never reusing its code.
 */
private val EXPECTED_STATUS_CATEGORY = mapOf(
    "1" to StatusCategory.TODO,
    "3" to StatusCategory.IN_PROGRESS,
    "10001" to StatusCategory.IN_PROGRESS,
    "10002" to StatusCategory.DONE,
    "10003" to StatusCategory.IN_PROGRESS,
    "10004" to StatusCategory.IN_PROGRESS,
)

@Serializable
private data class ExpectedSprints(val perProjectSprintCounts: Map<String, Int>)

@Serializable
private data class ExpectedFixtureNorm(val sprints: ExpectedSprints)

private val EXPECTED_NORM_JSON = Json { ignoreUnknownKeys = true }

private val expectedNormFixture: ExpectedFixtureNorm by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile } ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    EXPECTED_NORM_JSON.decodeFromString(file.readText())
}

/**
 * The normalized layer's own pipeline test (v0.2.0 plan §8/§11, plan commit 8a) — `JiraConnector`'s
 * PROCESS stream, driven through a full `SYNC` (REFERENCE → ISSUES → CHANGELOGS → WORKLOGS →
 * PROCESS) against the SAME `JiraStubServer` fixture `JiraSyncPipelineTest` uses. Asserts
 * invariants over the PERSISTED `norm.*` rows (not Tiling's in-memory guarantees, which
 * `TilingTest.kt` already covers exhaustively) — the DB is the thing that has to be right.
 *
 * `sample-data/jira/expected.json`'s `reopens`/`flagged` counters are computed over the WHOLE
 * simulated dataset, including the out-of-scope `SEC` project (`.claude/docs/ingestion.md`'s "Jira
 * stub" caveat about `worklogs.*` applies identically here — the generator was never asked to
 * scope THESE counters either). This suite therefore cross-checks reopens against an INDEPENDENT
 * re-derivation from the same raw changelog rows PROCESS itself consumed (never trusting the
 * whole-dataset JSON number directly), and treats `flagged`/sprint-carryover counts as
 * plausibility bounds against it instead of exact equality.
 */
class NormalizationPipelineTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())
    private fun cursors() = SyncCursorsService(sharedDatabaseForTests())
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())

    private suspend fun createConnection(dataSources: DataSourceService): UInt = dataSources.create(
        DataSourceRequest(
            name = unique("jira-norm"),
            enabled = true,
            syncIntervalMinutes = 60,
            backfillFrom = "2025-09-01",
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                email = "svc-${unique("acct")}@example.com",
                apiToken = "token-${UUID.randomUUID()}",
                projectKeys = IN_SCOPE_PROJECT_KEYS,
                authScheme = JiraAuthScheme.BASIC,
            ),
        ),
    )

    private fun buildJiraHttp(): JiraHttp {
        val httpClient = HttpClient(OkHttp) {
            engine { preconfigured = okhttp3.OkHttpClient() }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }
        return JiraHttp(httpClient, maxRetries = 4, maxResponseBytes = 33_554_432L, maxConcurrentRequests = 4)
    }

    private fun buildConnector(): JiraConnector = JiraConnector(
        newClient = { _, email, apiToken, authScheme ->
            val stubBaseUrl = JiraStubServer.start()
            HttpJiraClient(buildJiraHttp(), stubBaseUrl, stubBaseUrl, email, apiToken, authScheme)
        },
        sync = JiraSyncDependencies(
            dataSources = dataSources(),
            rawStore = rawStore(),
            cursors = cursors(),
            database = sharedDatabaseForTests(),
            workItems = workItems(),
            incrementalOverlapMinutes = 10,
            issuesPageSize = 100,
        ),
    )

    private fun claimFor(connId: UInt, kind: SyncJobKind = SyncJobKind.SYNC) = SyncJobClaim(
        id = 1u,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = kind,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    private suspend fun runConnectorOnce(connector: JiraConnector, connId: UInt, kind: SyncJobKind = SyncJobKind.SYNC) {
        connector.run(SyncJobRunContext(claimFor(connId, kind)) { _, _ -> true })
    }

    @Test
    fun `PROCESS after backfill produces work items whose persisted intervals satisfy every invariant`() = runBlocking {
        ensureNormMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()
        val items = workItems()

        runConnectorOnce(connector, connId) // SYNC now runs REFERENCE -> ISSUES -> CHANGELOGS -> WORKLOGS -> PROCESS

        assertEquals(1200L, items.countWorkItems(connId), "one norm.work_items row per in-scope raw issue")

        val intervalsByIssue = items.statusIntervalsByIssue(connId)
        assertEquals(1200, intervalsByIssue.size, "every work item must have its own status-interval timeline")

        intervalsByIssue.forEach { (issueId, intervals) ->
            val workItem = assertNotNull(items.workItemRow(connId, issueId), "issue $issueId")
            assertEquals(intervals.indices.map { it + 1 }, intervals.map { it.seq }, "issue $issueId: seq must be contiguous from 1")
            assertEquals(
                workItem[WorkItemStore.WorkItems.createdAt], intervals.first().fromAtMs,
                "issue $issueId: the first interval must start at created_at",
            )
            intervals.zipWithNext().forEach { (a, b) ->
                assertEquals(a.toAtMs, b.fromAtMs, "issue $issueId: interval $a -> $b must be contiguous")
            }
            assertEquals(1, intervals.count { it.toAtMs == null }, "issue $issueId: exactly one interval must be open")
        }
    }

    @Test
    fun `reopen count from persisted intervals matches an independent re-derivation from the raw changelog`() = runBlocking {
        ensureNormMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()
        val items = workItems()
        val store = rawStore()

        runConnectorOnce(connector, connId)

        val fromPersistedIntervals = items.statusIntervalsByIssue(connId).values.sumOf { intervals ->
            intervals.zipWithNext().count { (a, b) -> a.category == StatusCategory.DONE && b.category != StatusCategory.DONE }
        }

        // Independent re-derivation straight from raw.jira_issues/raw.jira_changelogs — the SAME
        // rows PROCESS itself read — using the hand-maintained EXPECTED_STATUS_CATEGORY map above,
        // never Tiling/Normalization's own code path.
        val rawIssueIds = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
                .toList().map { it[JiraRawStore.Issues.issueId] }
        }
        var independentReopens = 0
        rawIssueIds.forEach { issueId -> independentReopens += statusTransitionReopens(store, connId, issueId) }

        assertEquals(
            independentReopens, fromPersistedIntervals,
            "reopens computed from norm.* must match an independent re-derivation from raw.*",
        )
        assertTrue(
            fromPersistedIntervals > 0,
            "the synthetic dataset's ~5% reopen rate must produce at least one reopen among 1200 in-scope issues",
        )
        // A sanity bound against the WHOLE-DATASET figure in expected.json (57, including the
        // out-of-scope SEC project's own issues) — the in-scope subset can never exceed it.
        assertTrue(fromPersistedIntervals <= 57, "the in-scope reopen count can never exceed the whole-dataset figure in expected.json")
    }

    @Test
    fun `flagged and sprint reference counts are plausible against expected_json`() = runBlocking {
        ensureNormMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()
        val items = workItems()

        runConnectorOnce(connector, connId)

        val flaggedCount = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.WorkItems.selectAll()
                .where { (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.flagged eq true) }
                .count()
        }
        assertTrue(
            flaggedCount in 1..61,
            "sample-data/jira/expected.json flagged.count (61, whole-dataset) is the in-scope subset's upper bound",
        )

        val sprintCount = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.Sprints.selectAll().where { WorkItemStore.Sprints.connectionId eq connId }.count()
        }
        val expectedSprintCount = expectedNormFixture.sprints.perProjectSprintCounts.values.sum().toLong()
        assertEquals(
            expectedSprintCount, sprintCount,
            "sample-data/jira/expected.json sprints.perProjectSprintCounts (scrum-only, already in-scope)",
        )

        // Assignee: an EXACT cross-check between the raw issue's current `fields.assignee` and the
        // normalized row's `assignee_account_id` — a current-snapshot fact, not history, so this
        // must match one-for-one.
        val assignedInWorkItems = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.WorkItems.selectAll()
                .where { (WorkItemStore.WorkItems.connectionId eq connId) and WorkItemStore.WorkItems.assigneeAccountId.isNotNull() }
                .count()
        }
        val assignedInRaw = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList()
        }.count { row ->
            val payload = Json { ignoreUnknownKeys = true }.parseToJsonElement(row[JiraRawStore.Issues.payload]).jsonObject
            payload.getValue("fields").jsonObject["assignee"]?.let { it.toString() != "null" } == true
        }
        assertEquals(
            assignedInRaw.toLong(), assignedInWorkItems,
            "assignee_account_id must be non-null exactly when the raw issue currently has an assignee",
        )
        Unit
    }

    @Test
    fun `REPROCESS leaves the normalized digest unchanged`() = runBlocking {
        ensureNormMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()
        val items = workItems()

        runConnectorOnce(connector, connId)
        val digestBefore = statusIntervalDigest(items, connId)

        runConnectorOnce(connector, connId, SyncJobKind.REPROCESS)
        val digestAfter = statusIntervalDigest(items, connId)

        assertEquals(digestBefore, digestAfter, "a REPROCESS must rebuild byte-for-byte identical normalized rows")
    }

    @Test
    fun `a processing_version mismatch makes an issue eligible for the next PROCESS pass`() = runBlocking {
        ensureNormMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()
        val store = rawStore()

        runConnectorOnce(connector, connId)
        val issueId = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
                .toList().first()[JiraRawStore.Issues.issueId]
        }
        assertEquals(emptyList(), store.issuesToProcess(connId, currentProcessingVersion = 1, limit = 10).filter { it == issueId })

        // Simulate a PROCESSING_VERSION bump: downgrade one issue's stored version directly.
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }) {
                it[processingVersion] = 0
            }
        }
        val eligible = store.issuesToProcess(connId, currentProcessingVersion = 1, limit = 2_000)
        assertTrue(issueId in eligible, "a stale processing_version must make the issue eligible for the next PROCESS pass")
    }

    @Test
    fun `RECONCILE's tombstone is mirrored onto norm work_items in the same job`() = runBlocking {
        ensureNormMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()
        val items = workItems()

        runConnectorOnce(connector, connId)

        JiraStubServer.setScenarioState("jira-day2", "day2")
        try {
            runConnectorOnce(connector, connId, SyncJobKind.RECONCILE)

            val deletedId = expectedFixtureDay2Deleted()
            val movedId = expectedFixtureDay2Moved()
            val deletedWorkItem = assertNotNull(items.workItemRow(connId, deletedId))
            assertNotNull(deletedWorkItem[WorkItemStore.WorkItems.deletedAt], "norm.work_items must mirror raw.jira_issues.deleted_at")
            val movedWorkItem = assertNotNull(items.workItemRow(connId, movedId))
            assertNotNull(movedWorkItem[WorkItemStore.WorkItems.movedOutAt], "norm.work_items must mirror raw.jira_issues.moved_out_at")
            Unit
        } finally {
            JiraStubServer.resetScenarios()
        }
    }

    private suspend fun statusTransitionReopens(store: JiraRawStore, connId: UInt, issueId: Long): Int {
        val json = Json { ignoreUnknownKeys = true }
        val issue = json.parseToJsonElement(assertNotNull(store.issueForProcessing(connId, issueId)).payloadJson).jsonObject
        val currentStatusId = issue.getValue("fields").jsonObject.getValue("status").jsonObject.getValue("id").jsonPrimitive.content
        val events = store.changelogPayloadsForIssue(connId, issueId)
            .map { json.parseToJsonElement(it).jsonObject }
            .flatMap { history -> history.getValue("items").jsonArray.map { it.jsonObject } }
            .filter { it["fieldId"]?.jsonPrimitive?.contentOrNull == "status" }
            .map { it.getValue("from").jsonPrimitive.content to it.getValue("to").jsonPrimitive.content }
        val categories = (events.map { it.first } + events.map { it.second } + currentStatusId)
            .distinct()
            .associateWith { EXPECTED_STATUS_CATEGORY[it] ?: StatusCategory.UNKNOWN }
        var reopens = 0
        events.forEach { (from, to) ->
            if (categories.getValue(from) == StatusCategory.DONE && categories.getValue(to) != StatusCategory.DONE) reopens++
        }
        return reopens
    }

    private suspend fun statusIntervalDigest(items: WorkItemStore, connId: UInt): String {
        val digest = MessageDigest.getInstance("MD5")
        items.statusIntervalsByIssue(connId).toSortedMap().forEach { (issueId, intervals) ->
            intervals.forEach { interval ->
                val line = "$issueId|${interval.seq}|${interval.statusId}|${interval.fromAtMs}|${interval.toAtMs}|${interval.source}\n"
                digest.update(line.toByteArray())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expectedFixtureDay2Deleted(): Long {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
        return json.getValue("day2").jsonObject.getValue("deletedIssueId").jsonPrimitive.content.toLong()
    }

    private fun expectedFixtureDay2Moved(): Long {
        val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).first { it.isFile }
        val json = Json { ignoreUnknownKeys = true }.parseToJsonElement(file.readText()).jsonObject
        return json.getValue("day2").jsonObject.getValue("movedIssueId").jsonPrimitive.content.toLong()
    }
}
