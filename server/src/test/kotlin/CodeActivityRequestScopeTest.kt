package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.norm.NormCodeMrEvent
import ch.nokillswit.norm.NormCodeProject
import ch.nokillswit.reports.CodeActivityData
import ch.nokillswit.reports.CodeActivityRequest
import ch.nokillswit.reports.CodeRowBudget
import ch.nokillswit.reports.CodeEventRow
import ch.nokillswit.reports.CodeSource
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.codeActivityFigures
import ch.nokillswit.reports.fetchCodeReviewRequests
import ch.nokillswit.reports.loadCodeActivity
import ch.nokillswit.reports.requestReceivers
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The review-request read's key pre-filter (`fetchCodeReviewRequests` with a `CodeKeyScope`, `jsonb_exists_any` over `target_keys`) is
 * EXACT: it drops only requests the kernel would drop anyway. Over [CodeWorld] plus, written by hand on top of it: requests naming the
 * team's member (a), only people outside the team (b), several people at once (c), a person who joins the team on day 7 (d: a request
 * on day 3 is not hers, one on day 9 is), an undated one, ones outside the window, a tombstoned merge request, a request with no
 * target at all, a target that merely STARTS with a key, and a second GitLab source (another host) with the member's own key string.
 */
class CodeActivityRequestScopeTest {
    private val db = sharedDatabaseForTests()
    private val dataSources = GitLabFixtures.dataSources()
    private val resolver = IdentityResolver(CodeIdentityLinkStore(db))
    private val settings = MetricsSettingsService(db, dataSources, SyncJobsService(db, 3))
    private val service = ReportService(db, settings, TeamMembershipService(db, settings))

    /** The extra people and the second source of one [withScopeWorld] run. */
    private class Extras(val kC: String, val accC: String, val other: CodeSource)

    private suspend fun reportZone(): ZoneId = ZoneId.of(settings.read().timeZone)

    private fun request(world: CodeWorld, vararg events: NormCodeMrEvent, id: Long, tombstone: Boolean = false) = NormCodeMergeRequest(
        id, 1, id, "opened", false, world.kB, null, world.at(1), world.at(1), null, null, "feature", "main", "title $id", null, null, null,
        emptyList(), emptyList(), null, null, null, false, DetailsState.READ, if (tombstone) 1L else null, events.toList(),
    )

    private fun ask(seq: Int, actor: String, at: Long?, vararg targets: String) =
        NormCodeMrEvent(seq, MrEventKind.REVIEW_REQUESTED, actor, at, targets.toList())

    private suspend fun <T> withScopeWorld(body: suspend CodeWorld.(Extras) -> T): T = withCodeWorld(reportZone()) {
        val store = GitLabFixtures.codeStore()
        val kC = "u9010"
        val accC = "acc-c-${UUID.randomUUID()}"
        val hostB = "code-other-${UUID.randomUUID().toString().take(8)}.example.test"
        val connB = GitLabFixtures.createConnection(dataSources, baseUrl = "https://$hostB", enabled = false, namePrefix = "code-other")
        val decider = TestUsers.seed(uniqueEmail("code-scope"), "pw-code-scope-1")
        val links = CodeIdentityLinkStore(db)
        try {
            val carol = NormCodeIdentity(kC, IdentityKind.USER, 9010, "carol", "Carol C", "active", false, emptyList(), null)
            store.replaceIdentities(connId, identities + carol)
            // The drill links a merge request only when its project is stored.
            val project = NormCodeProject(1, "grp/app", "grp", null, archived = false, movedOutAt = null, deletedAt = null)
            store.replaceProjects(connId, listOf(project))
            links.insertIfAbsent(IdentityLink(source.host, kC, CodeIdentityDecision.LINKED, accC, decider.toLong(), 1L))
            val team = teamId
            val joined = at(7)
            suspendTransaction(db) {
                TeamMembershipService.TeamMembership.insert {
                    it[accountId] = accC
                    it[TeamMembershipService.TeamMembership.teamId] = team
                    it[validFrom] = joined
                    it[validTo] = null
                    it[createdAt] = 1L
                    it[updatedAt] = 1L
                }
            }
            store.replaceMergeRequests(
                connId,
                listOf(
                    request(this, ask(1, kB, at(2), kA), id = 30),
                    request(
                        this, ask(1, kB, at(2), kU), ask(2, kB, at(2), kB), ask(3, kU, at(4), "${kA}x"), ask(4, kB, at(5)), id = 31,
                    ),
                    request(this, ask(1, kB, at(3), kB, kA, kU), id = 32),
                    request(this, ask(1, kB, at(3), kC), ask(2, kB, at(9), kC), id = 33),
                    request(this, ask(1, kB, null, kA), ask(2, kB, at(20), kA), ask(3, kB, at(-1), kA), id = 34),
                    request(this, ask(1, kB, at(2), kA), id = 35, tombstone = true),
                ),
                now,
            )
            // The second source: the member's own key string is a target there, but that host links nobody to her.
            store.replaceMergeRequests(connB, listOf(request(this, ask(1, kB, at(2), kA, kU), id = 1)), now)
            body(Extras(kC, accC, CodeSource(connB, "https://$hostB", sourceHostOf("https://$hostB"))))
        } finally {
            suspendTransaction(db) { TeamMembershipService.TeamMembership.deleteWhere { accountId eq accC } }
            GitLabFixtures.purgeAll(connB)
            dataSources.delete(connB)
            TestUsers.softDelete(decider)
        }
    }

