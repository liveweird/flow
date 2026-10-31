package ch.nokillswit.jira

import ch.nokillswit.fetch.BoundedAnswer
import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunBudget
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraReferenceStream::class.java)

/** `sync_cursors.cursor` shape for the REFERENCE stream (v0.2.0 plan §7): resumable mid-pass. */
@Serializable
internal data class ReferenceCursor(
    val passStartedAt: Long,
    val step: JiraEntityKind,
    /** The board currently being processed by BOARD_CONFIGURATION/SPRINT — diagnostic; resume itself keys off [startAt]. */
    val boardId: Long? = null,
    /** A real `startAt` for the startAt-paged steps, or an index into the in-scope key/board list for the per-item ones. */
    val startAt: Int = 0,
    /** [REFERENCE_STEP_ORDER] when written; absent (`null`) in a cursor persisted before ISSUE_TYPE moved ahead of PROJECT_FIELDS. */
    val stepOrder: Int? = null,
    /** This pass skipped the USER step (the users were fresh, [JiraReferenceStream]): its end-of-pass sweep leaves USER alone. */
    val usersSkipped: Boolean = false,
)

/** Bumped when [STEP_ORDER] changes in a way an older cursor's step cannot resume across; [encode] stamps it on every cursor. */
internal const val REFERENCE_STEP_ORDER = 2

/**
 * Fixed step order (v0.2.0 plan §12 item 6) — doubles as `raw.jira_entities.kind`. PRIORITY is not a step: its
 * endpoint has no granular OAuth scope (`jira-integration.md`), and nothing reads the raw rows. A resumed cursor
 * naming PRIORITY finds no index and restarts the pass at step 0, which is idempotent; so does a PROJECT_FIELDS cursor
 * persisted before ISSUE_TYPE moved ahead of it (no `stepOrder`; see [JiraReferenceStream.run]).
 */
private val STEP_ORDER: List<JiraEntityKind> = JiraEntityKind.entries.filter { it != JiraEntityKind.PRIORITY }

