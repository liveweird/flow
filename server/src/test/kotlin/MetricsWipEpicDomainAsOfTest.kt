package ch.nokillswit

import ch.nokillswit.metrics.asRequest
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import io.ktor.server.testing.testApplication
import org.junit.BeforeClass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The WIP epic bands (`agg_daily_wip` TEAM/EPIC and DOMAIN/EPIC) read an epic's domain AS OF THE END OF EACH DAY (amendment A49,
 * `.claude/docs/domain-model.md`; `DeriveWipStep.kt`), with the aggregates' `(from, to]` rule (`coveringAt`), and the team is the
 * owner of THAT domain. One hand-built world on a private DISABLED connection (`.claude/docs/testing.md` "The derived fixture"):
 * two open epics live in `BBB` now and started in `AAA`; [NOON] moved at midday on Tuesday 10 February (so that day's end reads
 * `BBB`), [EDGE] exactly AT the end instant of that Tuesday (the row that ENDS there still covers the day, so Tuesday stays `AAA`).
 * `AAA` is owned by one team, `BBB` by another. The stored rows are graded per day with plain SQL and against an independent
 * Kotlin oracle over every day of the run; the stub has no moved epic, so the digests do not move. Test keys only (`AAA`, `BBB`).
 */
class MetricsWipEpicDomainAsOfTest {
    private val epicCreated = at("2026-01-12T12:00:00Z")
    private val taskCreated = at("2026-01-05T12:00:00Z")

    /** A stored `agg_daily_wip` EPIC figure summed over statuses and stages: (scope kind, scope id, day) to the epic count. */
    private fun epicWip(connId: UInt): Map<Triple<String, String, String>, Int> = jdbcRows(
        "SELECT scope_kind, scope_id, day, SUM(item_count) FROM metrics.agg_daily_wip " +
            "WHERE connection_id = ? AND item_kind = 'EPIC' GROUP BY scope_kind, scope_id, day",
        connId.toLong(),
    ).associate { Triple(it[0] as String, it[1] as String, it[2] as String) to (it[3] as Number).toInt() }

    private suspend fun seedMovedEpic(connId: UInt, issueId: Long, number: Int, moveAt: Long) {
        seedItem(
            connId, issueId, "BBB-$number", 1, null, epicCreated,
            listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated)), "{}",
            // The REAL Jira shape: `from`/`to` are null and the keys sit in `fromString`/`toString` only.
            keyChange = FieldChangeFact("Key", moveAt, null, "AAA-$number", null, "BBB-$number", fieldId = "issuekey"),
        )
    }

    @Test
    fun `the WIP epic bands read an epic's domain and owner at each day's end, a day-end move leaving that day in the old domain`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-wip-epic-asof", enabled = false)
            val teamA = TestTeams.seed(SyncedStubFixture.unique("wip-epic-a"))
            val teamB = TestTeams.seed(SyncedStubFixture.unique("wip-epic-b"))
            try {
                SyncedStubFixture.workItems().replaceStatuses(
                    connId,
                    listOf(
                        StatusRef("7", "In progress", StatusCategory.IN_PROGRESS),
                        StatusRef("9", "Done", StatusCategory.DONE),
                    ),
                )
                // AAA is a domain of the options (and so of the owner mapping below) only because an item of it exists.
                seedItem(
                    connId, AAA_TASK, "AAA-40", 0, null, taskCreated,
                    listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, taskCreated)), "{}",
                )
                // The Tuesday's end instant in the configured zone (`dim_date.day_end_ms`, also Wednesday's start).
                val zone = ZoneId.of(jdbcRows("SELECT time_zone FROM metrics.settings").single().single() as String)
                val edgeMove = LocalDate.parse("2026-02-10").plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                seedMovedEpic(connId, NOON, 31, NOON_MOVE)
                seedMovedEpic(connId, EDGE, 32, edgeMove)

                val config = DerivedStubFixture.metricsConfig()
                val current = config.effectiveConfig(connId)
                assertEquals(setOf("AAA", "BBB"), current.domains.map { it.domainKey }.toSet(), "the defaults map both projects 1:1")
                config.replaceConfig(
                    connId,
                    current.asRequest().copy(
                        domains = current.domains.map { it.copy(ownerTeamId = if (it.domainKey == "AAA") teamA else teamB) },
                    ),
                )
                DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }

                assertEquals(
                    listOf(Triple("AAA", 0L, NOON_MOVE), Triple("BBB", NOON_MOVE, null)), domainRows(connId, NOON),
                    "the noon epic's history is [AAA, 0..noon) [BBB, noon..)",
                )
                assertEquals(
                    listOf(Triple("AAA", 0L, edgeMove), Triple("BBB", edgeMove, null)), domainRows(connId, EDGE),
                    "the edge epic's row ends exactly at Tuesday's day_end_ms",
                )
                assertEquals(
                    edgeMove, jdbcRows("SELECT day_end_ms FROM metrics.dim_date WHERE day = '2026-02-10'").single().single(),
                    "the edge move sits exactly on Tuesday's day_end_ms",
                )

                val stored = epicWip(connId)
                val a = teamA.toString()
                val b = teamB.toString()
                fun count(kind: String, scope: String, day: String) = stored[Triple(kind, scope, day)] ?: 0
                // Per day: Monday the 9th both epics are AAA's; Tuesday the 10th the noon epic already reads BBB while the edge epic
                // (moved exactly at Tuesday's end) is still AAA; from Wednesday both are BBB's. TEAM follows the owner of that domain.
                for ((day, inA, inB) in listOf(Triple("2026-02-09", 2, 0), Triple("2026-02-10", 1, 1), Triple("2026-02-11", 0, 2))) {
                    assertEquals(inA, count("DOMAIN", "AAA", day), "DOMAIN/EPIC AAA on $day")
                    assertEquals(inB, count("DOMAIN", "BBB", day), "DOMAIN/EPIC BBB on $day")
                    assertEquals(inA, count("TEAM", a, day), "TEAM/EPIC (AAA's owner) on $day")
                    assertEquals(inB, count("TEAM", b, day), "TEAM/EPIC (BBB's owner) on $day")
                    assertEquals(0, count("TEAM", "UNOWNED", day), "both domains are owned on $day")
                }

                // The oracle: every day of the run, from the hand-built histories alone (a day's end e reads AAA while e <= the move).
                val days = jdbcRows(
                    "SELECT day, day_end_ms FROM metrics.dim_date WHERE day_end_ms > ? AND day_start_ms <= ? ORDER BY day",
                    epicCreated, DerivedStubFixture.PINNED_NOW,
                ).map { (it[0] as String) to (it[1] as Long) }
                val moves = listOf(NOON_MOVE, edgeMove)
                val expected = HashMap<Triple<String, String, String>, Int>()
                for ((day, dayEnd) in days) {
                    for (move in moves) {
                        val domain = if (dayEnd <= move) "AAA" else "BBB"
                        val team = if (domain == "AAA") a else b
                        expected.merge(Triple("DOMAIN", domain, day), 1, Int::plus)
                        expected.merge(Triple("TEAM", team, day), 1, Int::plus)
                    }
                }
                assertEquals(expected, stored, "every stored EPIC figure equals the independent per-day oracle")
            } finally {
                cleanUpTeams(listOf(teamA, teamB))
            }
        }

    companion object {
        private const val NOON = 922_001L
        private const val EDGE = 922_002L
        private const val AAA_TASK = 922_010L

        private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

        /** The noon epic's project move: Tuesday 10 February, midday UTC (inside one local day in any plausible zone). */
        private val NOON_MOVE = at("2026-02-10T12:00:00Z")

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
