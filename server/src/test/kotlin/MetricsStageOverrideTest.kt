package ch.nokillswit

import ch.nokillswit.DerivedStubFixture.PINNED_NOW
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.ItemStage
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDomainStatusStage
import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The per-domain status → stage override (`metrics.status_stage_map` rows with a non-empty
 * `domain_key`, `.claude/docs/metrics.md` "Per-domain stage overrides"): DERIVE reads an item's stage
 * for a status from its OWN domain's row when there is one, else the every-domain (`''`) row, else
 * `UNMAPPED`. All tests derive a private DISABLED connection (`.claude/docs/testing.md` "The derived
 * fixture") — never the shared one.
 */
class MetricsStageOverrideTest {
    private fun config() = DerivedStubFixture.metricsConfig()

    /**
     * One hand-built task. [earlier] are statuses it sat in BEFORE [statusId] (1 s each, in order); [movedFromKey] records an
     * `issuekey` change (the project move) at the instant it entered [statusId].
     */
    private suspend fun seedTask(
        connId: UInt,
        issueId: Long,
        issueKey: String,
        projectKey: String,
        statusId: String,
        statusName: String,
        earlier: List<Pair<String, String>> = emptyList(),
        movedFromKey: String? = null,
    ) {
        val createdAt = PINNED_NOW - 10_000L
        val currentFrom = createdAt + earlier.size * 1_000L
        val facts = WorkItemFacts(
            issueKey = issueKey,
            projectKey = projectKey,
            issueType = "Task",
            isSubtask = false,
            parentIssueId = null,
            summary = "A stage-override task",
            currentStatusId = statusId,
            resolution = null,
            priority = null,
            assigneeAccountId = null,
            reporterAccountId = null,
            createdAtMs = createdAt,
            updatedAtMs = createdAt,
            resolvedAtMs = null,
            storyPoints = null,
            originalEstimateSeconds = null,
            timeSpentSeconds = 0,
            labels = emptyList(),
            components = emptyList(),
            fixVersions = emptyList(),
            teamValueJson = null,
            rank = null,
            customFieldsJson = "{}",
            tombstone = TombstoneKind.NONE,
        )
        val normalized = NormalizedIssue(
            issueId = issueId,
            facts = facts,
            currentStatusName = statusName,
            currentStatusCategory = StatusCategory.IN_PROGRESS,
            statusIntervals = earlier.mapIndexed { index, (id, name) ->
                val from = createdAt + index * 1_000L
                val source = if (index == 0) IntervalSource.CREATED else IntervalSource.CHANGE
                NormalizedStatusInterval(index + 1, id, name, StatusCategory.IN_PROGRESS, from, from + 1_000L, source)
            } + NormalizedStatusInterval(
                earlier.size + 1, statusId, statusName, StatusCategory.IN_PROGRESS, currentFrom, null,
                if (earlier.isEmpty()) IntervalSource.CREATED else IntervalSource.CHANGE,
            ),
            fieldIntervals = emptyList(),
            fieldChanges = listOfNotNull(
                movedFromKey?.let { FieldChangeFact("Key", currentFrom, it, it, issueKey, issueKey, fieldId = "issuekey") },
            ),
            worklogs = emptyList(),
            currentSprintIds = emptyList(),
            flagged = false,
            anomalies = emptyList(),
        )
        SyncedStubFixture.workItems().replaceWorkItem(connId, normalized, createdAt)
    }

    private suspend fun stageOf(connId: UInt, issueId: Long): String = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.ItemStage.selectAll()
            .where { (MetricsTables.ItemStage.connectionId eq connId) and (MetricsTables.ItemStage.issueId eq issueId) }
            .toList().single()[MetricsTables.ItemStage.stage]
    }

    private suspend fun deliveryOf(connId: UInt, issueId: Long) = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactTaskDelivery.selectAll()
            .where { (MetricsTables.FactTaskDelivery.connectionId eq connId) and (MetricsTables.FactTaskDelivery.issueId eq issueId) }
            .toList().single()
    }

    @Test
    fun `an override applies to its own domain's items only, for its own status only, and removing it restores the every-domain stage`() =
        runBlocking {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-stage-override", enabled = false)
            // Status 7 is IN_PROGRESS by Jira category (the computed default); status 1 is TODO.
            SyncedStubFixture.workItems().replaceStatuses(
                connId,
                listOf(StatusRef("1", "Backlog", StatusCategory.TODO), StatusRef("7", "In review", StatusCategory.IN_PROGRESS)),
            )
            val aInReview = 910_001L
            val bInReview = 910_002L
            val aBacklog = 910_003L
            seedTask(connId, aInReview, "AAA-1", "AAA", "7", "In review")
            seedTask(connId, bInReview, "BBB-1", "BBB", "7", "In review")
            seedTask(connId, aBacklog, "AAA-2", "AAA", "1", "Backlog")

            val current = config().effectiveConfig(connId)
            assertEquals(setOf("AAA", "BBB"), current.domains.map { it.domainKey }.toSet(), "defaults map the two projects 1:1")
            fun request(overrides: List<MetricsDomainStatusStage>) = DataSourceMetricsConfigRequest(
                statusStages = current.statusStages,
                fields = current.fields,
                domains = current.domains,
                activityTypes = current.activityTypes,
                domainStatusStages = overrides,
            )

            // Domain AAA reads "In review" as DONE; domain BBB has no override.
            config().replaceConfig(connId, request(listOf(MetricsDomainStatusStage("AAA", "7", MetricsStage.DONE))))
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

            assertEquals("DONE", stageOf(connId, aInReview), "the override wins over the every-domain IN_PROGRESS in its own domain")
            assertEquals("IN_PROGRESS", stageOf(connId, bInReview), "another domain keeps the every-domain stage")
            assertEquals("NOT_STARTED", stageOf(connId, aBacklog), "a status the domain does not override keeps the every-domain stage")

            val overriddenFact = deliveryOf(connId, aInReview)
            assertEquals("DONE", overriddenFact[MetricsTables.FactTaskDelivery.currentStage])
            assertNotNull(overriddenFact[MetricsTables.FactTaskDelivery.doneAt], "a DONE override makes the item done")
            val otherFact = deliveryOf(connId, bInReview)
            assertEquals("IN_PROGRESS", otherFact[MetricsTables.FactTaskDelivery.currentStage])
            assertNull(otherFact[MetricsTables.FactTaskDelivery.doneAt])
            assertNotNull(otherFact[MetricsTables.FactTaskDelivery.startedAt])

            // Remove the override: a second DERIVE reads the every-domain stage again.
            config().replaceConfig(connId, request(emptyList()))
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, 2u) }
            assertEquals("IN_PROGRESS", stageOf(connId, aInReview), "removing the override restores the every-domain stage")
            assertNull(deliveryOf(connId, aInReview)[MetricsTables.FactTaskDelivery.doneAt])
        }

    private suspend fun stagesOf(connId: UInt, issueId: Long): List<Pair<String, String>> = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.ItemStage.selectAll()
            .where { (MetricsTables.ItemStage.connectionId eq connId) and (MetricsTables.ItemStage.issueId eq issueId) }
            .orderBy(MetricsTables.ItemStage.validFrom to SortOrder.ASC)
            .toList().map { it[MetricsTables.ItemStage.statusId] to it[MetricsTables.ItemStage.stage] }
    }

    @Test
    fun `a task that moved projects reads its whole history through its CURRENT domain, and an unmapped project key is a domain`() =
        runBlocking {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-stage-override-moved", enabled = false)
            SyncedStubFixture.workItems().replaceStatuses(
                connId,
                listOf(StatusRef("7", "In review", StatusCategory.IN_PROGRESS), StatusRef("8", "Testing", StatusCategory.IN_PROGRESS)),
            )
            // The mover lives in BBB now (key BBB-9) but started life in AAA (AAA-9) and sat in "7" before the move.
            val mover = 910_101L
            val inA = 910_102L
            val inB = 910_103L
            val inC = 910_104L
            seedTask(connId, mover, "BBB-9", "BBB", "8", "Testing", earlier = listOf("7" to "In review"), movedFromKey = "AAA-9")
            seedTask(connId, inA, "AAA-1", "AAA", "7", "In review")
            seedTask(connId, inB, "BBB-1", "BBB", "7", "In review")
            seedTask(connId, inC, "CCC-1", "CCC", "7", "In review")

            val current = config().effectiveConfig(connId)
            assertEquals(setOf("AAA", "BBB", "CCC"), current.domains.map { it.domainKey }.toSet())
            // CCC is left OUT of `domains`: DERIVE reads that project as its own domain, so an override keyed "CCC" is valid.
            config().replaceConfig(
                connId,
                DataSourceMetricsConfigRequest(
                    statusStages = current.statusStages,
                    fields = current.fields,
                    domains = current.domains.filter { it.projectKey != "CCC" },
                    activityTypes = current.activityTypes,
                    domainStatusStages = listOf(
                        MetricsDomainStatusStage("AAA", "7", MetricsStage.DONE),
                        MetricsDomainStatusStage("BBB", "7", MetricsStage.NOT_STARTED),
                        MetricsDomainStatusStage("BBB", "8", MetricsStage.DONE),
                        MetricsDomainStatusStage("CCC", "7", MetricsStage.DONE),
                    ),
                ),
            )
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

            // Current-domain rule: BOTH of the mover's intervals read BBB's map — "7" is NOT_STARTED even though AAA maps it to DONE.
            assertEquals(listOf("7" to "NOT_STARTED", "8" to "DONE"), stagesOf(connId, mover))
            assertEquals(listOf("7" to "DONE"), stagesOf(connId, inA))
            assertEquals(listOf("7" to "NOT_STARTED"), stagesOf(connId, inB))
            assertEquals(listOf("7" to "DONE"), stagesOf(connId, inC), "the unmapped project key CCC is its own domain")

            // Attribution stays as-was: the mover's task_domain history still names AAA, then BBB.
            val history = suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.TaskDomain.selectAll()
                    .where { (MetricsTables.TaskDomain.connectionId eq connId) and (MetricsTables.TaskDomain.issueId eq mover) }
                    .orderBy(MetricsTables.TaskDomain.validFrom to SortOrder.ASC)
                    .toList().map { it[MetricsTables.TaskDomain.domainKey] }
            }
            assertEquals(listOf("AAA", "BBB"), history)
        }

    @Test
    fun `DERIVE resolves each item_stage row as domain override, else every-domain row, else UNMAPPED - graded independently`() =
        runBlocking {
            val sharedConnId = SyncedStubFixture.connectionId()
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-stage-override-stub", enabled = false)
            SyncedStubFixture.cloneProcessedData(sharedConnId, connId)

            val current = config().effectiveConfig(connId)
            val overriddenDomain = "PLT"
            assertTrue(current.domains.any { it.domainKey == overriddenDomain }, "the stub's PLT project is a default 1:1 domain")
            // Rotate every every-domain mapping in ONE domain (NOT_STARTED -> IN_PROGRESS -> WAITING -> DONE -> NOT_STARTED).
            val stages = MetricsStage.entries
            val rotated = current.statusStages.map {
                MetricsDomainStatusStage(overriddenDomain, it.statusId, stages[(it.stage.ordinal + 1) % stages.size])
            }
            config().replaceConfig(
                connId,
                DataSourceMetricsConfigRequest(
                    statusStages = current.statusStages,
                    fields = current.fields,
                    domains = current.domains,
                    activityTypes = current.activityTypes,
                    domainStatusStages = rotated,
                ),
            )
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

            // The independent read: the stored map rows (straight from the table, not the service's reshaping)
            // and each item's domain from its dim row, resolved by hand per item_stage row.
            val (mapRows, domainByIssue, stageRows) = suspendTransaction(sharedDatabaseForTests()) {
                val maps = MetricsConfigService.StatusStageMap.selectAll()
                    .where { MetricsConfigService.StatusStageMap.connectionId eq connId }.toList()
                val domains = MetricsTables.DimTask.selectAll().where { MetricsTables.DimTask.connectionId eq connId }.toList()
                    .associate { it[MetricsTables.DimTask.issueId] to it[MetricsTables.DimTask.domainKey] } +
                    MetricsTables.DimEpic.selectAll().where { MetricsTables.DimEpic.connectionId eq connId }.toList()
                        .associate { it[MetricsTables.DimEpic.issueId] to it[MetricsTables.DimEpic.domainKey] }
                val stages = MetricsTables.ItemStage.selectAll().where { MetricsTables.ItemStage.connectionId eq connId }.toList()
                Triple(maps, domains, stages)
            }
            val stored = mapRows.groupBy { it[MetricsConfigService.StatusStageMap.domainKey] }
                .mapValues { (_, rows) ->
                    rows.associate { it[MetricsConfigService.StatusStageMap.statusId] to it[MetricsConfigService.StatusStageMap.stage] }
                }
            val everyDomain = stored[""].orEmpty()
            val perDomain = stored.filterKeys { it != "" }
            assertEquals(setOf(overriddenDomain), perDomain.keys, "only the one domain carries override rows")

            assertTrue(stageRows.isNotEmpty())
            var inOverriddenDomain = 0
            var overriddenDiffers = 0
            for (row in stageRows) {
                val issueId = row[MetricsTables.ItemStage.issueId]
                assertTrue(domainByIssue.containsKey(issueId), "item $issueId has a dim row naming its domain")
                val domain = domainByIssue.getValue(issueId)
                val statusId = row[MetricsTables.ItemStage.statusId]
                val expected = perDomain[domain]?.get(statusId) ?: everyDomain[statusId] ?: ItemStage.UNMAPPED.name
                assertEquals(expected, row[MetricsTables.ItemStage.stage], "item $issueId, domain $domain, status $statusId")
                if (domain == overriddenDomain) {
                    inOverriddenDomain++
                    if (expected != everyDomain[statusId]) overriddenDiffers++
                }
            }
            assertTrue(inOverriddenDomain > 0, "the stub has PLT items with status intervals")
            val mappedInOverriddenDomain = stageRows.count {
                val inDomain = domainByIssue[it[MetricsTables.ItemStage.issueId]] == overriddenDomain
                inDomain && it[MetricsTables.ItemStage.statusId] in everyDomain
            }
            assertEquals(
                mappedInOverriddenDomain,
                overriddenDiffers,
                "every mapped PLT status interval reads the rotated stage, never the every-domain one",
            )
        }
}
