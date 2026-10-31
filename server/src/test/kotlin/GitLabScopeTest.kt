package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.GitLabConnectionRequest
import ch.nokillswit.ingest.GitLabScopeCandidate
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.ScopeConflictReason
import ch.nokillswit.ingest.findGitLabScopeConflict
import ch.nokillswit.ingest.groupPathsOverlap
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The GitLab scope rules (`ingest/GitLabScope.kt`, `.claude/docs/ingestion.md` "Data sources"): every active GitLab data source reads
 * ONE instance, and no group path equals, sits under or contains another source's, so a project is never counted twice; Jira and GitLab
 * sources never conflict. The pure matrix first, then the same rules over HTTP (the `DataSourceScopeTest` shape): where the lowest-id
 * conflicting row is not necessarily ours (another INSTANCE conflicts with every GitLab row), assertions follow the `instance` the
 * problem names.
 */
class GitLabScopeTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"
    private fun group() = "grp-${UUID.randomUUID().toString().take(8)}"

    // ------------------------------------------------------------------ the pure rule

    private fun candidate(id: UInt, vararg paths: String, baseUrl: String = "https://gitlab.example.com") =
        GitLabScopeCandidate(id, "source-$id", baseUrl, paths.toList())

    @Test
    fun `two group paths overlap when equal or when one sits under the other, never on a shared prefix alone`() {
        assertTrue(groupPathsOverlap("a", "a"))
        assertTrue(groupPathsOverlap("a/b", "a"))
        assertTrue(groupPathsOverlap("a", "a/b"))
        assertTrue(groupPathsOverlap("a/b/c", "a"))
        assertFalse(groupPathsOverlap("ab", "a"))
        assertFalse(groupPathsOverlap("a", "ab"))
        assertFalse(groupPathsOverlap("a/b", "a/bc"))
        assertFalse(groupPathsOverlap("a/b", "c/b"))
    }

    @Test
    fun `the pure matrix - instance before overlap, lowest id first, updates never check the instance`() {
        val base = "https://gitlab.example.com"
        val others = listOf(candidate(7u, "x/two", baseUrl = base), candidate(3u, "x/one", baseUrl = base))

        // No conflict: another group of the same instance.
        assertNull(findGitLabScopeConflict(others, base, listOf("y"), checkInstance = true))
        assertNull(findGitLabScopeConflict(emptyList(), base, listOf("y"), checkInstance = true))

        // Equal, requested under, requested above: the lowest-id source is named, with the requested paths that clash.
        for ((requested, shared) in listOf(
            listOf("x/one") to listOf("x/one"),
            listOf("x/one/deep") to listOf("x/one/deep"),
            listOf("x") to listOf("x"),
        )) {
            val conflict = assertNotNull(findGitLabScopeConflict(others, base, listOf("free") + requested, checkInstance = true))
            assertEquals(ScopeConflictReason.GROUP_OVERLAP, conflict.reason)
            assertEquals(shared, conflict.keys)
            assertEquals("gitlab.example.com", conflict.host)
        }
        assertEquals(3u, findGitLabScopeConflict(others, base, listOf("x"), checkInstance = true)!!.conflictingId, "lowest id first")

        // Another instance wins over an overlap, but only on a create.
        val elsewhere = findGitLabScopeConflict(others, "https://other.example.com", listOf("x/one"), checkInstance = true)
        assertEquals(ScopeConflictReason.OTHER_INSTANCE, elsewhere!!.reason)
        assertEquals(emptyList(), elsewhere.keys)
        assertEquals("other.example.com", elsewhere.host)
        assertEquals(ScopeConflictReason.GROUP_OVERLAP, findGitLabScopeConflict(others, "https://other.example.com", listOf("x/one"),
            checkInstance = false)!!.reason)

        assertEquals(DataSourceKind.GITLAB, ScopeConflictReason.GROUP_OVERLAP.kind)
        assertEquals(DataSourceKind.GITLAB, ScopeConflictReason.OTHER_INSTANCE.kind)
        assertEquals(DataSourceKind.JIRA_CLOUD, ScopeConflictReason.OTHER_SITE.kind)
        assertEquals("urn:flow:problem:data-source-other-instance", ScopeConflictReason.OTHER_INSTANCE.problemType)
        assertEquals("urn:flow:problem:data-source-group-overlap", ScopeConflictReason.GROUP_OVERLAP.problemType)
    }

    // ------------------------------------------------------------------ over HTTP

    private fun request(name: String, vararg paths: String, baseUrl: String = "https://gitlab.example.com") = DataSourceRequest(
        name, true, 60, "2025-01-01", 3, gitlab = GitLabConnectionRequest(baseUrl, "glpat-${UUID.randomUUID()}", paths.toList()),
    )

    private suspend fun HttpClient.createSource(vararg paths: String, name: String = unique("glscope")): DataSourceResponse {
        val response = postJson("/api/v1/data-sources", request(name, *paths))
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpResponse.assertScopeConflict(type: String): ProblemDetail {
        assertEquals(HttpStatusCode.Conflict, status)
        val problem = body<ProblemDetail>()
        assertEquals(type, problem.type)
        return problem
    }

    private val hosts = arrayOf("gitlab.allowedHosts" to "gitlab.example.com,git.example.com")

    @Test
    fun `a GitLab source on another instance is 409 with the other-instance problem type and the conflicting source as instance`() =
        testApplication {
            configureApp(*hosts)
            startApplication()
            val admin = seededClient("glscope-inst", FlowUserRole.ADMIN)
            val first = admin.createSource(group())
            try {
                val response = admin.postJson("/api/v1/data-sources", request(unique("glscope-other"), group(),
                    baseUrl = "https://git.example.com"))
                val problem = response.assertScopeConflict("urn:flow:problem:data-source-other-instance")
                val instance = assertNotNull(problem.instance)
                assertTrue(Regex("^/api/v1/data-sources/\\d+$").matches(instance), "instance names the conflicting source: $instance")
                assertTrue("git.example.com" !in problem.detail!! || "gitlab.example.com" in problem.detail!!, problem.detail)
                assertEquals(HttpStatusCode.OK, admin.get(instance).status)
            } finally {
                admin.delete("/api/v1/data-sources/${first.id}")
            }
        }

    @Test
    fun `an equal or nested group path of another active source is 409 in both directions, naming the source and the clashing paths`() =
        testApplication {
            configureApp(*hosts)
            startApplication()
            val admin = seededClient("glscope-overlap", FlowUserRole.ADMIN)
            val root = group()
            val a = admin.createSource("$root/team", group())
            try {
                // Equal.
                val equal = admin.postJson("/api/v1/data-sources", request(unique("eq"), "$root/team"))
                    .assertScopeConflict("urn:flow:problem:data-source-group-overlap")
                assertEquals("/api/v1/data-sources/${a.id}", equal.instance)
                assertTrue(a.name in equal.detail!! && "$root/team" in equal.detail!!, equal.detail)
                // Under the other's path, and above it.
                admin.postJson("/api/v1/data-sources", request(unique("under"), "$root/team/sub/deep"))
                    .assertScopeConflict("urn:flow:problem:data-source-group-overlap")
                admin.postJson("/api/v1/data-sources", request(unique("above"), root))
                    .assertScopeConflict("urn:flow:problem:data-source-group-overlap")
                // A sibling that merely shares a prefix is fine, and the rejected requests left nothing behind.
                val sibling = admin.createSource("$root/team2")
                admin.delete("/api/v1/data-sources/${sibling.id}")
            } finally {
                admin.delete("/api/v1/data-sources/${a.id}")
            }
        }

    @Test
    fun `an update taking a path of another source is 409, a self-update with its own paths is 204, a deleted source frees its paths`() =
        testApplication {
            configureApp(*hosts)
            startApplication()
            val admin = seededClient("glscope-update", FlowUserRole.ADMIN)
            val taken = group()
            val a = admin.createSource(taken)
            val b = admin.createSource(group())
            try {
                val clash = admin.putJson(
                    "/api/v1/data-sources/${b.id}",
                    DataSourceRequest(b.name, true, 60, "2025-01-01", 3, gitlab = GitLabConnectionRequest("https://gitlab.example.com",
                        null, listOf("$taken/child"))),
                )
                assertEquals("/api/v1/data-sources/${a.id}",
                    clash.assertScopeConflict("urn:flow:problem:data-source-group-overlap").instance)
                val own = admin.putJson(
                    "/api/v1/data-sources/${b.id}",
                    DataSourceRequest(b.name, true, 60, "2025-01-01", 3, gitlab = GitLabConnectionRequest("https://gitlab.example.com",
                        null, b.gitlab!!.groupPaths)),
                )
                assertEquals(HttpStatusCode.NoContent, own.status, "a source never conflicts with itself")

                assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${a.id}").status)
                val reuse = admin.createSource(taken)
                admin.delete("/api/v1/data-sources/${reuse.id}")
            } finally {
                admin.delete("/api/v1/data-sources/${b.id}")
            }
        }

    @Test
    fun `Jira and GitLab sources never conflict with each other`() = testApplication {
        configureApp(*hosts)
        startApplication()
        val admin = seededClient("glscope-mixed", FlowUserRole.ADMIN)
        val gitlab = admin.createSource(group())
        val jiraKeys = uniqueProjectKeys(1)
        val jira = admin.postJson(
            "/api/v1/data-sources",
            DataSourceRequest(
                unique("jira-beside-gitlab"), true, 60, "2025-01-01", 3,
                jira = JiraConnectionRequest(TEST_JIRA_SITE_URL, "svc@example.com", "tok-${UUID.randomUUID()}", jiraKeys,
                    JiraAuthScheme.BASIC),
            ),
        )
        try {
            assertEquals(HttpStatusCode.Created, jira.status, jira.bodyAsText())
            val second = admin.createSource(group())
            admin.delete("/api/v1/data-sources/${second.id}")
        } finally {
            if (jira.status == HttpStatusCode.Created) admin.delete("/api/v1/data-sources/${jira.body<DataSourceResponse>().id}")
            admin.delete("/api/v1/data-sources/${gitlab.id}")
        }
    }
}
