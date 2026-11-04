package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.FetchSource
import ch.nokillswit.fetch.OutboundPermits
import ch.nokillswit.gitlab.MR_DETAILS_BATCH
import ch.nokillswit.gitlab.MR_DIFF_STATS_QUERY
import ch.nokillswit.gitlab.MR_KEYS
import ch.nokillswit.gitlab.GITLAB_AUTH_HEADER
import ch.nokillswit.gitlab.GitLabRateLimitSignal
import ch.nokillswit.gitlab.GitLabSettings
import ch.nokillswit.gitlab.HttpGitLabClient
import ch.nokillswit.gitlab.createGitLabHttp
import ch.nokillswit.gitlab.summarizeMergeRequest
import ch.nokillswit.gitlab.tooBroad
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.outbound.SelfManagedHosts
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://gitlab.example.test"
private const val CLOCK_NOW = 1_800_000_000_000L

/** `HttpGitLabClient` over the shared `SourceHttp` against a scripted `MockEngine` - no network, no WireMock. */
class GitLabClientTest {
    private val settings = GitLabSettings(SelfManagedHosts.NONE, null, null, 30, 8L * 1024 * 1024, 1, 180, 24, 5_000, 3_000)

    private class Wire(val sleeps: MutableList<Long> = mutableListOf())

    private fun client(engine: MockEngine, wire: Wire = Wire(), base: String = BASE, maxRetries: Int = 0): HttpGitLabClient {
        val http = createGitLabHttp(
            HttpClient(engine) {
                expectSuccess = false
                followRedirects = false
            },
            GitLabSettings(settings.hosts, null, null, 30, settings.maxResponseBytes, maxRetries, 180, 24, 5_000, 3_000),
            OutboundPermits(4),
            source = FetchSource("GitLab", "gitlab.maxResponseBytes", GitLabRateLimitSignal { CLOCK_NOW }, GITLAB_AUTH_HEADER),
            random = { 0.5 },
            sleeper = { wire.sleeps += it },
            clock = { CLOCK_NOW },
        )
        return HttpGitLabClient(http, base, "glpat-secret")
    }

    @Test
    fun `PRIVATE-TOKEN carries the token and Authorization is not sent`() = runBlocking {
        var privateToken: String? = null
        var authorization: String? = "unset"
        val engine = MockEngine { request ->
            privateToken = request.headers["PRIVATE-TOKEN"]
            authorization = request.headers[HttpHeaders.Authorization]
            respond("""{"version":"19.3.1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals("19.3.1", client(engine).version())
        assertEquals("glpat-secret", privateToken)
        assertNull(authorization)
    }

    @Test
    fun `X-Next-Page drives paging and a Link header to another host is never requested`() = runBlocking {
        val hosts = mutableListOf<String>()
        val pages = mutableListOf<String?>()
        val engine = MockEngine { request ->
            hosts += request.url.host
            val page = request.url.parameters["page"]
            pages += page
            val next = if (page == "1") "2" else ""
            respond(
                if (page == "1") """[{"id":1}]""" else """[{"id":2}]""",
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "X-Next-Page" to listOf(next),
                    "X-Total" to listOf("2"),
                    HttpHeaders.Link to listOf("<https://evil.example.test/api/v4/users?page=2>; rel=\"next\""),
                ),
            )
        }
        val client = client(engine)
        val first = client.users(1)
        assertEquals(2, first.nextPage)
        assertEquals(2L, first.total)
        val second = client.users(first.nextPage!!)
        assertNull(second.nextPage, "an empty X-Next-Page is the end")
        assertEquals(listOf<String?>("1", "2"), pages)
        assertEquals(setOf("gitlab.example.test"), hosts.toSet(), "the Link header was never followed")
    }

    @Test
    fun `an absent X-Next-Page header is INVALID_RESPONSE, never the last page`() = runBlocking {
        val engine = MockEngine { respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).users(1) }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).groupProjects(7, 1) }.code)
    }

    @Test
    fun `a token-info answer missing scopes or active is INVALID_RESPONSE`() = runBlocking {
        for (body in listOf("{}", """{"scopes":["read_api"]}""", """{"active":true}""")) {
            val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).tokenSelf() }.code, body)
        }
    }

    @Test
    fun `users come back slimmed to small values and the emails answer is reduced to its address strings`() = runBlocking {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/emails")) {
                """[{"id":1,"email":"S@x.test","confirmed_at":"2026-01-01"},{"id":2,"email":"t@x.test"}]"""
            } else {
                """[{"id":4,"username":"u4","name":"U Four","state":"active","bot":false,"email":"P@x.test","bio":"private text"}]"""
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("")))
        }
        val client = client(engine)
        val user = client.users(1).users.single()
        assertEquals(4L, user.id)
        assertEquals(listOf("P@x.test"), user.ownEmails)
        assertNull(user.slim["bio"], "the slim value carries no unlisted member")
        assertEquals(listOf("S@x.test", "t@x.test"), client.userEmails(4))
    }

    @Test
    fun `a page that does not advance, or is not a number, is INVALID_RESPONSE`() = runBlocking {
        for (next in listOf("1", "0", "abc")) {
            val engine = MockEngine {
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"),
                    "X-Next-Page" to listOf(next)))
            }
            val failure = assertFailsWith<FetchException> { client(engine).users(1) }
            assertEquals("INVALID_RESPONSE", failure.code, "X-Next-Page=$next")
        }
    }

    @Test
    fun `a group path travels as one percent-encoded segment and the list calls carry the scope parameters`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val engine = MockEngine { request ->
            seen += request.url
            respond(
                if (request.url.encodedPath.endsWith("/projects")) "[]" else """{"id":8,"full_path":"platform/tooling"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("")),
            )
        }
        val client = client(engine)
        val group = client.group("platform/tooling")
        assertEquals(8L, group.id)
        client.groupProjects(group.id, 3)
        assertEquals("/api/v4/groups/platform%2Ftooling", seen[0].encodedPath, "Ktor must keep %2F as one segment")
        assertEquals("/api/v4/groups/8/projects", seen[1].encodedPath)
        assertEquals(
            mapOf("include_subgroups" to "true", "archived" to "false", "with_shared" to "false", "per_page" to "100", "page" to "3"),
            seen[1].parameters.entries().associate { it.key to it.value.single() },
        )
    }

