package ch.nokillswit

import ch.nokillswit.metrics.asRequest
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.WorklogFact
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `epic_domain_key` AS-OF (amendment A47, `.claude/docs/domain-model.md`): a task's epic domain is read from the EPIC's own
 * `issuekey` history at the task's anchor (`done_at`, else the DERIVE clock), and the PV DOMAIN scope from the epic's history at
 * the END of each planned day. One hand-built world on a private DISABLED connection (`.claude/docs/testing.md` "The derived
 * fixture"): epic [EPIC] lives in `BBB` now and started in `AAA`, moved at [MOVE]; its children are tasks of `AAA` done before the
 * move, after it, exactly at it (half-open: the NEW row covers), never (open) and before the epic existed (the epic's first row reaches
 * back to instant 0, so the domain at its creation); a second test does the same for the worklog statement (`started_at`), a third
 * pins PV days before an epic's creation, a move exactly at a day's end and an origin the data source does not read, a fourth an
 * origin it is configured to read although no item of it is left. Every figure is
 * graded from the stored rows via plain SQL, never through the report code. The hand-building helpers live in
 * `EpicDomainAsOfTestSupport.kt` (shared with `MetricsEpicFactsAsOfTest`, A49).
 */
class MetricsEpicDomainAsOfTest {
    private val epicCreated = at("2026-01-12T12:00:00Z")
    private val taskCreated = at("2026-01-05T12:00:00Z")

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
        val (estimateField, startField, dueField) = cloneProfileFields(connId, SyncedStubFixture.connectionId())
        SyncedStubFixture.workItems().replaceStatuses(
            connId,
            listOf(
                StatusRef("1", "To Do", StatusCategory.TODO),
                StatusRef("7", "In progress", StatusCategory.IN_PROGRESS),
                StatusRef("9", "Done", StatusCategory.DONE),
            ),
        )
        // The epic: BBB-1 now, AAA-1 until MOVE; a 10 man-day budget over Mon 9 - Fri 13 February (five working days).
        seedItem(
            connId, EPIC, "BBB-1", 1, null, epicCreated,
            listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated)),
            """{"$estimateField": 10.0, "$startField": "2026-02-09", "$dueField": "2026-02-13"}""",
            // The REAL Jira shape: `from`/`to` are null and the keys sit in `fromString`/`toString` only (the value columns are NULL).
            keyChange = FieldChangeFact("Key", MOVE, null, "AAA-1", null, "BBB-1", fieldId = "issuekey"),
        )
        seedChild(connId, EPIC, BEFORE_MOVE, 10, taskCreated, MOVE - DAY, 3.0, estimateField)
        seedChild(connId, EPIC, AFTER_MOVE, 11, taskCreated, MOVE + DAY, 5.0, estimateField)
        seedChild(connId, EPIC, OPEN, 12, taskCreated, null, 1.0, estimateField)
        seedChild(connId, EPIC, AT_MOVE, 13, taskCreated, MOVE, 7.0, estimateField)
        // Done (and created) before the epic existed: the epic's first row is stretched back to 0, so the domain at its creation applies.
        seedChild(connId, EPIC, BEFORE_EPIC, 14, taskCreated, epicCreated - 3 * DAY, 2.0, estimateField)

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
            listOf(Triple("AAA", 0L, MOVE), Triple("BBB", MOVE, null)),
            domainRows(connId, EPIC),
            "the epic's task_domain history is [AAA, 0..move) [BBB, move..): the first row covers every anchor before the creation too",
        )
        // A task's own history stays a single AAA row (it never moved), unaffected by its epic.
        assertEquals(listOf(Triple("AAA", taskCreated, null)), domainRows(connId, BEFORE_MOVE))

        assertEquals(Delivery(EPIC, "AAA", "AAA", false), delivery(connId, BEFORE_MOVE), "done before the move: the epic was still in AAA")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, AFTER_MOVE), "done after the move: the epic is in BBB")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, OPEN), "an open task is read at the DERIVE clock: current domain")
        assertEquals(Delivery(EPIC, "AAA", "BBB", true), delivery(connId, AT_MOVE), "done exactly at the move: the NEW row covers")
        assertEquals(
            Delivery(EPIC, "AAA", "AAA", false), delivery(connId, BEFORE_EPIC),
            "done before the epic existed: the domain the epic had when it was created, not the one it has now",
        )

        // EV, DOMAIN: each done task's estimate lands on its done day in the epic's domain AT THAT instant.
        val ev = domainFlow(connId, "ev_md")
        assertEquals(3.0, ev[("AAA" to dayOf(MOVE - DAY))], "the pre-move completion is delivered into AAA")
        assertEquals(5.0, ev[("BBB" to dayOf(MOVE + DAY))], "the post-move completion is delivered into BBB")
        assertEquals(7.0, ev[("BBB" to dayOf(MOVE))], "the completion at the move is delivered into BBB")
        assertEquals(2.0, ev[("AAA" to dayOf(epicCreated - 3 * DAY))], "the pre-epic completion is delivered into the domain at creation")
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

    @Test
    fun `PV DOMAIN covers days before creation, a move at a day's end instant, and a project the connection does not ingest`() =
        runBlocking {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-epic-domain-asof-pv", enabled = false)
            val (estimateField, startField, dueField) = cloneProfileFields(connId, SyncedStubFixture.connectionId())
            SyncedStubFixture.workItems().replaceStatuses(
                connId,
                listOf(StatusRef("7", "In progress", StatusCategory.IN_PROGRESS), StatusRef("9", "Done", StatusCategory.DONE)),
            )
            val inProgress = listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated))
            // AAA is a domain of the options (and so of the as-was map) only because it has an item; OUT is neither configured nor carried.
            seedItem(connId, AAA_ITEM, "AAA-20", 0, null, taskCreated, inProgress.map { it.copy(atMs = taskCreated) }, "{}")
            // BACKDATED: created Monday 12 January midday, planned Thu 8 - Wed 14 January (five working days; the first two end before the
            // epic existed), moved to BBB at MOVE. Every planned day belongs to AAA, the domain at its creation; reading the CURRENT
            // domain for the pre-creation days would flip the budget BBB, BBB, AAA, AAA, AAA.
            seedItem(
                connId, BACKDATED, "BBB-2", 1, null, epicCreated, inProgress,
                """{"$estimateField": 10.0, "$startField": "2026-01-08", "$dueField": "2026-01-14"}""",
                keyChange = FieldChangeFact("Key", MOVE, "AAA-2", "AAA-2", "BBB-2", "BBB-2", fieldId = "issuekey"),
            )
            // BOUNDARY: planned Mon 9 - Fri 13 February, moved exactly at the END instant of Tuesday 10 February (`dim_date.day_end_ms`,
            // which is also Wednesday's start): PV reads the history at the day's END with the aggregates' `(from, to]` convention
            // (`coveringAt`), so the row that ENDS at that instant still covers Tuesday - Monday and Tuesday are AAA's, Wednesday on BBB's.
            val zone = ZoneId.of(jdbcRows("SELECT time_zone FROM metrics.settings").single().single() as String)
            val boundary = LocalDate.parse("2026-02-10").plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            seedItem(
                connId, BOUNDARY, "BBB-3", 1, null, epicCreated, inProgress,
                """{"$estimateField": 10.0, "$startField": "2026-02-09", "$dueField": "2026-02-13"}""",
                keyChange = FieldChangeFact("Key", boundary, "AAA-3", "AAA-3", "BBB-3", "BBB-3", fieldId = "issuekey"),
            )
            // FOREIGN: moved in from OUT, a project this connection never ingests (no item of it exists), at MOVE; planned Mon 2 - Fri 6
            // February, before the move, with a child done the day before it. OUT is not one of the connection's domains, so the
            // pre-move work keeps the epic's CURRENT domain (BBB) instead of re-attributing to a domain `OUT`.
            seedItem(
                connId, FOREIGN, "BBB-4", 1, null, epicCreated, inProgress,
                """{"$estimateField": 10.0, "$startField": "2026-02-02", "$dueField": "2026-02-06"}""",
                keyChange = FieldChangeFact("Key", MOVE, "OUT-4", "OUT-4", "BBB-4", "BBB-4", fieldId = "issuekey"),
            )
            seedItem(
                connId, FOREIGN_CHILD, "BBB-5", 0, FOREIGN, taskCreated,
                listOf(
                    StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, taskCreated),
                    StatusStep("9", "Done", StatusCategory.DONE, MOVE - DAY),
                ),
                """{"$estimateField": 4.0}""",
            )
            val config = DerivedStubFixture.metricsConfig()
            val current = config.effectiveConfig(connId)
            assertEquals(setOf("AAA", "BBB"), current.domains.map { it.domainKey }.toSet(), "OUT is not offered: nothing of it is ingested")
            config.replaceConfig(
                connId,
                current.asRequest().copy(
                    fields = current.fields.copy(
                        estimateTask = estimateField, estimateEpic = null, epicStart = startField, epicDue = dueField,
                    ),
                ),
            )
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

            assertEquals(listOf(Triple("BBB", 0L, MOVE), Triple("BBB", MOVE, null)), domainRows(connId, FOREIGN), "no row names OUT")
            assertEquals(Delivery(FOREIGN, "BBB", "BBB", false), delivery(connId, FOREIGN_CHILD), "pre-move work keeps the current domain")
            assertEquals(listOf(Triple("AAA", 0L, MOVE), Triple("BBB", MOVE, null)), domainRows(connId, BACKDATED))
            assertEquals(listOf(Triple("AAA", 0L, boundary), Triple("BBB", boundary, null)), domainRows(connId, BOUNDARY))
            assertEquals(
                jdbcRows("SELECT day_end_ms FROM metrics.dim_date WHERE day = '2026-02-10'").single().single(), boundary,
                "the move sits exactly on Tuesday's day_end_ms",
            )
            // The two epics share days (and a domain), so grade the sums per (domain, day): 2.0 md per planned day per epic.
            assertEquals(
                mapOf(
                    ("AAA" to "2026-01-08") to 2.0, ("AAA" to "2026-01-09") to 2.0, ("AAA" to "2026-01-12") to 2.0,
                    ("AAA" to "2026-01-13") to 2.0, ("AAA" to "2026-01-14") to 2.0,
                    ("AAA" to "2026-02-09") to 2.0, ("AAA" to "2026-02-10") to 2.0,
                    ("BBB" to "2026-02-11") to 2.0, ("BBB" to "2026-02-12") to 2.0,
                    ("BBB" to "2026-02-13") to 2.0,
                    ("BBB" to "2026-02-02") to 2.0, ("BBB" to "2026-02-03") to 2.0, ("BBB" to "2026-02-04") to 2.0,
                    ("BBB" to "2026-02-05") to 2.0, ("BBB" to "2026-02-06") to 2.0,
                ),
                domainFlow(connId, "pv_md"),
                "pre-creation days are AAA's; the day whose end instant is the move is still the OLD domain's; OUT is never a domain",
            )
            assertEquals(
                mapOf(("BBB" to dayOf(MOVE - DAY)) to 4.0), domainFlow(connId, "ev_md"), "the pre-move completion is delivered into BBB",
            )
        }

    @Test
    fun `a configured project with no live item keeps its history as-was, an unread one reads the current domain`() =
        runBlocking {
            // CFG is among the data source's CONFIGURED project keys although every issue of it moved to BBB (no item carries it).
            val connId = SyncedStubFixture.createConnection(
                projectKeys = SyncedStubFixture.IN_SCOPE_PROJECT_KEYS + "CFG", namePrefix = "jira-epic-domain-asof-cfg", enabled = false,
            )
            val (estimateField, _, _) = cloneProfileFields(connId, SyncedStubFixture.connectionId())
            SyncedStubFixture.workItems().replaceStatuses(
                connId,
                listOf(StatusRef("7", "In progress", StatusCategory.IN_PROGRESS), StatusRef("9", "Done", StatusCategory.DONE)),
            )
            val inProgress = listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated))
            // The real, null-valued key-change shape for both moves: one from CFG (configured, emptied), one from OUT (not read at all).
            seedItem(
                connId, CONFIGURED, "BBB-6", 1, null, epicCreated, inProgress, "{}",
                keyChange = FieldChangeFact("Key", MOVE, null, "CFG-6", null, "BBB-6", fieldId = "issuekey"),
            )
            seedItem(
                connId, CONFIGURED_CHILD, "BBB-7", 0, CONFIGURED, taskCreated,
                listOf(
                    StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, taskCreated),
                    StatusStep("9", "Done", StatusCategory.DONE, MOVE - DAY),
                ),
                """{"$estimateField": 4.0}""",
            )
            seedItem(
                connId, UNREAD, "BBB-8", 1, null, epicCreated, inProgress, "{}",
                keyChange = FieldChangeFact("Key", MOVE, null, "OUT-8", null, "BBB-8", fieldId = "issuekey"),
            )
            val config = DerivedStubFixture.metricsConfig()
            val current = config.effectiveConfig(connId)
            assertEquals(setOf("BBB"), current.domains.map { it.domainKey }.toSet(), "CFG carries no item: the options do not offer it")
            config.replaceConfig(
                connId, current.asRequest().copy(fields = current.fields.copy(estimateTask = estimateField, estimateEpic = null)),
            )
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

            assertEquals(
                listOf(Triple("CFG", 0L, MOVE), Triple("BBB", MOVE, null)), domainRows(connId, CONFIGURED),
                "CFG is read by the data source: its row stays as-was although no item of it is left",
            )
            assertEquals(
                listOf(Triple("BBB", 0L, MOVE), Triple("BBB", MOVE, null)), domainRows(connId, UNREAD),
                "OUT is not read: the current domain",
            )
            assertEquals(
                Delivery(CONFIGURED, "BBB", "CFG", true), delivery(connId, CONFIGURED_CHILD),
                "done before the move: the epic was still in CFG, a domain of its own",
            )
            assertEquals(
                mapOf(("CFG" to dayOf(MOVE - DAY)) to 4.0), domainFlow(connId, "ev_md"),
                "the pre-move completion is delivered into the epic's domain then",
            )
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
        seedItem(
            connId, EPIC, "BBB-1", 1, null, epicCreated,
            listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated)), "{}",
            keyChange = FieldChangeFact("Key", MOVE, "AAA-1", "AAA-1", "BBB-1", "BBB-1", fieldId = "issuekey"),
            worklogs = listOf(worklog(EPIC_WL_BEFORE, MOVE - DAY, 4), worklog(EPIC_WL_AFTER, MOVE + DAY, 6)),
        )
        seedChild(
            connId, EPIC, BEFORE_MOVE, 10, taskCreated, null, 1.0, "none",
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
        private const val BACKDATED = 920_002L
        private const val BOUNDARY = 920_003L
        private const val FOREIGN = 920_004L
        private const val FOREIGN_CHILD = 920_005L
        private const val AAA_ITEM = 920_006L
        private const val CONFIGURED = 920_007L
        private const val CONFIGURED_CHILD = 920_008L
        private const val UNREAD = 920_009L
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
