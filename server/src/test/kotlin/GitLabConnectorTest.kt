package ch.nokillswit

import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.fetch.FetchException
import ch.nokillswit.ingest.ConnectionTarget
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JobRefusedException
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.TOKEN_INACTIVE
import ch.nokillswit.ingest.TOKEN_SCOPE_TOO_BROAD
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import ch.nokillswit.ingest.SyncCursorsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitLab connector against the in-JVM `GitLabStubServer` (`sample-data/README.md` "GitLab stub"; ground truth
 * `sample-data/gitlab/expected.json`): Test connection's probe rows, and a SYNC through PROJECTS and USERS into `raw.gitlab_*` (a real
 * `HttpGitLabClient` over a plain OkHttp client, ~20 calls per run). Every connection is its own, DISABLED, scope rules off.
 */
class GitLabConnectorTest {
    private val connections = mutableListOf<UInt>()
    private val overrides = mutableListOf<StubMapping>()
    private val dataSources = GitLabFixtures.dataSources()
    private val store = GitLabFixtures.rawStore()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        overrides.forEach(GitLabStubServer::removeOverride)
        GitLabStubServer.resetScenarios()
        connections.forEach { GitLabFixtures.purgeAll(it) }
        connections.forEach { dataSources.delete(it) }
    }

    private suspend fun connection(vararg groups: String): UInt =
        GitLabFixtures.createConnection(dataSources, groupPaths = groups.toList().ifEmpty { listOf("platform",
            "apps") }).also { connections += it }

    private fun override(path: String, status: Int = 200, body: String = "{}") {
        overrides += GitLabStubServer.addOverride(
            get(urlPathEqualTo("/api/v4$path")).atPriority(1).willReturn(aResponse().withStatus(status).withHeader("Content-Type",
                "application/json").withBody(body)),
        )
    }

    private suspend fun testConnection(groups: List<String> = listOf("platform", "apps")): ConnectionTestResult =
        GitLabFixtures.connector(dataSources).testConnection(ConnectionTarget.GitLab(GitLabStubServer.start(), "glpat-test-token", groups))

    private fun lastProgress(progress: List<String>) =
        Json.parseToJsonElement(progress.last()).jsonObject.mapValues { it.value.jsonPrimitive.content.toLong() }

    // ------------------------------------------------------------------ Test connection

    @Test
    fun `test connection probes version, user, scopes, each group and its projects, users and emails - no cloud id`() = runBlocking<Unit> {
        val result = testConnection()
        assertEquals(
            listOf(
                "version", "user", "user_admin", "token_scopes", "group:platform", "group_projects:platform", "group:apps",
                "group_projects:apps", "users", "user_emails",
            ),
            result.rows.map { it.name },
        )
        assertTrue(result.rows.all { it.ok }, "every row of the stub world passes: ${result.rows.filterNot { it.ok }}")
        assertEquals(setOf("user_admin", "user_emails"), result.rows.filterNot { it.required }.map { it.name }.toSet())
        assertNull(result.cloudId)
        assertEquals("read_api", result.rows.single { it.name == "token_scopes" }.scopeHint)
    }

    @Test
    fun `a token with a write-capable scope fails the required token_scopes row with TOKEN_SCOPE_TOO_BROAD`() = runBlocking<Unit> {
        override("/personal_access_tokens/self", body = """{"scopes":["read_api","api"],"active":true}""")
        val row = testConnection().rows.single { it.name == "token_scopes" }
        assertFalse(row.ok)
        assertTrue(row.required)
        assertEquals("TOKEN_SCOPE_TOO_BROAD", row.code)
    }

    @Test
    fun `an inactive token fails token_scopes with TOKEN_INACTIVE`() = runBlocking<Unit> {
        override("/personal_access_tokens/self", body = """{"scopes":["read_api"],"active":false}""")
        assertEquals("TOKEN_INACTIVE", testConnection().rows.single { it.name == "token_scopes" }.code)
    }

    @Test
    fun `a non-admin owner is a failed OPTIONAL user_admin row and an unreadable user_emails row, never a failed test`() =
        runBlocking<Unit> {
        override("/user", body = """{"id":2,"is_admin":false}""")
        override("/users/2/emails", status = 403, body = """{"message":"403 Forbidden"}""")
        val rows = testConnection().rows.associateBy { it.name }
        assertEquals("NOT_ADMIN", rows.getValue("user_admin").code)
        assertEquals("FORBIDDEN_SCOPE", rows.getValue("user_emails").code)
        assertTrue(rows.values.filterNot { it.ok }.none { it.required }, "only optional rows fail")
    }

    @Test
    fun `a 401 on version is AUTHENTICATION_FAILED and a group the token cannot see fails its two rows`() = runBlocking<Unit> {
        override("/version", status = 401, body = """{"message":"401 Unauthorized"}""")
        assertEquals("AUTHENTICATION_FAILED", testConnection().rows.single { it.name == "version" }.code)

        val rows = testConnection(listOf("no-such-group")).rows.associateBy { it.name }
        assertEquals("NOT_FOUND", rows.getValue("group:no-such-group").code)
        assertEquals("NOT_FOUND", rows.getValue("group_projects:no-such-group").code, "unreachable, not a failure of its own")
    }

    @Test
    fun `the GitLab connector refuses a Jira target`() = runBlocking<Unit> {
        val jira = ConnectionTarget.Jira("https://acme.atlassian.net", "svc@example.com", "tok", listOf("ENG"), JiraAuthScheme.BASIC)
        assertFailsWith<IllegalStateException> { GitLabFixtures.connector(dataSources).testConnection(jira) }
    }

    // ------------------------------------------------------------------ SYNC

    @Test
    fun `SYNC stores the in-scope projects under their configured group and the instance's users with merged emails`() = runBlocking<Unit> {
        val connId = connection()
        val progress = mutableListOf<String>()
        GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId, progress = progress)

        val projects = store.projectRows(connId).associateBy { it[GitLabRawStore.Projects.projectId] }
        assertEquals(setOf(1L, 2L, 3L, 4L), projects.keys, "the archived (5) and the out-of-scope (6) projects are absent")
        assertEquals("platform", projects.getValue(1)[GitLabRawStore.Projects.groupPath])
        assertEquals("platform", projects.getValue(2)[GitLabRawStore.Projects.groupPath],
            "listed under the configured group, not its subgroup")
        assertEquals(7L, projects.getValue(2)[GitLabRawStore.Projects.groupId])
        assertEquals("platform/tooling/build-kit", projects.getValue(2)[GitLabRawStore.Projects.pathWithNamespace])
        assertEquals("apps", projects.getValue(3)[GitLabRawStore.Projects.groupPath])
        assertEquals("master", projects.getValue(4)[GitLabRawStore.Projects.defaultBranch])
        assertTrue(projects.values.all { it[GitLabRawStore.Projects.movedOutAt] == null && !it[GitLabRawStore.Projects.archived] })

        val users = store.userRows(connId).associateBy { it[GitLabRawStore.Users.userId] }
        assertEquals((1L..8L).toSet(), users.keys)
        fun emails(id: Long) =
            Json.parseToJsonElement(users.getValue(id)[GitLabRawStore.Users.emails]).jsonArray.map { it.jsonPrimitive.content }
        assertTrue("user3@flow-sample.example" in emails(4), "the confirmed secondary email of user 4: ${emails(4)}")
        assertEquals(emails(4).sorted(), emails(4))
        assertTrue(users.getValue(8)[GitLabRawStore.Users.bot])
        assertEquals("deactivated", users.getValue(7)[GitLabRawStore.Users.state])
        assertTrue(emails(2).any { it == "user1@flow-sample.example" })

        val counts = store.counts(connId)
        assertEquals(4L, counts.projects)
        assertEquals(8L, counts.users)
        val counters = lastProgress(progress)
        assertEquals(4L, counters["projects"])
        assertEquals(8L, counters["users"])
        assertNull(counters["usersEmailsSkipped"])
    }

    private suspend fun mrChanges(connId: UInt) =
        store.mergeRequestRows(connId).map { it[GitLabRawStore.MergeRequests.mrId] to it[GitLabRawStore.MergeRequests.changedAt] }

    @Test
    fun `a second SYNC changes no row - a scheduled one skips the fresh users, a requested one refetches them unchanged`() =
        runBlocking<Unit> {
        val connId = connection()
        val connector = GitLabFixtures.connector(dataSources)
        GitLabFixtures.run(connector, connId, onDemand = true)
        val before = store.projectRows(connId).map { it[GitLabRawStore.Projects.projectId] to it[GitLabRawStore.Projects.changedAt] } +
            store.userRows(connId).map { it[GitLabRawStore.Users.userId] to it[GitLabRawStore.Users.changedAt] }

        val commitsBefore = store.commitRows(connId).map { it[GitLabRawStore.Commits.sha] to it[GitLabRawStore.Commits.lastSeenAt] }
        assertEquals(300, commitsBefore.size)
        val mrsBefore = mrChanges(connId)
        assertEquals(60, mrsBefore.size)
        assertEquals(0L, store.countStaleMergeRequests(connId))

        val scheduled = mutableListOf<String>()
        GitLabFixtures.run(connector, connId, progress = scheduled)
        assertEquals(1L, lastProgress(scheduled)["usersSkipped"], "the users are younger than 24 h")
        assertNull(lastProgress(scheduled)["users"])
        val walked = setOf("commits", "commitFiles", "commitsRelisted", "mergeRequests", "mergeRequestsChanged", "mrDetails", "mrEvents")
        assertTrue(lastProgress(scheduled).keys.none { it in walked }, "unchanged heads and merge requests: nothing walked or re-read")

        val requested = mutableListOf<String>()
        GitLabFixtures.run(connector, connId, onDemand = true, progress = requested)
        assertEquals(8L, lastProgress(requested)["users"], "Sync now refreshes the users whatever their age")

        val after = store.projectRows(connId).map { it[GitLabRawStore.Projects.projectId] to it[GitLabRawStore.Projects.changedAt] } +
            store.userRows(connId).map { it[GitLabRawStore.Users.userId] to it[GitLabRawStore.Users.changedAt] }
        assertEquals(before, after, "no stored change: changed_at is stable")
        assertEquals(8L, store.counts(connId).users)
        assertNull(lastProgress(requested)["commits"])
        val commitsAfter = store.commitRows(connId).map { it[GitLabRawStore.Commits.sha] to it[GitLabRawStore.Commits.lastSeenAt] }
        assertEquals(commitsBefore, commitsAfter)
        assertTrue(lastProgress(requested).keys.none { it in walked }, "the requested run found nothing to write either")
        val mrsAfter = mrChanges(connId)
        assertEquals(mrsBefore, mrsAfter, "no merge request changed")
    }

    @Test
    fun `the day-2 scenario moves project 2 out of scope and brings project 7 in`() = runBlocking<Unit> {
        val connId = connection()
        val connector = GitLabFixtures.connector(dataSources)
        GitLabFixtures.run(connector, connId, onDemand = true)

        GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
        GitLabFixtures.run(connector, connId, onDemand = true)

        val projects = store.projectRows(connId).associateBy { it[GitLabRawStore.Projects.projectId] }
        assertNotNull(projects.getValue(2)[GitLabRawStore.Projects.movedOutAt], "project 2 left the configured groups")
        assertEquals("platform/tooling/lint-rules", projects.getValue(7)[GitLabRawStore.Projects.pathWithNamespace])
        assertNull(projects.getValue(1)[GitLabRawStore.Projects.movedOutAt])
        val counts = store.counts(connId)
        assertEquals(4L, counts.projects)
        assertEquals(1L, counts.projectsMovedOut)
        assertEquals(61, store.mergeRequestRows(connId).size,
            "project 7's merge request joined; project 2's stay (never tombstoned by a listing)")
        assertEquals(0L, store.countStaleMergeRequests(connId), "the day-2 SYNC re-read what changed")
    }

    @Test
    fun `a token that gained a write scope fails the SYNC with TOKEN_SCOPE_TOO_BROAD before anything is stored`() = runBlocking<Unit> {
        val connId = connection()
        override("/personal_access_tokens/self", body = """{"scopes":["read_api","write_repository"],"active":true}""")
        val failure = assertFailsWith<JobRefusedException> { GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId) }
        assertEquals(TOKEN_SCOPE_TOO_BROAD, failure.errorCode)
        assertTrue(failure.message!!.contains("write_repository") && !failure.message!!.contains("glpat"))
        assertTrue(store.projectRows(connId).isEmpty() && store.userRows(connId).isEmpty(), "nothing was stored")
    }

    @Test
    fun `a scope outside the read_ allow-list fails the SYNC with TOKEN_SCOPE_TOO_BROAD`() = runBlocking<Unit> {
        for (scope in listOf("create_runner", "manage_runner", "k8s_proxy", "self_rotate", "ai_features")) {
            val connId = connection()
            override("/personal_access_tokens/self", body = """{"scopes":["read_api","$scope"],"active":true}""")
            val failure = assertFailsWith<JobRefusedException> { GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId) }
            assertEquals(TOKEN_SCOPE_TOO_BROAD, failure.errorCode, scope)
            assertTrue(store.projectRows(connId).isEmpty())
            overrides.forEach(GitLabStubServer::removeOverride)
            overrides.clear()
        }
    }

    @Test
    fun `a token-info answer missing scopes or active fails closed, in Test connection and in the SYNC`() = runBlocking<Unit> {
        for (body in listOf("{}", """{"scopes":["read_api"]}""", """{"active":true}""")) {
            override("/personal_access_tokens/self", body = body)
            assertEquals("INVALID_RESPONSE", testConnection().rows.single { it.name == "token_scopes" }.code, body)
            val connId = connection()
            val failure = assertFailsWith<FetchException> { GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId) }
            assertEquals("INVALID_RESPONSE", failure.code, body)
            assertTrue(store.projectRows(connId).isEmpty() && store.userRows(connId).isEmpty(), "nothing was stored")
            overrides.forEach(GitLabStubServer::removeOverride)
            overrides.clear()
        }
    }

    @Test
    fun `a projects cursor written for another list of groups is dropped and the pass restarts`() = runBlocking<Unit> {
        val connId = connection()
        // The cursor says "every group of a two-group list was read, only the sweep is left"; the configured list is another one.
        SyncCursorsService(sharedDatabaseForTests()).put(
            connId, "projects", """{"passStartedAt":1,"groupIndex":2,"page":1,"groups":["old-a","old-b"]}""",
        )
        GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId)
        assertEquals(4L, store.counts(connId).projects, "the stale cursor did not skip the groups")
        assertEquals(0L, store.counts(connId).projectsMovedOut)
        val future = System.currentTimeMillis() + 1_000_000
        // A cursor for the SAME list still resumes at its position (the sweep alone here: nothing is read).
        SyncCursorsService(sharedDatabaseForTests()).put(
            connId, "projects", """{"passStartedAt":$future,"groupIndex":2,"page":1,"groups":["platform","apps"]}""",
        )
        GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId)
        assertEquals(4L, store.counts(connId).projectsMovedOut, "resumed at the sweep with a pass start in the future: all four unseen")
    }

    @Test
    fun `an inactive token fails the SYNC with TOKEN_INACTIVE, not as too broad`() = runBlocking<Unit> {
        val connId = connection()
        override("/personal_access_tokens/self", body = """{"scopes":["read_api"],"active":false}""")
        assertEquals(
            TOKEN_INACTIVE,
            assertFailsWith<JobRefusedException> { GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId) }.errorCode,
        )
    }

    @Test
    fun `a non-admin token syncs the users without asking for secondary emails`() = runBlocking<Unit> {
        val connId = connection()
        override("/user", body = """{"id":2,"is_admin":false}""")
        val progress = mutableListOf<String>()
        GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId, progress = progress)

        assertEquals(8L, store.counts(connId).users)
        assertEquals(8L, lastProgress(progress)["usersEmailsSkipped"])
        val user4 = store.userRows(connId).single { it[GitLabRawStore.Users.userId] == 4L }
        assertFalse("user3@flow-sample.example" in user4[GitLabRawStore.Users.emails], "the secondary email was never requested")
    }

    @Test
    fun `an emails endpoint that answers 403 turns the rest of the run to no emails and the SYNC still succeeds`() = runBlocking<Unit> {
        val connId = connection()
        overrides += GitLabStubServer.addOverride(
            get(urlPathMatching("/api/v4/users/[0-9]+/emails")).atPriority(1).willReturn(aResponse().withStatus(403).withBody("{}")),
        )
        val progress = mutableListOf<String>()
        GitLabFixtures.run(GitLabFixtures.connector(dataSources), connId, progress = progress)
        assertEquals(8L, store.counts(connId).users)
        assertEquals(8L, lastProgress(progress)["usersEmailsSkipped"])
    }

    @Test
    fun `a group the token cannot see fails the SYNC with NOT_FOUND`() = runBlocking<Unit> {
        val connId = connection("no-such-group")
        val failure = assertFailsWith<ch.nokillswit.fetch.FetchException> { GitLabFixtures.run(GitLabFixtures.connector(dataSources),
            connId) }
        assertEquals("NOT_FOUND", failure.code)
    }

    // ------------------------------------------------------------------ the other job kinds

    @Test
    fun `PURGE drains the raw tables, REPROCESS rebuilds every norm row, and a RECONCILE at day zero changes no row`() = runBlocking<Unit> {
        val connId = connection()
        val connector = GitLabFixtures.connector(dataSources)
        GitLabFixtures.run(connector, connId, onDemand = true)
        val stored = store.projectRows(connId).map { it[GitLabRawStore.Projects.projectId] to it[GitLabRawStore.Projects.changedAt] }
        val expectedSha = store.commitRows(connId, 1).first { it[GitLabRawStore.Commits.parentCount] <= 1 }[GitLabRawStore.Commits.sha]
        assertTrue(store.commitFileRows(connId, 1, expectedSha).isNotEmpty())
        assertEquals(60, store.mergeRequestRows(connId).size)
        assertTrue(store.mrEventRows(connId).isNotEmpty())
        val code = GitLabFixtures.codeStore()
        assertEquals(300, code.commitRows(connId).size, "the SYNC ends with PROCESS: every commit has a norm row")
        assertEquals(60, code.mergeRequestRows(connId).size)
        assertEquals(0L, GitLabFixtures.processStore().countNeedsProcessing(connId))
        val digest = GitLabFixtures.codeDigest(connId)

        val reconcile = mutableListOf<String>()
        GitLabFixtures.run(connector, connId, SyncJobKind.RECONCILE, progress = reconcile)
        assertEquals(digest, GitLabFixtures.codeDigest(connId), "a RECONCILE at day zero changes no norm row")
        GitLabFixtures.run(connector, connId, SyncJobKind.REPROCESS)
        assertEquals(digest, GitLabFixtures.codeDigest(connId), "REPROCESS rebuilds the same norm rows")
        assertEquals(0L, GitLabFixtures.processStore().countNeedsProcessing(connId))
        assertEquals(stored,
            store.projectRows(connId).map { it[GitLabRawStore.Projects.projectId] to it[GitLabRawStore.Projects.changedAt] })
        assertEquals(0L, store.counts(connId).mergeRequestsDeleted)
        assertEquals(60, store.mergeRequestRows(connId).size)
        assertEquals(4L, lastProgress(reconcile)["commitsRelisted"], "the four synced projects are re-listed")
        val leftover = SyncCursorsService(sharedDatabaseForTests()).getAll(connId).map { it.stream }.filter { it.startsWith("reconcile_") }
        assertEquals(emptyList(), leftover, "every reconcile cursor is cleared at the pass end")

        GitLabFixtures.run(connector, connId, SyncJobKind.PURGE)
        assertTrue(store.projectRows(connId).isEmpty() && store.userRows(connId).isEmpty())
        assertTrue(store.commitRows(connId).isEmpty(), "the commits are purged")
        assertTrue(store.commitFileRows(connId, 1, expectedSha).isEmpty(), "and their files with them")
        assertTrue(store.mergeRequestRows(connId).isEmpty(), "the merge requests are purged")
        assertTrue(store.mrEventRows(connId).isEmpty(), "and their events with them")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            GitLabStubServer.start()
        }
    }
}
