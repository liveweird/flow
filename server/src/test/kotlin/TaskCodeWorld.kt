package ch.nokillswit

import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.FieldChangeFact
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.extractIssueKeyCandidates
import ch.nokillswit.norm.purgeAll
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Report 22's hand-written two-layer world (`ReportTaskCodeTest`, `.claude/docs/reports.md` "Report 22"): a DISABLED Jira connection
 * ([jiraA], projects `TCA` and `TCB`) with `norm.work_items`, an `issuekey` change and `metrics.fact_task_delivery` rows written
 * directly, and two DISABLED GitLab connections ([glA], [glB]) on unique hosts with `norm.code_merge_requests` and their stored issue-key
 * candidates. Days count from Monday 2026-03-02 at midnight in [zone] (Saturday and Sunday off); the period is days 0-27 and the
 * report's clock is pinned to day 30. Tasks of the team [teamA] (and one of [teamB], one with no credit) share the domain [domain].
 *
 * The tasks (key: start - done; merge requests, `source`/`day`):
 *  - `TCA-1` (T1): 0 - 4; `glA` MR 1 title d2, MR 2 branch-only d3, `glB` MR 1 d3 12:00 (the SAME mr id on another source): the last
 *    merge is d3 12:00.
 *  - `TCA-2` (T2): 1 - 3; MR 3 d8, merged AFTER done.
 *  - `TCA-3` (T3): 2 - 4; MR 4 open, MR 5 closed: linked, none merged.
 *  - `TCA-4` (T4): never started - 9; MR 6 d8.
 *  - `TCB-7` (T5): 7 - 10, moved from `TCA-50` on day 6; MR 7 names the OLD key d10 12:00 (the last merge), MR 8 the new key d9.
 *  - `TCA-6` (T6): 2 - 11; only a DELETED merge request (MR 13) names it.
 *  - `TCA-11` (T10, [teamB]): 1 - 3; MR 15 d3. `TCA-13` (T11, no credit team): 1 - 4; MR 18 d4.
 *  - Never counted: `TCA-10` done on day 40 (MR 16), a sub-task `TCA-12` (MR 17).
 *  - Two tombstoned issues (ids 901 deleted, 902 moved out) also have the key `TCA-1`: not live, so `TCA-1` still names T1 alone.
 * Candidates that name no task: `TCA-999` (a known project, no such issue), `TCZ-3` (an unknown project) and `UTF-8`.
 */
internal class TaskCodeWorld(
    val zone: ZoneId,
    val glA: UInt,
    val glB: UInt,
    val jiraA: UInt,
    val teamA: UInt,
    val teamB: UInt,
    val domain: String,
) {
    private val base: LocalDate = LocalDate.of(2026, 3, 2)
    private val store = MetricsStore(sharedDatabaseForTests())
    private val facts = mutableListOf<FactTaskDeliveryRow>()

    /** Other Jira connections a test opened ([withSecondJira]); removed with the world. */
    val extraJira = mutableListOf<UInt>()

    fun at(day: Int, hour: Int = 0): Long = base.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L

    val now: Long = at(30)

    /** The request's period query: days 0-27 of the world. */
    val period = "from=2026-03-02&to=2026-03-29"

    /** One level-0 task: its `norm.work_items` row (and an `issuekey` change when [movedFrom] is given) and its fact row, queued. */
    suspend fun task(
        issueId: Long,
        key: String,
        started: Int?,
        done: Int?,
        team: UInt? = teamA,
        movedFrom: String? = null,
        subtask: Boolean = false,
        connId: UInt = jiraA,
    ) {
        val change = movedFrom?.let { FieldChangeFact("Key", at(6), it, it, key, key, "issuekey") }
        val steps = listOfNotNull(
            StatusStep("1", "To Do", StatusCategory.TODO, at(-30)),
            done?.let { StatusStep("9", "Done", StatusCategory.DONE, at(it)) },
        )
        seedItem(connId, issueId, key, 0, null, at(-30), steps, "{}", keyChange = change)
        if (connId == jiraA) {
            facts += handTask(
                issueId, started?.let { at(it) }, done?.let { at(it) }, creditTeamId = team, domain = domain, subtask = subtask,
            ).copy(issueKey = key)
        }
    }

    /** A tombstoned `norm.work_items` row ([TombstoneKind.DELETED] or [TombstoneKind.MOVED_OUT]) with [key]: no fact row, never live. */
    suspend fun ghost(issueId: Long, key: String, kind: TombstoneKind, connId: UInt = jiraA) {
        val steps = listOf(StatusStep("1", "To Do", StatusCategory.TODO, at(-30)))
        seedItem(connId, issueId, key, 0, null, at(-30), steps, "{}", tombstone = kind)
    }

    /** Writes the queued fact rows (a full replace of [jiraA]'s: call it after every [task] of the batch). */
    suspend fun publish() =
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(jiraA, facts, configRevision = 1L) }

    /** A merge request that names its keys in [title] / [branch]; `merged` and `closed` states carry [mergedDay], `opened` has none. */
    fun mr(
        id: Long,
        title: String,
        mergedDay: Int?,
        hour: Int = 0,
        state: String = if (mergedDay != null) "merged" else "opened",
        branch: String = "feature/x",
        deleted: Boolean = false,
    ) = NormCodeMergeRequest(
        id, 1, id, state, false, null, null, at(-2), at(-2), mergedDay?.let { at(it, hour) }, null, branch, "main", title, null, null, null,
        emptyList(), emptyList(), 3, 2, 1, false, DetailsState.READ, if (deleted) 1L else null, emptyList(),
        issueKeys = extractIssueKeyCandidates(title, branch),
    )

    suspend fun write(connId: UInt, vararg mergeRequests: NormCodeMergeRequest) =
        GitLabFixtures.codeStore().replaceMergeRequests(connId, mergeRequests.toList(), now)
}

