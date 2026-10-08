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
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraReferenceStream::class.java)

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

/**
 * Fixed step order (v0.2.0 plan §12 item 6) — doubles as `raw.jira_entities.kind`. PRIORITY is not a step: its
 * endpoint has no granular OAuth scope (`jira-integration.md`), and nothing reads the raw rows. A resumed cursor
 * naming PRIORITY finds no index and restarts the pass at step 0, which is idempotent.
 */
private val STEP_ORDER: List<JiraEntityKind> = JiraEntityKind.entries.filter { it != JiraEntityKind.PRIORITY }

private const val SCRUM_BOARD_TYPE = "scrum"

/**
 * The REFERENCE stream's board scope rule, shared with `JiraConnector.testConnection` so its board probes seed from a
 * board the SYNC would actually fetch: a board is in scope when its `location.projectKey` is one of [projectKeys].
 */
internal fun isInScopeBoard(board: JsonObject, projectKeys: List<String>): Boolean =
    board["location"]?.jsonObject?.get("projectKey")?.jsonPrimitive?.contentOrNull in projectKeys

/** Only scrum boards carry sprints (Jira's `/board/{id}/sprint` answers 400 for a Kanban board). */
internal fun isScrumBoard(board: JsonObject): Boolean = board["type"]?.jsonPrimitive?.contentOrNull == SCRUM_BOARD_TYPE

