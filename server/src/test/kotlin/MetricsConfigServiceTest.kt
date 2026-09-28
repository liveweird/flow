package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.CustomFieldProfile
import ch.nokillswit.ingest.DataProfileSections
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.defaultBackfillFrom
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsFieldConfig
import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsWorkCategoryMapping
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `metrics/MetricsConfigService.kt`'s `defaultConfig`/`options` branches (v0.3.0 M1 commit 4
 * review fix) — direct construction against [sharedDatabaseForTests], no `testApplication`
 * (the `IngestWorkerTest` shape): a profile with no `STORY_POINTS`-detected field, a "Target
 * start" field standing in for "Start date", the UNKNOWN-category status exclusion, and
 * `distinctCustomFieldValues`'s 200-value cap/`truncated` flag.
 */
class MetricsConfigServiceTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())
    private fun metricsConfig(dataSources: DataSourceService) =
        MetricsConfigService(sharedDatabaseForTests(), workItems(), dataSources)

    private val migrated = AtomicBoolean(false)
    private fun ensureMigrated() {
        if (migrated.compareAndSet(false, true)) {
            org.flywaydb.core.Flyway.configure()
                .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }

    private suspend fun createConnection(dataSources: DataSourceService): UInt = dataSources.create(
        DataSourceRequest(
            name = unique("metrics-service-conn"),
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

    private suspend fun seedStatuses(connId: UInt) {
        workItems().replaceStatuses(
            connId,
            listOf(
                StatusRef("1", "To Do", StatusCategory.TODO),
                StatusRef("2", "Weird", StatusCategory.UNKNOWN),
            ),
        )
    }

    private suspend fun seedWorkItem(connId: UInt, issueId: Long, customFieldsJson: String = "{}") {
        val now = System.currentTimeMillis()
        val facts = WorkItemFacts(
            issueKey = "ENG-$issueId",
            projectKey = "ENG",
            issueType = "Story",
            isSubtask = false,
            parentIssueId = null,
            summary = null,
            currentStatusId = "1",
            resolution = null,
            priority = null,
            assigneeAccountId = null,
            reporterAccountId = null,
            createdAtMs = now,
            updatedAtMs = now,
            resolvedAtMs = null,
            storyPoints = null,
            originalEstimateSeconds = null,
            timeSpentSeconds = 0,
            labels = emptyList(),
            components = emptyList(),
            fixVersions = emptyList(),
            teamValueJson = null,
            rank = null,
            customFieldsJson = customFieldsJson,
            tombstone = TombstoneKind.NONE,
        )
        val normalized = NormalizedIssue(
            issueId = issueId,
            facts = facts,
            currentStatusName = "To Do",
            currentStatusCategory = StatusCategory.TODO,
            statusIntervals = listOf(NormalizedStatusInterval(1, "1", "To Do", StatusCategory.TODO, now, null, IntervalSource.CREATED)),
            fieldIntervals = emptyList(),
            fieldChanges = emptyList(),
            worklogs = emptyList(),
            currentSprintIds = emptyList(),
            flagged = false,
            anomalies = emptyList(),
        )
        workItems().replaceWorkItem(connId, normalized, now)
    }

    private fun profileJson(customFields: List<CustomFieldProfile>): String =
        Json.encodeToString(DataProfileSections(customFields = customFields))

    @Test
    fun `defaults exclude UNKNOWN-category statuses, and fields stay null with no profile at all`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        seedWorkItem(connId, issueId = 1L)

        val config = metricsConfig(ds).effectiveConfig(connId)
        assertEquals(false, config.configured)
        assertEquals(listOf("1" to MetricsStage.NOT_STARTED), config.statusStages.map { it.statusId to it.stage })
        assertNull(config.fields.estimateTask, "no data profile at all — nothing to detect a STORY_POINTS field from")
        assertNull(config.fields.estimateEpic)
        assertNull(config.fields.epicStart)
        assertEquals("duedate", config.fields.epicDue)
    }

    @Test
    fun `a profile without a STORY_POINTS-detected field leaves the estimate roles null`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        seedWorkItem(connId, issueId = 2L)
        ds.updateProfile(connId, profileJson(listOf(CustomFieldProfile("customfield_1", "Some Other Field", "string", 0, 0.0, "OTHER"))))

        val config = metricsConfig(ds).effectiveConfig(connId)
        assertNull(config.fields.estimateTask)
        assertNull(config.fields.estimateEpic)
    }

    @Test
    fun `a profile with a Target start field, but no Start date field, is the epicStart fallback`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        seedWorkItem(connId, issueId = 3L)
        ds.updateProfile(
            connId,
            profileJson(listOf(CustomFieldProfile("customfield_2", "Target start", "date", 0, 0.0, "OTHER"))),
        )

        val config = metricsConfig(ds).effectiveConfig(connId)
        assertEquals("customfield_2", config.fields.epicStart)
    }

    @Test
    fun `a Start date field always wins over a Target start field`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        seedWorkItem(connId, issueId = 4L)
        ds.updateProfile(
            connId,
            profileJson(
                listOf(
                    CustomFieldProfile("customfield_3", "Target start", "date", 0, 0.0, "OTHER"),
                    CustomFieldProfile("customfield_4", "Start date", "date", 0, 0.0, "OTHER"),
                ),
            ),
        )

        val config = metricsConfig(ds).effectiveConfig(connId)
        assertEquals("customfield_4", config.fields.epicStart)
    }

    @Test
    fun `distinctCustomFieldValues itself is UNCAPPED - a single multi-valued field with 250 options`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        val options = (1..250).joinToString(",") { """{"id":"v$it","value":"Value $it"}""" }
        seedWorkItem(connId, issueId = 5L, customFieldsJson = """{"customfield_9001":[$options]}""")

        val result = workItems().distinctCustomFieldValues(connId, "customfield_9001")
        assertEquals(250, result.size, "the config table itself has no cap — only the OPTIONS display does")
    }

    @Test
    fun `the options endpoint caps workCategoryValues at 200 and reports truncated`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        val options = (1..250).joinToString(",") { """{"id":"v$it","value":"Value $it"}""" }
        seedWorkItem(connId, issueId = 5L, customFieldsJson = """{"customfield_9001":[$options]}""")

        val optionsResponse = metricsConfig(ds).options(connId, "customfield_9001")
        assertEquals(200, optionsResponse.workCategoryValues.size)
        assertTrue(optionsResponse.workCategoryValuesTruncated)
    }

    @Test
    fun `a work category value beyond the options display cap is still accepted on PUT`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        val options = (1..250).joinToString(",") { """{"id":"v$it","value":"Value $it"}""" }
        seedWorkItem(connId, issueId = 7L, customFieldsJson = """{"customfield_9003":[$options]}""")
        ds.updateProfile(connId, profileJson(listOf(CustomFieldProfile("customfield_9003", "Work Category", "option", 0, 0.0, "OTHER"))))

        // "v250" sorts past the options endpoint's first-200-by-id display window, but the config
        // table itself has no such limit — validation reads the FULL, uncapped set.
        val request = DataSourceMetricsConfigRequest(
            fields = MetricsFieldConfig(workCategory = "customfield_9003"),
            workCategories = listOf(MetricsWorkCategoryMapping("v250", "Value 250", "Some Category")),
        )
        val outcome = metricsConfig(ds).replaceConfig(connId, request)
        assertTrue(outcome.changed)
    }

    @Test
    fun `a field with 200 or fewer values is never marked truncated`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        seedStatuses(connId)
        seedWorkItem(connId, issueId = 6L, customFieldsJson = """{"customfield_9002":{"id":"v1","value":"Only value"}}""")

        val result = workItems().distinctCustomFieldValues(connId, "customfield_9002")
        assertEquals(1, result.size)
        val optionsResponse = metricsConfig(ds).options(connId, "customfield_9002")
        assertEquals(false, optionsResponse.workCategoryValuesTruncated)
    }
}
