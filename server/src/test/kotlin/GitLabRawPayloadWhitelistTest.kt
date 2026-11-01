package ch.nokillswit

import ch.nokillswit.gitlab.MR_KEYS
import ch.nokillswit.gitlab.NAMESPACE_KEYS
import ch.nokillswit.gitlab.PROJECT_KEYS
import ch.nokillswit.gitlab.USER_KEYS
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.BeforeClass
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A harvested stub text shorter than this is too generic to prove anything by being absent. */
private const val PRIVACY_MIN_CHARS = 20

/**
 * The GitLab privacy whitelist (plan decision 9): the stored data is facts about work, never the work itself. Three pins over the TWELVE
 * GitLab tables (six `raw.gitlab_*`, six `norm.code_*`) of the shared fixture: the exact column names (a new column needs a privacy
 * decision), the keys of every stored slim payload, and the absence of stub text that must never be stored (merge request descriptions,
 * note bodies, commit message bodies, URLs). Read-only.
 */
class GitLabRawPayloadWhitelistTest {
    private val fixtureId: UInt by lazy { runBlocking { SyncedGitLabFixture.connectionId() } }

    private val stubFiles: File by lazy {
        listOf(File("sample-data/gitlab-stub/__files"), File("../sample-data/gitlab-stub/__files")).first { it.isDirectory }
    }

    private val whitelist: Map<String, Set<String>> = mapOf(
        "raw.gitlab_projects" to setOf(
            "connection_id", "project_id", "path_with_namespace", "group_path", "group_id", "default_branch", "archived", "payload",
            "sha256", "first_seen_at", "last_seen_at", "changed_at", "moved_out_at", "deleted_at", "commits_head_sha", "relist_requested",
            "relist_started_at",
        ),
        "raw.gitlab_users" to setOf(
            "connection_id", "user_id", "username", "name", "state", "bot", "emails", "payload", "sha256", "first_seen_at", "last_seen_at",
            "changed_at", "deleted_at",
        ),
        "raw.gitlab_commits" to setOf(
            "connection_id", "project_id", "sha", "title", "author_name", "author_email", "authored_at", "committed_at", "parent_count",
            "additions", "deletions", "files_changed", "files_capped", "files_fetched_at", "files_error", "first_seen_at", "last_seen_at",
            "deleted_at", "needs_processing", "processed_at", "processing_version",
        ),
        "raw.gitlab_commit_files" to setOf(
            "connection_id", "project_id", "sha", "path", "old_path", "additions", "deletions", "new_file", "deleted_file", "renamed_file",
            "generated_file",
        ),
        "raw.gitlab_merge_requests" to setOf(
            "connection_id", "mr_id", "project_id", "iid", "state", "draft", "author_id", "merge_user_id", "created_at", "updated_at",
            "merged_at", "closed_at", "source_branch", "target_branch", "title", "head_sha", "merge_commit_sha", "squash_commit_sha",
            "reviewer_ids", "assignee_ids", "user_notes_count", "additions", "deletions", "file_count", "notes_capped", "details_synced_at",
            "details_error", "payload", "sha256", "first_seen_at", "last_seen_at", "changed_at", "deleted_at", "needs_processing",
            "processed_at", "processing_version",
        ),
        "raw.gitlab_mr_events" to setOf(
            "connection_id", "mr_id", "source", "source_id", "project_id", "actor_id", "event_at", "system", "note_type", "action",
            "targets",
        ),
        "norm.code_projects" to setOf(
            "connection_id", "project_id", "path_with_namespace", "group_path", "default_branch", "archived", "moved_out_at", "deleted_at",
        ),
        "norm.code_identities" to setOf(
            "connection_id", "identity_key", "kind", "user_id", "username", "display_name", "state", "bot", "emails", "deleted_at",
        ),
        "norm.code_commits" to setOf(
            "connection_id", "project_id", "sha", "identity_key", "title", "authored_at", "committed_at", "is_merge", "additions",
            "deletions", "files", "additions_counted", "deletions_counted", "files_counted", "files_capped", "deleted_at", "processed_at",
            "processing_version",
        ),
        "norm.code_merge_requests" to setOf(
            "connection_id", "mr_id", "project_id", "iid", "state", "draft", "author_key", "merge_user_key", "created_at", "updated_at",
            "merged_at", "closed_at", "source_branch", "target_branch", "title", "head_sha", "merge_commit_sha", "squash_commit_sha",
            "reviewer_keys", "assignee_keys", "additions", "deletions", "file_count", "notes_capped", "details_state", "deleted_at",
            "processed_at", "processing_version",
        ),
        "norm.code_mr_events" to setOf("connection_id", "mr_id", "seq", "kind", "actor_key", "event_at", "target_keys"),
        "norm.code_mr_issue_keys" to setOf("connection_id", "mr_id", "issue_key", "in_title", "in_branch"),
    )

