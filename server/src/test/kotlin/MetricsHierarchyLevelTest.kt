package ch.nokillswit

import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsWorkCategoryMapping
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import java.io.File
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `sample-data/jira/expected.json` → `aboveEpic`: the stub's one level-2 Program issue (generator constants, never hand-copied). */
@Serializable
private data class ExpectedAboveEpic(
    val issueId: String,
    val issueKey: String,
    val childEpicIssueKeys: List<String>,
    val storyPoints: Int,
    val worklogSeconds: Long,
)

@Serializable
private data class ExpectedAboveEpicFixture(val aboveEpic: ExpectedAboveEpic)

private val EXPECTED_JSON = Json { ignoreUnknownKeys = true }

private val aboveEpicExpected: ExpectedAboveEpic by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json")).firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    EXPECTED_JSON.decodeFromString<ExpectedAboveEpicFixture>(file.readText()).aboveEpic
}

/**
 * A31 — an issue above the epic level (hierarchy level 2+) is OUTSIDE the task/epic model: DERIVE writes no row of its own for it in
 * any `metrics.*` table (so it leaves the estimated backlog, WIP, throughput and every bridge), while its worklogs are KEPT
 * (invariant 6) as an epic-logged worklog would be — minus the epic. An item with an unknown (`null`) level stays a task but is
 * counted in `row_counts.unknownLevelItems`. The stub carries exactly one Program (`expected.json` → `aboveEpic`), pinned read-only
 * on the shared [DerivedStubFixture] below, and no issue of unknown level; the other tests change
 * `norm.work_items.hierarchy_level` on a PRIVATE disabled clone ([SyncedStubFixture.cloneProcessedData]) and derive it twice in ONE
 * `withPinnedSettings` block (every derive stamps the same `config_revision`, [MetricsDigestTest]'s convention).
 */
class MetricsHierarchyLevelTest {
    private fun <T> jdbc(block: (Connection) -> T): T =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use(block)