    @Test
    fun `a malformed group path or id is refused before any request is made`() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = client(engine)
        for (path in listOf("../etc", "a b", "a//b", "a/../b", "")) {
            assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.group(path) }.code, "path <$path>")
        }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.groupProjects(-1, 1) }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.userEmails(-5) }.code)
        assertEquals(0, requests)
    }

    @Test
    fun `a body of the wrong shape is INVALID_RESPONSE, never an uncaught exception`() = runBlocking {
        val engine = MockEngine {
            respond("""["not","an","object"]""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = client(engine)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.currentUser() }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.tokenSelf() }.code)
        val objectEngine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(objectEngine).users(1) }.code)
    }

    private suspend fun sleepAfter(headers: Array<Pair<String, String>>): List<Long> {
        val wire = Wire()
        val engine = MockEngine {
            respond(
                """{"version":"1"}""", HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    *headers.map { it.first to listOf(it.second) }.toTypedArray(),
                ),
            )
        }
        client(engine, wire).version()
        return wire.sleeps
    }

    @Test
    fun `RateLimit-Remaining near zero pauses until the reset, clamped to 1-5 seconds, 2 s when the reset is unknown`() = runBlocking {
        val nowSeconds = CLOCK_NOW / 1_000
        assertEquals(listOf(5_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3", "RateLimit-Reset" to "${nowSeconds + 60}")))
        assertEquals(listOf(2_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3", "RateLimit-Reset" to "${nowSeconds + 2}")))
        assertEquals(listOf(1_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "0", "RateLimit-Reset" to "${nowSeconds - 30}")),
            "a past reset still waits 1 s")
        assertEquals(listOf(2_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3")))
        assertEquals(listOf(2_000L), sleepAfter(arrayOf("RateLimit-Remaining" to "3", "RateLimit-Reset" to "soon")))
        assertEquals(emptyList(), sleepAfter(arrayOf("RateLimit-Remaining" to "100", "RateLimit-Reset" to "${nowSeconds + 60}")))
        assertEquals(emptyList(), sleepAfter(emptyArray()), "no headers, no pause")
    }

    @Test
    fun `a 429 pauses only the tenant that sent it - the pause key is the base URL`() = runBlocking {
        val wire = Wire()
        val engine = MockEngine { request ->
            if (request.url.host == "a.example.test" && request.url.encodedPath.endsWith("/version") && wire.sleeps.isEmpty()) {
                respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7"))
            } else {
                respond("""{"version":"1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val http = createGitLabHttp(
            HttpClient(engine) { expectSuccess = false },
            GitLabSettings(settings.hosts, null, null, 30, settings.maxResponseBytes, 0, 180, 24, 5_000, 3_000),
            OutboundPermits(4),
            random = { 0.5 },
            sleeper = { wire.sleeps += it },
            clock = { CLOCK_NOW },
        )
        val tenantA = HttpGitLabClient(http, "https://a.example.test", "tok-a")
        val tenantB = HttpGitLabClient(http, "https://b.example.test", "tok-b")

        assertEquals("RATE_LIMITED", assertFailsWith<FetchException> { tenantA.version() }.code)
        assertEquals("1", tenantB.version())
        assertTrue(wire.sleeps.isEmpty(), "the other tenant did not wait: ${wire.sleeps}")
        assertEquals("1", tenantA.version())
        assertEquals(listOf(7_000L), wire.sleeps, "the 429's tenant waited the Retry-After out before its next call")
    }

    @Test
    fun `scopes that could write are too broad - api, sudo and write_ scopes`() {
        assertTrue(listOf("read_api", "api").tooBroad())
        assertTrue(listOf("sudo").tooBroad())
        assertTrue(listOf("read_api", "write_repository").tooBroad())
        for (scope in listOf("create_runner", "manage_runner", "k8s_proxy", "self_rotate", "ai_features", "unknown_future_scope")) {
            assertTrue(listOf("read_api", scope).tooBroad(), "$scope is outside the read_ allow-list")
        }
        assertFalse(listOf("read_api").tooBroad())
        assertFalse(listOf("read_api", "read_user", "read_repository").tooBroad())
        assertFalse(emptyList<String>().tooBroad())
        // An Admin-Mode instance needs admin_mode for an admin token to read emails; beside read_* it stays read-only.
        assertFalse(listOf("read_api", "admin_mode").tooBroad())
        assertTrue(listOf("api", "admin_mode").tooBroad())
        assertTrue(listOf("read_api", "ADMIN_MODE").tooBroad())
    }

    // --- commits, merge base and diffs (V42, PR 7) ----------------------------------------------------------------------------

    private val sha1 = "a".repeat(40)
    private val sha2 = "b".repeat(40)
    private val json = headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf(""))

    private fun commitJson(sha: String, parents: List<String>, extra: String = "") =
        """{"id":"$sha","title":"Fix the thing","message":"Fix the thing\n\nlong body","author_name":"Ada","author_email":"ada@x.test",
           "authored_date":"2026-08-31T13:27:54.000+02:00","committed_date":"2026-08-31T11:30:00Z","committer_name":"Bob",
           "parent_ids":[${parents.joinToString(",") { "\"$it\"" }}]$extra}"""

    @Test
    fun `commits sends ref_name, with_stats, per_page and page, and since only when given`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val withStats = commitJson(sha1, listOf(sha2), ""","stats":{"additions":5,"deletions":2,"total":7}""")
        val engine = MockEngine { request ->
            seen += request.url
            respond(
                "[${withStats},${commitJson(sha2, listOf(sha1, sha1))}]",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("3"), "X-Total" to listOf("250")),
            )
        }
        val client = client(engine)
        val page = client.commits(7, "main", null, 2)
        assertEquals("/api/v4/projects/7/repository/commits", seen[0].encodedPath)
        assertEquals(
            mapOf("ref_name" to "main", "with_stats" to "true", "per_page" to "100", "page" to "2"),
            seen[0].parameters.entries().associate { it.key to it.value.single() },
        )
        assertEquals(3, page.nextPage)
        assertEquals(250L, page.total)
        val (first, merge) = page.commits
        assertEquals(sha1, first.sha)
        assertEquals("Fix the thing", first.title)
        assertEquals(1, first.parentCount)
        assertEquals(5, first.additions)
        assertEquals(2, first.deletions)
        assertEquals("Ada", first.authorName)
        assertEquals("ada@x.test", first.authorEmail)
        assertEquals(java.time.Instant.parse("2026-08-31T11:27:54Z").toEpochMilli(), first.authoredAt)
        assertEquals(java.time.Instant.parse("2026-08-31T11:30:00Z").toEpochMilli(), first.committedAt)
        assertEquals(2, merge.parentCount, "two parents: a merge")
        assertNull(merge.additions, "no stats, no lines")

        client.commits(7, "main", "2026-01-01T00:00:00Z", 1)
        assertEquals("2026-01-01T00:00:00Z", seen[1].parameters["since"])
    }

    @Test
    fun `a range is sent verbatim as the ref_name value`() = runBlocking {
        var query: String? = null
        val engine = MockEngine { request ->
            query = request.url.parameters["ref_name"]
            respond("[]", HttpStatusCode.OK, json)
        }
        client(engine).commits(7, "$sha1..main", null, 1)
        assertEquals("$sha1..main", query)
    }

    @Test
    fun `an unusable commit is INVALID_RESPONSE and a message stands in for a missing title`() = runBlocking {
        val bad = listOf(
            """[{"id":"$sha1","committed_date":"2026-08-31T11:30:00Z","authored_date":"2026-08-31T11:30:00Z"}]""",
            """[{"id":"xyz","parent_ids":[],"committed_date":"2026-08-31T11:30:00Z","authored_date":"2026-08-31T11:30:00Z"}]""",
            """[{"id":"$sha1","parent_ids":[],"committed_date":"yesterday","authored_date":"2026-08-31T11:30:00Z"}]""",
            """[{"id":"$sha1","parent_ids":[],"committed_date":"2026-08-31T11:30:00Z"}]""",
            """["text"]""",
        )
        for (body in bad) {
            val engine = MockEngine { respond(body, HttpStatusCode.OK, json) }
            assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).commits(7, "main", null, 1) }.code, body)
        }
        val engine = MockEngine {
            respond(
                """[{"id":"$sha1","message":"First line\nsecond","parent_ids":[],"committed_date":"2026-08-31T11:30:00.000+0200",
                    "authored_date":"2026-08-31T11:30:00Z"}]""",
                HttpStatusCode.OK, json,
            )
        }
        val commit = client(engine).commits(7, "main", null, 1).commits.single()
        assertEquals("First line", commit.title)
        assertEquals(0, commit.parentCount)
    }

    @Test
    fun `mergeBase sends refs as two parameters and returns the id, and a 400 or 404 is REF_NOT_FOUND`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val engine = MockEngine { request ->
            seen += request.url
            respond(commitJson(sha2, emptyList()), HttpStatusCode.OK, json)
        }
        assertEquals(sha2, client(engine).mergeBase(7, sha1, "main"))
        assertEquals("/api/v4/projects/7/repository/merge_base", seen.single().encodedPath)
        assertEquals(listOf(sha1, "main"), seen.single().parameters.getAll("refs[]"))

        for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.NotFound)) {
            val failing = MockEngine { respond("""{"message":"Could not find ref"}""", status, json) }
            assertEquals("REF_NOT_FOUND", assertFailsWith<FetchException> { client(failing).mergeBase(7, sha1, "main") }.code, "$status")
        }
        val noId = MockEngine { respond("""{"id":"short"}""", HttpStatusCode.OK, json) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(noId).mergeBase(7, sha1, "main") }.code)
    }

    @Test
    fun `commitDiff refuses a non-hex sha before any request`() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("[]", HttpStatusCode.OK, json)
        }
        val client = client(engine)
        for (bad in listOf("main", "../x", "ABCDEF" + "0".repeat(34), "a".repeat(39), "a".repeat(41), "")) {
            assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.commitDiff(7, bad, 1) }.code, "<$bad>")
        }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.commitDiff(-7, sha1, 1) }.code)
        assertEquals(0, requests)
        client.commitDiff(7, "c".repeat(64), 1) // SHA-256 repositories
        assertEquals(1, requests)
    }

    @Test
    fun `commitDiff counts a normal file, nulls the lines of a too_large file, keeps flags and reads X-Next-Page`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val engine = MockEngine { request ->
            seen += request.url
            respond(
                """[{"diff":"@@ -1,2 +1,3 @@\n ctx\n-old\n+new\n+more\n\\ No newline at end of file\n","new_path":"src/a.go","old_path":"src/a.go",
                    "new_file":false,"renamed_file":false,"deleted_file":false,"generated_file":false},
                   {"diff":"","new_path":"data/big.json","old_path":"data/big.json","too_large":true,"generated_file":true},
                   {"diff":"@@ -1 +1 @@\n-a\n+b\n","new_path":"docs/new.md","old_path":"docs/old.md","renamed_file":true},
                   {"diff":"","new_path":"docs/collapsed.md","old_path":"docs/collapsed.md","collapsed":true},
                   {"diff":"Binary files a/x.png and b/x.png differ\n","new_path":"x.png","old_path":"x.png","new_file":true},
                   {"diff":"@@ -1 +0,0 @@\n-gone\n","new_path":"gone.txt","old_path":"gone.txt","deleted_file":true}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("2")),
            )
        }
        val page = client(engine).commitDiff(7, sha1, 1)
        assertEquals("/api/v4/projects/7/repository/commits/$sha1/diff", seen.single().encodedPath)
        val query = seen.single().parameters.entries().associate { it.key to it.value.single() }
        assertEquals(mapOf("per_page" to "100", "page" to "1"), query)
        assertEquals(2, page.nextPage)
        val files = page.files.associateBy { it.path }
        assertEquals(2, files.getValue("src/a.go").additions)
        assertEquals(1, files.getValue("src/a.go").deletions)
        assertNull(files.getValue("src/a.go").oldPath, "old_path is kept for a rename only")
        assertNull(files.getValue("data/big.json").additions)
        assertNull(files.getValue("data/big.json").deletions)
        assertTrue(files.getValue("data/big.json").generatedFile)
        assertTrue(files.getValue("docs/new.md").renamedFile)
        assertEquals("docs/old.md", files.getValue("docs/new.md").oldPath)
        assertEquals(1, files.getValue("docs/new.md").additions)
        assertNull(files.getValue("docs/collapsed.md").additions)
        assertEquals(0, files.getValue("x.png").additions, "a binary file counts 0/0")
        assertEquals(0, files.getValue("x.png").deletions)
        assertTrue(files.getValue("x.png").newFile)
        assertTrue(files.getValue("gone.txt").deletedFile)
        assertEquals(1, files.getValue("gone.txt").deletions)
    }

    @Test
    fun `a diff entry without new_path is INVALID_RESPONSE`() = runBlocking {
        val engine = MockEngine { respond("""[{"diff":"@@ -1 +1 @@\n-a\n+b\n"}]""", HttpStatusCode.OK, json) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(engine).commitDiff(7, sha1, 1) }.code)
    }

    // --- merge requests, their notes, approvals, reviewers and the GraphQL diff stats (V43, PR 8) -------------------------------

    private val mrItem = """{"id":1041,"iid":4,"project_id":7,"title":"Add the thing","description":"SECRET DESCRIPTION","state":"merged",
        "created_at":"2026-08-01T08:00:00.000Z","updated_at":"2026-08-02T09:30:00.000+02:00","merged_at":"2026-08-02T07:00:00.000Z",
        "closed_at":null,"merged_by":{"id":5,"username":"m","name":"Mia","avatar_url":"https://x.test/a.png"},
        "merge_user":{"id":5,"username":"m","name":"Mia"},"closed_by":null,"author":{"id":3,"username":"a","name":"Ada"},
        "assignees":[{"id":9,"name":"Z"},{"id":3,"name":"Ada"}],"reviewers":[{"id":6,"name":"R"},{"id":5,"name":"Mia"},{"id":6,"name":"R"}],
        "labels":["customer-x","urgent"],"draft":true,"work_in_progress":false,"source_branch":"feature/x","target_branch":"main",
        "source_project_id":7,"target_project_id":7,"user_notes_count":3,"sha":"${"c".repeat(40)}","merge_commit_sha":"not-hex",
        "squash_commit_sha":null,"web_url":"https://gitlab.example.test/p/-/merge_requests/4","merge_status":"can_be_merged",
        "has_conflicts":false,"time_stats":{"time_estimate":0},"references":{"short":"!4"},"detailed_merge_status":"mergeable"}"""

    private fun seenQuery(url: io.ktor.http.Url) = url.parameters.entries().associate { it.key to it.value.single() }

    @Test
    fun `groupMergeRequests sends the fixed parameters and updated_after only when given, and keeps the slim values`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val engine = MockEngine { request ->
            seen += request.url
            respond(
                "[$mrItem]", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("3"), "X-Total" to listOf("250")),
            )
        }
        val client = client(engine)
        val page = client.groupMergeRequests(7, "2026-08-01T00:00:00Z", 2)
        client.groupMergeRequests(7, null, 1)
        assertEquals("/api/v4/groups/7/merge_requests", seen[0].encodedPath)
        val fixed = mapOf(
            "scope" to "all", "state" to "all", "include_subgroups" to "true", "order_by" to "updated_at", "sort" to "desc",
            "per_page" to "100",
        )
        assertEquals(fixed + mapOf("page" to "2", "updated_after" to "2026-08-01T00:00:00Z"), seenQuery(seen[0]))
        assertEquals(fixed + mapOf("page" to "1"), seenQuery(seen[1]))
        assertEquals(3, page.nextPage)
        assertEquals(250L, page.total)
        val mr = page.mergeRequests.single()
        assertEquals(listOf(1041L, 7L, 4L), listOf(mr.mrId, mr.projectId, mr.iid))
        assertEquals("merged", mr.state)
        assertEquals(true, mr.draft)
        assertEquals(3L, mr.authorId)
        assertEquals(5L, mr.mergeUserId)
        assertEquals(1_785_655_800_000L, mr.updatedAt, "the offset is honoured")
        assertEquals(1_785_654_000_000L, mr.mergedAt)
        assertNull(mr.closedAt)
        assertEquals("c".repeat(40), mr.headSha)
        assertNull(mr.mergeCommitSha, "a non-hex id is dropped")
        assertEquals(listOf(5L, 6L), mr.reviewerIds)
        assertEquals(listOf(3L, 9L), mr.assigneeIds)
        assertEquals(3, mr.userNotesCount)
        val payload = mr.payload as kotlinx.serialization.json.JsonObject
        assertTrue(payload.keys.all { it in MR_KEYS }, "only whitelisted keys: ${payload.keys}")
        for (key in listOf("description", "labels", "web_url", "merge_status", "has_conflicts", "time_stats", "references")) {
            assertFalse(key in payload, key)
        }
        assertEquals("""{"id":5}""", payload.getValue("merged_by").toString(), "a user reference is {id} only")
        assertEquals("""[{"id":3},{"id":9}]""", payload.getValue("assignees").toString())
        assertFalse("merge_commit_sha" in payload, "a non-hex id never enters the payload")
        assertFalse("closed_by" in payload, "a null member is dropped")
        assertFalse("Ada" in payload.toString() || "SECRET" in payload.toString() || "customer-x" in payload.toString())
    }

    @Test
    fun `two documents differing only in the order of the reviewers and assignees hash to the same payload`() {
        fun hash(item: String): String {
            return sha256Hex(canonicalJson(summarizeMergeRequest(Json.parseToJsonElement(item)).payload))
        }
        val reordered = mrItem
            .replace(
                """[{"id":6,"name":"R"},{"id":5,"name":"Mia"},{"id":6,"name":"R"}]""",
                """[{"id":5,"name":"Mia"},{"id":6,"name":"R"}]""",
            )
            .replace("""[{"id":9,"name":"Z"},{"id":3,"name":"Ada"}]""", """[{"id":3,"name":"Ada"},{"id":9,"name":"Z"}]""")
        assertTrue(reordered != mrItem)
        assertEquals(hash(mrItem), hash(reordered))
        // A field outside the whitelist moves nothing either.
        assertEquals(hash(mrItem), hash(mrItem.replace("\"has_conflicts\":false", "\"has_conflicts\":true")))
        val otherAssignee = mrItem.replace("\"id\":9,", "\"id\":10,")
        assertTrue(hash(mrItem) != hash(otherAssignee), "a real change of a reviewer or assignee does move it")
    }

    @Test
    fun `a merge request without an id, an iid, a state or a readable time is INVALID_RESPONSE`() = runBlocking {
        val broken = listOf(
            mrItem.replace("\"iid\":4,", ""),
            mrItem.replace("\"id\":1041,", ""),
            mrItem.replace("\"project_id\":7,", ""),
            mrItem.replace("\"iid\":4", "\"iid\":\"4\""),
            mrItem.replace("\"state\":\"merged\"", "\"state\":null"),
            mrItem.replace("\"updated_at\":\"2026-08-02T09:30:00.000+02:00\"", "\"updated_at\":\"soon\""),
            mrItem.replace("\"merged_at\":\"2026-08-02T07:00:00.000Z\"", "\"merged_at\":\"soon\""),
            mrItem.replace("{\"id\":6,\"name\":\"R\"}", "{\"name\":\"R\"}"),
            "\"not an object\"",
        )
        for (item in broken) {
            val engine = MockEngine { respond("[$item]", HttpStatusCode.OK, json) }
            val failure = assertFailsWith<FetchException>(item.take(80)) { client(engine).groupMergeRequests(7, null, 1) }
            assertEquals("INVALID_RESPONSE", failure.code)
        }
        val noNext = MockEngine { respond("[$mrItem]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(noNext).groupMergeRequests(7, null, 1) }.code)
    }

    @Test
    fun `mergeRequestNotes sends the sort parameters and returns action codes with no body anywhere`() = runBlocking {
        val seen = mutableListOf<io.ktor.http.Url>()
        val secret = "TOP-SECRET-NOTE-BODY"
        val engine = MockEngine { request ->
            seen += request.url
            respond(
                """[{"id":11,"body":"approved this merge request","system":true,"type":null,"created_at":"2026-08-31T11:27:54Z","author":{"id":3,"name":"Ada"}},
                    {"id":12,"body":"$secret","system":false,"type":"DiffNote","created_at":"2026-08-31T11:28:54Z","author":{"id":4}},
                    {"id":13,"body":"$secret approved this merge request","system":false,"type":null,"created_at":"2026-08-31T11:29:54Z"},
                    {"id":14,"body":"requested review from @alice and @bob","system":true,"created_at":"2026-08-31T11:30:54Z"}]""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Next-Page" to listOf("2")),
            )
        }
        val page = client(engine).mergeRequestNotes(7, 4, 1)
        assertEquals("/api/v4/projects/7/merge_requests/4/notes", seen.single().encodedPath)
        assertEquals(mapOf("sort" to "asc", "order_by" to "created_at", "per_page" to "100", "page" to "1"), seenQuery(seen.single()))
        assertEquals(2, page.nextPage)
        assertEquals(listOf("APPROVAL", "DIFF_COMMENT", "COMMENT", "REVIEW_REQUESTED"), page.notes.map { it.action })
        assertEquals(listOf("alice", "bob"), page.notes.last().targets)
        assertEquals(listOf(3L, 4L, null, null), page.notes.map { it.authorId })
        val everything = page.notes.joinToString { n ->
            listOf(n.id, n.authorId, n.createdAt, n.system, n.noteType, n.action, n.targets).joinToString("|")
        }
        assertFalse(secret in everything, "no note body survives the transform")
    }

    @Test
    fun `approvals reduce to approver ids, reviewers to id, state and time, and a missing merge request is NOT_FOUND`() = runBlocking {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/approvals") -> respond(
                    """{"id":1,"title":"SECRET TITLE","approvers":[],
                        "approved_by":[{"user":{"id":3,"name":"Ada"}},{"user":{"id":3}},{"user":{"id":8}},{"x":1}]}""",
                    HttpStatusCode.OK, json,
                )
                request.url.encodedPath.endsWith("/reviewers") -> respond(
                    """[{"user":{"id":3,"name":"Ada"},"state":"approved","created_at":"2026-08-31T11:27:54Z"},
                       {"user":{"id":4},"created_at":null},{"state":"reviewed"},{"user":{"id":5},"state":"unreviewed"}]""",
                    HttpStatusCode.OK, json,
                )
                else -> respond("""{"message":"404 Not found"}""", HttpStatusCode.NotFound, json)
            }
        }
        val client = client(engine)
        assertEquals(listOf(3L, 8L), client.mergeRequestApprovals(7, 4))
        val reviewers = client.mergeRequestReviewers(7, 4)
        assertEquals(listOf(3L, 4L, 5L), reviewers.map { it.userId }, "an entry without a user id is skipped")
        assertEquals(listOf("approved", "", "unreviewed"), reviewers.map { it.state })
        assertEquals(listOf(1_788_175_674_000L, null, null), reviewers.map { it.createdAt })
        assertEquals(listOf("/api/v4/projects/7/merge_requests/4/approvals", "/api/v4/projects/7/merge_requests/4/reviewers"), seen)
        assertEquals("NOT_FOUND", assertFailsWith<FetchException> { client.mergeRequestNotes(7, 9, 1) }.code)
        val noList = MockEngine { respond("""{"id":1}""", HttpStatusCode.OK, json) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(noList).mergeRequestApprovals(7, 4) }.code)
        val badReviewers = MockEngine { respond("""{"id":1}""", HttpStatusCode.OK, json) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client(badReviewers).mergeRequestReviewers(7, 4) }.code)
    }

    @Test
    fun `mergeRequestDiffStats posts the fixed query with variables to the origin graphql endpoint`() = runBlocking {
        var url: io.ktor.http.Url? = null
        var method: io.ktor.http.HttpMethod? = null
        var contentType: String? = null
        var privateToken: String? = null
        var body = ""
        val engine = MockEngine { request ->
            url = request.url
            method = request.method
            privateToken = request.headers["PRIVATE-TOKEN"]
            contentType = request.body.contentType?.withoutParameters()?.toString()
            body = (request.body as io.ktor.http.content.TextContent).text
            respond(
                """{"data":{"project":{"mergeRequests":{"nodes":[
                    {"iid":"4","diffStatsSummary":{"additions":10,"deletions":2,"fileCount":3}},
                    {"iid":"5","diffStatsSummary":null},
                    {"iid":"6","diffStatsSummary":{"additions":1,"deletions":0,"fileCount":1}},
                    {"iid":"99","diffStatsSummary":{"additions":1,"deletions":1,"fileCount":1}},
                    {"iid":"x","diffStatsSummary":{"additions":1,"deletions":1,"fileCount":1}},
                    {"iid":"7","diffStatsSummary":{"additions":-1,"deletions":1,"fileCount":1}}],
                    "pageInfo":{"hasNextPage":false}}}},
                    "errors":[{"message":"secret internal detail","extensions":{"code":"complexity"}},{"message":"again"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val answer = client(engine).mergeRequestDiffStats("platform/p1", listOf(4, 5, 6, 7))
        assertEquals("/api/graphql", url?.encodedPath)
        assertEquals("gitlab.example.test", url?.host)
        assertTrue(url?.parameters?.isEmpty() ?: false)
        assertEquals(io.ktor.http.HttpMethod.Post, method)
        assertEquals("application/json", contentType)
        assertEquals("glpat-secret", privateToken)
        assertFalse("glpat" in body, "the token is a header, never part of the body")
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(MR_DIFF_STATS_QUERY, sent.getValue("query").jsonPrimitive.content)
        assertTrue("diffStatsSummary" in MR_DIFF_STATS_QUERY && "project(fullPath: \$fullPath)" in MR_DIFF_STATS_QUERY)
        val variables = sent.getValue("variables").jsonObject
        assertEquals("platform/p1", variables.getValue("fullPath").jsonPrimitive.content)
        val iids = variables.getValue("iids").jsonArray.map { it.jsonPrimitive }
        assertTrue(iids.all { it.isString }, "GraphQL iids travel as strings")
        assertEquals(listOf("4", "5", "6", "7"), iids.map { it.content })
        val found = answer.stats.mapValues { Triple(it.value.additions, it.value.deletions, it.value.fileCount) }
        assertEquals(mapOf(4L to Triple(10, 2, 3), 6L to Triple(1, 0, 1)), found)
        assertEquals(2, answer.errors)
        assertEquals("complexity", answer.firstErrorCode)
        assertFalse(answer.projectMissing)
    }

    @Test
    fun `a project path or an iid can never change the query text`() = runBlocking {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respond("""{"data":{"project":null}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val hostile = "a/b\"}) { evil } #\n\$"
        val answer = client(engine).mergeRequestDiffStats(hostile, listOf(1))
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(MR_DIFF_STATS_QUERY, sent.getValue("query").jsonPrimitive.content)
        assertEquals(hostile, sent.getValue("variables").jsonObject.getValue("fullPath").jsonPrimitive.content)
        assertTrue(answer.projectMissing)
        assertTrue(answer.stats.isEmpty())
    }

    @Test
    fun `graphql errors, partial data, a next page and malformed bodies are gaps and never a failure`() = runBlocking {
        val jsonType = headersOf(HttpHeaders.ContentType, "application/json")
        suspend fun answer(reply: String) =
            client(MockEngine { respond(reply, HttpStatusCode.OK, jsonType) }).mergeRequestDiffStats("platform/p1", listOf(4))
        val stat = """{"iid":"4","diffStatsSummary":{"additions":1,"deletions":1,"fileCount":1}}"""
        for (reply in listOf(
            "[]", "\"x\"", "null", "{}", """{"data":5}""", """{"data":{}}""", """{"data":{"project":5}}""", """{"data":{"project":{}}}""",
            """{"data":{"project":{"mergeRequests":{"nodes":"x"}}}}""",
            """{"errors":[{"message":"boom"}]}""",
            """{"data":{"project":{"mergeRequests":{"nodes":[$stat],"pageInfo":{"hasNextPage":true}}}}}""",
        )) {
            assertTrue(answer(reply).stats.isEmpty(), reply)
        }
        val errors = answer("""{"errors":[{"message":"boom","extensions":{"code":"bad code!"}}]}""")
        assertEquals(1, errors.errors)
        assertNull(errors.firstErrorCode, "a code that is not a short identifier is not kept")
        assertTrue(answer("""{"data":{"project":null}}""").projectMissing)
        assertEquals(setOf(4L), answer("""{"data":{"project":{"mergeRequests":{"nodes":[$stat,$stat]}}}}""").stats.keys)
    }

    @Test
    fun `a graphql server failure is a FetchException and the arguments are checked before any request`() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("{}", HttpStatusCode.InternalServerError, json)
        }
        val client = client(engine)
        val failure = assertFailsWith<FetchException> { client.mergeRequestDiffStats("platform/p1", listOf(4)) }
        assertEquals("UPSTREAM_UNAVAILABLE", failure.code)
        val before = requests
        assertFailsWith<IllegalArgumentException> { client.mergeRequestDiffStats("platform/p1", emptyList()) }
        assertFailsWith<IllegalArgumentException> { client.mergeRequestDiffStats("platform/p1", (1L..51L).toList()) }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.mergeRequestDiffStats("platform/p1", listOf(-4)) }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.mergeRequestDiffStats(" ", listOf(4)) }.code)
        val longPath = "p".repeat(2_000)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.mergeRequestDiffStats(longPath, listOf(4)) }.code)
        assertEquals(before, requests)
        assertEquals(50, MR_DETAILS_BATCH)
    }

    @Test
    fun `project reduces the detail body to the probe's fields and a 404 is NOT_FOUND`() = runBlocking {
        val tokens = mutableListOf<String?>()
        val engine = MockEngine { request ->
            tokens += request.headers["PRIVATE-TOKEN"]
            when (request.url.encodedPath) {
                "/api/v4/projects/4" -> respond(
                    """{"id":4,"path_with_namespace":"platform/tools/p4","archived":true,"default_branch":"main","description":"SECRET",
                        "namespace":{"id":8,"full_path":"platform/tools","kind":"group"},"statistics":{"commit_count":9}}""",
                    HttpStatusCode.OK, json,
                )
                "/api/v4/projects/5" ->
                    respond("""{"id":5,"path_with_namespace":"platform/p5","default_branch":null}""", HttpStatusCode.OK, json)
                "/api/v4/projects/6" -> respond("""{"id":6}""", HttpStatusCode.OK, json)
                else -> respond("""{"message":"404 Project Not Found"}""", HttpStatusCode.NotFound, json)
            }
        }
        val client = client(engine)
        val detail = client.project(4)
        assertEquals(listOf(4L, "platform/tools/p4", "platform/tools", true, "main"),
            listOf(detail.id, detail.pathWithNamespace, detail.namespaceFullPath, detail.archived, detail.defaultBranch))
        assertEquals("glpat-secret", tokens.first())
        val bare = client.project(5)
        assertFalse(bare.archived, "a missing archived is false")
        assertNull(bare.namespaceFullPath)
        assertNull(bare.defaultBranch)
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { client.project(6) }.code, "no path_with_namespace")
        assertEquals("NOT_FOUND", assertFailsWith<FetchException> { client.project(99) }.code)
    }

    @Test
    fun `mergeRequest asks the single merge request, slims it and a 404 is NOT_FOUND`() = runBlocking {
        val seen = mutableListOf<String>()
        val engine = MockEngine { request ->
            seen += request.url.encodedPath
            if (request.url.encodedPath == "/api/v4/projects/7/merge_requests/4") respond(mrItem, HttpStatusCode.OK, json)
            else respond("""{"message":"404 Not found"}""", HttpStatusCode.NotFound, json)
        }
        val client = client(engine)
        val mr = client.mergeRequest(7, 4)
        assertEquals(listOf(1041L, 7L, 4L), listOf(mr.mrId, mr.projectId, mr.iid))
        assertEquals("merged", mr.state)
        val payload = mr.payload as kotlinx.serialization.json.JsonObject
        assertTrue(payload.keys.all { it in MR_KEYS }, "only whitelisted keys: ${payload.keys}")
        assertTrue(listOf("id", "iid", "project_id", "state", "updated_at").all { it in payload })
        assertFalse("description" in payload || "SECRET" in payload.toString())
        assertEquals("NOT_FOUND", assertFailsWith<FetchException> { client.mergeRequest(7, 12) }.code)
        assertEquals(listOf("/api/v4/projects/7/merge_requests/4", "/api/v4/projects/7/merge_requests/12"), seen)
    }

    @Test
    fun `a non-numeric or negative id is refused before any merge-request request`() = runBlocking {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("[]", HttpStatusCode.OK, json)
        }
        val client = client(engine)
        for (call in listOf<suspend () -> Any>(
            { client.groupMergeRequests(-7, null, 1) },
            { client.mergeRequestNotes(-7, 4, 1) },
            { client.mergeRequestNotes(7, -4, 1) },
            { client.mergeRequestApprovals(7, -4) },
            { client.mergeRequestReviewers(-7, 4) },
            { client.mergeRequest(-7, 4) },
            { client.mergeRequest(7, -4) },
            { client.project(-4) },
        )) assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { call() }.code)
        assertEquals(0, requests)
    }
}