    private fun List<CodeEventRow>.ids() = map { Triple(it.mr.connectionId, it.mr.mrId, it.seq) }

    @Test
    fun `the key scope keeps exactly the requests naming a scoped key of their own source`() = runBlocking<Unit> {
        withScopeWorld { x ->
            val other = x.other.connectionId
            val both = listOf(connId, other)
            suspend fun read(scope: Map<UInt, List<String>>?) =
                service.reportTransaction { fetchCodeReviewRequests(both, scope, window, CodeRowBudget()).ids() }
            // No scope: every dated request of the window on both sources, in (source, merge request, seq) order -- not the undated one,
            // the two outside the window or the tombstoned merge request's. The null-target request (31#4) is read, naming nobody.
            assertEquals(
                listOf(1L to 1, 2L to 1, 30L to 1, 31L to 1, 31L to 2, 31L to 3, 31L to 4, 32L to 1, 33L to 1, 33L to 2)
                    .map { (mr, seq) -> Triple(connId, mr, seq) } + Triple(other, 1L, 1),
                read(null),
            )
            // (a), (c) and (d): the member's key and the joiner's. NOT read: the request naming only "u9001x" (a key's prefix), the null
            // target, the outsiders' requests, the other source's; 33 is read on both days (the SQL cannot know her membership dates).
            assertEquals(
                listOf(2L to 1, 30L to 1, 32L to 1, 33L to 1, 33L to 2).map { (mr, seq) -> Triple(connId, mr, seq) },
                read(mapOf(connId to listOf(kA, x.kC))),
            )
            // (b): people outside the team only.
            assertEquals(listOf(Triple(connId, 31L, 1), Triple(connId, 32L, 1)), read(mapOf(connId to listOf(kU))))
            // The same key string on the OTHER source reads that source's request only.
            assertEquals(listOf(Triple(other, 1L, 1)), read(mapOf(other to listOf(kA))))
            // A source with no key contributes nothing, and no key at all is no statement.
            assertEquals(listOf(Triple(other, 1L, 1)), read(mapOf(connId to emptyList(), other to listOf(kU))))
            assertEquals(emptyList(), read(emptyMap()))
        }
    }

    @Test
    fun `the figures and the drill are those of the unfiltered read`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-request-scope", FlowUserRole.USER)
        withScopeWorld { x ->
            application.attributes[ReportServiceKey].clock = { now }
            val zone = reportZone()
            val sources = listOf(source, x.other)
            val (data, figures) = service.reportTransaction {
                val calendar = WorkingCalendar(zone, setOf(6, 7), emptySet<LocalDate>())
                val request = CodeActivityRequest(sources, teamId, emptyList(), window, now)
                val loaded: CodeActivityData = loadCodeActivity(resolver, request, calendar, zone, minSample = 1, budget = CodeRowBudget())
                loaded to codeActivityFigures(loaded)
            }
            // By hand: the member got requests on merge requests 2 (day 3), 30 and 32; the joiner on 33 on day 9 only. The other source's
            // request naming the same key string is nobody's there.
            val received = figures.members.associate { it.member.accountId to it.reviewRequestsReceived }
            assertEquals(mapOf<String?, Int>(accA to 3, x.accC to 1), received)
            // The pre-filter is invisible to the kernel: the receivers of EVERY request of the window are those of the scoped ones.
            val every = service.reportTransaction {
                fetchCodeReviewRequests(sources.map { it.connectionId }, null, window, CodeRowBudget())
            }
            val receivers = { events: List<CodeEventRow> ->
                events.flatMap { event -> data.context.requestReceivers(event).map { it to event.mr } }.toSet()
            }
            assertTrue(data.requests.size < every.size)
            assertEquals(receivers(every), receivers(data.requests))
            assertEquals(4, receivers(data.requests).size)

            val query = "teamId=$teamId&from=2026-03-02&to=2026-03-15&connectionId=$connId&view=REVIEW_REQUESTS_RECEIVED"
            val response = client.get("/api/v1/reports/code-activity/items?$query")
            assertEquals(HttpStatusCode.OK, response.status)
            val drill: ReportDrillItems = response.body()
            assertEquals(4, drill.total)
            assertEquals(4, drill.bar.count)
            assertEquals(
                setOf("grp/app!2" to accA, "grp/app!30" to accA, "grp/app!32" to accA, "grp/app!33" to x.accC),
                drill.items.map { it.issueKey to it.accountId }.toSet(),
            )
        }
    }
}
