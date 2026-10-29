package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reports scope guard over the WHOLE spec (`.claude/docs/reports.md` "Scope"): every report path (under `/api/v1/reports/`) of
 * `documentation.yaml`, requested with NO query at all, is either refused with the exact scope `400` its rule names or is on
 * the exempt list (scoped by construction: reference data, a selection, a person, Home's three-team cap, a drill that already
 * requires its team). A report path the test does not list fails it, so a new report must choose its rule here. The pure matrix
 * of the rules is `ReportFilterTest`; this pins which route carries which one.
 */
class ReportScopeGuardTest {

    private val teamOrDomain = "teamId or domain is required: a report is read per team or per domain, never for the whole unit"
    private val teamOnly = "teamId is required: this report carries no domain slice and is read per team, never for the whole unit"
    private val epicDomainOrTeam =
        "epicId, domain or teamId is required: epic progress is read per epic, per domain or per team, never for the whole unit"

    /** Path suffix (after `/api/v1/reports/`) -> the exact `detail` the unscoped request is refused with. */
    private val guarded: Map<String, String> = buildMap {
        listOf(
            "throughput", "task-estimation-accuracy", "task-estimates", "epic-estimation-accuracy", "estimate-adjustments",
            "cycle-time", "reported-time-ratio", "blocked-time", "costs",
        ).forEach { put(it, teamOrDomain); put("$it/items", teamOrDomain) }
        listOf("wip", "backlog", "aging-wip", "data-quality").forEach { put(it, teamOrDomain) }
        listOf("commitments", "sprint-consistency", "sprint-consistency/items").forEach { put(it, teamOnly) }
        put("epic-progress", epicDomainOrTeam)
    }

    /** The paths that name their own scope (or are reference data), with the reason; none answers the scope detail. */
    private val exempt: Map<String, String> = mapOf(
        "filters" to "reference data for the pickers, bounded",
        "home" to "up to three picked teams, default the first three by name (D1)",
        "home/items" to "teamId is already required",
        "deep-dive" to "bounded by its own selection (domain+sprints or epics, at most 500 tasks)",
        "deep-dive/sprints" to "domain is already required",
        "deep-dive/epics" to "one page of the epic picker, bounded by pageSize",
        "compare-people" to "exactly two people, across every team",
        "compare-people/items" to "exactly two people, across every team",
        "person-trend" to "one person, across every team",
        "person-trend/items" to "one person, across every team",
    )

    @Test
    fun `every report path of the spec is guarded with its rule or exempt, and an unscoped read of a guarded one is the scope 400`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("scope-guard")
            val prefix = "/api/v1/reports/"
            val specPaths = OpenApiSpec.parsed.paths.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
            assertEquals(guarded.keys + exempt.keys, specPaths, "a new report path must join the guarded or the exempt list")

            for ((suffix, detail) in guarded) {
                val response = client.get("$prefix$suffix")
                assertEquals(HttpStatusCode.BadRequest, response.status, suffix)
                assertEquals(detail, response.body<ProblemDetail>().detail, suffix)
            }
            for ((suffix, why) in exempt) {
                val response = client.get("$prefix$suffix")
                val detail = if (response.status == HttpStatusCode.BadRequest) response.body<ProblemDetail>().detail.orEmpty() else ""
                assertFalse("never for the whole unit" in detail, "$suffix is exempt ($why) yet refused as a whole-unit read: $detail")
            }
        }

    @Test
    fun `a team, a domain or an epic lifts the refusal where the rule takes it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("scope-guard-lift")
        val domain = DerivedStubFixture.DOMAIN
        // The scope passes the guard; whatever the route answers next is not the scope refusal.
        val passes = listOf(
            "throughput?teamId=0", "throughput?domain=$domain", "wip?teamId=0", "epic-progress?epicId=NOPE-1", "epic-progress?teamId=0",
            "commitments?teamId=0", "sprint-consistency?teamId=0",
        )
        for (path in passes) {
            val response = client.get("/api/v1/reports/$path")
            val detail = if (response.status == HttpStatusCode.BadRequest) response.body<ProblemDetail>().detail.orEmpty() else ""
            assertFalse("never for the whole unit" in detail, "$path: $detail")
        }
        // A domain is no scope where the report has no domain slice.
        val domainOnly = client.get("/api/v1/reports/commitments?domain=$domain")
        assertEquals(HttpStatusCode.BadRequest, domainOnly.status)
        assertEquals(teamOnly, domainOnly.body<ProblemDetail>().detail)
        // The sprint mode of the throughput drill needs its team; a bucket bar does not.
        val sprintMode = client.get("/api/v1/reports/throughput/items?domain=$domain&sprintId=1")
        assertEquals(HttpStatusCode.BadRequest, sprintMode.status)
        assertTrue("the sprint mode needs teamId" in sprintMode.body<ProblemDetail>().detail.orEmpty())
    }
}
