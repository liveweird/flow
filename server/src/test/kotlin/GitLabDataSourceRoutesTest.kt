package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.gitlab.CommitFilesOutcome
import ch.nokillswit.gitlab.CommitFilesResult
import ch.nokillswit.gitlab.DETAILS_ERROR_FETCH_FAILED
import ch.nokillswit.gitlab.GitLabDiffStats
import ch.nokillswit.gitlab.MrDetailsOutcome
import ch.nokillswit.gitlab.MrEventSource
import ch.nokillswit.gitlab.RawGitLabMergeRequest
import ch.nokillswit.gitlab.RawGitLabMrEvent
import ch.nokillswit.gitlab.RawGitLabCommit
import ch.nokillswit.gitlab.RawGitLabCommitFile
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.DATA_SOURCE_KIND_UNSUPPORTED
import ch.nokillswit.ingest.DEFAULT_PATH_EXCLUSIONS
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourcePageResponse
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.DataSourceTestRequest
import ch.nokillswit.ingest.DataSourceKindsResponse
import ch.nokillswit.ingest.GitLabConnectionRequest
import ch.nokillswit.ingest.SyncStatusResponse
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitLab half of the data-source wire (`ingest/GitLabDataSource.kt`, `DataSourceKindWrites.kt`, `GitLabScope.kt`): create / read /
 * update
 * / test over HTTP against the in-JVM `GitLabStubServer` (`configureApp("gitlab.stubBaseUrl" to ...)`, whose origin is the only base URL
 * the dev-mode policy accepts), the capability endpoint, the operator allow-list, GitLab switched off, and the audit trail. The Jira
 * side of
 * the same routes is `DataSourceRoutesTest`; the scope rules are `GitLabScopeTest`.
 */
class GitLabDataSourceRoutesTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    /** A group path no other test uses, so the overlap rule never depends on another class's rows. */
    private fun group() = "grp-${UUID.randomUUID().toString().take(8)}"

    private fun gitlab(
        baseUrl: String = GitLabStubServer.baseUrl,
        token: String? = "glpat-${UUID.randomUUID()}",
        groups: List<String> = listOf(group()),
        pathExclusions: List<String>? = null,
    ) = GitLabConnectionRequest(baseUrl, token, groups, pathExclusions)

    private fun request(name: String, gitlab: GitLabConnectionRequest? = gitlab(),
        jira: ch.nokillswit.ingest.JiraConnectionRequest? = null) =
        DataSourceRequest(name, true, 60, null, 3, jira, gitlab)

    private suspend fun rawSecret(id: UInt): String = suspendTransaction(sharedDatabaseForTests()) {
        val connections = DataSourceService.Connections
        connections.selectAll().where { connections.id eq id }.toList().single()[connections.secret]
    }

    /** Every source a test creates, soft-deleted after it (pass or fail): an active GitLab row left behind would 409 every later test. */
    private val createdIds = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        createdIds.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private suspend fun HttpClient.create(name: String = unique("gl"), gitlab: GitLabConnectionRequest = gitlab()): DataSourceResponse {
        val response = postJson("/api/v1/data-sources", request(name, gitlab))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body<DataSourceResponse>().also { createdIds += it.id }
    }

    private suspend fun HttpClient.problem(response: io.ktor.client.statement.HttpResponse, status: HttpStatusCode): ProblemDetail {
        assertEquals(status, response.status)
        return response.body()
    }

    @Test
    fun `create returns a GITLAB source with a gitlab block and no jira block, hides the token and stores it encrypted`() =
        testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glcrud", FlowUserRole.ADMIN)
        val token = "glpat-secret-${UUID.randomUUID()}"
        val paths = listOf(group())

        val response = admin.postJson("/api/v1/data-sources", request(unique("GitLab Prod"), gitlab(token = token, groups = paths)))
        assertEquals(HttpStatusCode.Created, response.status)
        assertNotNull(response.headers["Location"])
        val body = response.bodyAsText()
        assertFalse(token in body, "the token must never appear in a response")
        val created = Json.decodeFromString<DataSourceResponse>(body)
        assertEquals(DataSourceKind.GITLAB, created.kind)
        assertNull(created.jira)
        val block = assertNotNull(created.gitlab)
        assertEquals(GitLabStubServer.baseUrl, block.baseUrl)
        assertEquals(paths, block.groupPaths)
        assertTrue(block.hasToken)
        assertTrue(rawSecret(created.id).startsWith("enc:v1:"), "encrypted at rest, never plaintext")
        assertFalse(token in rawSecret(created.id))

        assertEquals(created, admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>())
        val listed = admin.get("/api/v1/data-sources?name=${created.name}").body<DataSourcePageResponse>()
        assertEquals(listOf(created.id), listed.items.map { it.id })
        assertEquals(created.gitlab, listed.items.single().gitlab)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/${created.id}").status)
    }

    @Test
    fun `PUT renames, replaces the group paths, rotates the token on request and refuses to change kind or base URL`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glput", FlowUserRole.ADMIN)
        val created = admin.create()
        val newGroups = listOf(group(), group())
        withAuditCapture { capture ->
            val rename = admin.putJson(
                "/api/v1/data-sources/${created.id}",
                request(unique("renamed"), gitlab(token = null, groups = newGroups)),
            )
            assertEquals(HttpStatusCode.NoContent, rename.status)
            val afterRename = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>()
            assertEquals(newGroups, afterRename.gitlab!!.groupPaths)
            assertTrue(afterRename.gitlab!!.hasToken, "omitting the token keeps the current one")
            assertEquals(2L, afterRename.configRevision)

            val before = rawSecret(created.id)
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/data-sources/${created.id}", request(afterRename.name, gitlab(token = "glpat-rotated",
                    groups = newGroups))).status,
            )
            assertTrue(rawSecret(created.id) != before, "a present token rotates the secret")
            assertNotNull(capture.awaitEvent { it.message == "data_source.token_rotated" && it.hasKeyValue("dataSourceId",
                created.id.toLong()) })
            val updated = assertNotNull(capture.awaitEvent { it.message == "data_source.updated" && it.hasKeyValue("dataSourceId",
                created.id.toLong()) })
            assertTrue(updated.hasKeyValue("kind", "GITLAB"))
            assertTrue(updated.keyValuePairs.any { it.key == "host" && it.value == "localhost" })
            assertTrue(updated.keyValuePairs.none { "glpat" in it.value?.toString().orEmpty() })
        }

        // The base URL is the identity (409), spelled differently or not; the same origin with a trailing slash is the same identity.
        val otherUrl = admin.putJson("/api/v1/data-sources/${created.id}", request(created.name, gitlab("https://gitlab.example.com",
            null, newGroups)))
        assertEquals(HttpStatusCode.BadRequest, otherUrl.status, "a base URL outside the policy is refused before the identity check")
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/data-sources/${created.id}", request(created.name, gitlab(GitLabStubServer.baseUrl + "/", null,
                newGroups))).status,
        )
        // The kind is fixed at create.
        val jiraBlock = ch.nokillswit.ingest.JiraConnectionRequest(
            TEST_JIRA_SITE_URL, "svc@example.com", null, uniqueProjectKeys(1), ch.nokillswit.ingest.JiraAuthScheme.BASIC,
        )
        val kindChange = admin.putJson("/api/v1/data-sources/${created.id}", request(created.name, gitlab = null, jira = jiraBlock))
        assertTrue(admin.problem(kindChange, HttpStatusCode.Conflict).detail!!.contains("kind is fixed"))
    }

    @Test
    fun `a different base URL on a PUT is 409 the identity rule when the policy accepts it`() = testApplication {
        // Two origins the policy accepts: the host list names both, so the identity check (not the policy) is what refuses the change.
        configureApp("gitlab.allowedHosts" to "gitlab.example.com,git.example.com")
        startApplication()
        val admin = seededClient("glident", FlowUserRole.ADMIN)
        val created = admin.create(gitlab = gitlab("https://gitlab.example.com"))
        val problem = admin.problem(
            admin.putJson("/api/v1/data-sources/${created.id}", request(created.name, gitlab("https://git.example.com", null,
                created.gitlab!!.groupPaths))),
            HttpStatusCode.Conflict,
        )
        assertTrue(problem.detail!!.contains("baseUrl is the data source identity"), problem.detail)
    }

    @Test
    fun `exactly one block must be present, on create and on the ad-hoc test`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glblocks", FlowUserRole.ADMIN)
        val jiraBlock = ch.nokillswit.ingest.JiraConnectionRequest(
            TEST_JIRA_SITE_URL, "svc@example.com", "tok", uniqueProjectKeys(1), ch.nokillswit.ingest.JiraAuthScheme.BASIC,
        )
        for (body in listOf(request(unique("both"), gitlab(), jiraBlock), request(unique("neither"), gitlab = null))) {
            val response = admin.postJson("/api/v1/data-sources", body)
            assertTrue(admin.problem(response, HttpStatusCode.BadRequest).detail!!.contains("Exactly one of jira or gitlab"))
        }
        for (body in listOf(DataSourceTestRequest(jiraBlock, gitlab()), DataSourceTestRequest())) {
            val response = admin.postJson("/api/v1/data-sources/test", body)
            assertTrue(admin.problem(response, HttpStatusCode.BadRequest).detail!!.contains("Exactly one of jira or gitlab"))
        }
    }

    @Test
    fun `group paths are normalised, folded and bounded, and a nested or invalid path is 400`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glpaths", FlowUserRole.ADMIN)
        val base = group()

        val created = admin.create(gitlab = gitlab(groups = listOf("/${base.uppercase()}/", base, "$base-other")))
        assertEquals(listOf(base, "$base-other"), created.gitlab!!.groupPaths, "trimmed, lowercased and the duplicate folded")
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${created.id}").status) // frees the paths for the rest

        suspend fun detail(groups: List<String>): String =
            admin.problem(admin.postJson("/api/v1/data-sources", request(unique("bad"), gitlab(groups = groups))),
                HttpStatusCode.BadRequest).detail!!
        assertTrue("must not repeat or nest" in detail(listOf(base, "$base/sub")))
        assertTrue("must not repeat or nest" in detail(listOf("$base/a/b", "$base/a")))
        assertTrue("Invalid group path" in detail(listOf("bad path")))
        assertTrue("Invalid group path" in detail(listOf("a//b")))
        assertTrue("Invalid group path" in detail(listOf("-leading-hyphen")))
        assertTrue("Invalid group path" in detail(listOf("x".repeat(300))))
        assertTrue("groupPaths must contain 1-10" in detail(emptyList()))
        assertTrue("groupPaths must contain 1-10" in detail((1..11).map { "g$it" }))
        // A nested path is only a problem inside one request: a sibling that merely shares a prefix is fine.
        val sibling = admin.create(gitlab = gitlab(groups = listOf(base, "${base}x")))
        assertEquals(2, sibling.gitlab!!.groupPaths.size)
    }

    @Test
    fun `token and base URL bounds are 400`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("gltoken", FlowUserRole.ADMIN)
        suspend fun detail(gitlab: GitLabConnectionRequest) =
            admin.problem(admin.postJson("/api/v1/data-sources", request(unique("bad"), gitlab)), HttpStatusCode.BadRequest).detail!!
        assertTrue("token is required" in detail(gitlab(token = null)))
        assertTrue("token is required" in detail(gitlab(token = "   ")))
        assertTrue("token must be 1-1000" in detail(gitlab(token = "t".repeat(1001))))
        assertTrue("baseUrl must be 1-300" in detail(gitlab(baseUrl = "")))
        assertTrue("gitlab-base-url" in detail(gitlab(baseUrl = "ftp://x")) || "gitlab-" in detail(gitlab(baseUrl = "not a url")))
    }

    @Test
    fun `the operator list decides the origin - an unlisted host is 400 gitlab-host-not-allowed, a listed one is accepted`() =
        testApplication {
        configureApp("gitlab.allowedHosts" to "gitlab.example.com")
        startApplication()
        val admin = seededClient("glhosts", FlowUserRole.ADMIN)

        val refused = admin.problem(
            admin.postJson("/api/v1/data-sources", request(unique("other"), gitlab("https://other.example.com"))),
                HttpStatusCode.BadRequest,
        )
        assertTrue("gitlab-host-not-allowed" in refused.detail!!, refused.detail)
        assertFalse("other.example.com" in refused.detail!!, "the refusal does not echo the URL")
        val scheme = admin.problem(
            admin.postJson("/api/v1/data-sources", request(unique("http"), gitlab("http://gitlab.example.com"))), HttpStatusCode.BadRequest,
        )
        assertTrue("gitlab-base-url-scheme" in scheme.detail!!, scheme.detail)

        val accepted = admin.create(gitlab = gitlab("https://GitLab.Example.com/"))
        assertEquals("https://gitlab.example.com", accepted.gitlab!!.baseUrl, "stored canonical")

        val kinds = admin.get("/api/v1/data-sources/kinds").body<DataSourceKindsResponse>()
        assertEquals(listOf("https://gitlab.example.com"), kinds.items.single { it.kind == DataSourceKind.GITLAB }.allowedOrigins)
    }

    @Test
    fun `path exclusions follow the defaults until set, a list replaces them, omitted keeps them, empty excludes nothing`() =
        testApplication {
            configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
            startApplication()
            val admin = seededClient("glpathex", FlowUserRole.ADMIN)
            val groups = listOf(group())
            val created = admin.create(gitlab = gitlab(groups = groups))
            assertEquals(DEFAULT_PATH_EXCLUSIONS, created.gitlab!!.pathExclusions, "omitted on create = the built-in defaults")

            suspend fun put(pathExclusions: List<String>?) = admin.putJson(
                "/api/v1/data-sources/${created.id}",
                request(created.name, gitlab(token = null, groups = groups, pathExclusions = pathExclusions)),
            )
            suspend fun effective() = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>().gitlab!!.pathExclusions
            // What the SYNC job reads: the effective list (the defaults for a null one, the stored list otherwise, an empty list as is).
            suspend fun forSync() = assertNotNull(GitLabFixtures.dataSources().readGitLabForSync(created.id)).pathExclusions
            assertEquals(DEFAULT_PATH_EXCLUSIONS, forSync(), "a never-set list reads as the defaults")

            assertEquals(HttpStatusCode.NoContent, put(listOf("*.lock", " docs/** ", "*.lock")).status)
            assertEquals(listOf("*.lock", "docs/**"), effective(), "trimmed, de-duplicated, order kept")
            assertEquals(listOf("*.lock", "docs/**"), forSync(), "the stored list is what the job reads")
            assertEquals(HttpStatusCode.NoContent, put(null).status)
            assertEquals(listOf("*.lock", "docs/**"), effective(), "omitted on update keeps the stored list")
            assertEquals(HttpStatusCode.NoContent, put(emptyList()).status)
            assertEquals(emptyList(), effective(), "an empty list excludes nothing and is not the defaults")
            assertEquals(emptyList(), forSync(), "and the job reads it as empty, not as the defaults")
            assertEquals(HttpStatusCode.NoContent, put(null).status)
            assertEquals(emptyList(), effective())

            val withList = admin.create(gitlab = gitlab(pathExclusions = listOf("Case.LOCK")))
            assertEquals(listOf("Case.LOCK"), withList.gitlab!!.pathExclusions, "case-sensitive, never lowercased")
        }

    @Test
    fun `an invalid path exclusion list is a 400 on create and update`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glpathbad", FlowUserRole.ADMIN)
        val created = admin.create()
        suspend fun refused(patterns: List<String>): ProblemDetail = admin.problem(
            admin.postJson("/api/v1/data-sources", request(unique("bad"), gitlab(pathExclusions = patterns))), HttpStatusCode.BadRequest,
        )

        assertTrue("at most 100" in refused(List(101) { "p$it.lock" }).detail!!)
        assertTrue("Invalid path exclusion: [" in refused(listOf("[")).detail!!)
        assertTrue("1-255" in refused(listOf("   ")).detail!!)
        assertTrue("1-255" in refused(listOf("x".repeat(256))).detail!!)
        refused(listOf("bad\u0007pattern"))
        refused(listOf("a//b"))
        // Exactly the bounds are fine, and the same rules guard an update.
        assertEquals(100, admin.create(gitlab = gitlab(pathExclusions = List(100) { "p$it.lock" })).gitlab!!.pathExclusions.size)
        val update = admin.putJson(
            "/api/v1/data-sources/${created.id}",
            request(created.name, gitlab(token = null, groups = created.gitlab!!.groupPaths, pathExclusions = listOf("["))),
        )
        assertTrue("Invalid path exclusion" in admin.problem(update, HttpStatusCode.BadRequest).detail!!)
    }

    @Test
    fun `GET kinds lists both kinds for an ADMIN and is 403 for a USER`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glkinds", FlowUserRole.ADMIN)
        val user = seededClient("glkinds-user", FlowUserRole.USER)

        val kinds = admin.get("/api/v1/data-sources/kinds").body<DataSourceKindsResponse>()
        assertEquals(listOf(DataSourceKind.JIRA_CLOUD, DataSourceKind.GITLAB), kinds.items.map { it.kind })
        val jira = kinds.items.first()
        assertTrue(jira.enabled && jira.allowedOrigins.isEmpty())
        val gitlab = kinds.items.last()
        assertTrue(gitlab.enabled)
        assertEquals(listOf(GitLabStubServer.baseUrl), gitlab.allowedOrigins, "the development stub's origin")
        assertEquals(DEFAULT_PATH_EXCLUSIONS, gitlab.defaultPathExclusions)
        assertEquals(14, gitlab.defaultPathExclusions.size)
        assertEquals(emptyList(), jira.defaultPathExclusions)

        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/data-sources/kinds").status)
    }

    @Test
    fun `with GitLab off a create is 400 gitlab-disabled, kinds says so, an existing row is still listed and its test is 409`() =
        testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("gloff", FlowUserRole.ADMIN)

        val refused = admin.problem(
            admin.postJson("/api/v1/data-sources", request(unique("off"), gitlab("https://gitlab.example.com"))), HttpStatusCode.BadRequest,
        )
        assertTrue("gitlab-disabled" in refused.detail!!, refused.detail)
        val adHoc = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(gitlab = gitlab("https://gitlab.example.com")))
        assertTrue("gitlab-disabled" in admin.problem(adHoc, HttpStatusCode.BadRequest).detail!!)

        val kinds = admin.get("/api/v1/data-sources/kinds").body<DataSourceKindsResponse>()
        val gitlab = kinds.items.single { it.kind == DataSourceKind.GITLAB }
        assertFalse(gitlab.enabled)
        assertTrue(gitlab.allowedOrigins.isEmpty())
        assertTrue(kinds.items.single { it.kind == DataSourceKind.JIRA_CLOUD }.enabled)

        val rowId = runBlocking { GitLabRowFixture.insert("gl-off") }
        try {
            val read = admin.get("/api/v1/data-sources/$rowId")
            assertEquals(HttpStatusCode.OK, read.status, "list/read/delete keep working with GitLab off")
            assertEquals(DataSourceKind.GITLAB, read.body<DataSourceResponse>().kind)
            val test = admin.post("/api/v1/data-sources/$rowId/test")
            assertEquals(DATA_SOURCE_KIND_UNSUPPORTED, admin.problem(test, HttpStatusCode.Conflict).type)
        } finally {
            GitLabRowFixture.remove(rowId)
        }
    }

    @Test
    fun `ad-hoc and stored Test connection run the GitLab probes against the stub and audit kind and host, never the token`() =
        testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("gltest", FlowUserRole.ADMIN)
        val token = "glpat-adhoc-${UUID.randomUUID()}"
        withAuditCapture { capture ->
            val adHoc = admin.postJson(
                "/api/v1/data-sources/test",
                DataSourceTestRequest(gitlab = gitlab(token = token, groups = listOf("platform", "apps"))),
            )
            assertEquals(HttpStatusCode.OK, adHoc.status)
            val result = adHoc.body<ConnectionTestResult>()
            assertTrue(result.rows.all { it.ok }, "every probe of the stub world passes: ${result.rows.filterNot { it.ok }}")
            assertEquals(listOf("version", "user", "user_admin", "token_scopes"), result.rows.take(4).map { it.name })
            assertNull(result.cloudId)
            assertFalse(token in adHoc.bodyAsText())
            val event = assertNotNull(capture.awaitEvent { it.message == "data_source.tested" && it.hasKeyValue("kind", "GITLAB") })
            assertTrue(event.hasKeyValue("ok", true) && event.hasKeyValue("host", "localhost"))
            assertTrue(event.keyValuePairs.none { token in it.value?.toString().orEmpty() })
        }

        val created = admin.create(gitlab = gitlab(groups = listOf("platform", "apps")))
        val stored = admin.post("/api/v1/data-sources/${created.id}/test")
        assertEquals(HttpStatusCode.OK, stored.status)
        assertTrue(stored.body<ConnectionTestResult>().rows.all { it.ok })
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/data-sources/987654321/test").status)
    }

    @Test
    fun `a GitLab source can be synced on demand and its status carries the GitLab counts, never Jira ones`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glstatus", FlowUserRole.ADMIN)
        val created = admin.create()
        try {
            val store = GitLabFixtures.rawStore()
            runBlocking {
                store.upsertProjects(created.id, listOf(rawProject(1), rawProject(2)), now = 1_000)
                store.upsertUsers(created.id, listOf(rawUser(1), rawUser(2), rawUser(3)), now = 1_000)
                store.markProjectsMovedOutNotSeenSince(created.id, passStartedAt = 2_000, now = 3_000)
                store.upsertProjects(created.id, listOf(rawProject(1)), now = 4_000)
                // Four commits: 1 pending, 2 a merge, 3 tombstoned, 4 read (its files stored) - live 3, merges 1, deleted 1, pending 1.
                store.upsertCommits(created.id, 1, (1..4).map { rawCommit(it, parents = if (it == 2) 2 else 1) }, now = 5_000)
                store.tombstoneCommits(created.id, 1, listOf(commitSha(3)), now = 6_000)
                val files = CommitFilesResult(listOf(RawGitLabCommitFile("a.go", null, 1, 0, false, false, false, false)), false)
                store.flushCommitFiles(created.id, listOf(CommitFilesOutcome(1, commitSha(4), files, null)), now = 7_000)
                // Five merge requests: 1 merged and read with stats and two events, 2 opened and read with a GraphQL gap, 3 never read,
                // 4 stamped FETCH_FAILED (live, unread) and 5 tombstoned by a reconcile.
                store.upsertMergeRequests(
                    created.id, listOf(rawMr(1, "merged"), rawMr(2, "opened"), rawMr(3, "opened"), rawMr(4, "opened"), rawMr(5, "opened")),
                    now = 8_000,
                )
                store.tombstoneMergeRequests(created.id, listOf(1_005L), now = 8_500)
                val events = listOf(
                    RawGitLabMrEvent(MrEventSource.NOTE, 11, 5, 9_000, system = true, noteType = null, action = "MERGED"),
                    RawGitLabMrEvent(MrEventSource.APPROVAL, 5, 5, null, system = false, noteType = null, action = "APPROVAL"),
                )
                store.settleMrDetails(created.id, 1_001, events, now = 20_000, MrDetailsOutcome(stats = GitLabDiffStats(7, 3, 2)))
                store.settleMrDetails(created.id, 1_002, emptyList(), now = 20_000, MrDetailsOutcome())
                store.settleMrDetails(created.id, 1_004, emptyList(), now = 20_000, MrDetailsOutcome(error = DETAILS_ERROR_FETCH_FAILED))
            }
            val status = admin.get("/api/v1/data-sources/${created.id}/status")
            assertEquals(HttpStatusCode.OK, status.status)
            val body = status.body<SyncStatusResponse>()
            assertEquals(DataSourceKind.GITLAB, body.connection.kind)
            assertNotNull(body.connection.gitlab)
            val counts = assertNotNull(body.counts.gitlab)
            assertEquals(1L, counts.projects)
            assertEquals(1L, counts.projectsMovedOut)
            assertEquals(0L, counts.projectsDeleted)
            assertEquals(3L, counts.users)
            assertEquals(0L, counts.usersDeleted)
            assertEquals(3L, counts.commits, "live commits, merges included")
            assertEquals(1L, counts.commitsMerge)
            assertEquals(1L, counts.commitsDeleted)
            assertEquals(1L, counts.commitFilesPending, "the backlog excludes the merge, the tombstone and the read commit")
            assertEquals(4L, counts.mergeRequests, "the tombstoned one is not live")
            assertEquals(1L, counts.mergeRequestsMerged)
            assertEquals(2L, counts.mrEvents, "the notes, approvals and reviewer states stored")
            assertEquals(1L, counts.mrDetailsPending, "only the merge request never read")
            assertEquals(1L, counts.mrDiffStatsMissing, "the read merge request whose stats are a gap")
            assertEquals(1L, counts.mergeRequestsDeleted, "marked deleted by a reconcile")
            assertEquals(1L, counts.mrDetailsUnread, "the live merge request whose details read failed")
            assertEquals(0L, body.counts.rawIssues)

            val queued = admin.postJson("/api/v1/data-sources/${created.id}/sync-jobs", buildJsonObject { put("kind",
                JsonPrimitive("SYNC")) })
            assertEquals(HttpStatusCode.Accepted, queued.status, "the job API is kind-agnostic")
        } finally {
            runBlocking { GitLabFixtures.purgeAll(created.id) }
        }
    }

    @Test
    fun `create, update and scope rejection audit kind and host and never the token`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("glaudit", FlowUserRole.ADMIN)
        val token = "glpat-audit-${UUID.randomUUID()}"
        val shared = group()
        withAuditCapture { capture ->
            val created = admin.create(gitlab = gitlab(token = token, groups = listOf(shared)))
            val createdEvent = assertNotNull(capture.awaitEvent { it.message == "data_source.created" &&
                it.hasKeyValue("dataSourceId", created.id.toLong()) })
            assertTrue(createdEvent.hasKeyValue("kind", "GITLAB"))
            assertTrue(createdEvent.hasKeyValue("host", "localhost"))
            assertTrue(createdEvent.keyValuePairs.none { token in it.value?.toString().orEmpty() } &&
                token !in createdEvent.formattedMessage)

            val clash = admin.postJson("/api/v1/data-sources", request(unique("clash"), gitlab(token = token, groups = listOf(shared))))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            val rejected = assertNotNull(capture.awaitEvent { it.message == "data_source.scope_rejected" &&
                it.hasKeyValue("dataSourceId", null) })
            assertTrue(rejected.hasKeyValue("kind", "GITLAB") && rejected.hasKeyValue("reason", "GROUP_OVERLAP"))
            assertTrue(rejected.hasKeyValue("groupPaths", listOf(shared)) && rejected.hasKeyValue("projectKeys", emptyList<String>()))
            assertTrue(rejected.keyValuePairs.none { token in it.value?.toString().orEmpty() })
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            GitLabStubServer.start()
        }
    }
}

