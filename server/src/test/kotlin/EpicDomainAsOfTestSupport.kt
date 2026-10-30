package ch.nokillswit

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.readProfileSections
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorklogFact
import kotlin.test.assertTrue

/*
 * Shared hand-building helpers of the epic-domain as-of tests (`MetricsEpicDomainAsOfTest`, A47, and `MetricsEpicFactsAsOfTest`,
 * A49): a normalized item written straight into `norm.*` on a private DISABLED connection, a task child of an epic, the stored
 * `metrics.task_domain` history of an item, and the stub profile's custom fields cloned onto the private connection.
 */

private const val SEED_HOUR_MS = 3_600_000L

internal data class StatusStep(val statusId: String, val name: String, val category: StatusCategory, val atMs: Long)

/** One hand-built item; [steps] is its status path from creation (the last is the current one); [custom] its `custom_fields` JSON. */
internal suspend fun seedItem(
    connId: UInt,
    issueId: Long,
    issueKey: String,
    level: Int,
    parent: Long?,
    createdAt: Long,
    steps: List<StatusStep>,
    custom: String,
    keyChange: FieldChangeFact? = null,
    worklogs: List<WorklogFact> = emptyList(),
) {
    val current = steps.last()
    val facts = WorkItemFacts(
        issueKey = issueKey,
        projectKey = issueKey.substringBefore('-'),
        issueType = if (level == 1) "Epic" else "Task",
        isSubtask = false,
        parentIssueId = parent,
        summary = "An epic-domain as-of item",
        currentStatusId = current.statusId,
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
        hierarchyLevel = level,
        customFieldsJson = custom,
        tombstone = TombstoneKind.NONE,
    )
    val intervals = steps.mapIndexed { index, step ->
        val source = if (index == 0) IntervalSource.CREATED else IntervalSource.CHANGE
        val to = steps.getOrNull(index + 1)?.atMs
        NormalizedStatusInterval(index + 1, step.statusId, step.name, step.category, step.atMs, to, source)
    }
    val normalized = NormalizedIssue(
        issueId = issueId,
        facts = facts,
        currentStatusName = current.name,
        currentStatusCategory = current.category,
        statusIntervals = intervals,
        fieldIntervals = emptyList(),
        fieldChanges = listOfNotNull(keyChange),
        worklogs = worklogs,
        currentSprintIds = emptyList(),
        flagged = false,
        anomalies = emptyList(),
    )
    SyncedStubFixture.workItems().replaceWorkItem(connId, normalized, createdAt)
}

/** A task of `AAA` under the epic [epicId]: To Do, then In progress, then Done at [doneAt] (open when null), with [estimate] man-days. */
internal suspend fun seedChild(
    connId: UInt,
    epicId: Long,
    issueId: Long,
    number: Int,
    createdAt: Long,
    doneAt: Long?,
    estimate: Double,
    field: String,
    worklogs: List<WorklogFact> = emptyList(),
) {
    val steps = listOfNotNull(
        StatusStep("1", "To Do", StatusCategory.TODO, createdAt),
        StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, createdAt + SEED_HOUR_MS),
        doneAt?.let { StatusStep("9", "Done", StatusCategory.DONE, it) },
    )
    seedItem(connId, issueId, "AAA-$number", 0, epicId, createdAt, steps, """{"$field": $estimate}""", worklogs = worklogs)
}

internal fun domainRows(connId: UInt, issueId: Long): List<Triple<String?, Long, Long?>> = jdbcRows(
    "SELECT domain_key, valid_from, valid_to FROM metrics.task_domain WHERE connection_id = ? AND issue_id = ? ORDER BY valid_from",
    connId.toLong(), issueId,
).map { Triple(it[0] as String?, it[1] as Long, it[2] as Long?) }

/**
 * Copies [sourceConnId]'s profile (the shared synced connection; the caller passes `SyncedStubFixture.connectionId()` so the
 * fixture trigger stays in a class that warms it, `StubFixtureWarmupGuardTest`) onto [connId], as `cloneProcessedData` does,
 * and returns three custom field ids to use.
 */
internal suspend fun cloneProfileFields(connId: UInt, sourceConnId: UInt): Triple<String, String, String> {
    PostgresTestSupport.connect().use { c ->
        c.createStatement().use { st ->
            val table = DataSourceService.Connections.tableName
            st.executeUpdate(
                "UPDATE $table SET profile = src.profile, profile_at = src.profile_at " +
                    "FROM $table src WHERE src.id = $sourceConnId AND $table.id = $connId",
            )
        }
    }
    val profileFields = SyncedStubFixture.dataSources().readProfileSections(connId)?.customFields.orEmpty()
    val free = profileFields.filter { it.role != "SPRINT" }.map { it.id }
    assertTrue(free.size >= 3, "the stub's profile lists three custom fields to use as the estimate and the epic start/due")
    return Triple(free[0], free[1], free[2])
}
