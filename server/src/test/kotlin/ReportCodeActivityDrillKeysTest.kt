package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.identities.CodeIdentityConfirmResult
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.norm.NormCodeProject
import ch.nokillswit.norm.WorkItemStore.FieldChanges
import ch.nokillswit.norm.extractIssueKeyCandidates
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.ThroughputReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * The Jira keys on Report 21's merge-request drill rows (`linkedKeys`, A51, `.claude/docs/reports.md` "Report 21"): the stored candidates
 * of a merge request (`norm.code_mr_issue_keys`) scoped at READ time to the known Jira projects (`reports/IssueKeyScope.kt`). The
 * hand-written [CodeWorld] pins every case by hand (two keys, a lowercase branch-only key, a candidate of an unknown project hidden, a key
 * of a known project shown although no such issue exists, the old project of a moved issue, a project removed from the source hidden
 * again and the `keys:` validator line that carries it); the processed GitLab stub's clone is compared with an INDEPENDENT SQL scope.
 */
class ReportCodeActivityDrillKeysTest {
    private val db = sharedDatabaseForTests()
    private val host = sourceHostOf(GitLabStubServer.baseUrl)
    private val settings = MetricsSettingsService(db, GitLabFixtures.dataSources(), SyncJobsService(db, 3))
    private val jira = SyncedStubFixture.dataSources()

    /** A project key only this class's Jira source lists (and removes again): the scope's configured half, isolated from the fixtures'. */
    private val scoped = "ZQK"

    /** A project only an `issuekey` change of this class's Jira source names: the scope's moved-issue half. */
    private val moved = "ZQM"

    private suspend fun reportZone(): ZoneId = ZoneId.of(settings.read().timeZone)

    private fun ApplicationTestBuilder.pin(world: CodeWorld) {
        application.attributes[ReportServiceKey].clock = { world.now }
    }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/code-activity/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private fun keyed(world: CodeWorld, id: Long, title: String, branch: String) = NormCodeMergeRequest(
        id, 1, id, "merged", false, world.kA, null, world.at(1), world.at(1), world.at(3), null, branch, "main", title, null, null, null,
        emptyList(), emptyList(), 3, 2, 1, false, DetailsState.READ, null, emptyList(),
        issueKeys = extractIssueKeyCandidates(title, branch),
    )

    /** `grp/app!<iid>` to its listed keys, for the merge requests this class wrote (ids 31 and up). */
    private fun ReportDrillItems.keysByMr(): Map<String, List<String>?> =
        items.filter { it.issueKey.substringAfter('!').toLong() >= FIRST_KEYED_ID }.associate { it.issueKey to it.linkedKeys }

    private suspend fun <T> inWorld(body: suspend CodeWorld.(jiraId: UInt) -> T): T = withCodeWorld(reportZone()) {
        GitLabFixtures.codeStore().replaceProjects(
            connId, listOf(NormCodeProject(1, "grp/app", "grp", null, archived = false, movedOutAt = null, deletedAt = null)),
        )
        val jiraId = SyncedStubFixture.createConnection(
            projectKeys = SyncedStubFixture.IN_SCOPE_PROJECT_KEYS + scoped, enabled = false, namePrefix = "drill-keys",
        )
        // The moved-issue half: an `issuekey` change out of $moved into a configured project (the issue id is nobody's row).
        suspendTransaction(db) {
            FieldChanges.insert {
                it[connectionId] = jiraId
                it[issueId] = 880_001L
                it[seq] = 1
                it[field] = "Key"
                it[changedAt] = 1L
                it[fromText] = "$moved-3"
                it[toText] = "FLO-77"
                it[fieldId] = "issuekey"
            }
        }
        try {
            body(jiraId)
        } finally {
            suspendTransaction(db) { FieldChanges.deleteWhere { connectionId eq jiraId } }
            jira.delete(jiraId)
        }
    }

