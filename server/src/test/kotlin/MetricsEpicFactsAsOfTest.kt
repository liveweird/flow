package ch.nokillswit

import ch.nokillswit.metrics.asRequest
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.reports.EpicEstimationAccuracyReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.BeforeClass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An epic's OWN fact is read as of its done instant (amendment A49, `.claude/docs/domain-model.md`): `fact_epic_delivery.domain_key`
 * and `owner_team_id` are the domain the epic had at `done_at ?: now` (the task rule, A21) and that domain's owner, while
 * `dim_epic.domain_key` stays the domain NOW. One hand-built world on a private DISABLED connection (`.claude/docs/testing.md` "The
 * derived fixture"): four epics live in `BBB` now and started in `AAA`, moved at [MOVE]; one was done before the move (AAA, its team),
 * one after it (BBB), one is still open (BBB, read now) and one was done exactly AT the move (half-open `[from, to)`, so the NEW
 * domain). The stored rows are graded with plain SQL; the epic estimation accuracy report is then read over HTTP, by domain and by team,
 * to show that a finished epic is listed under the domain it finished in. Test keys only (`AAA`, `BBB`).
 */
class MetricsEpicFactsAsOfTest {
    private val epicCreated = at("2026-01-12T12:00:00Z")
    private val taskCreated = at("2026-01-05T12:00:00Z")

    private data class EpicFact(val domainKey: String?, val ownerTeamId: Int?)

    private fun epicFacts(connId: UInt): Map<Long, EpicFact> = jdbcRows(
        "SELECT issue_id, domain_key, owner_team_id FROM metrics.fact_epic_delivery WHERE connection_id = ?", connId.toLong(),
    ).associate { it[0] as Long to EpicFact(it[1] as String?, (it[2] as Number?)?.toInt()) }

    private fun epicDimDomains(connId: UInt): Map<Long, String?> = jdbcRows(
        "SELECT issue_id, domain_key FROM metrics.dim_epic WHERE connection_id = ?", connId.toLong(),
    ).associate { it[0] as Long to it[1] as String? }

    /** A level-1 item in `BBB` now whose issue key was `AAA-<number>` until [MOVE], done at [doneAt] (still in progress when null). */
    private suspend fun seedMovedEpic(connId: UInt, issueId: Long, number: Int, doneAt: Long?) {
        val steps = listOfNotNull(
            StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, epicCreated),
            doneAt?.let { StatusStep("9", "Done", StatusCategory.DONE, it) },
        )
        seedItem(
            connId, issueId, "BBB-$number", 1, null, epicCreated, steps, "{}",
            // The REAL Jira shape: `from`/`to` are null and the keys sit in `fromString`/`toString` only.
            keyChange = FieldChangeFact("Key", MOVE, null, "AAA-$number", null, "BBB-$number", fieldId = "issuekey"),
        )
    }

    private suspend fun HttpClient.epicAccuracy(query: String): EpicEstimationAccuracyReport {
        val response = get("/api/v1/reports/epic-estimation-accuracy?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epic-estimation-accuracy?$query")
        return response.body()
    }

    @Test
    fun `an epic's fact reads its domain and owner as of its done instant, an open one now, and the dimension stays current`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-epic-facts-asof", enabled = false)
            val teamA = TestTeams.seed(SyncedStubFixture.unique("epic-facts-a"))
            val teamB = TestTeams.seed(SyncedStubFixture.unique("epic-facts-b"))
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
                    connId, AAA_TASK, "AAA-30", 0, null, taskCreated,
                    listOf(StatusStep("7", "In progress", StatusCategory.IN_PROGRESS, taskCreated)), "{}",
                )
                seedMovedEpic(connId, DONE_BEFORE, 21, MOVE - DAY)
                seedMovedEpic(connId, DONE_AFTER, 22, MOVE + DAY)
                seedMovedEpic(connId, OPEN, 23, null)
                seedMovedEpic(connId, DONE_AT_MOVE, 24, MOVE)

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
                    listOf(Triple("AAA", 0L, MOVE), Triple("BBB", MOVE, null)), domainRows(connId, DONE_BEFORE),
                    "the epic's history is [AAA, 0..move) [BBB, move..)",
                )
                assertEquals(
                    mapOf(
                        DONE_BEFORE to EpicFact("AAA", teamA.toInt()),
                        DONE_AFTER to EpicFact("BBB", teamB.toInt()),
                        OPEN to EpicFact("BBB", teamB.toInt()),
                        DONE_AT_MOVE to EpicFact("BBB", teamB.toInt()),
                    ),
                    epicFacts(connId),
                    "done before the move: AAA and its owner; done after, open (read now), done at the move (half-open): BBB and its owner",
                )
                assertEquals(
                    mapOf(DONE_BEFORE to "BBB", DONE_AFTER to "BBB", OPEN to "BBB", DONE_AT_MOVE to "BBB"),
                    epicDimDomains(connId),
                    "the dimension is the epic as it is now, like dim_task",
                )

                // The report reads the fact: the epic done in AAA is listed under AAA (and AAA's owner), not under the domain it has now.
                val client = seededClient("reports-epic-facts-asof")
                val range = "connectionId=$connId&from=2026-01-01&to=2026-12-31"
                fun EpicEstimationAccuracyReport.keys() = epics.map { it.issueKey }.toSet()
                val inA = client.epicAccuracy("$range&domain=AAA")
                assertEquals(setOf("BBB-21"), inA.keys(), "the epic done before the move is listed under AAA, with its current key")
                assertEquals(1, inA.excluded.population)
                val inB = client.epicAccuracy("$range&domain=BBB")
                assertEquals(setOf("BBB-22", "BBB-24"), inB.keys(), "done after and at the move: BBB; the open one is not finished")
                assertEquals(setOf("BBB-21"), client.epicAccuracy("$range&teamId=$teamA").keys(), "the owner of the as-of domain")
                assertEquals(setOf("BBB-22", "BBB-24"), client.epicAccuracy("$range&teamId=$teamB").keys())
            } finally {
                cleanUpTeams(listOf(teamA, teamB))
            }
        }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val DONE_BEFORE = 921_001L
        private const val DONE_AFTER = 921_002L
        private const val OPEN = 921_003L
        private const val DONE_AT_MOVE = 921_004L
        private const val AAA_TASK = 921_010L

        private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

        /** The epics' project move: Wednesday 11 February, midday UTC (inside one local day in any plausible zone). */
        private val MOVE = at("2026-02-11T12:00:00Z")

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
