package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.format.DateTimeParseException

// A merge request's notes reduced to ACTION CODES inside the response transform (`gitlab-integration.md` decision 9, plan L10): the body
// is read by [systemNoteAction] / [mentions] and by nothing else, and [GitLabNoteSummary] has no member that could hold it.

/** The action codes of a user's own note. */
const val NOTE_ACTION_COMMENT = "COMMENT"
const val NOTE_ACTION_DIFF_COMMENT = "DIFF_COMMENT"

/** The action code of a system note whose text matches none of [SYSTEM_NOTE_RULES]. */
const val NOTE_ACTION_OTHER_SYSTEM = "OTHER_SYSTEM"

private const val NOTES_ENDPOINT = "/projects/{id}/merge_requests/{iid}/notes"

/** Only this much of a system note's text is ever examined for its action (the longest prefix rule is ~45 characters). */
private const val CLASSIFIED_PREFIX_CHARS = 200

/** Only this much of a review-request note is scanned for `@username` tokens. */
private const val MENTION_SCAN_CHARS = 4_096

/**
 * One note reduced to small values: [action] is a code, never text; [targets] are the `@usernames` of a review-request SYSTEM note and
 * empty for every other note; there is NO body member (compile-time privacy, pinned by reflection in `GitLabMrNotesTest`).
 */
class GitLabNoteSummary(
    val id: Long,
    val authorId: Long?,
    val createdAt: Long,
    val system: Boolean,
    val noteType: String?,
    val action: String,
    val targets: List<String>,
)

/** One page of a merge request's notes with the paging fields of [GitLabPage] (no total: nothing reads it). */
class GitLabNotePage(val notes: List<GitLabNoteSummary>, val nextPage: Int?)

/**
 * The system-note rules in order, each a case-insensitive text PREFIX (after trim) that must end at a word boundary - `merged` is
 * `MERGED`, `mergedevil` is not. The first match wins; the texts are GitLab's own (`added 3 commits` carries a list after it).
 */
private val SYSTEM_NOTE_RULES: List<Pair<Regex, String>> = listOf(
    "approved this merge request" to "APPROVAL",
    "unapproved this merge request" to "UNAPPROVAL",
    "requested changes" to "CHANGES_REQUESTED",
    "requested review from" to MR_ACTION_REVIEW_REQUESTED,
    "removed review request for" to MR_ACTION_REVIEW_REQUEST_REMOVED,
    "added [0-9]+ commits?" to "COMMITS_ADDED",
    "merged" to "MERGED",
    "closed" to "CLOSED",
    "reopened" to "REOPENED",
    "marked this merge request as \\*\\*draft\\*\\*" to "DRAFT",
    "marked as a \\*\\*work in progress\\*\\*" to "DRAFT",
    "marked this merge request as \\*\\*ready\\*\\*" to "READY",
    "unmarked as a \\*\\*work in progress\\*\\*" to "READY",
).map { (prefix, action) -> Regex("^$prefix(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE) to action }

/** The `@username` token: GitLab usernames are letters, digits, `_`, `.` and `-`. */
private val MENTION = Regex("@([A-Za-z0-9_.-]+)")

/** The action code of a SYSTEM note by its [body]; text that matches no rule - or is absent - is [NOTE_ACTION_OTHER_SYSTEM]. */
internal fun systemNoteAction(body: String?): String {
    val text = body?.trim()?.take(CLASSIFIED_PREFIX_CHARS) ?: return NOTE_ACTION_OTHER_SYSTEM
    return SYSTEM_NOTE_RULES.firstOrNull { (rule, _) -> rule.containsMatchIn(text) }?.second ?: NOTE_ACTION_OTHER_SYSTEM
}

/** The action code of a user's own note: a `DiffNote` is a comment on code, anything else (`DiscussionNote`, null, other) a comment. */
internal fun userNoteAction(noteType: String?): String = if (noteType == "DiffNote") NOTE_ACTION_DIFF_COMMENT else NOTE_ACTION_COMMENT

/**
 * The distinct `@usernames` of [body] in order of appearance, at most [MAX_TARGETS], each at most [MAX_TARGET_CHARS] characters, a trailing
 * `.`/`-` (sentence punctuation) dropped. Scans at most [MENTION_SCAN_CHARS] of the text, so a hostile body costs a bounded scan.
 */
internal fun mentions(body: String): List<String> =
    MENTION.findAll(body.take(MENTION_SCAN_CHARS)).map { it.groupValues[1].trimEnd('.', '-').take(MAX_TARGET_CHARS) }
        .filter { it.isNotEmpty() }.distinct().take(MAX_TARGETS).toList()

private fun invalid(detail: String) = FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, NOTES_ENDPOINT, detail)

/**
 * One note of `/merge_requests/:iid/notes` as a [GitLabNoteSummary]. `id` and a readable `created_at` are required (INVALID_RESPONSE
 * otherwise); a missing `system` is false. The `body` is read here ([systemNoteAction] for a system note, [mentions] for a review
 * request) and not kept - a user's own note body is not even read.
 */
internal fun summarizeNote(item: JsonElement): GitLabNoteSummary {
    val note = item as? JsonObject ?: throw invalid("a note that is not an object")
    val id = note.number("id") ?: throw invalid("a note without a numeric id")
    val created = note.text("created_at") ?: throw invalid("a note without created_at")
    val createdAt = try {
        parseGitLabInstantMillis(created)
    } catch (_: DateTimeParseException) {
        throw invalid("a note with an unreadable created_at")
    }
    val system = (note["system"] as? JsonPrimitive)?.booleanOrNull ?: false
    val noteType = note.text("type")?.let { cutText(it, GitLabRawStore.MAX_NOTE_TYPE) }
    val action = if (system) systemNoteAction(note.text("body")) else userNoteAction(noteType)
    return GitLabNoteSummary(
        id = id,
        authorId = (note["author"] as? JsonObject)?.number("id"),
        createdAt = createdAt,
        system = system,
        noteType = noteType,
        action = action,
        targets = if (system && action in REVIEW_REQUEST_ACTIONS) mentions(note.text("body").orEmpty()) else emptyList(),
    )
}
