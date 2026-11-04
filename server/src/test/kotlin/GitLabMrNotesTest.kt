package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.gitlab.GitLabNoteSummary
import ch.nokillswit.gitlab.MAX_TARGETS
import ch.nokillswit.gitlab.MAX_TARGET_CHARS
import ch.nokillswit.gitlab.mentions
import ch.nokillswit.gitlab.summarizeNote
import ch.nokillswit.gitlab.systemNoteAction
import ch.nokillswit.gitlab.userNoteAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The note classifier (`gitlab/GitLabMrNotes.kt`, plan L10): action codes by system-note text, bounded targets, no body kept. */
class GitLabMrNotesTest {

    /** The system-note texts the stub serves (`sample-data/gitlab/lib/mrs.mjs`) and the variants real instances send. */
    private val classified = listOf(
        "marked this merge request as **draft**" to "DRAFT",
        "marked this merge request as **ready**" to "READY",
        "approved this merge request" to "APPROVAL",
        "unapproved this merge request" to "UNAPPROVAL",
        "requested changes" to "CHANGES_REQUESTED",
        "merged" to "MERGED",
        "closed" to "CLOSED",
        "reopened" to "REOPENED",
        "requested review from @alice" to "REVIEW_REQUESTED",
        "added 3 commits\n\n* 1a2b3c4d - Fix\n* 5e6f7a8b - Tidy\n\n[Compare with previous version](/p1/-/merge_requests/4/diffs)" to
            "COMMITS_ADDED",
        "added 1 commit\n\n* 1a2b3c4d - Fix the thing" to "COMMITS_ADDED",
        // Real-instance variants.
        "requested review from @alice and @bob" to "REVIEW_REQUESTED",
        "removed review request for @alice" to "REVIEW_REQUEST_REMOVED",
        "marked as a **Work In Progress**" to "DRAFT",
        "unmarked as a **Work In Progress**" to "READY",
        "closed via merge request !5" to "CLOSED",
        // Case and surrounding whitespace.
        "  Approved This Merge Request \n" to "APPROVAL",
        "\tMERGED" to "MERGED",
        // Everything else, hostile or merely unknown.
        "changed the description" to "OTHER_SYSTEM",
        "assigned to @x" to "OTHER_SYSTEM",
        "mentioned in merge request !5" to "OTHER_SYSTEM",
        "mergedevil" to "OTHER_SYSTEM",
        "approved this merge requests" to "OTHER_SYSTEM",
        "requested changesX" to "OTHER_SYSTEM",
        "added commits" to "OTHER_SYSTEM",
        "added  commits" to "OTHER_SYSTEM",
        "added 3 commitsX" to "OTHER_SYSTEM",
        "<script>approved this merge request</script>" to "OTHER_SYSTEM",
        "please see: approved this merge request" to "OTHER_SYSTEM",
        "'; DROP TABLE raw.gitlab_mr_events; --" to "OTHER_SYSTEM",
        "" to "OTHER_SYSTEM",
        "   " to "OTHER_SYSTEM",
        "x".repeat(20_000) to "OTHER_SYSTEM",
        "\u0000approved this merge request" to "OTHER_SYSTEM",
    )

    @Test
    fun `every system-note text maps to its action code`() {
        for ((body, action) in classified) assertEquals(action, systemNoteAction(body), "<${body.take(60)}>")
        assertEquals("OTHER_SYSTEM", systemNoteAction(null), "a system note without a body")
    }

    @Test
    fun `a user note is a comment, a DiffNote a diff comment, and its body is never classified`() {
        assertEquals("DIFF_COMMENT", userNoteAction("DiffNote"))
        for (type in listOf("DiscussionNote", null, "", "diffnote", "Other")) assertEquals("COMMENT", userNoteAction(type), "<$type>")
        val note = summarizeNote(note(system = false, type = "DiscussionNote", body = "approved this merge request"))
        assertEquals("COMMENT", note.action, "only SYSTEM notes are classified by their text")
        assertTrue(note.targets.isEmpty())
    }

