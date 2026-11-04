package ch.nokillswit.gitlab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// The one GraphQL query Flow sends: a project's merge requests' `diffStatsSummary`, for up to [MR_DETAILS_BATCH] iids per call (the REST
// `/changes` is a paged diff per merge request - the thing this replaces). Every answer is a map of stats plus counters; NOTHING in a
// GraphQL answer throws: an error, partial data, a missing project or a malformed body leaves the affected merge requests out of
// [GitLabDiffStatsAnswer.stats] and the caller records a null gap. Transport failures (a `FetchException`) are the caller's to turn
// into gaps.

/** The most merge requests one GraphQL call asks for (the query's `first:` and the details batch). */
const val MR_DETAILS_BATCH = 50

/**
 * The query. Both inputs travel as VARIABLES (`$fullPath`, `$iids`), never spliced into the text, so a project path or an iid cannot
 * change the query. The text holds `diffStatsSummary` and `project(fullPath: $fullPath)`, which the stub's matchers read.
 */
const val MR_DIFF_STATS_QUERY: String =
    "query MrDiffStats(\$fullPath: ID!, \$iids: [String!]) { project(fullPath: \$fullPath) { " +
        "mergeRequests(iids: \$iids, first: $MR_DETAILS_BATCH) { nodes { iid diffStatsSummary { additions deletions fileCount } } " +
        "pageInfo { hasNextPage } } } }"

/**
 * What a diff-stats call learned: the [stats] by iid for the merge requests GitLab answered with a complete summary; [errors] = the size of
 * the top-level `errors` array; [projectMissing] = `data.project` was null (no such project for this token); [firstErrorCode] = the first
 * error's `extensions.code` when it is a short identifier (it goes into one log line; a message is never kept). Every requested iid absent
 * from [stats] is a gap.
 */
class GitLabDiffStatsAnswer(
    val stats: Map<Long, GitLabDiffStats>,
    val errors: Int,
    val projectMissing: Boolean,
    val firstErrorCode: String? = null,
)

/** The request body: the fixed [MR_DIFF_STATS_QUERY] with `fullPath` and the iids as strings (GraphQL's `iid` type) in `variables`. */
fun diffStatsRequest(fullPath: String, iids: List<Long>): JsonObject = JsonObject(
    mapOf(
        "query" to JsonPrimitive(MR_DIFF_STATS_QUERY),
        "variables" to JsonObject(
            mapOf("fullPath" to JsonPrimitive(fullPath), "iids" to JsonArray(iids.map { JsonPrimitive(it.toString()) })),
        ),
    ),
)

private val ERROR_CODE = Regex("^[A-Za-z0-9_]{1,40}$")

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.count(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 }

/** The stats of one `nodes` entry, or null when its iid is not one that was asked for or its summary is not three counts. */
private fun statsOf(node: JsonElement, asked: Set<Long>): Pair<Long, GitLabDiffStats>? {
    val entry = node as? JsonObject
    val iid = (entry?.get("iid") as? JsonPrimitive)?.takeIf { it.isString }?.content?.toLongOrNull()?.takeIf { it in asked }
    val summary = entry?.obj("diffStatsSummary")
    val additions = summary?.count("additions")
    val deletions = summary?.count("deletions")
    val fileCount = summary?.count("fileCount")
    return if (iid == null || additions == null || deletions == null || fileCount == null) {
        null
    } else {
        iid to GitLabDiffStats(additions, deletions, fileCount)
    }
}

/** The stats of a present project; an unexpected shape - or a next page, an anomaly since `first` equals the batch - is none at all. */
private fun projectStats(project: JsonObject, asked: Set<Long>): Map<Long, GitLabDiffStats> {
    val requests = project.obj("mergeRequests")
    val hasNextPage = (requests?.obj("pageInfo")?.get("hasNextPage") as? JsonPrimitive)?.booleanOrNull == true
    val nodes = requests?.get("nodes") as? JsonArray
    val stats = LinkedHashMap<Long, GitLabDiffStats>()
    if (nodes != null && !hasNextPage) for (node in nodes) statsOf(node, asked)?.let { (iid, found) -> stats.putIfAbsent(iid, found) }
    return stats
}

/** Reads a GraphQL answer to [iids]; never throws - any shape problem is an answer with fewer (or no) stats. */
fun parseDiffStatsAnswer(tree: JsonElement, iids: List<Long>): GitLabDiffStatsAnswer {
    val root = tree as? JsonObject
    val errors = root?.get("errors") as? JsonArray
    val code = errors?.firstNotNullOfOrNull { ((it as? JsonObject)?.obj("extensions")?.get("code") as? JsonPrimitive)?.content }
        ?.takeIf { ERROR_CODE.matches(it) }
    val project = root?.obj("data")?.get("project")
    val stats = if (project is JsonObject) projectStats(project, iids.toSet()) else emptyMap()
    return GitLabDiffStatsAnswer(stats, errors?.size ?: 0, project is JsonNull, code)
}

/**
 * The transport failures of the ONE GraphQL call that mean the ENDPOINT is TRANSIENTLY out ("outage" kind: slow or down): the first one
 * in a run trips the circuit breaker (`mrGraphqlBreaker`): NO further GraphQL call is made for the rest of the run, and every merge
 * request settled from then on - the batch that tripped it included - is stamped with its stats GAPPED while its REST reads go on. An
 * outage costs one GraphQL call a run and never stalls the events backlog. Everything else - a rate limit, a rejected token, a blocked
 * host - ends the run as it would for any REST call.
 */
internal val GRAPHQL_OUTAGE_CODES = setOf("TIMEOUT", "UPSTREAM_UNAVAILABLE")

/**
 * The GraphQL failures about the ANSWER, or that fail fast and are usually permanent (unreadable, too big, no such endpoint, GraphQL
 * disabled or redirected, refused to this token): a NULL GAP for that project's merge requests. One cheap call per batch, so the REST
 * events backlog never waits on a nicety that will not come.
 */
internal val GRAPHQL_GAP_CODES = setOf("INVALID_RESPONSE", "NOT_FOUND", "LIMIT_EXCEEDED", "REDIRECT", "FORBIDDEN_SCOPE")
