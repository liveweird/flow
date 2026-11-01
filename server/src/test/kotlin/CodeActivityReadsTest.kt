package ch.nokillswit

import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.MrEventKind
import ch.nokillswit.norm.NormCodeCommit
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.norm.NormCodeMrEvent
import ch.nokillswit.reports.CodeActivityRequest
import ch.nokillswit.reports.CodeMember
import ch.nokillswit.reports.CodeMrRef
import ch.nokillswit.reports.CodeSource
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.codeActivityFigures
import ch.nokillswit.reports.codeSourcesOf
import ch.nokillswit.reports.fetchCodeActorEvents
import ch.nokillswit.reports.fetchCodeCommits
import ch.nokillswit.reports.fetchCodeIdentityLabels
import ch.nokillswit.reports.fetchCodeKeyActivity
import ch.nokillswit.reports.fetchCodeMergeRequests
import ch.nokillswit.reports.fetchCodeQualityCounts
import ch.nokillswit.reports.fetchCodeReviewRequests
import ch.nokillswit.reports.fetchCodeReviewSummaries
import ch.nokillswit.reports.loadCodeActivity
import ch.nokillswit.reports.resolveCodeSources
import ch.nokillswit.reports.CodeReviewSummary
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Report 21's SQL reads and read plan (`reports/CodeActivityReads.kt`, `CodeActivityLoad.kt`) over HAND-WRITTEN `norm.code_*` rows on a
 * disabled GitLab connection of its own (a unique host, so its links never meet another class's), with unique accounts; everything is
 * removed in `finally`. Days count from Monday 2026-03-02 (UTC); the window is days 0-13 and now is day 14.
 *
 * Keys: `kA` (alice, LINKED to the team's member), `kB` (LINKED, a reviewer with no team), `kBot` (decided BOT), `kU` (undecided human).
 */
class CodeActivityReadsTest {
    private val db = sharedDatabaseForTests()
    private val dataSources = GitLabFixtures.dataSources()
    private val store = GitLabFixtures.codeStore()
    private val links = CodeIdentityLinkStore(db)
    private val resolver = IdentityResolver(links)
    private val zone = ZoneId.of("UTC")
    private val calendar = WorkingCalendar(zone, setOf(6, 7), emptySet<LocalDate>())
    private val service = run {
        val settings = MetricsSettingsService(db, dataSources, SyncJobsService(db, 3))
        ReportService(db, settings, TeamMembershipService(db, settings))
    }

    private val kA = "u9001"
    private val kB = "u9002"
    private val kU = "u9003"
    private val kBot = "u9004"
    private val base = LocalDate.of(2026, 3, 2)
    private fun at(day: Int, hour: Int = 0): Long =
        base.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L
    private val window = at(0) to at(14)
    private val now = at(14)

    private class World(val source: CodeSource, val teamId: UInt, val accA: String, val accB: String) {
        val connId: UInt get() = source.connectionId
    }

    private fun commit(
        sha: String,
        key: String,
        day: Int,
        counted: Triple<Int, Int, Int>?,
        merge: Boolean = false,
        deleted: Boolean = false,
    ) =
        NormCodeCommit(
            1, sha, key, "t", at(day), at(day), merge, counted?.second, counted?.third, counted?.first, counted?.second, counted?.third,
            counted?.first, false, if (deleted) 1L else null,
        )

    private fun event(seq: Int, kind: MrEventKind, actor: String?, at: Long?, targets: List<String> = emptyList()) =
        NormCodeMrEvent(seq, kind, actor, at, targets)

    private fun mr(
        id: Long,
        author: String?,
        created: Int,
        state: String,
        merged: Int? = null,
        closed: Int? = null,
        draft: Boolean = false,
        size: Pair<Int, Int>? = null,
        files: Int? = null,
        details: DetailsState = DetailsState.READ,
        deleted: Boolean = false,
        events: List<NormCodeMrEvent> = emptyList(),
    ) = NormCodeMergeRequest(
        id, 1, id, state, draft, author, null, at(created), at(created), merged?.let { at(it) }, closed?.let { at(it) }, "feature", "main",
        "title $id", null, null, null, emptyList(), emptyList(), size?.first, size?.second, files, false, details,
        if (deleted) 1L else null,
        events,
    )

    private fun identity(key: String, userId: Long, username: String?, displayName: String?, bot: Boolean = false) = NormCodeIdentity(
        key, IdentityKind.USER, userId, username, displayName, "active", bot, emptyList(), null,
    )

    private val commits = listOf(
        commit("a1", kA, 1, Triple(3, 10, 2)),
        commit("a2", kA, 2, null),
        commit("a3", kA, -3, Triple(1, 1, 1)),
        commit("a4", kA, 3, Triple(1, 1, 1), merge = true),
        commit("a5", kA, 4, Triple(1, 1, 1), deleted = true),
        commit("b1", kB, 5, Triple(1, 4, 4)),
        commit("u1", kU, 6, Triple(1, 1, 1)),
    )

    private val mergeRequests = listOf(
        mr(
            1, kA, 1, "merged", merged = 4, size = 20 to 5, files = 3,
            events = listOf(
                event(1, MrEventKind.REVIEW_REQUESTED, kA, at(1), listOf(kB)),
                event(2, MrEventKind.REVIEWED, kB, at(2)),
                event(3, MrEventKind.COMMENT, kB, at(3)),
                event(4, MrEventKind.APPROVAL, kB, at(3, 1)),
                event(5, MrEventKind.APPROVAL, kB, at(3, 2)),
                event(6, MrEventKind.COMMENT, kBot, at(2)),
                event(7, MrEventKind.COMMENT, kA, at(2)),
                event(8, MrEventKind.COMMENT, null, at(2)),
                event(9, MrEventKind.COMMENT, kU, null),
            ),
        ),
        mr(
            2, kA, 6, "merged", merged = 8, size = 1 to 1, files = 1,
            events = listOf(event(1, MrEventKind.REVIEW_REQUESTED, kU, at(3), listOf(kA))),
        ),
        mr(3, kA, 7, "opened", draft = true),
        mr(4, kA, -20, "opened"),
        mr(5, kA, -30, "merged", merged = -25, size = 1 to 1, files = 1),
        mr(6, kA, 9, "opened", deleted = true, events = listOf(event(1, MrEventKind.COMMENT, kB, at(9)))),
        mr(7, kU, 3, "merged", merged = 6, details = DetailsState.PENDING),
        mr(8, kA, 2, "closed", closed = 5),
        mr(9, null, 4, "merged", merged = 9, details = DetailsState.PENDING),
    )

    private suspend fun insertMembership(teamId: UInt, account: String, fromDay: Int) {
        suspendTransaction(db) {
            TeamMembershipService.TeamMembership.insert {
                it[accountId] = account
                it[TeamMembershipService.TeamMembership.teamId] = teamId
                it[validFrom] = at(fromDay)
                it[validTo] = null
                it[createdAt] = 1L
                it[updatedAt] = 1L
            }
        }
    }

    /** A disabled GitLab connection on a unique host with the world's rows, its team, member, links and decider; all removed after. */
    private fun withWorld(body: suspend World.() -> Unit) = runBlocking {
        val host = "code-reads-${UUID.randomUUID().toString().take(8)}.example.test"
        val connId = GitLabFixtures.createConnection(dataSources, baseUrl = "https://$host", enabled = false, namePrefix = "code-reads")
        val teamId = TestTeams.seed("code-reads-${UUID.randomUUID().toString().take(8)}")
        val userId = TestUsers.seed(uniqueEmail("code-reads"), "pw-code-reads-1")
        val source = CodeSource(connId, "https://$host", sourceHostOf("https://$host"))
        val world = World(source, teamId, "acc-a-${UUID.randomUUID()}", "acc-b-${UUID.randomUUID()}")
        try {
            store.replaceIdentities(
                connId,
                listOf(
                    identity(kA, 9001, "alice", "Alice A"),
                    identity(kB, 9002, "bob", "Bob B"),
                    identity(kU, 9003, "unlinked-user", null),
                    identity(kBot, 9004, "ci-bot", "CI Bot", bot = true),
                ),
            )
            store.replaceCommits(connId, commits, now)
            store.replaceMergeRequests(connId, mergeRequests, now)
            val decider = userId.toLong()
            links.insertIfAbsent(IdentityLink(world.source.host, kA, CodeIdentityDecision.LINKED, world.accA, decider, 1L))
            links.insertIfAbsent(IdentityLink(world.source.host, kB, CodeIdentityDecision.LINKED, world.accB, decider, 1L))
            links.insertIfAbsent(IdentityLink(world.source.host, kBot, CodeIdentityDecision.BOT, null, decider, 1L))
            insertMembership(teamId, world.accA, -100)
            world.body()
        } finally {
            suspendTransaction(db) {
                TeamMembershipService.TeamMembership.deleteWhere { accountId inList listOf(world.accA, world.accB) }
            }
            TestTeams.service.delete(teamId)
            links.deleteHost(world.source.host)
            TestUsers.softDelete(userId)
            GitLabFixtures.purgeAll(connId)
            dataSources.delete(connId)
        }
    }

    private suspend fun <T> read(block: suspend () -> T): T = service.reportTransaction { block() }

    @Test
    fun `the scope is the active GitLab sources, a disabled one included, and a deleted or non-GitLab id is a 400`() = runBlocking<Unit> {
        val host = "code-scope-${UUID.randomUUID().toString().take(8)}.example.test"
        val connId = GitLabFixtures.createConnection(dataSources, baseUrl = "https://$host")
        val jiraId = jiraRow()
        try {
            val all = resolveCodeSources(dataSources, null)
            assertTrue(connId in all.map { it.connectionId }, "a disabled GitLab source is in scope")
            assertFalse(jiraId in all.map { it.connectionId }, "a Jira source is never in the code scope")
            assertEquals(listOf(connId), resolveCodeSources(dataSources, connId).map { it.connectionId })
            assertEquals(sourceHostOf(all.single { it.connectionId == connId }.baseUrl), all.single { it.connectionId == connId }.host)
            val jira = assertFailsWith<BadRequestException> { resolveCodeSources(dataSources, jiraId) }
            assertEquals("Unknown or inactive connectionId: $jiraId", jira.message)
            dataSources.delete(connId)
            assertFalse(connId in resolveCodeSources(dataSources, null).map { it.connectionId }, "a soft-deleted source is out of scope")
            assertFailsWith<BadRequestException> { resolveCodeSources(dataSources, connId) }
        } finally {
            GitLabRowFixture.remove(jiraId)
            GitLabFixtures.purgeAll(connId)
            dataSources.delete(connId)
        }
    }

    @Test
    fun `the source list is the sorted id order and an unknown id is refused without a statement`() {
        val urls = mapOf(7u to "https://b.example.test", 3u to "https://a.example.test:8443")
        assertEquals(listOf(3u, 7u), codeSourcesOf(urls, null).map { it.connectionId })
        assertEquals("a.example.test:8443", codeSourcesOf(urls, 3u).single().host)
        assertFailsWith<BadRequestException> { codeSourcesOf(urls, 5u) }
    }

    @Test
    fun `commits are the scope's live non-merge commits committed in the window`() = withWorld {
        val connections = listOf(connId)
        read {
            assertEquals(listOf("a1", "a2"), fetchCodeCommits(connections, mapOf(connId to listOf(kA)), window).map { it.sha })
            // Every key of the sources (the UNASSIGNED bucket's read): a merge commit, a tombstone and one before the window stay out.
            assertEquals(listOf("a1", "a2", "b1", "u1"), fetchCodeCommits(connections, null, window).map { it.sha })
            assertEquals(emptyList(), fetchCodeCommits(connections, emptyMap(), window))
            assertEquals(emptyList(), fetchCodeCommits(emptyList(), null, window))
            val a1 = fetchCodeCommits(connections, mapOf(connId to listOf(kA)), window).first()
            assertEquals(Triple(3, 10, 2), Triple(a1.filesCounted, a1.additionsCounted, a1.deletionsCounted))
            // a2's counted figures are unknown, never zero.
            val a2 = fetchCodeCommits(connections, mapOf(connId to listOf(kA)), window).last()
            assertNull(a2.additionsCounted)
            assertFalse(a2.countsKnown)
        }
    }

    @Test
    fun `merge requests are the ones the window places, plus every open one, never a tombstone`() = withWorld {
        read {
            val ofA = fetchCodeMergeRequests(listOf(connId), mapOf(connId to listOf(kA)), window).map { it.mrId }
            // 1, 2 merged in the window; 3 created in it; 8 closed in it; 4 is open however old. 5 is long done, 6 a tombstone.
            assertEquals(setOf(1L, 2L, 3L, 4L, 8L), ofA.toSet())
            val every = fetchCodeMergeRequests(listOf(connId), null, window).map { it.mrId }
            // An unlinked author's 7 and an author-less 9 come with no key scope only.
            assertEquals(setOf(1L, 2L, 3L, 4L, 7L, 8L, 9L), every.toSet())
            val m1 = fetchCodeMergeRequests(listOf(connId), mapOf(connId to listOf(kA)), window).single { it.mrId == 1L }
            assertEquals(25, m1.sizeLines)
            assertTrue(m1.detailsRead)
            assertFalse(fetchCodeMergeRequests(listOf(connId), null, window).single { it.mrId == 7L }.detailsRead)
        }
    }

    @Test
    fun `actor events are the dated acts of the scope's actors on live merge requests, review requests apart`() = withWorld {
        read {
            val ofB = fetchCodeActorEvents(listOf(connId), mapOf(connId to listOf(kB)), window)
            // REVIEWED, COMMENT and two APPROVALs on merge request 1; the comment on the tombstoned 6 is not read.
            assertEquals(listOf(MrEventKind.REVIEWED, MrEventKind.COMMENT, MrEventKind.APPROVAL, MrEventKind.APPROVAL), ofB.map { it.kind })
            assertTrue(ofB.all { it.mr == CodeMrRef(connId, 1) && it.mrAuthorKey == kA })
            // An undated event is in no window.
            assertEquals(emptyList(), fetchCodeActorEvents(listOf(connId), mapOf(connId to listOf(kU)), window))
            // The requester's REVIEW_REQUESTED is not an act of review.
            val ofA = fetchCodeActorEvents(listOf(connId), mapOf(connId to listOf(kA)), window)
            assertTrue(ofA.none { it.kind == MrEventKind.REVIEW_REQUESTED })
            val requests = fetchCodeReviewRequests(listOf(connId), window)
            assertEquals(listOf(listOf(kB), listOf(kA)), requests.map { it.targetKeys })
            assertEquals(listOf(kA, kU), requests.map { it.actorKey })
        }
    }

    @Test
    fun `the review summaries pair per actor the first dated review-kind event and whether they approved`() = withWorld {
        read {
            val byActor = fetchCodeReviewSummaries(listOf(CodeMrRef(connId, 1), CodeMrRef(connId, 2))).associateBy { it.actorKey }
            // kB: REVIEWED on day 2 is the first signal (a REVIEWED-only first review reads the same), then a comment and two approvals.
            assertEquals(CodeReviewSummary(CodeMrRef(connId, 1), kB, at(2), approved = true), byActor.getValue(kB))
            assertEquals(at(2), byActor.getValue(kBot).firstReviewAt)
            assertEquals(at(2), byActor.getValue(kA).firstReviewAt)
            // kU only has an undated comment: no first review, no approval. The actor-less event and REVIEW_REQUESTED are left out.
            assertNull(byActor.getValue(kU).firstReviewAt)
            assertFalse(byActor.getValue(kU).approved)
            assertEquals(setOf(kA, kB, kU, kBot), byActor.keys)
            assertEquals(emptyList(), fetchCodeReviewSummaries(emptyList()))
        }
    }

    @Test
    fun `key activity and the quality counts are connection-wide`() = withWorld {
        read {
            val activity = fetchCodeKeyActivity(listOf(connId), window).associateBy { it.key }
            // Alice: a1, a2; merge requests created or merged in the window 1, 2, 3, 8; REVIEW_REQUESTED and COMMENT on 1.
            assertEquals(Triple(2L, 4L, 2L), activity.getValue(kA).let { Triple(it.commits, it.mergeRequests, it.events) })
            assertEquals(Triple(1L, 0L, 4L), activity.getValue(kB).let { Triple(it.commits, it.mergeRequests, it.events) })
            // kU: one commit, merge request 7, the dated REVIEW_REQUESTED on 2 (the undated comment is not counted).
            assertEquals(Triple(1L, 1L, 1L), activity.getValue(kU).let { Triple(it.commits, it.mergeRequests, it.events) })
            assertEquals(Triple(0L, 0L, 1L), activity.getValue(kBot).let { Triple(it.commits, it.mergeRequests, it.events) })
            val quality = fetchCodeQualityCounts(listOf(connId), window)
            assertEquals(1, quality.commitsLinesUnknown)
            // Merged in the window with an unknown total / details not read: merge requests 7 and 9.
            assertEquals(2, quality.mrsSizeUnknown)
            assertEquals(2, quality.mrsDetailsNotRead)
            assertEquals(1, quality.eventsUndated)
            assertEquals(0, fetchCodeQualityCounts(emptyList(), window).eventsUndated)
        }
    }

    @Test
    fun `identity labels are the display name, else the username`() = withWorld {
        read {
            assertEquals(
                mapOf(kA to "Alice A", kU to "unlinked-user"),
                fetchCodeIdentityLabels(listOf(connId), listOf(kA, kU, "u1234567")),
            )
            assertEquals(emptyMap(), fetchCodeIdentityLabels(listOf(connId), emptyList()))
        }
    }

    @Test
    fun `the read plan for a team attributes by the links and the memberships`() = withWorld {
        val figures = read {
            val request = CodeActivityRequest(listOf(source), teamId, emptyList(), window, now)
            codeActivityFigures(loadCodeActivity(resolver, request, calendar, zone, minSample = 1))
        }
        val flow = figures.flow
        // Alice's merge requests 1 and 2 merged in the window, 8 closed unmerged; bob (no team) reviewed 1.
        assertEquals(listOf(1L, 2L), flow.mrs.map { it.scoped.row.mrId })
        assertEquals(1, flow.closedUnmerged)
        // 1: created day 1 (Tue), first review day 2 (REVIEWED by bob; the BOT, the self-comment and the undated comment do not count).
        assertEquals(listOf(1.0, null), flow.mrs.map { it.firstReviewWorkingDays })
        assertEquals(listOf(3.0, 1.0), flow.mrs.map { it.cycleWorkingDays })
        assertEquals(1, flow.mergedWithoutReview.count)
        assertEquals(1, flow.mergedWithoutApproval.count)
        assertEquals(0.5, flow.mergedWithoutReview.share)
        assertEquals(listOf(1, 1), flow.weekly.map { it.merged })
        assertEquals(0.5, figures.review.reviewersPerMergedMrP50)
        assertEquals(1.0, figures.review.topReviewerShare)
        // Open now: 4 (Tue Feb 10 to Mon Mar 16 = 24 working days) waits; 3 is a draft, counted apart.
        assertEquals(1, figures.open.count)
        assertEquals(1, figures.open.drafts)
        assertEquals(24.0, figures.open.ageP50)
        val alice = figures.members.single()
        assertEquals(CodeMember(accA, null), alice.member)
        assertEquals(2, alice.commits)
        assertEquals(listOf(3L, 10L, 2L), listOf(alice.filesChanged, alice.linesAdded, alice.linesDeleted))
        assertEquals(1, alice.commitsLinesUnknown)
        assertEquals(listOf(2, 4, 2), listOf(alice.mrsMerged, alice.mrsOpened, alice.openMrs))
        assertEquals(2.0, alice.mrCycleWorkingDaysP50)
        assertEquals(13.5, alice.mrSizeLinesP50)
        assertEquals(listOf(1, 0, 0), listOf(alice.comments, alice.reviewsGiven, alice.approvalsGiven))
        assertEquals(1, alice.reviewRequestsReceived)
        // Quality is connection-wide and counts the undecided only: kU needs an admin, the BOT is a decision.
        assertEquals(1, figures.quality.unlinkedIdentities)
        assertEquals(listOf(1L, 1L, 1L), figures.quality.let { listOf(it.unlinkedCommits, it.unlinkedMergeRequests, it.unlinkedEvents) })
    }

    @Test
    fun `the UNASSIGNED bucket lists the undecided identities and the linked with no membership, never a BOT`() = withWorld {
        val (data, figures) = read {
            val request = CodeActivityRequest(listOf(source), 0u, emptyList(), window, now)
            val loaded = loadCodeActivity(resolver, request, calendar, zone, minSample = 1)
            loaded to codeActivityFigures(loaded)
        }
        val members = figures.members.associateBy { it.member }
        assertEquals(setOf(CodeMember(null, kU), CodeMember(accB, null), CodeMember(null, null)), members.keys)
        // kU: the unlinked human's commit and merged merge request 7; labelled by the identity's username.
        assertEquals(listOf(1, 1), members.getValue(CodeMember(null, kU)).let { listOf(it.commits, it.mrsMerged) })
        assertEquals("unlinked-user", data.labels[kU])
        assertNull(data.labels[kBot], "a decided key is not listed, so it needs no label")
        // Bob is LINKED but in no team: his commit, one review of merge request 1 with ONE approval despite two events, the comment
        // and the request.
        val bob = members.getValue(CodeMember(accB, null))
        assertEquals(
            listOf(1, 1, 1, 1, 1),
            listOf(bob.commits, bob.reviewsGiven, bob.approvalsGiven, bob.comments, bob.reviewRequestsReceived),
        )
        // The BOT's comment is in no volume; the author-less merge request 9 has a member of its own.
        assertEquals(1, members.getValue(CodeMember(null, null)).mrsMerged)
        assertEquals(setOf(7L, 9L), figures.flow.mrs.map { it.scoped.row.mrId }.toSet())
        // Their details were not read: not "unreviewed".
        assertTrue(figures.flow.mrs.all { it.review == null })
        assertNull(figures.flow.mergedWithoutReview.share)
    }

    private suspend fun jiraRow(): UInt = suspendTransaction(db) {
        val stamp = System.currentTimeMillis()
        DataSourceService.Connections.insert {
            it[kind] = DataSourceKind.JIRA_CLOUD.name
            it[name] = "code-scope-jira-${UUID.randomUUID().toString().take(8)}"
            it[enabled] = false
            it[secret] = ""
            it[settings] = GitLabRowFixture.MINIMAL_SETTINGS
            it[syncIntervalMinutes] = 60
            it[backfillFrom] = "2025-01-01"
            it[reconcileHourUtc] = 3
            it[configRevision] = 1
            it[consecutiveFailures] = 0
            it[createdAt] = stamp
            it[updatedAt] = stamp
            it[markedAsDeleted] = false
        }[DataSourceService.Connections.id].value
    }
}
