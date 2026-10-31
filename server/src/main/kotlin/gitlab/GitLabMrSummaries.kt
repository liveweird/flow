package ch.nokillswit.gitlab

import ch.nokillswit.fetch.FetchException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.format.DateTimeParseException

// The slices of GitLab's merge-request answers the MERGE REQUESTS and MR DETAILS streams read, each reduced to small values INSIDE the
// response transform (`HttpGitLabClient.getPaged`'s convert / `requestAs`'s transform): the parsed tree is garbage before the caller makes
// its next call. A missing or odd field is INVALID_RESPONSE for the page, or an explicit null - never a silent zero.

/** One page of a group's merge requests with the paging fields of [GitLabPage]. */
class GitLabMergeRequestPage(val mergeRequests: List<RawGitLabMergeRequest>, val nextPage: Int?, val total: Long?)

/** One entry of `/reviewers`: the reviewer's user id, GitLab's review [state] as sent (`""` when absent) and when they were added. */
class GitLabReviewerSummary(val userId: Long, val state: String, val createdAt: Long?)

private const val MR_ENDPOINT = "/groups/{id}/merge_requests"

/**
 * The keys of a stored merge request's slim payload (the privacy whitelist, `GitLabMrStoreTest` and `GitLabClientTest` pin it): no
 * description, labels, URLs, names, merge status or time stats. The four user references and the two user lists are reduced to `{id}`.
 */
internal val MR_KEYS: List<String> = listOf(
    "id", "iid", "project_id", "source_project_id", "target_project_id", "title", "state", "draft", "work_in_progress", "created_at",
    "updated_at", "merged_at", "closed_at", "source_branch", "target_branch", "sha", "merge_commit_sha", "squash_commit_sha",
    "user_notes_count", "author", "merge_user", "merged_by", "closed_by", "assignees", "reviewers",
)

private val USER_REFERENCE_KEYS = listOf("author", "merge_user", "merged_by", "closed_by")
private val USER_LIST_KEYS = listOf("assignees", "reviewers")
private val SHA_KEYS = setOf("sha", "merge_commit_sha", "squash_commit_sha")
private val TEXT_CUTS = mapOf(
    "title" to GitLabRawStore.MAX_NAME,
    "state" to GitLabRawStore.MAX_STATE,
    "source_branch" to GitLabRawStore.MAX_BRANCH,
    "target_branch" to GitLabRawStore.MAX_BRANCH,
)

private fun invalid(detail: String) = FetchException(GITLAB_SOURCE, "INVALID_RESPONSE", null, MR_ENDPOINT, detail)

/** A numeric member (a JSON number, never a numeric string), or null when missing, null or anything else. */
internal fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun idObject(id: Long) = JsonObject(mapOf("id" to JsonPrimitive(id)))

private fun JsonObject.userId(key: String): Long? = (get(key) as? JsonObject)?.number("id")

private fun JsonObject.idList(key: String): List<Long> = when (val list = get(key)) {
    null, is JsonNull -> emptyList()
    is JsonArray -> list.map { (it as? JsonObject)?.number("id") ?: throw invalid("a $key entry without a numeric id") }.distinct().sorted()
    else -> throw invalid("$key is not a list")
}

private fun JsonObject.requiredNumber(key: String): Long = number(key) ?: throw invalid("a merge request without a numeric $key")

private fun JsonObject.requiredMillis(key: String): Long = millisOf(text(key) ?: throw invalid("a merge request without $key"), key)

private fun JsonObject.optionalMillis(key: String): Long? = text(key)?.let { millisOf(it, key) }

private fun millisOf(text: String, key: String): Long = try {
    parseGitLabInstantMillis(text)
} catch (_: DateTimeParseException) {
    throw invalid("a merge request with an unreadable $key")
}

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

/**
 * The merge request document reduced to [MR_KEYS]: a null member is dropped, `title` and the branch names are cut (NUL-replaced, ≤255
 * code points; `state` ≤30), a SHA is kept only when it is a full commit id, and every user reference is `{id}` (the user's name,
 * avatar and URLs never enter the payload that is hashed and stored). The user lists are sorted by id, so a reordering by GitLab moves
 * no hash.
 */
internal fun slimMergeRequest(mr: JsonObject): JsonObject {
    val members = LinkedHashMap<String, JsonElement>()
    for (key in MR_KEYS) {
        val value = mr[key]?.takeUnless { it is JsonNull } ?: continue
        when (key) {
            in USER_REFERENCE_KEYS -> (value as? JsonObject)?.number("id")?.let { members[key] = idObject(it) }
            in USER_LIST_KEYS -> members[key] = JsonArray(mr.idList(key).map(::idObject))
            in SHA_KEYS -> mr.text(key)?.takeIf { SHA_PATTERN.matches(it) }?.let { members[key] = JsonPrimitive(it) }
            in TEXT_CUTS -> mr.text(key)?.let { members[key] = JsonPrimitive(cutText(it, TEXT_CUTS.getValue(key))) }
            // A scalar only: a nested object or list under a key we expect to be one must never be copied into the payload.
            else -> if (value is JsonPrimitive) members[key] = value
        }
    }
    return JsonObject(members)
}

/** One group-listing item as [RawGitLabMergeRequest] (see `GitLabClient.groupMergeRequests` for the rules). */
internal fun summarizeMergeRequest(item: JsonElement): RawGitLabMergeRequest {
    val mr = item as? JsonObject ?: throw invalid("a merge request that is not an object")
    return RawGitLabMergeRequest(
        mrId = mr.requiredNumber("id"),
        projectId = mr.requiredNumber("project_id"),
        iid = mr.requiredNumber("iid"),
        state = mr.text("state") ?: throw invalid("a merge request without a state"),
        draft = mr.flag("draft") ?: mr.flag("work_in_progress") ?: false,
        authorId = mr.userId("author"),
        mergeUserId = mr.userId("merge_user") ?: mr.userId("merged_by"),
        createdAt = mr.requiredMillis("created_at"),
        updatedAt = mr.requiredMillis("updated_at"),
        mergedAt = mr.optionalMillis("merged_at"),
        closedAt = mr.optionalMillis("closed_at"),
        sourceBranch = mr.text("source_branch").orEmpty(),
        targetBranch = mr.text("target_branch").orEmpty(),
        title = mr.text("title").orEmpty(),
        headSha = mr.text("sha")?.takeIf { SHA_PATTERN.matches(it) },
        mergeCommitSha = mr.text("merge_commit_sha")?.takeIf { SHA_PATTERN.matches(it) },
        squashCommitSha = mr.text("squash_commit_sha")?.takeIf { SHA_PATTERN.matches(it) },
        reviewerIds = mr.idList("reviewers"),
        assigneeIds = mr.idList("assignees"),
        userNotesCount = (mr["user_notes_count"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
        payload = slimMergeRequest(mr),
    )
}

/**
 * `/reviewers` entries as [GitLabReviewerSummary]: an entry without a numeric `user.id` is skipped (there is no one to attribute it to),
 * a missing `state` is `""`, a missing `created_at` is null and an unreadable one INVALID_RESPONSE.
 */
internal fun summarizeReviewers(list: JsonArray): List<GitLabReviewerSummary> = list.mapNotNull { entry ->
    val reviewer = entry as? JsonObject ?: return@mapNotNull null
    val userId = reviewer.userId("user") ?: return@mapNotNull null
    val state = cutText(reviewer.text("state").orEmpty(), GitLabRawStore.MAX_ACTION)
    GitLabReviewerSummary(userId, state, reviewer.optionalMillis("created_at"))
}
