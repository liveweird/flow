package ch.nokillswit

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.asRequest
import ch.nokillswit.metrics.readProfileSections
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorklogFact
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `epic_domain_key` AS-OF (amendment A47, `.claude/docs/domain-model.md`): a task's epic domain is read from the EPIC's own
 * `issuekey` history at the task's anchor (`done_at`, else the DERIVE clock), and the PV DOMAIN scope from the epic's history at
 * the END of each planned day. One hand-built world on a private DISABLED connection (`.claude/docs/testing.md` "The derived
 * fixture"): epic [EPIC] lives in `BBB` now and started in `AAA`, moved at [MOVE]; its children are tasks of `AAA` done before the
 * move, after it, exactly at it (half-open: the NEW row covers), never (open) and before the epic existed (no covering row, so the
 * epic's current domain); a second test does the same for the worklog statement (`started_at`). Every figure is graded from the
 * stored rows via plain SQL, never through the report code.
 */
class MetricsEpicDomainAsOfTest {
    private val epicCreated = at("2026-01-12T12:00:00Z")
    private val taskCreated = at("2026-01-05T12:00:00Z")

    private data class StatusStep(val statusId: String, val name: String, val category: StatusCategory, val atMs: Long)

    /** One hand-built item; [steps] is its status path from creation (the last is the current one); [custom] its `custom_fields` JSON. */
    private suspend fun seed(
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

    /** A task of `AAA` under [EPIC] that sat To Do, then In progress, then Done at [doneAt] (open when null), with [estimate] man-days. */
    private suspend fun seedChild(
        connId: UInt,
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
            StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, createdAt + HOUR),
            doneAt?.let { StatusStep("9", "Done", StatusCategory.DONE, it) },
        )
        seed(connId, issueId, "AAA-$number", 0, EPIC, createdAt, steps, """{"$field": $estimate}""", worklogs = worklogs)
    }

    private fun domainRows(connId: UInt, issueId: Long): List<Triple<String?, Long, Long?>> = jdbcRows(
        "SELECT domain_key, valid_from, valid_to FROM metrics.task_domain WHERE connection_id = ? AND issue_id = ? ORDER BY valid_from",
        connId.toLong(), issueId,
    ).map { Triple(it[0] as String?, it[1] as Long, it[2] as Long?) }

    private data class Delivery(val epicId: Long?, val domainKey: String?, val epicDomainKey: String?, val crossDomain: Boolean)

    private fun delivery(connId: UInt, issueId: Long): Delivery = jdbcRows(
        "SELECT epic_id, domain_key, epic_domain_key, cross_domain FROM metrics.fact_task_delivery " +
            "WHERE connection_id = ? AND issue_id = ?",
        connId.toLong(), issueId,
    ).single().let { Delivery(it[0] as Long?, it[1] as String?, it[2] as String?, it[3] as Boolean) }

    /** The `dim_date` day key (`YYYY-MM-DD`) that contains [ms] — the day an event lands on, whatever the configured zone. */
    private fun dayOf(ms: Long): String =
        jdbcRows("SELECT day FROM metrics.dim_date WHERE day_start_ms <= ? AND ? < day_end_ms", ms, ms).single().single() as String

    /** Every non-zero `agg_daily_flow` DOMAIN figure of [column] on this connection: (domain, day) to the value. */
    private fun domainFlow(connId: UInt, column: String): Map<Pair<String, String>, Double> = jdbcRows(
        "SELECT scope_id, day, $column FROM metrics.agg_daily_flow WHERE connection_id = ? AND scope_kind = 'DOMAIN' AND $column <> 0",
        connId.toLong(),
    ).associate { (it[0] as String to it[1] as String) to (it[2] as Number).toDouble() }

    @Test
    fun `a task reads its epic's domain as of its done instant, and the PV DOMAIN scope as of each planned day's end`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-epic-domain-asof", enabled = false)
        // The profile (custom field ids) comes from the shared connection, copied the way `cloneProcessedData` does.
        PostgresTestSupport.connect().use { c ->
            c.createStatement().use { st ->
                val table = DataSourceService.Connections.tableName
                st.executeUpdate(
                    "UPDATE $table SET profile = src.profile, profile_at = src.profile_at " +
                        "FROM $table src WHERE src.id = ${SyncedStubFixture.connectionId()} AND $table.id = $connId",
                )
            }
        }
        val profileFields = SyncedStubFixture.dataSources().readProfileSections(connId)?.customFields.orEmpty()
        val free = profileFields.filter { it.role != "SPRINT" }.map { it.id }
        assertTrue(free.size >= 3, "the stub's profile lists three custom fields to use as the estimate and the epic start/due")
        val (estimateField, startField, dueField) = free

        SyncedStubFixture.workItems().replaceStatuses(
            connId,
            listOf(
                StatusRef("1", "To Do", StatusCategory.TODO),
                StatusRef("7", "In progress", StatusCategory.IN_PROGRESS),
                StatusRef("9", "Done", StatusCategory.DONE),
            ),
        )
        // The epic: BBB-1 now, AAA-1 until MOVE; a 10 man-day budget over Mon 9 - Fri 13 February (five working days).
        seed(
            connId, EPIC, "BBB-1", 1, null, epicCreated,
            listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated)),
            """{"$estimateField": 10.0, "$startField": "2026-02-09", "$dueField": "2026-02-13"}""",
            keyChange = FieldChangeFact("Key", MOVE, "AAA-1", "AAA-1", "BBB-1", "BBB-1", fieldId = "issuekey"),
        )
        seedChild(connId, BEFORE_MOVE, 10, taskCreated, MOVE - DAY, 3.0, estimateField)
        seedChild(connId, AFTER_MOVE, 11, taskCreated, MOVE + DAY, 5.0, estimateField)
        seedChild(connId, OPEN, 12, taskCreated, null, 1.0, estimateField)
        seedChild(connId, AT_MOVE, 13, taskCreated, MOVE, 7.0, estimateField)
        // Done (and created) before the epic existed: the anchor precedes its first history row, so its CURRENT domain applies.
        seedChild(connId, BEFORE_EPIC, 14, taskCreated, epicCreated - 3 * DAY, 2.0, estimateField)

        val config = DerivedStubFixture.metricsConfig()
        val current = config.effectiveConfig(connId)
        assertEquals(setOf("AAA", "BBB"), current.domains.map { it.domainKey }.toSet(), "the defaults map both projects 1:1")
        config.replaceConfig(
            connId,
            current.asRequest().copy(
                fields = current.fields.copy(
                    estimateTask = estimateField, estimateEpic = null, epicStart = startField, epicDue = dueField,
                ),
            ),
        )
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

        // The epic's own history: one row per project it lived in, tiled at the move.
        assertEquals(
            listOf(Triple("AAA", epicCreated, MOVE), Triple("BBB", MOVE, null)),
            domainRows(connId, EPIC),
            "the epic's task_domain history is [AAA, created..move) [BBB, move..)",
        )
        // A task's own history stays a single AAA row (it never moved), unaffected by its epic.
        assertEquals(listOf(Triple("AAA", taskCreated, null)), domainRows(connId, BEFORE_MOVE))

        assertEquals(Delivery(EPIC, "AAA", "AAA", false), delivery(connId, BEFORE_MOVE), "done before the move: the epic was still in AAA")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, AFTER_MOVE), "done after the move: the epic is in BBB")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, OPEN), "an open task is read at the DERIVE clock: current domain")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, AT_MOVE), "done exactly at the move: the NEW row covers")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, BEFORE_EPIC), "before the epic's first row: its current domain")

        // EV, DOMAIN: each done task's estimate lands on its done day in the epic's domain AT THAT instant.
        val ev = domainFlow(connId, "ev_md")
        assertEquals(3.0, ev[("AAA" to dayOf(MOVE - DAY))], "the pre-move completion is delivered into AAA")
        assertEquals(5.0, ev[("BBB" to dayOf(MOVE + DAY))], "the post-move completion is delivered into BBB")
        assertEquals(7.0, ev[("BBB" to dayOf(MOVE))], "the completion at the move is delivered into BBB")
        assertEquals(2.0, ev[("BBB" to dayOf(epicCreated - 3 * DAY))], "the pre-epic completion falls back to BBB")
        assertEquals(4, ev.size, "no other DOMAIN EV row exists on this connection")

        // PV, DOMAIN: the budget's working days before the move's day (their END precedes it) sit in AAA, the others in BBB.
        val budget = (jdbcRows(
            "SELECT budget_md FROM metrics.fact_epic_plan WHERE connection_id = ? AND issue_id = ? AND superseded_at IS NULL",
            connId.toLong(), EPIC,
        ).single().single() as Number).toDouble()
        assertEquals(10.0, budget, "the epic's own estimate is the budget")
        val pv = domainFlow(connId, "pv_md")
        assertEquals(
            mapOf(
                ("AAA" to "2026-02-09") to 2.0, ("AAA" to "2026-02-10") to 2.0,
                ("BBB" to "2026-02-11") to 2.0, ("BBB" to "2026-02-12") to 2.0, ("BBB" to "2026-02-13") to 2.0,
            ),
            pv,
            "two planned days end before the move (AAA), the move's own day and the two after it are BBB's",
        )
        assertEquals(budget, pv.values.sum(), 1e-9, "the DOMAIN split still sums to the budget")
    }

    private data class Logged(val taskDomainKey: String?, val epicId: Long?, val epicDomainKey: String?, val md: Double)

    private fun logged(connId: UInt, worklogId: Long): Logged = jdbcRows(
        "SELECT task_domain_key, epic_id, epic_domain_key, md FROM metrics.fact_worklog WHERE connection_id = ? AND worklog_id = ?",
        connId.toLong(), worklogId,
    ).single().let { Logged(it[0] as String?, it[1] as Long?, it[2] as String?, (it[3] as Number).toDouble()) }

    @Test
    fun `a worklog reads its epic's domain as of started_at, on a child and on the epic itself, and AC DOMAIN follows`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-epic-domain-asof-wl", enabled = false)
        SyncedStubFixture.workItems().replaceStatuses(
            connId,
            listOf(
                StatusRef("1", "To Do", StatusCategory.TODO),
                StatusRef("7", "In progress", StatusCategory.IN_PROGRESS),
                StatusRef("9", "Done", StatusCategory.DONE),
            ),
        )
        fun worklog(id: Long, startedAt: Long, hours: Long) = WorklogFact(id, null, startedAt, hours * 3600, startedAt, startedAt)
        // The epic lives in BBB now and moved from AAA at MOVE; its worklogs sit on both sides of the move, one on a child and one on
        // the epic itself per side (distinct days and lengths, whole-hour multiples so the aggregate's rounding is exact).
        seed(
            connId, EPIC, "BBB-1", 1, null, epicCreated,
            listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated)), "{}",
            keyChange = FieldChangeFact("Key", MOVE, "AAA-1", "AAA-1", "BBB-1", "BBB-1", fieldId = "issuekey"),
            worklogs = listOf(worklog(EPIC_WL_BEFORE, MOVE - DAY, 4), worklog(EPIC_WL_AFTER, MOVE + DAY, 6)),
        )
        seedChild(
            connId, BEFORE_MOVE, 10, taskCreated, null, 1.0, "none",
            worklogs = listOf(worklog(CHILD_WL_BEFORE, MOVE - 2 * DAY, 2), worklog(CHILD_WL_AFTER, MOVE + 2 * DAY, 8)),
        )
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

        // A child's own domain stays AAA (it never moved); the epic's is read at started_at.
        val childBefore = logged(connId, CHILD_WL_BEFORE)
        val childAfter = logged(connId, CHILD_WL_AFTER)
        assertEquals(Triple("AAA", EPIC, "AAA"), Triple(childBefore.taskDomainKey, childBefore.epicId, childBefore.epicDomainKey))
        assertEquals(Triple("AAA", EPIC, "BBB"), Triple(childAfter.taskDomainKey, childAfter.epicId, childAfter.epicDomainKey))
        // A worklog ON the epic: its task domain IS the epic's as-of domain.
        val epicBefore = logged(connId, EPIC_WL_BEFORE)
        val epicAfter = logged(connId, EPIC_WL_AFTER)
        assertEquals(Triple("AAA", EPIC, "AAA"), Triple(epicBefore.taskDomainKey, epicBefore.epicId, epicBefore.epicDomainKey))
        assertEquals(Triple("BBB", EPIC, "BBB"), Triple(epicAfter.taskDomainKey, epicAfter.epicId, epicAfter.epicDomainKey))

        // AC, DOMAIN: each worklog's md lands on its day in the epic's domain AT started_at.
        assertEquals(
            mapOf(
                ("AAA" to dayOf(MOVE - 2 * DAY)) to childBefore.md, ("AAA" to dayOf(MOVE - DAY)) to epicBefore.md,
                ("BBB" to dayOf(MOVE + DAY)) to epicAfter.md, ("BBB" to dayOf(MOVE + 2 * DAY)) to childAfter.md,
            ),
            domainFlow(connId, "ac_md"),
            "the pre-move worklogs are AAA's, the post-move ones BBB's",
        )
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val EPIC = 920_001L
        private const val BEFORE_MOVE = 920_010L
        private const val AFTER_MOVE = 920_011L
        private const val OPEN = 920_012L
        private const val AT_MOVE = 920_013L
        private const val BEFORE_EPIC = 920_014L
        private const val CHILD_WL_BEFORE = 920_021L
        private const val CHILD_WL_AFTER = 920_022L
        private const val EPIC_WL_BEFORE = 920_023L
        private const val EPIC_WL_AFTER = 920_024L

        private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

        /** The epic's project move: Wednesday 11 February, midday UTC (inside one local day in any plausible zone). */
        private val MOVE = at("2026-02-11T12:00:00Z")

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
