package ch.nokillswit.norm

import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.validation.canonicalEmail

/**
 * The connector-agnostic code-layer vocabulary and kernel (`gitlab-integration.md` "PROCESS / PROFILE"; the V44 `norm.code_*` tables,
 * `norm/CodeActivityStore.kt`). `gitlab/GitLabNormalizer.kt` is the one producer today; nothing here knows a GitLab code string.
 * This file holds the codes the store compiles against; the pure kernel (identity keys, the counted-lines rule, the event order)
 * joins them.
 */

/**
 * Bump on ANY change to the code layer's PROCESS rules (the identity keys, the counted-lines rule, the event kinds and order, the merge
 * request row) - `raw.gitlab_commits` / `raw.gitlab_merge_requests` `processing_version IS DISTINCT FROM` this reprocesses every row on
 * the next PROCESS pass, with no migration. Deliberately a separate constant from Jira's [PROCESSING_VERSION]: neither side reprocesses
 * the other.
 *
 * `1` (PR 10): the first code layer.
 * `2`: the merge requests' issue-key candidates (`norm.code_mr_issue_keys`, V46; `extractIssueKeyCandidates`). Commits re-process too,
 * to the same rows.
 */
const val CODE_PROCESSING_VERSION = 2

/** What an identity key stands for (`norm.code_identities.kind`, Kotlin-enum-whitelisted, no CHECK). */
enum class IdentityKind {
    /** `u<id>`: a GitLab user (listed, or a ghost named by a fact but absent from the users listing). */
    USER,

    /** `e<hash>`: a commit author's address that matches no GitLab user. */
    EMAIL,
}

/** The review-event kinds a merge request carries (`norm.code_mr_events.kind`, Kotlin-enum-whitelisted, no CHECK). */
enum class MrEventKind { COMMENT, DIFF_COMMENT, APPROVAL, REVIEW_REQUESTED, REVIEWED, CHANGES_REQUESTED }

/**
 * Whether a merge request's events are current (`norm.code_merge_requests.details_state`): [READ] a complete details read, [PENDING] the
 * read is stale (the event set is empty or old), [UNREADABLE] a terminal details error. A report reads the state, never "no events".
 */
enum class DetailsState { READ, PENDING, UNREADABLE }

/** The identity key of a GitLab user id: `u<id>`. */
fun identityKeyForUser(id: Long): String = "u$id"

/**
 * The identity key of a commit author address that matches no GitLab user: `e` + the first 32 hex characters of the sha256 of the
 * [canonicalEmail] form. The key holds no address (decision 10); two spellings of one mailbox hash alike.
 */
fun identityKeyForEmail(email: String): String = "e" + sha256Hex(canonicalEmail(email)).take(EMAIL_KEY_HASH_CHARS)

private const val EMAIL_KEY_HASH_CHARS = 32

/** GitLab's private commit address, `<user id>-<username>@users.noreply.<host>`: the user id is in the local part (decision 10). */
val NOREPLY_USER_ID = Regex("""^(\d+)-[^@]+@users\.noreply\.""")

/** The GitLab user id a CANONICAL [email] names through the noreply pattern, or null (a bot's `<name>@noreply.<host>` is not one). */
fun noreplyUserId(email: String): Long? = NOREPLY_USER_ID.find(email)?.groupValues?.get(1)?.toLongOrNull()

/** One stored file of a commit: its path (GitLab's `new_path`, never `old_path`) and its lines; null = UNKNOWN (too large, collapsed). */
class CountedFile(val path: String, val additions: Int?, val deletions: Int?)

/** A commit's GitLab `stats` over ALL its files; null = the stats are missing. */
class LineStats(val additions: Int?, val deletions: Int?)

/** The counted figures: [files] included, and the lines of those files (null = UNKNOWN, never zero). */
class CountedLines(val files: Int, val additions: Int?, val deletions: Int?)

/**
 * The counted-lines rule (`gitlab-integration.md` "Path exclusions"), pure. [files] are the commit's STORED files; [excluded] says
 * whether a path matches an exclusion glob. `files` = the files not excluded. Additions and deletions are each computed
 * independently: the sum of the included files' KNOWN lines, plus - only when at least one included file has UNKNOWN lines - the remainder
 * `max(0, stats - Σ RAW lines of EVERY file with known lines, excluded ones included)`: the remainder is the unknown files' lines by
 * construction, credited once. When every unknown file is excluded the remainder is dropped; when an included unknown file exists and the
 * stats are null the figure is null. A binary file is a known 0/0.
 *
 * A [capped] commit (more files than were stored) gets NO remainder: the stats cover files that were never stored, so the remainder would
 * credit them to the stored unknown-lines files. If an included unknown-lines file exists the counted lines are null (unknown), else
 * they are the sum of the known included lines (a lower bound over the stored files).
 *
 * Worked example (the stub's `special.tooLarge` commit): stats 5269/215, a lock file 59/66 (excluded), `README` 10/9 and a too-large
 * `catalog` null/null. Raw known = 69/75, remainder 5200/140: counted = 2 files (README, catalog), 10 + 5200 = 5210
 * additions and 9 + 140 = 149 deletions. With the catalog excluded too: 1 file, 10/9.
 */
fun countLines(files: List<CountedFile>, stats: LineStats, capped: Boolean = false, excluded: (String) -> Boolean): CountedLines {
    val included = files.filterNot { excluded(it.path) }
    return CountedLines(
        files = included.size,
        additions = countDimension(files, included, stats.additions, capped) { it.additions },
        deletions = countDimension(files, included, stats.deletions, capped) { it.deletions },
    )
}

private fun countDimension(
    all: List<CountedFile>,
    included: List<CountedFile>,
    total: Int?,
    capped: Boolean,
    lines: (CountedFile) -> Int?,
): Int? {
    val includedKnown = included.sumOf { lines(it) ?: 0 }
    if (included.none { lines(it) == null }) return includedKnown
    // Capped: no remainder (the stats include files that were never stored). Otherwise missing stats leave the figure unknown.
    if (capped || total == null) return null
    val rawKnown = all.sumOf { lines(it) ?: 0 }
    return includedKnown + maxOf(0, total - rawKnown)
}

/**
 * One review event before ordering: [source] and [sourceId] are the raw row's own key parts (a deterministic last tie-break, so a REPROCESS
 * writes the same `seq`s). `gitlab/GitLabNormalizer.kt` builds these; the kernel knows no GitLab code string.
 */
class MrEventInput(
    val kind: MrEventKind,
    val actorKey: String?,
    val eventAt: Long?,
    val targetKeys: List<String>,
    val source: String,
    val sourceId: Long,
)

private val EVENT_ORDER: Comparator<MrEventInput> = compareBy<MrEventInput, Long?>(nullsLast()) { it.eventAt }
    .thenBy { it.kind.name }
    .thenBy(nullsLast()) { it.actorKey }
    .thenBy { it.source }
    .thenBy { it.sourceId }

/** Orders [events] (time ascending, nulls last; kind name; actor, null last; raw source; raw source id) and numbers them 1..n. */
fun orderEvents(events: List<MrEventInput>): List<NormCodeMrEvent> =
    events.sortedWith(EVENT_ORDER).mapIndexed { index, event ->
        NormCodeMrEvent(index + 1, event.kind, event.actorKey, event.eventAt, event.targetKeys)
    }