/**
 * The REFERENCE stream (v0.2.0 plan §7): one full, resumable pass over every kind
 * [JiraEntityKind] names, hash-diffing each entity (`JiraRawStore.upsertEntity`). Boards are
 * scoped to [projectKeys] (the stub has no board-side project filter, so this fetches every board
 * and filters — `sample-data/README.md`'s "Gateway shape"/paging notes); board sprints are fetched
 * only for scrum boards (Jira's own `/board/{id}/sprint` 400s for a Kanban board). Entities not
 * seen during the pass are tombstoned at the end and resurrected the next time they reappear. The sweep covers
 * every [JiraEntityKind], the no-longer-fetched PRIORITY included, so a connection's old PRIORITY rows are
 * tombstoned on its next pass (intended). `PROJECT_FIELDS` is the one OPTIONAL step ([runProjectFields]): it skips a project
 * rather than failing the pass, and keeps that project's previous entity out of the sweep.
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
            runStep(context, passStartedAt, step, startAt)
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

    private suspend fun runStep(context: StreamContext, passStartedAt: Long, step: JiraEntityKind, startAt: Int) {
        when (step) {
            JiraEntityKind.FIELD -> runSingleShot(context, step) { client.fields() }
            JiraEntityKind.STATUS_CATEGORY -> runSingleShot(context, step) { client.statusCategories() }
            JiraEntityKind.ISSUE_TYPE -> runSingleShot(context, step) { client.issueTypes() }
            JiraEntityKind.ISSUE_LINK_TYPE -> runSingleShot(context, step) {
                client.issueLinkTypes()["issueLinkTypes"]?.jsonArray ?: JsonArray(emptyList())
            }
            JiraEntityKind.STATUS -> runStartAtPaged(context, passStartedAt, step, startAt) { client.statusesSearch(it) }
            JiraEntityKind.PROJECT -> runStartAtPaged(context, passStartedAt, step, startAt) { client.projectsSearch(it) }
            JiraEntityKind.RESOLUTION -> runStartAtPaged(context, passStartedAt, step, startAt) { client.resolutions(it) }
            JiraEntityKind.PROJECT_STATUSES -> runPerProjectKey(context, passStartedAt, startAt)
            JiraEntityKind.PROJECT_FIELDS -> runProjectFields(context, passStartedAt, startAt)
            JiraEntityKind.USER -> runUsersPaged(context, passStartedAt, startAt)
            JiraEntityKind.BOARD -> runStartAtPaged(context, passStartedAt, step, startAt, ::isInScopeBoard) { client.boards(it) }
            JiraEntityKind.BOARD_CONFIGURATION -> runBoardConfigurations(context, passStartedAt, startAt)
            JiraEntityKind.SPRINT -> runSprints(context, passStartedAt, startAt)
            JiraEntityKind.PRIORITY -> error("PRIORITY is never in STEP_ORDER")
        }
    }

    private fun isInScopeBoard(board: JsonObject): Boolean = isInScopeBoard(board, projectKeys)

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

    /**
     * `PROJECT_FIELDS` — OPTIONAL by design (`GET /projects/fields` is experimental and needs `read:field-configuration:jira`).
     * Per configured project key, from the project id (the stored `PROJECT` entity) and the issue-type ids (the
     * `PROJECT_STATUSES` payload stored earlier in the pass), stores ONE entity: the sorted distinct field ids of the project's
     * field scheme plus its epic/task split ([JiraProjectFields.payload]; the hierarchy levels come from the `ISSUE_TYPE`
     * entities, which the fixed step order stores just before this step). A project whose inputs are missing, whose call
     * fails with ANY [JiraFetchException] but `BLOCKED_HOST` ([skipsProjectFields]), or that yields no field at all, is
     * SKIPPED with one warn (no query text) and one `projectFieldsSkipped` progress tick: its previous entity is only
     * `touch`ed — kept live through the end-of-pass tombstone sweep — and the pass goes on. Lease loss, cancellation, a
     * blocked host and non-Jira failures
     * (database errors) propagate as in any reference step.
     */
    private suspend fun runProjectFields(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        val projectIds = JiraProjectFields.projectIdsByKey(
            rawStore.entityPayloadsByKind(context.connectionId, JiraEntityKind.PROJECT.name).map { Json.parseToJsonElement(it) },
        )
        val statusPayloads = rawStore.entityRowsByKind(context.connectionId, JiraEntityKind.PROJECT_STATUSES.name).toMap()
        val issueTypePayloads = rawStore.entityPayloadsByKind(context.connectionId, JiraEntityKind.ISSUE_TYPE.name)
        val hierarchy = JiraNormalizer.issueTypeHierarchy(issueTypePayloads)
        var index = resumeIndex
        while (index < projectKeys.size) {
            val key = projectKeys[index]
            val payload = fetchProjectFieldsPayload(key, projectIds[key], statusPayloads[key], hierarchy)
            context.transaction {
                if (payload != null) {
                    rawStore.upsertEntity(context.connectionId, JiraEntityKind.PROJECT_FIELDS.name, key, payload, context.clock())
                    context.incrementProgress("entities")
                    context.incrementProgress("pages")
                } else {
                    rawStore.touchEntity(context.connectionId, JiraEntityKind.PROJECT_FIELDS.name, key, context.clock())
                    context.incrementProgress("projectFieldsSkipped")
                }
                context.putCursor(name, encode(ReferenceCursor(passStartedAt, JiraEntityKind.PROJECT_FIELDS, startAt = index + 1)))
            }
            context.heartbeat()
            index++
        }
    }

    /** The `PROJECT_FIELDS` payload for [key], or `null` when this project's step is skipped (see [runProjectFields]). */
    private suspend fun fetchProjectFieldsPayload(
        key: String,
        projectId: Long?,
        statusesPayload: String?,
        hierarchy: Map<String, Int>,
    ): String? {
        val workTypeIds = try {
            statusesPayload?.let { JiraProjectFields.issueTypeIds(Json.parseToJsonElement(it).jsonArray) }.orEmpty()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        if (projectId == null || workTypeIds.isEmpty()) {
            log.warn("PROJECT_FIELDS skipped for project {}: no project id or issue types are stored yet", key)
            return null
        }
        val scheme = try {
            JiraProjectFields.fetchScheme(client, projectId, workTypeIds)
        } catch (failure: JiraFetchException) {
            if (!skipsProjectFields(failure)) throw failure
            log.warn("PROJECT_FIELDS skipped for project {}: {} (status {})", key, failure.code, failure.status)
            return null
        }
        if (scheme.fieldIds.isEmpty()) {
            log.warn("PROJECT_FIELDS skipped for project {}: the response listed no fields", key)
            return null
        }
        return JiraProjectFields.payload(key, projectId, scheme, hierarchy)
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
        val scrumBoards = fetchInScopeBoards(context).filter(::isScrumBoard)
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