private fun rawProject(id: Long) = ch.nokillswit.gitlab.RawGitLabProject(
    id, "g/p$id", "g", 1, "main", false,
    Json.parseToJsonElement("""{"id":$id,"path_with_namespace":"g/p$id","namespace":{"id":1,"full_path":"g"}}"""),
)

private fun rawUser(id: Long) = ch.nokillswit.gitlab.RawGitLabUser(
    id, "u$id", "User $id", "active", false, emptyList(), Json.parseToJsonElement("""{"id":$id,"username":"u$id"}"""),
)

private fun commitSha(n: Int) = n.toString(16).padStart(40, '0')

private fun rawCommit(n: Int, parents: Int) = RawGitLabCommit(
    commitSha(n), "Commit $n", "Author", "a@x.test", n * 1_000L - 10, n * 1_000L, parents, additions = 1, deletions = 1,
)

private fun rawMr(n: Long, state: String) = RawGitLabMergeRequest(
    mrId = 1_000 + n, projectId = 1, iid = n, state = state, draft = false, authorId = 5, mergeUserId = null, createdAt = 100,
    updatedAt = 200 + n, mergedAt = null, closedAt = null, sourceBranch = "feature/$n", targetBranch = "main", title = "Merge request $n",
    headSha = null, mergeCommitSha = null, squashCommitSha = null, reviewerIds = emptyList(), assigneeIds = emptyList(), userNotesCount = 0,
    payload = buildJsonObject { put("id", 1_000 + n) },
)
