package ch.nokillswit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Before
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The GitLab stub's contract over plain HTTP, no app: auth, offset-pagination headers, the range/merge-base forms, group
 * and project lookups, the GraphQL null gap, the 404 shapes and the `gitlab-day2` scenario. The figures asserted come from
 * `sample-data/gitlab/expected.json` (the generator's ground truth), never from numbers copied into this file.
 */
class GitLabStubServerTest {
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val base: String get() = GitLabStubServer.start()

    private val expected: JsonObject by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).firstOrNull { it.isFile }
            ?: error("sample-data/gitlab/expected.json not found from ${File(".").absolutePath}")
        json.parseToJsonElement(file.readText()).jsonObject
    }

    private class Reply(val status: Int, val body: String, private val headers: Map<String, List<String>>) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }

    @Before
    fun startAtDayZero() {
        GitLabStubServer.resetScenarios()
    }

    private fun call(path: String, token: Boolean = true, method: String = "GET", body: String? = null): Reply {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
        if (token) builder.header("PRIVATE-TOKEN", "any-non-empty-token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.body(), response.headers().map())
    }

    private fun Reply.obj(): JsonObject = json.parseToJsonElement(body).jsonObject
    private fun Reply.array(): JsonArray = json.parseToJsonElement(body).jsonArray
    private fun JsonElement.str(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
    private fun JsonObject.at(vararg path: String): JsonElement =
        path.fold(this as JsonElement) { node, key -> node.jsonObject.getValue(key) }
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    // The ids, branches and paths below all come from expected.json, never from literals in the tests.
    private val projectList: JsonArray by lazy { expected.at("projects", "list").jsonArray }
    private val inScopeIds: List<Int> by lazy { expected.at("projects", "inScopeIds").jsonArray.map { it.jsonPrimitive.int } }
    private fun projectRow(id: Int): JsonElement = projectList.first { it.str("id").toInt() == id }
    private fun branchOf(id: Int) = projectRow(id).str("defaultBranch")
    private fun groupOf(id: Int) = projectRow(id).str("groupId").toInt()

    /** The first in-scope project off `master` with merge commits, and the in-scope project whose default branch is `master`. */
    private val mergeProject: Int by lazy {
        inScopeIds.first { branchOf(it) != "master" && projectCommits(it).str("merges").toInt() > 0 }
    }
    private val masterProject: Int by lazy { inScopeIds.first { branchOf(it) == "master" } }
    private val mainBranch: String get() = branchOf(mergeProject)
    private val subgroup: JsonElement by lazy { expected.at("groups").jsonArray.first { it.jsonObject.getValue("parentId") !is JsonNull } }

    private fun projectCommits(id: Int) = expected.at("commits", "perProject", id.toString())
    private fun total(id: Int) = projectCommits(id).str("total").toInt()

    private fun commitsPath(id: Int, ref: String) =
        "/api/v4/projects/$id/repository/commits?ref_name=${enc(ref)}&since=2026-01-01T00:00:00Z&with_stats=true&per_page=100"

    private fun groupMergeRequests(groupId: Int, extra: String = "") =
        "/api/v4/groups/$groupId/merge_requests?scope=all&state=all&include_subgroups=true&order_by=updated_at&sort=asc&per_page=100$extra"

    private fun groupProjects(groupId: Int) = "/api/v4/groups/$groupId/projects?include_subgroups=true&archived=false&per_page=100"

    private fun graphqlStats(fullPath: String): Reply {
        val stats = "iid diffStatsSummary { additions deletions fileCount }"
        val query = "query(${'$'}fullPath: ID!) { project(fullPath: ${'$'}fullPath) { mergeRequests { nodes { $stats } } } }"
        return call("/api/graphql", method = "POST", body = """{"query":"$query","variables":{"fullPath":"$fullPath"}}""")
    }

    @Test
    fun `any non-empty PRIVATE-TOKEN or Bearer token is accepted and everything else is a 401`() {
        assertEquals(401, call("/api/v4/version", token = false).status)
        assertEquals("""{"message":"401 Unauthorized"}""", call("/api/v4/user", token = false).body)
        val empty = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/v4/version")).header("PRIVATE-TOKEN", "").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, empty.statusCode())
        val basic = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/v4/version")).header("Authorization", "Basic YTpi").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, basic.statusCode())
        val bearer = http.send(
            HttpRequest.newBuilder(URI.create("$base/api/v4/version")).header("Authorization", "Bearer abc").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, bearer.statusCode())
        assertTrue(call("/api/v4/version").obj().str("version").isNotBlank())
    }

    @Test
    fun `the token is a read_api admin personal access token`() {
        val user = call("/api/v4/user").obj()
        assertEquals("true", user.str("is_admin"))
        assertEquals(expected.at("token", "userId").jsonPrimitive.int, user.getValue("id").jsonPrimitive.int)
        val token = call("/api/v4/personal_access_tokens/self").obj()
        assertEquals(listOf("read_api"), token.getValue("scopes").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", token.str("active"))
        assertTrue(token.str("expires_at").matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `groups resolve by id and by url-encoded full path, and group projects exclude archived ones in id order`() {
        val byId = call("/api/v4/groups/${subgroup.str("id")}").obj()
        assertEquals(subgroup.str("fullPath"), byId.str("full_path"))
        assertEquals(subgroup.str("fullPath"), call("/api/v4/groups/${enc(subgroup.str("fullPath"))}").obj().str("full_path"))
        assertEquals(404, call("/api/v4/groups/999").status)
        assertEquals("""{"message":"404 Group Not Found"}""", call("/api/v4/groups/999").body)

        val archivedIds = expected.at("projects", "archivedIds").jsonArray.map { it.jsonPrimitive.int }
        val archivedGroup = groupOf(archivedIds.first())
        val live = call(groupProjects(archivedGroup) + "&page=1")
        val liveIds = live.array().map { it.str("id").toInt() }
        assertTrue(liveIds.none { it in archivedIds }, "archived projects are excluded with archived=false")
        assertEquals(liveIds.sorted(), liveIds)
        val expectedLive = expected.at("projects", "startedGroupProjects", archivedGroup.toString(), "live")
        assertEquals(liveIds, expectedLive.jsonArray.map { it.jsonPrimitive.int })
        // include_subgroups: the parent group lists the subgroup's project too.
        val inSubgroup = projectList.first {
            it.str("groupId") == subgroup.str("id") && it.str("scope") == "in" && it.str("day2Only") == "false"
        }
        assertTrue(call(groupProjects(subgroup.str("parentId").toInt())).array().any { it.str("id") == inSubgroup.str("id") })
    }

    @Test
    fun `the commit list carries the offset pagination headers, absolute Link urls and stats`() {
        val reply = call(commitsPath(mergeProject, mainBranch))
        assertEquals(200, reply.status)
        assertEquals(total(mergeProject), reply.array().size)
        assertEquals("1", reply.header("X-Page"))
        assertEquals("100", reply.header("X-Per-Page"))
        assertEquals(total(mergeProject).toString(), reply.header("X-Total"))
        assertEquals("1", reply.header("X-Total-Pages"))
        assertEquals("", reply.header("X-Next-Page"))
        assertEquals("", reply.header("X-Prev-Page"))
        assertTrue(reply.header("Link")!!.contains("<$base/api/v4/projects/$mergeProject/repository/commits?"), reply.header("Link"))
        assertTrue(reply.array().first().jsonObject.containsKey("stats"))
        assertTrue(reply.array().any { it.jsonObject.getValue("parent_ids").jsonArray.size == 2 }, "merge commits carry two parents")
        // The master default branch of the ledger project, and a page size the connector never uses.
        assertEquals(total(masterProject), call(commitsPath(masterProject, branchOf(masterProject))).array().size)
        assertEquals(404, call("/api/v4/projects/$mergeProject/repository/commits?ref_name=$mainBranch&with_stats=true&per_page=50").status)
    }

    @Test
    fun `a stored head gives the range form and a merge base that is the head itself`() {
        val head = expected.at("storedHeads", mergeProject.toString())
        val sha = head.str("storedSha")
        val range = call(commitsPath(mergeProject, "$sha..$mainBranch"))
        assertEquals(head.str("commitsAfterStoredHead").toInt(), range.array().size)
        assertEquals(range.array().size.toString(), range.header("X-Total"))
        val base = call("/api/v4/projects/$mergeProject/repository/merge_base?refs[]=$sha&refs[]=$mainBranch")
        assertEquals(sha, base.obj().str("id"))
        // At the tip of the started history there is nothing new: an empty page that still carries the headers.
        val tip = call(commitsPath(mergeProject, "${head.str("startedTipSha")}..$mainBranch"))
        assertEquals("[]", tip.body)
        assertEquals("0", tip.header("X-Total"))
    }

    @Test
    fun `the many-files commit diff spans several pages and keeps the file fields`() {
        val many = expected.at("special", "manyFiles")
        val project = many.str("projectId")
        val sha = many.str("sha")
        val pages = many.str("diffPages").toInt()
        assertTrue(pages > 10)
        var files = 0
        for (page in 1..pages) {
            val reply = call("/api/v4/projects/$project/repository/commits/$sha/diff?per_page=100&page=$page")
            assertEquals(200, reply.status, "page $page")
            assertEquals(page.toString(), reply.header("X-Page"))
            assertEquals(if (page < pages) (page + 1).toString() else "", reply.header("X-Next-Page"))
            assertEquals(many.str("files"), reply.header("X-Total"))
            files += reply.array().size
        }
        assertEquals(many.str("files").toInt(), files)
        val first = call("/api/v4/projects/$project/repository/commits/$sha/diff?per_page=100").array().first().jsonObject
        val fileKeys = listOf(
            "diff", "new_path", "old_path", "a_mode", "b_mode", "new_file", "renamed_file", "deleted_file", "generated_file",
        )
        for (key in fileKeys) assertTrue(first.containsKey(key), key)
    }

    private fun Reply.link(rel: String): String? =
        header("Link")?.let { Regex("""<([^>]+)>; rel="$rel"""").find(it)?.groupValues?.get(1) }

    private fun pageOf(url: String): Int = Regex("""[?&]page=(\d+)""").find(url)!!.groupValues[1].toInt()

    @Test
    fun `following rel next walks the pages and page 2 links back with prev, first and last`() {
        val many = expected.at("special", "manyFiles")
        val pages = many.str("diffPages").toInt()
        val diff = "/api/v4/projects/${many.str("projectId")}/repository/commits/${many.str("sha")}/diff?per_page=100"
        val first = call(diff)
        assertEquals(null, first.link("prev"))
        val next = first.link("next")!!
        assertTrue(next.startsWith(base), "Link urls are absolute: $next")
        val second = call(next.removePrefix(base))
        assertEquals(200, second.status)
        assertEquals("2", second.header("X-Page"))
        assertEquals(100, second.array().size)
        assertEquals(1, pageOf(second.link("prev")!!))
        assertEquals(1, pageOf(second.link("first")!!))
        assertEquals(pages, pageOf(second.link("last")!!))
        assertEquals(3, pageOf(second.link("next")!!))
    }

    @Test
    fun `a page past the end is an empty list with the usual headers and page 1 is only page absent or 1`() {
        val commits = commitsPath(mergeProject, mainBranch)
        for (page in listOf(2, 99, 0)) {
            val reply = call("$commits&page=$page")
            assertEquals(200, reply.status, "page $page")
            assertEquals("[]", reply.body, "page $page")
            assertEquals(page.toString(), reply.header("X-Page"))
            assertEquals("", reply.header("X-Next-Page"))
            assertEquals("100", reply.header("X-Per-Page"))
            assertEquals(total(mergeProject).toString(), reply.header("X-Total"))
            assertEquals("1", reply.header("X-Total-Pages"))
        }
        assertEquals(total(mergeProject), call("$commits&page=1").array().size)
        val many = expected.at("special", "manyFiles")
        val pages = many.str("diffPages").toInt()
        val diff = "/api/v4/projects/${many.str("projectId")}/repository/commits/${many.str("sha")}/diff?per_page=100"
        val past = call("$diff&page=${pages + 1}")
        assertEquals("[]", past.body)
        assertEquals(many.str("files"), past.header("X-Total"))
        assertEquals(pages.toString(), past.header("X-Total-Pages"))
    }

    @Test
    fun `the too_large file has an empty diff and a collapsed one is flagged`() {
        val tooLarge = expected.at("special", "tooLarge")
        val reply = call("/api/v4/projects/${tooLarge.str("projectId")}/repository/commits/${tooLarge.str("sha")}/diff?per_page=100")
        val file = reply.array().first { it.str("new_path") == tooLarge.str("path") }.jsonObject
        assertEquals("", file.getValue("diff").jsonPrimitive.content)
        assertEquals("true", file.getValue("too_large").jsonPrimitive.content)
        val collapsed = expected.at("special", "collapsed")
        val other = call("/api/v4/projects/${collapsed.str("projectId")}/repository/commits/${collapsed.str("sha")}/diff?per_page=100")
        assertTrue(other.array().any { it.jsonObject["collapsed"]?.jsonPrimitive?.content == "true" })
    }

    @Test
    fun `a busy merge request has two pages of notes and the merge request details endpoints answer`() {
        val busy = expected.at("mergeRequests", "list").jsonArray.first { it.str("notePages").toInt() > 1 }
        val path = "/api/v4/projects/${busy.str("projectId")}/merge_requests/${busy.str("iid")}"
        val first = call("$path/notes?sort=asc&order_by=created_at&per_page=100&page=1")
        val second = call("$path/notes?sort=asc&order_by=created_at&per_page=100&page=2")
        assertEquals("2", first.header("X-Next-Page"))
        assertEquals("", second.header("X-Next-Page"))
        assertEquals(busy.str("notes").toInt(), first.array().size + second.array().size)
        assertEquals(200, call("$path/approvals").status)
        assertTrue(call("$path/reviewers").array().all { it.jsonObject.containsKey("state") })
        val group = call(groupMergeRequests(groupOf(mergeProject), "&updated_after=2020-01-01T00:00:00Z"))
        val listed = expected.at("mergeRequests", "groupListPages", groupOf(mergeProject).toString(), "total").jsonPrimitive.content
        assertEquals(listed, group.header("X-Total"))
    }

    @Test
    fun `graphql answers every merge request of a project and leaves one null gap with an error`() {
        val gap = expected.at("mergeRequests", "nullDiffStatsGap")
        val project = expected.at("projects", "list").jsonArray.first { it.str("id") == gap.str("projectId") }.str("fullPath")
        val reply = graphqlStats(project)
        assertEquals(200, reply.status)
        val nodes = reply.obj().at("data", "project", "mergeRequests", "nodes").jsonArray
        assertEquals(expected.at("mergeRequests", "perProject", gap.str("projectId")).jsonPrimitive.int, nodes.size)
        val nullNodes = nodes.filter { it.jsonObject.getValue("diffStatsSummary").toString() == "null" }
        assertEquals(1, nullNodes.size)
        assertEquals(gap.str("iid"), nullNodes.single().str("iid"))
        assertEquals(1, reply.obj().getValue("errors").jsonArray.size)
        // An unknown project is a null project, not an error.
        val unknown = call("/api/graphql", method = "POST", body = """{"query":"{ project(fullPath: \"nowhere/none\") { id } }"}""")
        assertEquals("""{"data":{"project":null}}""", unknown.body)
    }

    @Test
    fun `404 bodies carry the GitLab shapes`() {
        assertEquals("""{"message":"404 Project Not Found"}""", call("/api/v4/projects/9999").body)
        assertEquals("""{"message":"404 Project Not Found"}""", call(commitsPath(9999, "main")).body)
        val notes = "/api/v4/projects/$mergeProject/merge_requests/9999/notes?sort=asc&order_by=created_at&per_page=100"
        assertEquals("""{"message":"404 Not found"}""", call(notes).body)
        val diff = "/api/v4/projects/$mergeProject/repository/commits/${"0".repeat(40)}/diff?per_page=100"
        assertEquals("""{"message":"404 Commit Not Found"}""", call(diff).body)
        assertEquals("""{"message":"404 User Not Found"}""", call("/api/v4/users/9999/emails").body)
        val branch = "/api/v4/projects/$mergeProject/repository/branches/no-such-branch"
        assertEquals("""{"message":"404 Branch Not Found"}""", call(branch).body)
        // The day-2-only project does not exist at day zero.
        val newId = expected.at("projects", "day2NewIds").jsonArray.first().jsonPrimitive.content
        assertEquals(404, call("/api/v4/projects/$newId").status)
    }

    @Test
    fun `users list the admin view and the secondary email endpoint`() {
        val users = call("/api/v4/users?per_page=100&page=1")
        assertEquals(expected.at("users", "list").jsonArray.size.toString(), users.header("X-Total"))
        val adminView = listOf("email", "bot", "is_admin", "state")
        assertTrue(users.array().all { user -> adminView.all { user.jsonObject.containsKey(it) } })
        val withSecondary = expected.at("users", "withSecondaryEmailIds").jsonArray.first().jsonPrimitive.content
        assertEquals(1, call("/api/v4/users/$withSecondary/emails").array().size)
    }

    @Test
    fun `day2 force-pushes a project, renames a default branch, deletes a merge request and moves a project out`() {
        val day2 = expected.at("day2").jsonObject
        GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
        try {
            // force-push: the head moved, and merge_base with the old head is an older commit
            val push = day2.getValue("forcePush").jsonObject
            val pushProject = push.str("projectId").toInt()
            val list = call(commitsPath(pushProject, branchOf(pushProject)))
            assertEquals(push.str("newTip"), list.array().first().str("id"))
            assertTrue(list.array().none { it.str("id") == push.str("oldTip") })
            val refs = "refs[]=${push.str("oldTip")}&refs[]=${branchOf(pushProject)}"
            val base = call("/api/v4/projects/$pushProject/repository/merge_base?$refs")
            assertEquals(push.str("divergenceSha"), base.obj().str("id"))
            // the default branch rename
            val rename = day2.getValue("renamedDefaultBranch").jsonObject
            val renamed = rename.str("projectId")
            assertEquals(rename.str("to"), call("/api/v4/projects/$renamed").obj().str("default_branch"))
            assertEquals(404, call("/api/v4/projects/$renamed/repository/branches/${rename.str("from")}").status)
            assertEquals(200, call("/api/v4/projects/$renamed/repository/branches/${rename.str("to")}").status)
            // a deleted merge request is gone from the lists and 404 on its endpoints
            val gone = day2.getValue("deletedMergeRequest").jsonObject
            assertEquals(404, call("/api/v4/projects/${gone.str("projectId")}/merge_requests/${gone.str("iid")}/approvals").status)
            val groupList = call(groupMergeRequests(groupOf(gone.str("projectId").toInt())))
            assertTrue(groupList.array().none { it.str("iid") == gone.str("iid") && it.str("project_id") == gone.str("projectId") })
            // the moved project leaves the in-scope groups but still answers by id, now in the new namespace
            val moved = day2.getValue("movedProject").jsonObject
            val oldGroup = moved.getValue("goneFromGroupProjects").jsonArray.first().jsonPrimitive.int
            val inOldGroup = call(groupProjects(oldGroup)).array().map { it.str("id") }
            assertTrue(moved.str("projectId") !in inOldGroup)
            assertEquals(moved.str("newFullPath"), call("/api/v4/projects/${moved.str("projectId")}").obj().str("path_with_namespace"))
            // the new project appears
            val created = day2.getValue("newProject").jsonObject
            assertEquals(200, call("/api/v4/projects/${created.str("id")}").status)
            assertTrue(inOldGroup.contains(created.str("id")))
        } finally {
            GitLabStubServer.resetScenarios()
        }
        assertEquals(branchOf(masterProject), call("/api/v4/projects/$masterProject").obj().str("default_branch"))
    }
}
