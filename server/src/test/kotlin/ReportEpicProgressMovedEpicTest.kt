package ch.nokillswit

import ch.nokillswit.metrics.asRequest
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.WorklogFact
import ch.nokillswit.reports.EpicProgressReport
import ch.nokillswit.reports.EpicProgressRow
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * Report 15's DOMAIN drill lists a moved epic once per domain it was in, each row holding that domain's share only (amendment A49,
 * `.claude/docs/domain-model.md`; `.claude/docs/reports.md` Report 15), so a domain's rows add up to its totals. One hand-built world
 * on a private DISABLED connection (`.claude/docs/testing.md` "The derived fixture"), derived under the pinned clock; every key is a
 * test key (`AAA`, `BBB`) and every worklog a multiple of two hours (a quarter man-day at 8 h/day), so AC is exact to the cent.
 * `BBB-1` lives in `BBB` and moved from `AAA` at [MOVE] (Wednesday 11 February, midday); its plan (10 man-days, Mon 9 - Fri 13 February)
 * puts two days in `AAA` and three in `BBB`, its children are done on both sides and its worklogs sit on both sides, on a child and on
 * the epic. `BBB-2` never moved; `BBB-3` moved too but is planned wholly after the move; `BBB-4` moves only AFTER the report's asOf
 * day (25 February) so the report still sees it in `AAA`. The EPIC level keeps showing the whole epic.
 */
class ReportEpicProgressMovedEpicTest {
    private val epicCreated = at("2026-01-12T12:00:00Z")
    private val taskCreated = at("2026-01-05T12:00:00Z")

    private suspend fun HttpClient.epicProgress(query: String): EpicProgressReport {
        val response = get("/api/v1/reports/epic-progress?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epic-progress?$query")
        return response.body()
    }

    private fun worklog(id: Long, startedAt: Long, hours: Long) = worklogMinutes(id, startedAt, hours * 60)

    private fun worklogMinutes(id: Long, startedAt: Long, minutes: Long) =
        WorklogFact(id, null, startedAt, minutes * 60, startedAt, startedAt)

    private fun List<EpicProgressRow>.byKey(key: String): EpicProgressRow = single { it.key == key }

    private fun EpicProgressRow.evm() = Triple(pv, ev, ac)

    private fun EpicProgressRow.periodEvm() = Triple(period.pv, period.ev, period.ac)