    @Test
    fun `a merge request shows the Jira keys of its known projects only - sorted, uppercased, junk and unknown projects hidden`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("code-drill-keys", FlowUserRole.USER)
            inWorld {
                pin(this)
                val store = GitLabFixtures.codeStore()
                store.replaceMergeRequests(
                    connId,
                    listOf(
                        keyed(this, 31, "FLO-1: export (PLT-14)", "feature/export"),
                        keyed(this, 32, "Tidy the retry budget", "feature/flo-8-tidy"),
                        keyed(this, 33, "Bump ABC-12 and UTF-8 for GTM-3", "fix_FLO-8"),
                        keyed(this, 34, "FLO-900: a key beyond the stub's range", "feature/x"),
                        keyed(this, 35, "No key here", "feature/nothing"),
                        keyed(this, 36, "$scoped-5: a project only this source lists", "feature/y"),
                        keyed(this, 37, "$moved-3 moved to FLO-77", "feature/z"),
                    ),
                    now,
                )
                val query = "teamId=$teamId&from=2026-03-02&to=2026-03-15&connectionId=$connId&view=MRS_MERGED"
                val listed = client.items(query).keysByMr()
                assertEquals(
                    mapOf(
                        "grp/app!31" to listOf("FLO-1", "PLT-14"),
                        // The branch is lowercased by tooling: the key is found and shown uppercased.
                        "grp/app!32" to listOf("FLO-8"),
                        // ABC-12 (unknown project), UTF-8 (junk) and FIX_FLO-8 (the underscore candidate) are stored but not shown.
                        "grp/app!33" to listOf("FLO-8", "GTM-3"),
                        // A known project, no such issue in Jira: shown all the same (it is a link; Jira answers 404).
                        "grp/app!34" to listOf("FLO-900"),
                        "grp/app!35" to emptyList(),
                        "grp/app!36" to listOf("$scoped-5"),
                        // The OLD project of a moved issue is known through the `issuekey` change, and so is the new one.
                        "grp/app!37" to listOf("FLO-77", "$moved-3"),
                    ),
                    listed,
                )
                // The merge requests of the world itself name nothing: an empty list, not null.
                assertEquals(
                    listOf(emptyList(), emptyList()),
                    client.items(query).items.filter { it.issueKey in listOf("grp/app!1", "grp/app!2") }.map { it.linkedKeys },
                )
                // A commit is linked through its merge request only: no list at all.
                val commits = client.items("teamId=$teamId&from=2026-03-02&to=2026-03-15&connectionId=$connId&view=COMMITS")
                assertTrue(commits.items.isNotEmpty() && commits.items.all { it.linkedKeys == null })
                // The stored candidates are the full set: a project that becomes known later links retroactively.
                assertTrue(
                    jdbcRows("SELECT issue_key FROM norm.code_mr_issue_keys WHERE connection_id = ? AND mr_id = 33", connId.toInt())
                        .map { it[0] as String }.containsAll(listOf("ABC-12", "UTF-8", "FIX_FLO-8", "FLO-8", "GTM-3")),
                )
            }
        }

    @Test
    fun `removing a project from the Jira source hides its keys again and moves the code readers' etag, not a Jira report's`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("code-drill-keys-etag", FlowUserRole.USER)
            inWorld { jiraId ->
                pin(this)
                GitLabFixtures.codeStore().replaceMergeRequests(connId, listOf(keyed(this, 36, "$scoped-5: scoped", "feature/y")), now)
                val scope = "teamId=$teamId&from=2026-03-02&to=2026-03-15&connectionId=$connId"
                val drill = "/api/v1/reports/code-activity/items?$scope&view=MRS_MERGED"
                val report = "/api/v1/reports/code-activity?$scope"
                val jiraReport = "/api/v1/reports/cycle-time?teamId=$teamId&from=2026-03-02&to=2026-03-15"
                suspend fun etag(url: String) = assertNotNull(client.get(url).headers[HttpHeaders.ETag], url)
                val before = listOf(etag(drill), etag(report))
                val jiraBefore = etag(jiraReport)
                assertEquals(listOf("$scoped-5"), client.items(drill.substringAfter('?')).keysByMr()["grp/app!36"])

                SyncedStubFixture.putProjectKeys(jiraId, SyncedStubFixture.IN_SCOPE_PROJECT_KEYS)

                assertNotEquals(before[0], etag(drill), "the drill's tag carries the project list")
                assertNotEquals(before[1], etag(report), "so does the report's (one stamp for every code reader)")
                assertEquals(jiraBefore, etag(jiraReport), "a Jira report's tag does not move on a project-list edit")
                assertEquals(emptyList(), client.items(drill.substringAfter('?')).keysByMr()["grp/app!36"], "the key is no longer shown")
                SyncedStubFixture.putProjectKeys(jiraId, SyncedStubFixture.IN_SCOPE_PROJECT_KEYS + scoped)
                assertEquals(before[0], etag(drill), "restoring the list restores the tag")
            }
        }

    @Test
    fun `a Jira report's items carry no linkedKeys`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("code-drill-keys-jira", FlowUserRole.USER)
        val range = "connectionId=$connId&from=2025-06-01&to=2026-03-05&domain=${DerivedStubFixture.DOMAIN}&bucket=MONTH"
        val throughput = client.get("/api/v1/reports/throughput?$range")
        assertEquals(HttpStatusCode.OK, throughput.status)
        val month = throughput.body<ThroughputReport>().byBucket.first { it.deliveredItems > 0 }.bucketStart
        val drill = client.get("/api/v1/reports/throughput/items?$range&bucketStart=$month")
        assertEquals(HttpStatusCode.OK, drill.status)
        val items = drill.body<ReportDrillItems>().items
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.linkedKeys == null && it.itemKind.name == "TASK" })
    }

    @Test
    fun `the stub's merge requests show exactly the keys an independent SQL scope admits`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("code-drill-keys-admin", FlowUserRole.ADMIN)
        val user = seededClient("code-drill-keys-user", FlowUserRole.USER)
        application.attributes[ReportServiceKey].clock = { REFERENCE_TIME }
        val teamId = TestTeams.seed("code-drill-keys-${System.nanoTime()}")
        val memberships = TeamMembershipService(db, settings)
        val membershipIds = mutableListOf<UInt>()
        val jiraId = SyncedStubFixture.createConnection(enabled = false, namePrefix = "drill-keys-stub")
        try {
            val confirmed = admin.post("/api/v1/data-sources/$ownId/code-identities/confirm-proposals").body<CodeIdentityConfirmResult>()
            assertEquals(CodeIdentityConfirmResult(linked = 6, bots = 1), confirmed)
            CodeIdentityLinkStore(db).forHost(host).filter { it.decision == CodeIdentityDecision.LINKED }.map { it.jiraAccountId!! }
                .distinct().forEach { membershipIds += memberships.create(teamId, TeamMembershipCreateRequest(it, validFrom = 0L)).id }

            // The independent scope: the configured keys of EVERY active Jira source plus the project parts of their issuekey changes.
            val configured = jdbcRows(
                """SELECT DISTINCT k FROM source_connections s, jsonb_array_elements_text(s.settings -> 'projectKeys') AS k
                   WHERE s.kind = 'JIRA_CLOUD' AND NOT s.marked_as_deleted""",
            ).map { it[0] as String }
            val changed = jdbcRows(
                """SELECT DISTINCT t FROM norm.work_item_field_changes f JOIN source_connections s ON s.id = f.connection_id,
                     LATERAL unnest(ARRAY[f.from_value, f.from_text, f.to_value, f.to_text]) AS t
                   WHERE f.field_id = 'issuekey' AND s.kind = 'JIRA_CLOUD' AND NOT s.marked_as_deleted""",
            ).mapNotNull { (it[0] as String?)?.substringBefore('-')?.takeIf { project -> project.matches(Regex("[A-Z][A-Z0-9_]{1,9}")) } }
            val known = (configured + changed).toSet()
            assertTrue(known.containsAll(SyncedStubFixture.IN_SCOPE_PROJECT_KEYS), "the scope oracle sees the stub's projects: $known")

            val from = "2026-03-01"
            val to = "2026-08-31"
            val base = "teamId=$teamId&from=$from&to=$to&connectionId=$ownId&tailPercent=0"
            var shown = 0
            var hidden = 0
            for (view in listOf("MRS_MERGED", "MRS_OPENED", "OPEN_MRS")) {
                for (item in user.items("$base&view=$view").items) {
                    val (path, iid) = item.issueKey.split('!')
                    val candidates = jdbcRows(
                        """SELECT k.issue_key FROM norm.code_mr_issue_keys k
                           JOIN norm.code_merge_requests m ON m.connection_id = k.connection_id AND m.mr_id = k.mr_id
                           JOIN norm.code_projects p ON p.connection_id = m.connection_id AND p.project_id = m.project_id
                           WHERE k.connection_id = ? AND p.path_with_namespace = ? AND m.iid = ?""",
                        ownId.toInt(), path, iid.toLong(),
                    ).map { it[0] as String }
                    val want = candidates.filter { it.substringBefore('-') in known }.sorted()
                    assertEquals(want, item.linkedKeys, "$view ${item.issueKey}")
                    shown += want.size
                    hidden += candidates.size - want.size
                }
            }
            assertTrue(shown > 0, "some merge request of the stub names a key of a known project")
            assertTrue(hidden > 0, "some candidate of the stub is junk or an unknown project and stays hidden")
        } finally {
            membershipIds.forEach { memberships.delete(teamId, it) }
            TestTeams.service.delete(teamId)
            jira.delete(jiraId)
        }
    }

    companion object {
        private const val FIRST_KEYED_ID = 31L
        private val REFERENCE_TIME = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
        private val dataSources = GitLabFixtures.dataSources()
        private var ownId: UInt = 0u

        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            warmStubFixtures()
            warmGitLabFixture()
            runBlocking {
                CodeIdentityLinkStore(sharedDatabaseForTests()).deleteHost(sourceHostOf(GitLabStubServer.baseUrl))
                ownId = GitLabFixtures.createConnection(dataSources, namePrefix = "code-drill-keys")
                SyncedGitLabFixture.cloneProcessedData(SyncedGitLabFixture.connectionId(), ownId)
            }
        }

        @JvmStatic
        @AfterClass
        fun removeOwnConnection() = runBlocking<Unit> {
            CodeIdentityLinkStore(sharedDatabaseForTests()).deleteHost(sourceHostOf(GitLabStubServer.baseUrl))
            GitLabFixtures.purgeAll(ownId)
            dataSources.delete(ownId)
        }
    }
}