/**
 * Runs [body] in a fresh [TaskCodeWorld] with the merge requests and tasks above written, and removes everything in `finally`. The
 * metrics settings are NOT touched here: a test pins `minSampleSize` itself ([withMinSampleSize]).
 */
internal suspend fun <T> withTaskCodeWorld(zone: ZoneId, body: suspend TaskCodeWorld.() -> T): T {
    val gitlab = GitLabFixtures.dataSources()
    val jira = SyncedStubFixture.dataSources()
    fun host() = "task-code-${UUID.randomUUID().toString().take(8)}.example.test"
    val glA = GitLabFixtures.createConnection(gitlab, baseUrl = "https://${host()}", enabled = false, namePrefix = "task-code-a")
    val glB = GitLabFixtures.createConnection(gitlab, baseUrl = "https://${host()}", enabled = false, namePrefix = "task-code-b")
    val jiraA = SyncedStubFixture.createConnection(projectKeys = listOf("TCA", "TCB"), enabled = false, namePrefix = "task-code")
    val teamA = TestTeams.seed("task-code-a-${UUID.randomUUID().toString().take(8)}")
    val teamB = TestTeams.seed("task-code-b-${UUID.randomUUID().toString().take(8)}")
    val world = TaskCodeWorld(zone, glA, glB, jiraA, teamA, teamB, "TCD${UUID.randomUUID().toString().take(6).uppercase()}")
    try {
        with(world) {
            task(101, "TCA-1", 0, 4)
            task(102, "TCA-2", 1, 3)
            task(103, "TCA-3", 2, 4)
            task(104, "TCA-4", null, 9)
            task(105, "TCB-7", 7, 10, movedFrom = "TCA-50")
            task(106, "TCA-6", 2, 11)
            task(110, "TCA-11", 1, 3, team = teamB)
            task(111, "TCA-13", 1, 4, team = null)
            task(109, "TCA-10", 1, 40)
            task(112, "TCA-12", 1, 5, subtask = true)
            // Tombstoned issues sharing TCA-1's key (a deleted one, a moved-out one): not live, so they must not make it ambiguous.
            ghost(901, "TCA-1", TombstoneKind.DELETED)
            ghost(902, "TCA-1", TombstoneKind.MOVED_OUT)
            publish()
            write(
                glA,
                mr(1, "TCA-1: add parser", 2),
                mr(2, "refactor parser", 3, branch = "feature/tca-1-y"),
                mr(3, "[TCA-2] late fix", 8),
                mr(4, "TCA-3 wip", null),
                mr(5, "TCA-3 abandoned", null, state = "closed"),
                mr(6, "TCA-4 cleanup", 8),
                mr(7, "TCA-50: moved", 10, hour = 12),
                mr(8, "TCB-7 follow-up", 9),
                mr(9, "UTF-8 parser", 2),
                mr(11, "TCA-999 nonexistent", 2),
                mr(12, "TCZ-3 other project", 2),
                mr(13, "TCA-6 deleted", 2, deleted = true),
                mr(15, "TCA-11 other team", 3),
                mr(16, "TCA-10 later", 3),
                mr(17, "TCA-12 subtask", 3),
                mr(18, "TCA-13 no team", 4),
            )
            write(glB, mr(1, "TCA-1 hotfix", 3, hour = 12))
        }
        return world.body()
    } finally {
        suspendTransaction(sharedDatabaseForTests()) { MetricsStore(sharedDatabaseForTests()).deleteFactTaskDelivery(jiraA) }
        (world.extraJira + jiraA).forEach { id ->
            SyncedStubFixture.workItems().purgeAll(id)
            jira.delete(id)
        }
        listOf(glA, glB).forEach { id ->
            GitLabFixtures.purgeAll(id)
            gitlab.delete(id)
        }
        listOf(teamA, teamB).forEach { TestTeams.service.delete(it) }
    }
}

/** A second DISABLED Jira connection listing `TCA`, so a `TCA-<n>` key can name an issue in two sources. */
internal suspend fun TaskCodeWorld.withSecondJira(): UInt =
    SyncedStubFixture.createConnection(projectKeys = listOf("TCA"), enabled = false, namePrefix = "task-code-b").also { extraJira += it }
