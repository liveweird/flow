package ch.nokillswit

import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.MetricsWorkCategoryMapping
import ch.nokillswit.metrics.readProfileSections
import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.BeforeClass

/**
 * The work-category custom field through a REAL DERIVE (`DeriveTaskRows.ownWorkCategory`): the stub's default config leaves
 * `fields.workCategory` unset, so nothing else exercises the `custom_fields` key that `workItemsForDerivation` keeps in SQL for it
 * (`norm/NormDerivationReads.kt` `KeptCustomFields`). A private DISABLED clone gets a work-category value written into a few live
 * tasks' `custom_fields` (beside the keys they already carry), the field is configured, DERIVE runs, and `dim_task` / `fact_worklog`
 * are graded against a category computed here straight from the STORED `custom_fields`, never through the deriver.
 */
class DeriveWorkCategoryFieldTest {
    private val categoryByValue = mapOf("wc-a" to "Product Development", "wc-b" to "Cost of Poor Quality")

    @Test
    fun `DERIVE reads the configured work category field through the SQL custom_fields trim - graded from the stored custom_fields`() =
        runBlocking {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-work-category-stub", enabled = false)
            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            val workItems = SyncedStubFixture.workItems()
            val config = DerivedStubFixture.metricsConfig()

            val current = config.effectiveConfig(connId)
            val taken = setOfNotNull(
                current.fields.estimateTask, current.fields.estimateEpic, current.fields.epicStart, current.fields.epicDue,
            )
            val fieldId = SyncedStubFixture.dataSources().readProfileSections(connId)?.customFields.orEmpty()
                .map { it.id }.firstOrNull { it !in taken }
            checkNotNull(fieldId) { "the stub's profile lists a custom field the derive config does not already use" }

            // Tasks with worklogs first (their fact_worklog rows carry the category too), then some without.
            val withWorklogs = workItems.worklogSecondsByIssue(connId).keys
            val tasks = workItems.workItemsForDerivation(connId)
                .filter { !it.isSubtask && hierarchyBucket(it.hierarchyLevel) == HierarchyBucket.TASK }
            val chosen = (tasks.filter { it.issueId in withWorklogs }.take(SEEDED_WITH_WORKLOGS) +
                tasks.filter { it.issueId !in withWorklogs }.take(SEEDED_WITHOUT)).map { it.issueId }
            assertTrue(chosen.size > 5 && chosen.any { it in withWorklogs }, "the stub has live tasks, some with worklogs")

            suspendTransaction(sharedDatabaseForTests()) {
                for ((index, issueId) in chosen.withIndex()) {
                    val where = (WorkItemStore.WorkItems.connectionId eq connId) and (WorkItemStore.WorkItems.issueId eq issueId)
                    val existing = WorkItemStore.WorkItems.selectAll().where { where }.toList().single()[WorkItemStore.WorkItems.customFields]
                    val valueId = if (index % 2 == 0) "wc-a" else "wc-b"
                    val merged = JsonObject(
                        Json.parseToJsonElement(existing).jsonObject +
                            (fieldId to JsonObject(mapOf("id" to JsonPrimitive(valueId), "value" to JsonPrimitive(valueId)))),
                    )
                    WorkItemStore.WorkItems.update({ where }) { it[customFields] = merged.toString() }
                }
            }
            config.replaceConfig(
                connId,
                DataSourceMetricsConfigRequest(
                    statusStages = current.statusStages,
                    fields = current.fields.copy(workCategory = fieldId),
                    domains = current.domains,
                    activityTypes = current.activityTypes,
                    workCategories = categoryByValue.map { (valueId, category) -> MetricsWorkCategoryMapping(valueId, valueId, category) },
                ),
            )
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

            // Independent expectation: the category of each item, read off the stored custom_fields by hand.
            val stored = suspendTransaction(sharedDatabaseForTests()) {
                WorkItemStore.WorkItems.selectAll().where { WorkItemStore.WorkItems.connectionId eq connId }.toList()
                    .associate { it[WorkItemStore.WorkItems.issueId] to it[WorkItemStore.WorkItems.customFields] }
            }
            fun expectedCategory(issueId: Long): String? {
                val value = Json.parseToJsonElement(stored.getValue(issueId)).jsonObject[fieldId] as? JsonObject ?: return null
                return categoryByValue[value["id"]?.jsonPrimitive?.content]
            }
            val (dimTasks, worklogs) = suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.DimTask.selectAll().where { MetricsTables.DimTask.connectionId eq connId }.toList() to
                    MetricsTables.FactWorklog.selectAll().where { MetricsTables.FactWorklog.connectionId eq connId }.toList()
            }

            val dimByIssue = dimTasks.associateBy { it[MetricsTables.DimTask.issueId] }
            for (issueId in chosen) {
                val expected = expectedCategory(issueId)
                assertTrue(expected != null, "item $issueId carries a mapped value")
                val dim = dimByIssue.getValue(issueId)
                assertEquals(expected, dim[MetricsTables.DimTask.workCategory], "dim_task.work_category of $issueId")
                assertEquals("OWN", dim[MetricsTables.DimTask.workCategorySource], "item $issueId has its own category")
            }
            assertEquals(setOf("Product Development", "Cost of Poor Quality"), chosen.mapNotNull { expectedCategory(it) }.toSet())

            val chosenWorklogs = worklogs.filter { it[MetricsTables.FactWorklog.issueId] in chosen }
            assertTrue(chosenWorklogs.isNotEmpty(), "the chosen tasks with worklogs have fact_worklog rows")
            for (row in chosenWorklogs) {
                assertEquals(
                    expectedCategory(row[MetricsTables.FactWorklog.issueId]), row[MetricsTables.FactWorklog.workCategory],
                    "fact_worklog.work_category of worklog ${row[MetricsTables.FactWorklog.worklogId]}",
                )
            }
        }

    companion object {
        private const val SEEDED_WITH_WORKLOGS = 12
        private const val SEEDED_WITHOUT = 4

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