    @Test
    fun `the twelve tables hold exactly the whitelisted columns`() {
        assertEquals(GitLabCodeTables.ALL.toSet(), whitelist.keys, "every GitLab table is pinned")
        for ((table, columns) in whitelist) {
            val message = "$table: a new column needs a privacy decision (no code, diffs, descriptions or note bodies)"
            assertEquals(columns, GitLabCodeTables.columns(table), message)
        }
    }

    private fun payloads(table: String): List<JsonObject> = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT payload::text FROM $table WHERE connection_id = $fixtureId").use { rs ->
                buildList { while (rs.next()) add(Json.parseToJsonElement(rs.getString(1)).jsonObject) }
            }
        }
    }

    @Test
    fun `every stored slim payload carries only its whitelisted keys`() {
        val projects = payloads("raw.gitlab_projects")
        assertTrue(projects.isNotEmpty())
        projects.forEach { payload ->
            assertTrue((payload.keys - "namespace").all { it in PROJECT_KEYS }, "project payload keys: ${payload.keys}")
            val namespace = payload["namespace"]?.jsonObject ?: return@forEach
            assertTrue(namespace.keys.all { it in NAMESPACE_KEYS }, "namespace keys: ${namespace.keys}")
        }
        val users = payloads("raw.gitlab_users")
        assertTrue(users.isNotEmpty())
        users.forEach { assertTrue((it.keys - "emails").all { key -> key in USER_KEYS }, "user payload keys: ${it.keys}") }
        val mergeRequests = payloads("raw.gitlab_merge_requests")
        assertTrue(mergeRequests.isNotEmpty())
        mergeRequests.forEach { assertTrue(it.keys.all { key -> key in MR_KEYS }, "merge request payload keys: ${it.keys}") }
    }

    private fun strings(file: String, member: String): List<String> =
        Json.parseToJsonElement(File(stubFiles, file).readText()).jsonArray.mapNotNull {
            (it.jsonObject[member] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        }

    private fun stubFilesMatching(glob: Regex): List<String> = stubFiles.list().orEmpty().filter { glob.matches(it) }.sorted()

    @Test
    fun `no description, note body, commit message body or URL of the stub reaches any stored row`() {
        val descriptions = stubFilesMatching(Regex("group-\\d+-merge-requests-.*\\.json")).flatMap { strings(it, "description") }
        val notes = stubFilesMatching(Regex("p\\d+-mr-\\d+-notes-page-\\d+\\.json")).flatMap { strings(it, "body") }
        val commitFiles = stubFilesMatching(Regex("p\\d+-commits-.*page-\\d+\\.json"))
        // A text equal to a stored TITLE (a merge commit's body repeats the commit it merges) is the title, stored by design: not a leak.
        val mergeRequestFiles = stubFilesMatching(Regex("group-\\d+-merge-requests-.*\\.json"))
        val titles = (commitFiles + mergeRequestFiles).flatMap { strings(it, "title") }.toSet()
        val commitBodies = commitFiles.flatMap { strings(it, "message") }
            .flatMap { it.lineSequence().drop(1).map(String::trim).filter(String::isNotEmpty).toList() }
            .filterNot { it in titles }
        val urls = commitFiles.flatMap { strings(it, "web_url") }
        val texts = (descriptions + notes + commitBodies + urls).filter { it.length >= PRIVACY_MIN_CHARS && it !in titles }.distinct()
        assertTrue(texts.size >= 20, "the privacy scan saw only ${texts.size} stub texts")
        assertTrue(commitBodies.isNotEmpty() && urls.isNotEmpty() && notes.isNotEmpty(), "every kind of text was harvested")
        for (table in GitLabCodeTables.ALL) {
            val stored = GitLabCodeTables.text(fixtureId, table)
            for (text in texts) assertFalse(text in stored, "$table carries stub text: ${text.take(30)}")
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }
}