    @Test
    fun `review-request notes carry their at-usernames and no other note does`() {
        fun targets(body: String, system: Boolean = true) = summarizeNote(note(system = system, body = body)).targets
        assertEquals(listOf("alice", "bob"), targets("requested review from @alice and @bob"))
        assertEquals(listOf("alice"), targets("removed review request for @alice"))
        val punctuated = targets("requested review from @a.b, @a.b and @c-d_e.")
        assertEquals(listOf("a.b", "c-d_e"), punctuated, "distinct, in order, sentence punctuation trimmed")
        assertEquals(emptyList(), targets("requested review from nobody"))
        assertEquals(emptyList(), targets("assigned to @x"), "an OTHER_SYSTEM note names no one")
        assertEquals(emptyList(), targets("approved this merge request by @x"))
        assertEquals(emptyList(), targets("requested review from @alice", system = false), "a user's note never yields targets")
    }

    @Test
    fun `the at-username extraction is bounded in count, length and scanned text`() {
        val many = (1..60).joinToString(" ") { "@user$it" }
        assertEquals((1..MAX_TARGETS).map { "user$it" }, mentions("requested review from $many"))
        val long = mentions("@" + "n".repeat(5_000)).single()
        assertEquals(MAX_TARGET_CHARS, long.length)
        // A hostile body of a million tokens costs a bounded scan: only the first 4 KiB is looked at.
        val flood = mentions("@a ".repeat(1_000_000))
        assertEquals(listOf("a"), flood)
        assertEquals(emptyList(), mentions(" ".repeat(10_000) + "@late"), "a mention beyond the scanned prefix is not seen")
    }

    @Test
    fun `a note summary has no body and carries id, author, time, system flag, type and action`() {
        assertTrue(GitLabNoteSummary::class.java.declaredFields.none { "body" in it.name.lowercase() })
        assertTrue(GitLabNoteSummary::class.java.declaredMethods.none { "body" in it.name.lowercase() })
        val summary = summarizeNote(note(system = true, id = 9, body = "approved this merge request", authorId = 4))
        assertEquals(9L, summary.id)
        assertEquals(4L, summary.authorId)
        assertEquals("APPROVAL", summary.action)
        assertTrue(summary.system)
        assertEquals(1_788_175_674_000L, summary.createdAt)
        assertNull(summary.noteType)
    }

    @Test
    fun `a note without a numeric id or a readable time is INVALID_RESPONSE`() {
        fun invalid(note: JsonObject) = assertFailsWith<FetchException> { summarizeNote(note) }.code
        assertEquals("INVALID_RESPONSE", invalid(buildJsonObject { put("created_at", "2026-08-31T11:27:54Z") }))
        assertEquals("INVALID_RESPONSE", invalid(buildJsonObject { put("id", "9"); put("created_at", "2026-08-31T11:27:54Z") }))
        assertEquals("INVALID_RESPONSE", invalid(buildJsonObject { put("id", 9) }))
        assertEquals("INVALID_RESPONSE", invalid(buildJsonObject { put("id", 9); put("created_at", "yesterday") }))
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { summarizeNote(JsonPrimitive("x")) }.code)
        // A missing `system` is a user note; a long type is cut to the column.
        val odd = summarizeNote(buildJsonObject { put("id", 9); put("created_at", "2026-08-31T11:27:54Z"); put("type", "T".repeat(100)) })
        assertEquals(false, odd.system)
        assertEquals(30, odd.noteType?.length)
    }

    private fun note(system: Boolean, id: Long = 1, body: String = "", type: String? = null, authorId: Long? = null): JsonObject =
        Json.parseToJsonElement(
            buildJsonObject {
                put("id", id)
                put("system", system)
                put("body", body)
                put("created_at", "2026-08-31T11:27:54Z")
                if (type != null) put("type", type)
                if (authorId != null) put("author", buildJsonObject { put("id", authorId) })
            }.toString(),
        ) as JsonObject
}