private fun ReferenceCursor.isLegacyProjectFields() = step == JiraEntityKind.PROJECT_FIELDS && stepOrder == null

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
 * [JiraEntityKind] names, hash-diffing each entity (`JiraRawStore.upsertEntity`/`upsertEntities`). Boards are
 * scoped to [projectKeys] (the stub has no board-side project filter, so this fetches every board
 * and filters — `sample-data/README.md`'s "Gateway shape"/paging notes); board sprints are fetched
 * only for scrum boards (Jira's own `/board/{id}/sprint` 400s for a Kanban board). Entities not
 * seen during the pass are tombstoned at the end and resurrected the next time they reappear. The sweep covers
 * every [JiraEntityKind], the no-longer-fetched PRIORITY included, so a connection's old PRIORITY rows are
 * tombstoned on its next pass (intended). `PROJECT_FIELDS` is the one OPTIONAL step ([runProjectFields]): it skips a project
 * rather than failing the pass, and keeps that project's previous entity out of the sweep.
 *
 * Every loop that follows a host's paging carries a ceiling on pages AND rows ([ReferenceCeilings], `.claude/docs/jira-integration.md`
 * "Paging ceilings"). Past one the step fails `LIMIT_EXCEEDED` BEFORE the page's transaction, except PROJECT_FIELDS, whose project is
 * skipped like any failure of its optional endpoint. A failed REFERENCE step fails the whole SYNC of that connection (ISSUES and the rest
 * do not run), every run, until the scope fits: the paged steps resume from the cursor's `startAt` and the SPRINT pass from the cursor's
 * board index, but the in-scope boards ([fetchInScopeBoards]) and ONE board's sprints re-page from 0 each run, so for those the ceiling
 * is a HARD scope cap, not a resumable limit (a run that re-stores identical pages is no progress for the pause, `Stream.kt`
 * [StreamContext.ceilingTrip]).
 *
 * The USER step (`users/search`, ~47 of an incremental SYNC's ~74 calls at the ~2,300 users measured on 2026-10-24) runs only when the
 * users are older than [usersRefreshMillis] (`jira.usersRefreshHours`, default 24 h; 0 = every pass): "older" is the oldest
 * `last_seen_at` of the live
 * USER rows, which a completed pass lifts to its own start. A pass that skips it records that on its cursor (a resumed pass still
 * knows) and leaves the USER rows out of its sweep, so users it never fetched are not tombstoned. A SYNC an ADMIN requested passes 0.
 */
class JiraReferenceStream internal constructor(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val projectKeys: List<String>,
    private val usersRefreshMillis: Long,
    private val ceilings: ReferenceCeilings,
) : Stream {
    constructor(client: JiraClient, rawStore: JiraRawStore, projectKeys: List<String>, usersRefreshMillis: Long = 0) :
        this(client, rawStore, projectKeys, usersRefreshMillis, ReferenceCeilings())

    override val name: String = "reference"

    /** Whether this pass skipped the USER step; seeded from a resumed cursor and stamped on every cursor [encode] writes. */
    private var usersSkipped = false

    override suspend fun run(context: StreamContext) {
        val existing = context.cursor(name)?.cursor?.let { decodeCursor<ReferenceCursor>(it) }
        val passStartedAt = existing?.passStartedAt ?: context.clock()
        // A cursor naming PRIORITY (no longer a step) or a pre-reorder PROJECT_FIELDS (it ran BEFORE ISSUE_TYPE then, so
        // resuming there would skip ISSUE_TYPE and let the sweep tombstone every issue type) restarts the pass at step 0.
        val resumable = existing?.takeIf { it.step in STEP_ORDER && !it.isLegacyProjectFields() }
        val resumedStepIndex = resumable?.let { STEP_ORDER.indexOf(it.step) } ?: 0
        var stepIndex = resumedStepIndex
        usersSkipped = resumable?.usersSkipped ?: false

        while (stepIndex < STEP_ORDER.size) {
            val step = STEP_ORDER[stepIndex]
            val startAt = if (stepIndex == resumedStepIndex) resumable?.startAt ?: 0 else 0
            runStep(context, passStartedAt, step, startAt)
            stepIndex++
            if (stepIndex < STEP_ORDER.size) {
                context.transaction { context.putCursor(name, encode(ReferenceCursor(passStartedAt, STEP_ORDER[stepIndex]))) }
            }
        }

        context.transaction {
            JiraEntityKind.entries.filterNot { usersSkipped && it == JiraEntityKind.USER }
                .forEach { rawStore.markEntitiesDeletedNotSeenSince(context.connectionId, it.name, passStartedAt) }
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
        val changed = context.transaction { rawStore.storeEntities(context, step, elements) }
        context.heartbeat(changed)
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
        val budget = RunBudget(JIRA_SOURCE, START_AT_ENDPOINTS.getValue(step), ceilings.startAt, "entities")
        while (true) {
            budget.nextPage()
            val page = fetch(startAt)
            budget.add(page.values.size)
            val toStore = page.values.filter { filter(it.jsonObject) }
            val consumed = page.values.size
            val nextStartAt = startAt + consumed
            val isLast = page.isLast == true || nextStartAt >= page.total || consumed == 0
            val changed = context.transaction {
                rawStore.storeEntities(context, step, JsonArray(toStore)).also {
                    context.putCursor(name, encode(ReferenceCursor(passStartedAt, step, startAt = nextStartAt)))
                }
            }
            context.heartbeat(changed)
            if (isLast) break
            startAt = nextStartAt
        }
    }

    /** `users/search` is a raw array with no `total` — last page detected once a page is shorter than the FIRST page seen. */
    private suspend fun runUsersPaged(context: StreamContext, passStartedAt: Long, resumeStartAt: Int) {
        if (resumeStartAt == 0 && usersAreFresh(context)) {
            usersSkipped = true
            context.transaction {
                context.putCursor(name, encode(ReferenceCursor(passStartedAt, JiraEntityKind.USER)))
                context.incrementProgress("usersSkipped")
            }
            return
        }
        // A fetch from the start sweeps USER again, even when an attempt that skipped was interrupted right after its cursor.
        if (resumeStartAt == 0) usersSkipped = false
        var startAt = resumeStartAt
        var firstPageSize: Int? = null
        val budget = RunBudget(JIRA_SOURCE, USERS_SEARCH_ENDPOINT, ceilings.users, "users")
        while (true) {
            budget.nextPage()
            val page = client.usersSearch(startAt)
            budget.add(page.size)
            val seenFirstPageSize = firstPageSize
            val isLast = page.isEmpty() || (seenFirstPageSize != null && page.size < seenFirstPageSize)
            if (firstPageSize == null) firstPageSize = page.size
            val nextStartAt = startAt + page.size
            val changed = context.transaction {
                val idOf = { user: JsonObject -> user.requireString("accountId", USERS_SEARCH_ENDPOINT, "a user") }
                rawStore.storeEntities(context, JiraEntityKind.USER, page, idOf).also {
                    context.putCursor(name, encode(ReferenceCursor(passStartedAt, JiraEntityKind.USER, startAt = nextStartAt)))
                }
            }
            context.heartbeat(changed)
            if (isLast) break
            startAt = nextStartAt
        }
    }

    /** Every live USER row was seen within [usersRefreshMillis]; never with the refresh off (0) or no user stored yet. */
    private suspend fun usersAreFresh(context: StreamContext): Boolean {
        if (usersRefreshMillis <= 0) return false
        val oldest = rawStore.oldestLiveEntitySeenAt(context.connectionId, JiraEntityKind.USER.name) ?: return false
        return context.clock() - oldest < usersRefreshMillis
    }

    private suspend fun runPerProjectKey(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        var index = resumeIndex
        while (index < projectKeys.size) {
            val key = projectKeys[index]
            val statuses = client.projectStatuses(key)
            val outcome = context.transaction {
                val kind = JiraEntityKind.PROJECT_STATUSES
                rawStore.upsertEntity(context.connectionId, kind.name, key, statuses, context.clock()).also {
                    context.putCursor(name, encode(ReferenceCursor(passStartedAt, kind, startAt = index + 1)))
                    context.countEntityOutcome(it)
                }
            }
            context.heartbeat(outcome.isChange)
            index++
        }
    }

    /**
     * `PROJECT_FIELDS` — OPTIONAL by design (`GET /projects/fields` is experimental and needs `read:field-configuration:jira`).
     * Per configured project key, from the project id (the stored `PROJECT` entity) and the issue-type ids (the
     * `PROJECT_STATUSES` payload stored earlier in the pass), stores ONE entity: the sorted distinct field ids of the project's
     * field scheme plus its epic/task split ([JiraProjectFields.payload]; the hierarchy levels come from the `ISSUE_TYPE`
     * entities, which the fixed step order stores just before this step). A project whose inputs are missing, whose call
     * fails with ANY [FetchException] but `BLOCKED_HOST` ([skipsProjectFields]), or that yields no field at all, is
     * SKIPPED with one warn (no query text) and one `projectFieldsSkipped` progress tick: its previous entity is only
     * `touch`ed — kept live through the end-of-pass tombstone sweep — and the pass goes on. Lease loss, cancellation, a
     * blocked host and non-Jira failures (database errors) propagate as in any reference step.
     */
    private suspend fun runProjectFields(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        // The stored kinds are read in chunks and reduced to the values this step needs (`JiraProjectFields.storedInputs`), so no kind's
        // size can fail or stall the step.
        val inputs = JiraProjectFields.storedInputs(rawStore, context.connectionId)
        var index = resumeIndex
        while (index < projectKeys.size) {
            val key = projectKeys[index]
            val payload = fetchProjectFieldsPayload(key, inputs.projectIds[key], inputs.workTypeIds[key].orEmpty(), inputs.hierarchy)
            val changed = context.transaction {
                val stored = if (payload != null) {
                    val kind = JiraEntityKind.PROJECT_FIELDS.name
                    val outcome = rawStore.upsertEntity(context.connectionId, kind, key, payload, context.clock())
                    context.countEntityOutcome(outcome)
                    outcome.isChange
                } else {
                    rawStore.touchEntity(context.connectionId, JiraEntityKind.PROJECT_FIELDS.name, key, context.clock())
                    context.incrementProgress("projectFieldsSkipped")
                    false
                }
                context.putCursor(name, encode(ReferenceCursor(passStartedAt, JiraEntityKind.PROJECT_FIELDS, startAt = index + 1)))
                stored
            }
            context.heartbeat(changed)
            index++
        }
    }

    /** The `PROJECT_FIELDS` payload for [key], or `null` when this project's step is skipped (see [runProjectFields]). */
    private suspend fun fetchProjectFieldsPayload(
        key: String,
        projectId: Long?,
        workTypeIds: List<Long>,
        hierarchy: Map<String, Int>,
    ): String? {
        if (projectId == null || workTypeIds.isEmpty()) {
            log.warn("PROJECT_FIELDS skipped for project {}: no project id or issue types are stored yet", key)
            return null
        }
        val scheme = try {
            JiraProjectFields.fetchScheme(client, projectId, workTypeIds, ceilings.projectFields)
        } catch (failure: FetchException) {
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
            val boardId = boardIdOf(boards[index])
            val body = client.boardConfiguration(boardId)
            val outcome = context.transaction {
                rawStore.upsertEntity(
                    context.connectionId, JiraEntityKind.BOARD_CONFIGURATION.name, boardId.toString(), body, context.clock(),
                ).also {
                    context.putCursor(
                        name,
                        encode(ReferenceCursor(passStartedAt, JiraEntityKind.BOARD_CONFIGURATION, boardId = boardId, startAt = index + 1)),
                    )
                    context.countEntityOutcome(it)
                }
            }
            context.heartbeat(outcome.isChange)
            index++
        }
    }

    /**
     * Only scrum boards carry sprints (Kanban boards have none in real Jira) — a mid-board
     * interruption restarts THAT board's own paging from 0 (idempotent).
     */
    private suspend fun runSprints(context: StreamContext, passStartedAt: Long, resumeIndex: Int) {
        val scrumBoards = fetchInScopeBoards(context).filter(::isScrumBoard)
        // ONE budget for the whole pass, across boards: a ceiling per board would be multiplied by the number of boards.
        val budget = RunBudget(JIRA_SOURCE, BOARD_SPRINTS_ENDPOINT, ceilings.sprints, "sprints")
        var index = resumeIndex
        while (index < scrumBoards.size) {
            val boardId = boardIdOf(scrumBoards[index])
            var sprintStartAt = 0
            while (true) {
                budget.nextPage()
                val page = client.boardSprints(boardId, sprintStartAt)
                budget.add(page.values.size)
                val consumed = page.values.size
                val nextSprintStartAt = sprintStartAt + consumed
                val isLast = page.isLast == true || nextSprintStartAt >= page.total || consumed == 0
                val changed = context.transaction {
                    rawStore.storeEntities(context, JiraEntityKind.SPRINT, page.values).also {
                        context.putCursor(
                            name,
                            encode(ReferenceCursor(passStartedAt, JiraEntityKind.SPRINT, boardId = boardId, startAt = index)),
                        )
                    }
                }
                context.heartbeat(changed)
                if (isLast) break
                sprintStartAt = nextSprintStartAt
            }
            index++
        }
    }

    /**
     * A small, idempotent helper (in-scope boards are always few) - no cursor of its own, safe to redo on resume. It holds the
     * in-scope boards of every page, so the answer is bounded ([ReferenceCeilings.boards]): past it `LIMIT_EXCEEDED` fails the step,
     * whose cursor stays where it was.
     */
    private suspend fun fetchInScopeBoards(context: StreamContext): List<JsonObject> {
        val answer = BoundedAnswer<JsonObject>(JIRA_SOURCE, BOARDS_ENDPOINT, ceilings.boards, "boards")
        var startAt = 0
        while (true) {
            answer.nextPage()
            val page = client.boards(startAt)
            page.values.map { it.jsonObject }.filter(::isInScopeBoard).forEach { answer.add(it, it.toString().length) }
            val consumed = page.values.size
            val nextStartAt = startAt + consumed
            context.heartbeat(changed = false) // nothing was written: the lease and the clock, not progress
            if (page.isLast == true || nextStartAt >= page.total || consumed == 0) break
            startAt = nextStartAt
        }
        return answer.items
    }

    /** A stored board's numeric id (`INVALID_RESPONSE`, constant text, for one the host sent without). */
    private fun boardIdOf(board: JsonObject): Long = board.requireLong("id", BOARDS_ENDPOINT, "a board")

    private fun encode(cursor: ReferenceCursor): String =
        encodeCursor(cursor.copy(stepOrder = REFERENCE_STEP_ORDER, usersSkipped = usersSkipped))
}