    @Test
    fun `a moved epic lists under every domain it was in with that domain's share, and the rows add up to the totals`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-epic-progress-moved", enabled = false)
        val (estimateField, startField, dueField) = cloneProfileFields(connId, SyncedStubFixture.connectionId())
        SyncedStubFixture.workItems().replaceStatuses(
            connId,
            listOf(
                StatusRef("1", "To Do", StatusCategory.TODO),
                StatusRef("7", "In progress", StatusCategory.IN_PROGRESS),
                StatusRef("9", "Done", StatusCategory.DONE),
            ),
        )
        val inProgress = listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated))

        fun plan(budget: Double, start: String, due: String) =
            """{"$estimateField": $budget, "$startField": "$start", "$dueField": "$due"}"""
        // BBB-1: AAA until MOVE. PV 4 (9, 10 Feb) in AAA and 6 (11-13 Feb) in BBB; EV 3 (done 10 Feb) / 5 (12 Feb);
        // AC 0.5 + 0.25 / 0.75 + 1.
        seedItem(
            connId, MOVED, "BBB-1", 1, null, epicCreated, inProgress, plan(10.0, "2026-02-09", "2026-02-13"),
            keyChange = FieldChangeFact("Key", MOVE, null, "AAA-1", null, "BBB-1", fieldId = "issuekey"),
            worklogs = listOf(worklog(WL_EPIC_BEFORE, MOVE - DAY, 2), worklog(WL_EPIC_AFTER, MOVE + DAY, 8)),
        )
        seedChild(
            connId, MOVED, CHILD_BEFORE, 10, taskCreated, MOVE - DAY, 3.0, estimateField,
            worklogs = listOf(worklog(WL_CHILD_BEFORE, MOVE - 2 * DAY, 4)),
        )
        seedChild(
            connId, MOVED, CHILD_AFTER, 11, taskCreated, MOVE + DAY, 5.0, estimateField,
            worklogs = listOf(worklog(WL_CHILD_AFTER, MOVE + 2 * DAY, 6)),
        )
        // BBB-2 never moved: PV 4 (11, 12 Feb), EV 2 (12 Feb), AC 0.5 (12 Feb), all BBB's.
        seedItem(connId, STAYED, "BBB-2", 1, null, epicCreated, inProgress, plan(4.0, "2026-02-11", "2026-02-12"))
        seedChild(
            connId, STAYED, CHILD_STAYED, 12, taskCreated, MOVE + DAY, 2.0, estimateField,
            worklogs = listOf(worklog(WL_STAYED, MOVE + DAY, 4)),
        )
        // BBB-3 moved but is planned wholly after the move and has no work: a baseline in BBB, nothing in AAA.
        seedItem(
            connId, LATE_PLAN, "BBB-3", 1, null, epicCreated, inProgress, plan(6.0, "2026-02-16", "2026-02-18"),
            keyChange = FieldChangeFact("Key", MOVE, null, "AAA-3", null, "BBB-3", fieldId = "issuekey"),
        )
        // BBB-4 moves on 25 February, after the asOf day (20 February): up to then it is an AAA epic, PV 2 (18, 19 Feb), EV 1 (12 Feb).
        seedItem(
            connId, MOVES_LATER, "BBB-4", 1, null, epicCreated, inProgress, plan(2.0, "2026-02-18", "2026-02-19"),
            keyChange = FieldChangeFact("Key", LATER, null, "AAA-4", null, "BBB-4", fieldId = "issuekey"),
        )
        seedChild(connId, MOVES_LATER, CHILD_LATER, 13, taskCreated, MOVE + DAY, 1.0, estimateField)

        val config = DerivedStubFixture.metricsConfig()
        val current = config.effectiveConfig(connId)
        assertEquals(setOf("AAA", "BBB"), current.domains.map { it.domainKey }.toSet(), "the defaults map both projects 1:1")
        config.replaceConfig(
            connId,
            current.asRequest().copy(
                fields = current.fields.copy(estimateTask = estimateField, estimateEpic = null, epicStart = startField, epicDue = dueField),
            ),
        )
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
        assertEquals(listOf(Triple("AAA", 0L, MOVE), Triple("BBB", MOVE, null)), domainRows(connId, MOVED), "the epic's history")

        val client = seededClient("reports-epic-progress-moved")
        val base = "connectionId=$connId&from=2026-01-01&to=2026-02-20&basis=CUMULATIVE"

        // AAA: the moved epic's AAA share (not the whole epic), and BBB-4, still an AAA epic at asOf; BBB-3 has nothing there.
        val inA = client.epicProgress("$base&domain=AAA")
        assertEquals("2026-02-20", inA.asOf.day)
        assertEquals(listOf("BBB-1", "BBB-4"), inA.rows.map { it.key }, "BBB-2 never was in AAA; BBB-3 is planned after the move")
        assertEquals(Triple(4.0, 3.0, 0.75), inA.rows.byKey("BBB-1").evm(), "the epic's AAA share")
        assertEquals(true, inA.rows.byKey("BBB-1").partial)
        assertEquals(Triple(2.0, 1.0, 0.0), inA.rows.byKey("BBB-4").evm(), "moves after asOf: whole")
        assertNull(inA.rows.byKey("BBB-4").partial, "an epic still in one domain at asOf is not partial")
        assertEquals(Triple(6.0, 4.0, 0.75), Triple(inA.asOf.pv, inA.asOf.ev, inA.asOf.ac), "AAA's totals")
        assertSumsToTotals("AAA", inA)

        // BBB: the moved epic's BBB share, the never-moved sibling in full, and the late-planned epic with its baseline only.
        val inB = client.epicProgress("$base&domain=BBB")
        assertEquals(listOf("BBB-1", "BBB-2", "BBB-3"), inB.rows.map { it.key }, "BBB-4 was never in BBB up to asOf")
        assertEquals(Triple(6.0, 5.0, 1.75), inB.rows.byKey("BBB-1").evm(), "the epic's BBB share")
        assertEquals(true, inB.rows.byKey("BBB-1").partial)
        assertEquals(Triple(4.0, 2.0, 0.5), inB.rows.byKey("BBB-2").evm())
        assertNull(inB.rows.byKey("BBB-2").partial, "a never-moved epic is not partial")
        assertEquals(Triple(6.0, 0.0, 0.0), inB.rows.byKey("BBB-3").evm())
        assertEquals(true, inB.rows.byKey("BBB-3").partial, "it moved, so its BBB row is a share too")
        assertEquals(Triple(16.0, 7.0, 2.25), Triple(inB.asOf.pv, inB.asOf.ev, inB.asOf.ac), "BBB's totals")
        assertSumsToTotals("BBB", inB)
        // The two shares of the moved epic make the whole epic.
        assertEquals(10.0, inA.rows.byKey("BBB-1").pv + inB.rows.byKey("BBB-1").pv)
        assertEquals(8.0, inA.rows.byKey("BBB-1").ev + inB.rows.byKey("BBB-1").ev)
        assertEquals(2.5, inA.rows.byKey("BBB-1").ac + inB.rows.byKey("BBB-1").ac, 1e-9)

        // The EPIC level still shows the whole epic, and lists no rows.
        val whole = client.epicProgress("$base&epicId=BBB-1")
        assertEquals(Triple(10.0, 8.0, 2.5), Triple(whole.asOf.pv, whole.asOf.ev, whole.asOf.ac), "the whole epic")
        assertTrue(whole.rows.isEmpty())

        // The period view (11-20 February): each row's `period` is that domain's share inside it, and they sum to the domain's.
        val periodBase = "connectionId=$connId&from=2026-02-11&to=2026-02-20"
        val periodA = client.epicProgress("$periodBase&domain=AAA")
        assertEquals("2026-02-11", periodA.period.firstDay)
        assertEquals(Triple(0.0, 0.0, 0.0), periodA.rows.byKey("BBB-1").periodEvm(), "all AAA's share is before 11 Feb")
        assertEquals(Triple(4.0, 3.0, 0.75), periodA.rows.byKey("BBB-1").evm(), "the cumulative figures stay")
        assertEquals(Triple(2.0, 1.0, 0.0), periodA.rows.byKey("BBB-4").periodEvm())
        assertPeriodSums("AAA", periodA)
        val periodB = client.epicProgress("$periodBase&domain=BBB")
        assertEquals(Triple(6.0, 5.0, 1.75), periodB.rows.byKey("BBB-1").periodEvm(), "all BBB's share is inside")
        assertEquals(Triple(4.0, 2.0, 0.5), periodB.rows.byKey("BBB-2").periodEvm())
        assertEquals(Triple(16.0, 7.0, 2.25), Triple(periodB.period.pv, periodB.period.ev, periodB.period.ac))
        assertPeriodSums("BBB", periodB)

        // Once the asOf day is past the later move, BBB-4 is a moved epic too: one row per domain, its shares splitting the whole.
        val after = client.epicProgress("connectionId=$connId&from=2026-01-01&to=2026-03-02&basis=CUMULATIVE&domain=AAA")
        assertEquals("2026-03-02", after.asOf.day)
        assertEquals(true, after.rows.byKey("BBB-4").partial)
        assertEquals(Triple(2.0, 1.0, 0.0), after.rows.byKey("BBB-4").evm())
        assertSumsToTotals("AAA after the later move", after)
    }

    /**
     * A second small world (A49): `AAA-7` goes AAA -> BBB -> AAA ([OUT] and [BACK]) and `BBB-8` has a covering `task_domain` row with
     * no domain at all (its first key, `NOPE`, is no project key), which every reader COALESCEs to `dim_epic.domain_key`. Worklogs are
     * 1 h 20 min (a sixth of a man-day), so AC meets the stored rounding: `fact_worklog.md` has four decimals, `agg_daily_flow.ac_md`
     * two per day. PV and EV are exact; AC agrees within [ROUNDING_TOLERANCE] per day with a share.
     */
    @Test
    fun `a returning epic splits in both domains, a null domain row falls back, and fractional AC rounds per day`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-epic-progress-moved-back", enabled = false)
            val (estimateField, startField, dueField) = cloneProfileFields(connId, SyncedStubFixture.connectionId())
            SyncedStubFixture.workItems().replaceStatuses(
                connId,
                listOf(
                    StatusRef("1", "To Do", StatusCategory.TODO),
                    StatusRef("7", "In progress", StatusCategory.IN_PROGRESS),
                    StatusRef("9", "Done", StatusCategory.DONE),
                ),
            )
            val inProgress = listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated))

            fun plan(budget: Double, start: String, due: String) =
                """{"$estimateField": $budget, "$startField": "$start", "$dueField": "$due"}"""
            // AAA-7 (10 man-days, Mon 9 - Fri 13 Feb: 2 a day) is in AAA until OUT (Tue 10 Feb midday), BBB until BACK (Thu 12 Feb
            // midday), AAA again: PV 2 + 4 in AAA (9, 12, 13 Feb) and 4 in BBB (10, 11 Feb). Children done before OUT (1), between (2)
            // and after BACK (4): EV 5 in AAA, 2 in BBB. A sixth of a man-day logged on 9 Feb (AAA), twice on 11 Feb (BBB), 13 Feb (AAA).
            seedItem(
                connId, RETURNED, "AAA-7", 1, null, epicCreated, inProgress, plan(10.0, "2026-02-09", "2026-02-13"),
                keyChange = FieldChangeFact("Key", OUT, null, "AAA-7", null, "BBB-7", fieldId = "issuekey"),
                laterKeyChanges = listOf(FieldChangeFact("Key", BACK, null, "BBB-7", null, "AAA-7", fieldId = "issuekey")),
                worklogs = listOf(worklogMinutes(WL_RETURN_AAA_1, OUT - DAY, 80)),
            )
            seedChild(connId, RETURNED, CHILD_RETURN_1, 20, taskCreated, OUT - DAY, 1.0, estimateField)
            seedChild(
                connId, RETURNED, CHILD_RETURN_2, 21, taskCreated, OUT + DAY, 2.0, estimateField,
                // Two logs on one day: the day's AC is 0.3333, stored 0.33.
                worklogs = listOf(worklogMinutes(WL_RETURN_BBB_1, OUT + DAY, 80), worklogMinutes(WL_RETURN_BBB_2, OUT + DAY + HOUR, 80)),
            )
            seedChild(
                connId, RETURNED, CHILD_RETURN_3, 22, taskCreated, BACK + DAY, 4.0, estimateField,
                worklogs = listOf(worklogMinutes(WL_RETURN_AAA_2, BACK + DAY, 80)),
            )
            // BBB-8 (5 man-days, 9 - 13 Feb) has a null-domain row until NULL_END: the epic is BBB's throughout. A child done and
            // 4 h logged inside that row: EV 2 and AC 0.5, all BBB's.
            seedItem(
                connId, NULL_ROW, "BBB-8", 1, null, epicCreated, inProgress, plan(5.0, "2026-02-09", "2026-02-13"),
                keyChange = FieldChangeFact("Key", NULL_END, null, "NOPE", null, "BBB-8", fieldId = "issuekey"),
            )
            seedChild(
                connId, NULL_ROW, CHILD_NULL_ROW, 23, taskCreated, NULL_END - DAY, 2.0, estimateField,
                worklogs = listOf(worklog(WL_NULL_ROW, NULL_END - DAY, 4)),
            )

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
            assertEquals(
                listOf(Triple("AAA", 0L, OUT), Triple("BBB", OUT, BACK), Triple("AAA", BACK, null)), domainRows(connId, RETURNED),
                "the returning epic's history",
            )
            assertEquals(listOf(Triple(null, 0L, NULL_END), Triple("BBB", NULL_END, null)), domainRows(connId, NULL_ROW), "the null row")

            val client = seededClient("reports-epic-progress-moved-back")
            val base = "connectionId=$connId&from=2026-01-01&to=2026-02-20&basis=CUMULATIVE"

            // (a) both domains list the returning epic with its share, partial in both; it ends in AAA, where its baseline is current.
            val inA = client.epicProgress("$base&domain=AAA")
            assertEquals(listOf("AAA-7"), inA.rows.map { it.key }, "BBB-8 never was in AAA")
            assertEquals(true, inA.rows.byKey("AAA-7").partial)
            assertEquals(listOf(6.0, 5.0), inA.rows.byKey("AAA-7").let { listOf(it.pv, it.ev) }, "AAA's two stretches, PV and EV exact")
            assertEquals(0.33, inA.rows.byKey("AAA-7").ac, 1e-9, "two logs of a sixth, one man-day rounded to four decimals")
            val inB = client.epicProgress("$base&domain=BBB")
            assertEquals(listOf("AAA-7", "BBB-8"), inB.rows.map { it.key })
            assertEquals(true, inB.rows.byKey("AAA-7").partial)
            assertEquals(listOf(4.0, 2.0), inB.rows.byKey("AAA-7").let { listOf(it.pv, it.ev) }, "BBB's one stretch")
            assertEquals(0.33, inB.rows.byKey("AAA-7").ac, 1e-9, "two logs on one day: 0.3333")
            // (b) the null row reads as BBB, in the partial count (not partial) and in the PV domain (all five days).
            assertEquals(Triple(5.0, 2.0, 0.5), inB.rows.byKey("BBB-8").evm(), "a null domain row is the epic's current domain, BBB")
            assertNull(inB.rows.byKey("BBB-8").partial, "one domain, however the rows are keyed")
            // The whole epic is the sum of its shares: exact for PV and EV, AC within the stored per-day rounding.
            val whole = client.epicProgress("$base&epicId=AAA-7")
            val shares = listOf(inA, inB).map { it.rows.byKey("AAA-7") }
            assertEquals(whole.asOf.pv, shares.sumOf { it.pv })
            assertEquals(whole.asOf.ev, shares.sumOf { it.ev })
            assertEquals(10.0, whole.asOf.pv)
            assertEquals(7.0, whole.asOf.ev)
            assertEquals(whole.asOf.ac, shares.sumOf { it.ac }, ROUNDING_TOLERANCE * 4 + 1e-9, "AC to the rounding")
            // (c) Σ rows vs the DOMAIN totals: PV and EV exact, AC within the rounding of the days that hold a share.
            for ((label, body, days) in listOf(Triple("AAA", inA, 2), Triple("BBB", inB, 2))) {
                assertEquals(body.asOf.pv, body.rows.sumOf { it.pv }, "$label: PV adds up exactly")
                assertEquals(body.asOf.ev, body.rows.sumOf { it.ev }, "$label: EV adds up exactly")
                // Half a cent per stored day with a share, and half a cent for the row's own final rounding.
                val tolerance = ROUNDING_TOLERANCE * (days + 1) + 1e-9
                assertEquals(body.asOf.ac, body.rows.sumOf { it.ac }, tolerance, "$label: AC within the stored per-day rounding")
            }
            assertEquals(Triple(6.0, 5.0, 0.34), Triple(inA.asOf.pv, inA.asOf.ev, inA.asOf.ac), "AAA's stored per-day AC: 0.17 + 0.17")
            assertEquals(Triple(9.0, 4.0, 0.83), Triple(inB.asOf.pv, inB.asOf.ev, inB.asOf.ac), "BBB's: 0.33 + 0.5")
            // The period view (10 - 20 Feb) splits the same way.
            val periodB = client.epicProgress("connectionId=$connId&from=2026-02-10&to=2026-02-20&domain=BBB")
            assertEquals(8.0, periodB.rows.sumOf { it.period.pv }, "BBB's period PV: the returning epic's 4, BBB-8's 4 (10 - 13 Feb)")
            assertEquals(periodB.period.pv, periodB.rows.sumOf { it.period.pv })
            assertEquals(periodB.period.ev, periodB.rows.sumOf { it.period.ev })
        }

    private fun assertSumsToTotals(label: String, body: EpicProgressReport) {
        assertEquals(body.asOf.pv, body.rows.sumOf { it.pv }, 1e-9, "$label: the rows' PV add up to the domain's")
        assertEquals(body.asOf.ev, body.rows.sumOf { it.ev }, 1e-9, "$label: the rows' EV add up to the domain's")
        assertEquals(body.asOf.ac, body.rows.sumOf { it.ac }, 1e-9, "$label: the rows' AC add up to the domain's")
    }

    private fun assertPeriodSums(label: String, body: EpicProgressReport) {
        assertEquals(body.period.pv, body.rows.sumOf { it.period.pv }, 1e-9, "$label: the rows' period PV add up to the domain's")
        assertEquals(body.period.ev, body.rows.sumOf { it.period.ev }, 1e-9, "$label: the rows' period EV add up to the domain's")
        assertEquals(body.period.ac, body.rows.sumOf { it.period.ac }, 1e-9, "$label: the rows' period AC add up to the domain's")
    }

    companion object {
        /** Half a cent: the rounding of one stored `agg_daily_flow` day (`MetricsDerivationTest`'s `ROUNDING_TOLERANCE`, private there). */
        private const val ROUNDING_TOLERANCE = 0.005
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val MOVED = 923_001L
        private const val STAYED = 923_002L
        private const val LATE_PLAN = 923_003L
        private const val MOVES_LATER = 923_004L
        private const val CHILD_BEFORE = 923_010L
        private const val CHILD_AFTER = 923_011L
        private const val CHILD_STAYED = 923_012L
        private const val CHILD_LATER = 923_013L
        private const val WL_EPIC_BEFORE = 923_021L
        private const val WL_EPIC_AFTER = 923_022L
        private const val WL_CHILD_BEFORE = 923_023L
        private const val WL_CHILD_AFTER = 923_024L
        private const val WL_STAYED = 923_025L
        private const val RETURNED = 923_031L
        private const val NULL_ROW = 923_033L
        private const val CHILD_RETURN_1 = 923_040L
        private const val CHILD_RETURN_2 = 923_041L
        private const val CHILD_RETURN_3 = 923_042L
        private const val CHILD_NULL_ROW = 923_043L
        private const val WL_RETURN_AAA_1 = 923_051L
        private const val WL_RETURN_BBB_1 = 923_052L
        private const val WL_RETURN_AAA_2 = 923_053L
        private const val WL_RETURN_BBB_2 = 923_054L
        private const val WL_NULL_ROW = 923_055L

        private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

        /** The epics' project move: Wednesday 11 February, midday UTC (inside one local day in any plausible zone). */
        private val MOVE = at("2026-02-11T12:00:00Z")

        /** AAA-7 leaves AAA (Tue 10 February, midday) and comes back ([BACK], Thu 12 February); BBB-8's null row ends at [NULL_END]. */
        private val OUT = at("2026-02-10T12:00:00Z")
        private val BACK = at("2026-02-12T12:00:00Z")
        private val NULL_END = at("2026-02-11T12:00:00Z")

        /** BBB-4's move, after the report's asOf day. */
        private val LATER = at("2026-02-25T12:00:00Z")

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
