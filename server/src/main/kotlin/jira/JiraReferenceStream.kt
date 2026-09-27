package ch.nokillswit.jira

import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val REFERENCE_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** `sync_cursors.cursor` shape for the REFERENCE stream (v0.2.0 plan §7): resumable mid-pass. */
@Serializable
internal data class ReferenceCursor(
    val passStartedAt: Long,
    val step: JiraEntityKind,
    /** The board currently being processed by BOARD_CONFIGURATION/SPRINT — diagnostic; resume itself keys off [startAt]. */
    val boardId: Long? = null,
    /** A real `startAt` for the startAt-paged steps, or an index into the in-scope key/board list for the per-item ones. */
    val startAt: Int = 0,
)

/** Fixed step order (v0.2.0 plan §12 item 6) — doubles as `raw.jira_entities.kind`. */
private val STEP_ORDER: List<JiraEntityKind> = JiraEntityKind.entries.toList()

private const val SCRUM_BOARD_TYPE = "scrum"

/**
 * The REFERENCE stream (v0.2.0 plan §7): one full, resumable pass over every kind
 * [JiraEntityKind] names, hash-diffing each entity (`JiraRawStore.upsertEntity`). Boards are
 * scoped to [projectKeys] (the stub has no board-side project filter, so this fetches every board
 * and filters — `sample-data/README.md`'s "Gateway shape"/paging notes); board sprints are fetched
 * only for scrum boards (Jira's own `/board/{id}/sprint` 400s for a Kanban board). Entities not
 * seen during the pass are tombstoned at the end and resurrected the next time they reappear.
 */
class JiraReferenceStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val projectKeys: List<String>,
) : Stream {
    override val name: String = "reference"

    override suspend fun run(context: StreamContext) {
        val existing = context.cursor(name)?.cursor?.let { REFERENCE_CURSOR_JSON.decodeFromString<ReferenceCursor>(it) }
        val passStartedAt = existing?.passStartedAt ?: context.clock()
        val resumedStepIndex = existing?.let { STEP_ORDER.indexOf(it.step) }?.takeIf { it >= 0 } ?: 0
        var stepIndex = resumedStepIndex

        while (stepIndex < STEP_ORDER.size) {
            val step = STEP_ORDER[stepIndex]
            val startAt = if (stepIndex == resumedStepIndex) existing?.startAt ?: 0 else 0
            when (step) {
                JiraEntityKind.FIELD -> runSingleShot(context, step) { client.fields() }
                JiraEntityKind.STATUS_CATEGORY -> runSingleShot(context, step) { client.statusCategories() }
                JiraEntityKind.ISSUE_TYPE -> runSingleShot(context, step) { client.issueTypes() }
                JiraEntityKind.ISSUE_LINK_TYPE -> runSingleShot(context, step) {
                    client.issueLinkTypes()["issueLinkTypes"]?.jsonArray ?: JsonArray(emptyList())
                }
                JiraEntityKind.STATUS -> runStartAtPaged(context, passStartedAt, step, startAt) { client.statusesSearch(it) }
                JiraEntityKind.PROJECT -> runStartAtPaged(context, passStartedAt, step, startAt) { client.projectsSearch(it) }
                JiraEntityKind.PRIORITY -> runStartAtPaged(context, passStartedAt, step, startAt) { client.priorities(it) }
                JiraEntityKind.RESOLUTION -> runStartAtPaged(context, passStartedAt, step, startAt) { client.resolutions(it) }
                JiraEntityKind.PROJECT_STATUSES -> runPerProjectKey(context, passStartedAt, startAt)
                JiraEntityKind.USER -> runUsersPaged(context, passStartedAt, startAt)
                JiraEntityKind.BOARD -> runStartAtPaged(context, passStartedAt, step, startAt, ::isInScopeBoard) { client.boards(it) }
                JiraEntityKind.BOARD_CONFIGURATION -> runBoardConfigurations(context, passStartedAt, startAt)
                JiraEntityKind.SPRINT -> runSprints(context, passStartedAt, startAt)
            }
            stepIndex++
            if (stepIndex < STEP_ORDER.size) {
                context.transaction { context.putCursor(name, encode(ReferenceCursor(passStartedAt, STEP_ORDER[stepIndex]))) }
            }
        }

        context.transaction {
            JiraEntityKind.entries.forEach { rawStore.markEntitiesDeletedNotSeenSince(context.connectionId, it.name, passStartedAt) }
            context.clearCursor(name)
        }
    }

    private fun isInScopeBoard(board: JsonObject): Boolean =
        board["location"]?.jsonObject?.get("projectKey")?.jsonPrimitive?.contentOrNull in projectKeys

    private suspend fun runSingleShot(context: StreamContext, step: JiraEntityKind, fetch: suspend () -> JsonArray) {
        val elements = fetch()
        context.transaction { storeEntities(context, step, elements) }
        context.heartbeat()
    }

    private suspend fun runStartAtPaged(
        context: StreamContext,
        passStartedAt: Long,
        step: JiraEntityKind,
        resumeStartAt: Int,
        filter: (JsonObject) -> Boolean = { true },
        fetch: suspend (Int) -> JiraStartAtPage,
    ) {
        var startAt = resumeStartAt
        while (true) {
            val page = fetch(startAt)
            val toStore = page.values.filter { filter(it.jsonObject) }
            val consumed = page.values.size
            val nextStartAt = startAt + consumed
            val isLast = page.isLast == true || nextStartAt >= page.total || consumed == 0
            context.transaction {
                storeEntities(context, step, JsonArray(toStore))
                context.putCursor(name, encode(ReferenceCursor(passStartedAt, step, startAt = nextStartAt)))
            }
            context.heartbeat()
            if (isLast) break
            startAt = nextStartAt
        }
    }

    /** `users/search` is a raw array with no `total` — last page detected once a page is shorter than the FIRST page seen. */
    private suspend fun runUsersPaged(context: StreamContext, passStartedAt: Long, resumeStartAt: Int) {
        var startAt = resumeStartAt
        var firstPageSize: Int? = null
        while (true) {
            val page = client.usersSearch(startAt)
            val seenFirstPageSize = firstPageSize
            val isLast = page.isEmpty() || (seenFirstPageSize != null && page.size < seenFirstPageSize)
            if (firstPageSize == null) firstPageSize = page.size
            val nextStartAt = startAt + page.size
            context.transaction {
                storeEntities(context, JiraEntityKind.USER, page) { it.getValue("accountId").jsonPrimitive.content }
                context.putCursor(name, encode(ReferenceCursor(passStartedAt, JiraEntityKind.USER, startAt = nextStartAt)))
            }
            context.heartbeat()
            if (isLast) break
            startAt = nextStartAt
        }
    }

    private suspend fun runPerProjectKey(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        var index = resumeIndex
        while (index < projectKeys.size) {
            val key = projectKeys[index]
            val statuses = client.projectStatuses(key)
            context.transaction {
                rawStore.upsertEntity(context.connectionId, JiraEntityKind.PROJECT_STATUSES.name, key, statuses.toString(), context.clock())
                context.putCursor(name, encode(ReferenceCursor(passStartedAt, JiraEntityKind.PROJECT_STATUSES, startAt = index + 1)))
                context.incrementProgress("entities")
                context.incrementProgress("pages")
            }
            context.heartbeat()
            index++
        }
    }

    private suspend fun runBoardConfigurations(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        val boards = fetchInScopeBoards(context)
        var index = resumeIndex
        while (index < boards.size) {
            val boardId = boards[index].getValue("id").jsonPrimitive.content.toLong()
            val body = client.boardConfiguration(boardId)
            context.transaction {
                rawStore.upsertEntity(
                    context.connectionId, JiraEntityKind.BOARD_CONFIGURATION.name, boardId.toString(), body.toString(), context.clock(),
                )
                context.putCursor(
                    name,
                    encode(ReferenceCursor(passStartedAt, JiraEntityKind.BOARD_CONFIGURATION, boardId = boardId, startAt = index + 1)),
                )
                context.incrementProgress("entities")
                context.incrementProgress("pages")
            }
            context.heartbeat()
            index++
        }
    }

    /**
     * Only scrum boards carry sprints (Kanban boards have none in real Jira) — a mid-board
     * interruption restarts THAT board's own paging from 0 (idempotent).
     */
    private suspend fun runSprints(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        val scrumBoards = fetchInScopeBoards(context).filter { it["type"]?.jsonPrimitive?.contentOrNull == SCRUM_BOARD_TYPE }
        var index = resumeIndex
        while (index < scrumBoards.size) {
            val boardId = scrumBoards[index].getValue("id").jsonPrimitive.content.toLong()
            var sprintStartAt = 0
            while (true) {
                val page = client.boardSprints(boardId, sprintStartAt)
                val consumed = page.values.size
                val nextSprintStartAt = sprintStartAt + consumed
                val isLast = page.isLast == true || nextSprintStartAt >= page.total || consumed == 0
                context.transaction {
                    storeEntities(context, JiraEntityKind.SPRINT, page.values)
                    context.putCursor(
                        name,
                        encode(ReferenceCursor(passStartedAt, JiraEntityKind.SPRINT, boardId = boardId, startAt = index)),
                    )
                }
                context.heartbeat()
                if (isLast) break
                sprintStartAt = nextSprintStartAt
            }
            index++
        }
    }

    /** A small, idempotent, unpaginated-in-effect helper (boards are always few) — no cursor of its own, safe to redo on resume. */
    private suspend fun fetchInScopeBoards(context: StreamContext): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var startAt = 0
        while (true) {
            val page = client.boards(startAt)
            result += page.values.map { it.jsonObject }.filter(::isInScopeBoard)
            val consumed = page.values.size
            val nextStartAt = startAt + consumed
            context.heartbeat()
            if (page.isLast == true || nextStartAt >= page.total || consumed == 0) break
            startAt = nextStartAt
        }
        return result
    }

    private suspend fun storeEntities(
        context: StreamContext,
        step: JiraEntityKind,
        elements: JsonArray,
        idOf: (JsonObject) -> String = { it.getValue("id").jsonPrimitive.content },
    ) {
        elements.forEach { element ->
            val obj = element.jsonObject
            rawStore.upsertEntity(context.connectionId, step.name, idOf(obj), element.toString(), context.clock())
        }
        context.incrementProgress("entities", elements.size.toLong())
        context.incrementProgress("pages")
    }

    private fun encode(cursor: ReferenceCursor): String = REFERENCE_CURSOR_JSON.encodeToString(cursor)
}