    private fun <T> Connection.query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) read(rs) else null }.toList() } }

    private fun Connection.exec(sql: String): Int = createStatement().use { it.executeUpdate(sql) }

    private fun count(table: String, connId: UInt, issueId: Long): Long =
        jdbc { c -> c.query("SELECT count(*) FROM metrics.$table WHERE connection_id = $connId AND issue_id = $issueId") { it.getLong(1) } }
            .single()

    private fun sum(sql: String): BigDecimal = jdbc { c -> c.query(sql) { it.getBigDecimal(1) } }.single() ?: BigDecimal.ZERO

    /** The newest SUCCEEDED derive's `row_counts` of [connId]. */
    private fun rowCounts(connId: UInt) = Json.parseToJsonElement(
        jdbc { c ->
            c.query(
                "SELECT row_counts FROM metrics.derive_runs WHERE connection_id = $connId AND status = 'SUCCEEDED' " +
                    "ORDER BY id DESC LIMIT 1",
            ) { it.getString(1) }
        }.single(),
    ).jsonObject

    private fun rowCount(connId: UInt, key: String): Int = rowCounts(connId).getValue(key).jsonPrimitive.content.toInt()

    /**
     * Removes [issueId]'s sprint MEMBERSHIP (the Sprint changelog items and the current-sprint list DERIVE rebuilds `task_sprint` from)
     * on the clone, so the task sits in no started sprint and counts toward the estimated backlog (D9). Its Sprint field intervals
     * stay, so its worklogs still resolve a sprint while it is a task.
     */
    private suspend fun leaveSprints(connId: UInt, issueId: Long) {
        val sprintField = DerivedStubFixture.metricsConfig().detectedSprintFieldId(connId)
        assertNotNull(sprintField, "the stub profile has a Sprint field")
        jdbc { c ->
            c.exec(
                "DELETE FROM norm.work_item_field_changes " +
                    "WHERE connection_id = $connId AND issue_id = $issueId AND field = '$sprintField'",
            )
            c.exec("UPDATE norm.work_items SET current_sprint_ids = '[]' WHERE connection_id = $connId AND issue_id = $issueId")
        }
    }

    private fun setLevel(connId: UInt, issueId: Long, level: Int?) {
        val updated = jdbc { c ->
            c.exec("UPDATE norm.work_items SET hierarchy_level = ${level ?: "NULL"} WHERE connection_id = $connId AND issue_id = $issueId")
        }
        assertEquals(1, updated, "exactly the one picked work item changes level")
    }

    /** A private, disabled, FLO-board-mapped processed clone: its connection id and the FLO board's team (the FLO domain's owner). */
    private suspend fun preparedClone(prefix: String): Pair<UInt, UInt> {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-hierarchy-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        val config = DerivedStubFixture.metricsConfig()
        val ownerTeam = DerivedStubFixture.mapFloBoardToNewTeam(connId, config, prefix)
        // The default config maps no work-category field, so no task has an OWN category: map the stub's "Work type" picker
        // (`customfield_10030`) so the items carrying value 10100 do.
        val current = config.effectiveConfig(connId)
        config.replaceConfig(
            connId,
            DataSourceMetricsConfigRequest(
                statusStages = current.statusStages,
                domainStatusStages = current.domainStatusStages,
                fields = current.fields.copy(workCategory = WORK_CATEGORY_FIELD),
                domains = current.domains,
                boards = current.boards,
                activityTypes = current.activityTypes,
                workCategories = listOf(MetricsWorkCategoryMapping(WORK_CATEGORY_VALUE_ID, null, WORK_CATEGORY)),
                blockedStatuses = current.blockedStatuses,
                sprintCapacities = current.sprintCapacities,
            ),
        )
        return connId to ownerTeam
    }

    private val authorsKnownToPeople = """
          AND EXISTS (
              SELECT 1 FROM norm.work_item_worklogs l JOIN norm.people p ON p.connection_id = l.connection_id
                  AND p.account_id = l.author_account_id
              WHERE l.connection_id = w.connection_id AND l.issue_id = w.issue_id)
          AND NOT EXISTS (
              SELECT 1 FROM norm.work_item_worklogs l
              WHERE l.connection_id = w.connection_id AND l.issue_id = w.issue_id
                AND (l.author_account_id IS NULL OR l.author_account_id NOT IN (
                    SELECT account_id FROM norm.people WHERE connection_id = w.connection_id)))
          AND NOT EXISTS (SELECT 1 FROM norm.work_items c WHERE c.connection_id = w.connection_id AND c.parent_issue_id = w.issue_id)
    """

    private val distinctAuthors = """
        (SELECT count(DISTINCT l.author_account_id) FROM norm.work_item_worklogs l
         WHERE l.connection_id = w.connection_id AND l.issue_id = w.issue_id)
    """

    /**
     * The BACKLOG item (A): a level-0, NOT_STARTED, own-estimated FLO task carrying work-category value [WORK_CATEGORY_VALUE_ID]
     * (an OWN category once [preparedClone] maps it), no sub-task and worklogs by
     * authors known to `norm.people` only, read off the SHARED derived fixture (read-only; the clone carries the same issue ids).
     * Epic-carrying tasks and ones with several authors win, then the lowest id. The stub parks every such task in a (closed) sprint,
     * which takes it out of the estimated backlog: [leaveSprints] strips the membership on the clone.
     */
    private suspend fun pickBacklogItem(): Long {
        val shared = DerivedStubFixture.connectionId()
        val picked = jdbc { c ->
            c.query(
                """
                SELECT w.issue_id
                FROM norm.work_items w
                JOIN metrics.dim_task t ON t.connection_id = w.connection_id AND t.issue_id = w.issue_id
                JOIN metrics.fact_task_delivery f ON f.connection_id = w.connection_id AND f.issue_id = w.issue_id
                WHERE w.connection_id = $shared AND w.hierarchy_level = 0 AND NOT w.is_subtask AND w.project_key = 'FLO'
                  AND f.current_stage = 'NOT_STARTED' AND f.estimate_current_md > 0
                  AND w.custom_fields -> '$WORK_CATEGORY_FIELD' ->> 'id' = '$WORK_CATEGORY_VALUE_ID'
                  $authorsKnownToPeople
                ORDER BY (t.epic_id IS NULL), $distinctAuthors DESC, w.issue_id
                LIMIT 1
                """.trimIndent(),
            ) { it.getLong(1) }
        }.firstOrNull()
        return assertNotNull(picked, "the stub must carry an estimated NOT_STARTED FLO task with an OWN category and worklogs")
    }

    /**
     * The SPRINT item (B), distinct from [excluded]: a started level-0 FLO task (so it is not in the estimated backlog) with no
     * sub-task, worklogs by known authors only and at least one `fact_worklog` row that resolved a sprint, read off the shared derived
     * fixture. Epic-carrying tasks and ones with several authors win, then the lowest id.
     */
    private suspend fun pickSprintItem(excluded: Long): Long {
        val shared = DerivedStubFixture.connectionId()
        val picked = jdbc { c ->
            c.query(
                """
                SELECT w.issue_id
                FROM norm.work_items w
                JOIN metrics.dim_task t ON t.connection_id = w.connection_id AND t.issue_id = w.issue_id
                JOIN metrics.fact_task_delivery f ON f.connection_id = w.connection_id AND f.issue_id = w.issue_id
                WHERE w.connection_id = $shared AND w.hierarchy_level = 0 AND NOT w.is_subtask AND w.project_key = 'FLO'
                  AND w.issue_id <> $excluded AND f.current_stage <> 'NOT_STARTED'
                  AND EXISTS (SELECT 1 FROM metrics.fact_worklog fw WHERE fw.connection_id = w.connection_id
                      AND fw.issue_id = w.issue_id AND fw.sprint_id_at_started IS NOT NULL)
                  $authorsKnownToPeople
                ORDER BY (t.epic_id IS NULL), $distinctAuthors DESC, w.issue_id
                LIMIT 1
                """.trimIndent(),
            ) { it.getLong(1) }
        }.firstOrNull()
        return assertNotNull(picked, "the stub must carry a started FLO task whose worklogs resolve a sprint")
    }

    /** ANY live level-0 FLO task of the clone — the unknown-level case needs no particular shape. */
    private fun pickAnyTask(connId: UInt): Long = assertNotNull(
        jdbc { c ->
            c.query(
                "SELECT issue_id FROM norm.work_items WHERE connection_id = $connId AND hierarchy_level = 0 AND NOT is_subtask " +
                    "AND project_key = 'FLO' AND deleted_at IS NULL AND moved_out_at IS NULL ORDER BY issue_id LIMIT 1",
            ) { it.getLong(1) }
        }.firstOrNull(),
        "the clone has a level-0 FLO task",
    )

    private class TaskSide(
        val estimateMd: BigDecimal?,
        val domainKey: String,
        val epicId: Long?,
        val workCategory: String?,
        val workCategorySource: String,
    )

    private class WorklogSide(
        val worklogId: Long,
        val md: BigDecimal,
        val author: String,
        val epicId: Long?,
        val epicDomainKey: String?,
        val taskDomainKey: String?,
        val sprintId: Long?,
        val foreignWork: Boolean,
        val workCategory: String?,
    )

    private class Totals(
        val backlogItems: Int,
        val backlogMd: BigDecimal,
        val worklogRows: Long,
        val worklogMd: BigDecimal,
        val teamAc: BigDecimal,
        val epicAc: BigDecimal,
    )

    private fun taskSide(connId: UInt, issueId: Long): TaskSide = jdbc { c ->
        c.query(
            """
            SELECT f.estimate_current_md, t.domain_key, t.epic_id, t.work_category, t.work_category_source
            FROM metrics.dim_task t JOIN metrics.fact_task_delivery f ON f.connection_id = t.connection_id AND f.issue_id = t.issue_id
            WHERE t.connection_id = $connId AND t.issue_id = $issueId
            """.trimIndent(),
        ) { TaskSide(it.getBigDecimal(1), it.getString(2), it.getObject(3) as Long?, it.getString(4), it.getString(5)) }
    }.single()

    private fun worklogSides(connId: UInt, issueId: Long): List<WorklogSide> = jdbc { c ->
        c.query(
            """
            SELECT worklog_id, md, author_account_id, epic_id, epic_domain_key, task_domain_key, sprint_id_at_started, foreign_work,
                   work_category
            FROM metrics.fact_worklog WHERE connection_id = $connId AND issue_id = $issueId ORDER BY worklog_id
            """.trimIndent(),
        ) {
            WorklogSide(
                it.getLong(1), it.getBigDecimal(2), it.getString(3), it.getObject(4) as Long?, it.getString(5), it.getString(6),
                it.getObject(7) as Long?, it.getBoolean(8), it.getString(9),
            )
        }
    }

    /** The domain's estimated backlog on [day] (D9, `agg_daily_flow`'s DOMAIN scope) and the connection-wide worklog/AC totals. */
    private fun totals(connId: UInt, domainKey: String, day: String): Totals {
        val (items, md) = jdbc { c ->
            c.query(
                "SELECT backlog_items, backlog_md FROM metrics.agg_daily_flow WHERE connection_id = $connId AND scope_kind = 'DOMAIN' " +
                    "AND scope_id = '$domainKey' AND day = '$day'",
            ) { it.getInt(1) to it.getBigDecimal(2) }
        }.singleOrNull() ?: (0 to BigDecimal.ZERO)
        val worklogRows = totalsOfWorklogs(connId)
        return Totals(
            items,
            md,
            worklogRows,
            sum("SELECT sum(md) FROM metrics.fact_worklog WHERE connection_id = $connId"),
            sum("SELECT sum(ac_md) FROM metrics.agg_daily_flow WHERE connection_id = $connId AND scope_kind = 'TEAM'"),
            sum("SELECT sum(ac_md) FROM metrics.agg_daily_flow WHERE connection_id = $connId AND scope_kind = 'EPIC'"),
        )
    }

    private fun totalsOfWorklogs(connId: UInt): Long =
        jdbc { c -> c.query("SELECT count(*) FROM metrics.fact_worklog WHERE connection_id = $connId") { it.getLong(1) } }.single()

    /** The last day the domain has an estimated backlog (the flow aggregate also carries future PV days, which never hold a backlog). */
    private fun lastBacklogDay(connId: UInt, domainKey: String): String = jdbc { c ->
        c.query(
            "SELECT max(day) FROM metrics.agg_daily_flow " +
                "WHERE connection_id = $connId AND scope_kind = 'DOMAIN' AND scope_id = '$domainKey' AND backlog_items > 0",
        ) { it.getString(1) }
    }.single()

    /** Tables keyed by `(connection_id, issue_id)` that must hold NO row of an out-of-model item (everything but `fact_worklog`). */
    private val modelTables = listOf(
        "dim_task", "dim_epic", "fact_task_delivery", "fact_epic_delivery", "fact_epic_plan", "task_epic", "task_domain", "task_assignee",
        "task_sprint", "item_stage", "item_blocked", "item_estimate", "fact_sprint_scope",
    )

    @Test
    fun `a task moved above the epic level leaves every model table but keeps its worklogs as an epic-logged item's`() = runBlocking {
        val backlogId = pickBacklogItem()
        val sprintId = pickSprintItem(excluded = backlogId)
        val flipped = listOf(backlogId, sprintId)
        val (connId, ownerTeam) = preparedClone("hierarchy-above-owner")
        leaveSprints(connId, backlogId)
        val otherTeam = TestTeams.seed(uniqueEmail("hierarchy-above-other"))
        val metricsSettings = DerivedStubFixture.metricsSettings()
        val membershipService = TeamMembershipService(sharedDatabaseForTests(), metricsSettings)
        val authors = jdbc { c ->
            c.query(
                "SELECT DISTINCT author_account_id FROM norm.work_item_worklogs WHERE connection_id = $connId " +
                    "AND issue_id IN (${flipped.joinToString()}) ORDER BY author_account_id",
            ) { it.getString(1) }
        }
        // First author: a member of ANOTHER team (foreign work); second (if any): of the owner team (not foreign); the rest: no team.
        val teamByAuthor = buildMap {
            authors.getOrNull(0)?.let { put(it, otherTeam) }
            authors.getOrNull(1)?.let { put(it, ownerTeam) }
        }
        // `metrics.team_membership` is global by account id: every row this test creates is removed again in the finally.
        val created = mutableListOf<Pair<UInt, UInt>>()
        try {
            DerivedStubFixture.withPinnedSettings(metricsSettings) {
                teamByAuthor.forEach { (account, team) ->
                    val row = membershipService.create(team, TeamMembershipCreateRequest(account, MEMBER_SINCE_MS, null))
                    created += team to row.id
                }
                DerivedStubFixture.derivePinned(connId, jobId = 1u)
                val tasksBefore = taskSide(connId, backlogId) to taskSide(connId, sprintId)
                val backlogBefore = tasksBefore.first
                val worklogsBefore = flipped.associateWith { worklogSides(connId, it) }
                val day = lastBacklogDay(connId, backlogBefore.domainKey)
                val totalsBefore = totals(connId, backlogBefore.domainKey, day)
                val taskRowsBefore = rowCount(connId, "tasks")
                // The stub's own Program (A31, expected.json aboveEpic) is the one item above the epic level before the flip.
                val aboveEpicBefore = rowCount(connId, "aboveEpicItems")
                assertEquals(1, aboveEpicBefore, "the default stub has exactly one level-2+ issue")
                assertEquals(0, rowCount(connId, "unknownLevelItems"), "the default stub has no issue of unknown level")
                flipped.forEach {
                    assertTrue(worklogsBefore.getValue(it).isNotEmpty(), "item $it has worklogs")
                    assertEquals(1, count("dim_task", connId, it), "item $it is a modelled task")
                }
                assertTrue(worklogsBefore.getValue(sprintId).any { it.sprintId != null }, "as a task a worklog resolves a sprint")
                assertNotNull(backlogBefore.workCategory, "the backlog item has an OWN work category")
                assertEquals(backlogBefore.domainKey, tasksBefore.second.domainKey, "both items share the FLO domain and its owner")

                flipped.forEach { setLevel(connId, it, 2) }
                DerivedStubFixture.derivePinned(connId, jobId = 2u)

                flipped.forEach { id ->
                    modelTables.forEach { assertEquals(0, count(it, connId, id), "no $it row for the above-epic item $id") }
                }
                assertEquals(aboveEpicBefore + 2, rowCount(connId, "aboveEpicItems"))
                assertEquals(0, rowCount(connId, "unknownLevelItems"))
                assertEquals(taskRowsBefore - 2, rowCount(connId, "tasks"), "the items are no longer task rows")

                // The estimated backlog (D9, the domain scope) drops by exactly the backlog item's estimate and one item.
                val totalsAfter = totals(connId, backlogBefore.domainKey, day)
                assertEquals(totalsBefore.backlogItems - 1, totalsAfter.backlogItems)
                assertEquals(0, totalsBefore.backlogMd.subtract(backlogBefore.estimateMd).compareTo(totalsAfter.backlogMd))

                // Invariant 6: every worklog row survives, none gained or lost minutes; the team AC total is unchanged,
                // the EPIC AC total loses exactly what had been attributed to the items' epics.
                assertEquals(totalsBefore.worklogRows, totalsAfter.worklogRows)
                assertEquals(0, totalsBefore.worklogMd.compareTo(totalsAfter.worklogMd))
                assertEquals(0, totalsBefore.teamAc.compareTo(totalsAfter.teamAc))
                val epicLogged = worklogsBefore.values.flatten().filter { it.epicId != null }
                assertTrue(epicLogged.isNotEmpty(), "a picked item had epic-attributed worklogs, so the EPIC AC drop is not vacuous")
                // `ac_md` is rounded to 2 decimals per (scope, day) group while `fact_worklog.md` keeps 4, so each epic-day group can be
                // off by up to 0.005 against the exact sum: allow 0.01 per epic-attributed worklog.
                val epicAcDrop = totalsBefore.epicAc.subtract(totalsAfter.epicAc)
                val tolerance = BigDecimal("0.01").multiply(BigDecimal(epicLogged.size))
                assertTrue(
                    epicAcDrop.subtract(epicLogged.sumOf { it.md }).abs() <= tolerance,
                    "never in epic EVM: EPIC AC fell by $epicAcDrop, the epic-attributed worklogs sum to ${epicLogged.sumOf { it.md }}",
                )

                flipped.forEach { id ->
                    val before = worklogsBefore.getValue(id)
                    val task = if (id == backlogId) tasksBefore.first else tasksBefore.second
                    val after = worklogSides(connId, id)
                    assertEquals(before.map { it.worklogId }, after.map { it.worklogId })
                    after.forEach { wl ->
                        assertNull(wl.epicId, "an above-epic item's worklog has no epic")
                        assertNull(wl.epicDomainKey)
                        assertNull(wl.sprintId)
                        assertEquals(task.domainKey, wl.taskDomainKey, "attributed to the item's own domain")
                        // A22's epic branch, recomputed independently: foreign when the author's team is known and not the owner's.
                        val authorTeam = teamByAuthor[wl.author]
                        assertEquals(authorTeam != null && authorTeam != ownerTeam, wl.foreignWork, "foreign_work of ${wl.author}")
                        // The category is the item's own, with no epic fallback.
                        assertEquals(if (task.workCategorySource == "OWN") task.workCategory else null, wl.workCategory)
                    }
                }
                val backlogAfter = worklogSides(connId, backlogId)
                assertEquals(backlogBefore.workCategory, backlogAfter.first().workCategory, "the backlog item keeps its OWN category")
                val allAfter = flipped.flatMap { worklogSides(connId, it) }
                assertTrue(allAfter.any { it.foreignWork }, "the oracle above is not vacuous: one author belongs to another team")
                assertTrue(allAfter.any { !it.foreignWork }, "nor is it one-sided: some worklogs are not foreign")
                Unit
            }
        } finally {
            created.forEach { (team, id) -> membershipService.delete(team, id) }
        }
    }

    @Test
    fun `the stub's Program above the epic level is in no model table, keeps its one worklog and leaves its child epics whole`() =
        runBlocking {
            // Read-only over the shared derived fixture (`DerivedStubFixture`): one DERIVE of the stub, never mutated here.
            val connId = DerivedStubFixture.connectionId()
            val program = aboveEpicExpected
            val issueId = program.issueId.toLong()

            modelTables.forEach { assertEquals(0, count(it, connId, issueId), "no $it row for the Program ${program.issueKey}") }
            assertEquals(1, rowCount(connId, "aboveEpicItems"), "the fixture's DERIVE counted exactly the one Program")
            assertEquals(0, rowCount(connId, "unknownLevelItems"), "the stub has no issue of unknown level")

            // Invariant 6: its one worklog is kept — as an above-epic worklog: its own domain, no epic, no sprint.
            val worklog = worklogSides(connId, issueId).single()
            assertEquals("790000", worklog.worklogId.toString())
            assertNull(worklog.epicId)
            assertNull(worklog.epicDomainKey)
            assertNull(worklog.sprintId)
            assertEquals("FLO", worklog.taskDomainKey)
            val hoursPerDay = DerivedStubFixture.HOURS_PER_DAY
            val expectedMd =
                BigDecimal(program.worklogSeconds).divide(BigDecimal(3600 * hoursPerDay), 4, java.math.RoundingMode.HALF_UP)
            assertEquals(0, expectedMd.compareTo(worklog.md), "md = ${program.worklogSeconds} s / 3600 / $hoursPerDay h per day")

            // Its two child epics are ordinary epics: a dim_epic row and a fact_epic_delivery row each; nothing walks above the
            // epic, so no task or epic row points at the Program.
            assertEquals(2, program.childEpicIssueKeys.size)
            program.childEpicIssueKeys.forEach { key ->
                val epicId = jdbc { c ->
                    c.query("SELECT issue_id FROM metrics.dim_epic WHERE connection_id = $connId AND issue_key = '$key'") { it.getLong(1) }
                }.single()
                assertEquals(1, count("fact_epic_delivery", connId, epicId), "$key has its fact_epic_delivery row")
            }
            val pointingAtProgram = jdbc { c ->
                c.query("SELECT count(*) FROM metrics.dim_task WHERE connection_id = $connId AND epic_id = $issueId") { it.getLong(1) }
            }.single()
            assertEquals(0L, pointingAtProgram, "no task is attributed to the Program as if it were an epic")
        }

    @Test
    fun `an item of unknown level stays a task and is counted`() = runBlocking {
        val (connId, _) = preparedClone("hierarchy-unknown-owner")
        val issueId = pickAnyTask(connId)
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(connId, jobId = 1u)
            val tasksBefore = rowCount(connId, "tasks")
            val aboveEpicBefore = rowCount(connId, "aboveEpicItems")
            val worklogsBefore = totalsOfWorklogs(connId)
            assertEquals(0, rowCount(connId, "unknownLevelItems"))

            setLevel(connId, issueId, null)
            DerivedStubFixture.derivePinned(connId, jobId = 2u)

            assertEquals(1, count("dim_task", connId, issueId), "a null level is a task")
            assertEquals(1, count("fact_task_delivery", connId, issueId))
            assertEquals(tasksBefore, rowCount(connId, "tasks"))
            assertEquals(1, rowCount(connId, "unknownLevelItems"))
            assertEquals(aboveEpicBefore, rowCount(connId, "aboveEpicItems"), "the null level does not move the Program count")
            assertEquals(worklogsBefore, totalsOfWorklogs(connId))
        }
    }

    private companion object {
        /** 2001-09-09 — before any stub worklog, so a membership covers every `started_at`. */
        const val MEMBER_SINCE_MS = 1_000_000_000_000L
        const val WORK_CATEGORY_FIELD = "customfield_10030"
        const val WORK_CATEGORY_VALUE_ID = "10100"
        const val WORK_CATEGORY = "Product Development"
    }
}
