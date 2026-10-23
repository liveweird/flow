package ch.nokillswit

import ch.nokillswit.metrics.foreignWorkSql
import ch.nokillswit.metrics.isForeignWork
import ch.nokillswit.metrics.worklogMd
import ch.nokillswit.metrics.worklogMdSql
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * `fact_worklog` is built by ONE `INSERT ... SELECT` over the bridges the same DERIVE just wrote (`DeriveWorklogSql.kt`, build-times
 * WHY 28), except for items above the epic level. The metrics digest proves it byte for byte on the stub; THIS class proves each
 * attribution rule on a hand-built world with literal expected rows, written from the rules in `.claude/docs/metrics.md`
 * "Worklog cost facts" and NOT from the SQL:
 *
 * - the author's team at the worklog's own `started_at` (a member who changes team mid-period, a non-member, no author at all);
 * - the task's domain and epic as-of `started_at` (an `issuekey` move between two domains, a parent move between two epics, a
 *   covering parent row that names NO epic, which must not fall back to the current one);
 * - a sub-task carrying its parent's CURRENT epic whatever the parent's history was;
 * - the work category: the item's own, else the (as-of) epic's, else none;
 * - foreign work: the sprint team when known, else the assignee's team; a worklog on an epic against its domain's owner team;
 * - a worklog on an epic and one above the epic level (the JVM remainder), a deleted and a moved-out item (no row);
 * - `md` rounding at a tie and `late_ms` (clamped at 0, null without `created`).
 *
 * The world carries no Sprint field in a data profile, so the sprint step writes no `dim_sprint` row: the sprint team still has to
 * come from the board mapping, which is what the statement is given.
 */
class FactWorklogSqlTest {
    private val day = 86_400_000L
    private val base = 1_768_000_000_000L
    private fun at(days: Double) = base + Math.round(days * day)

    private fun <T> jdbc(block: (Connection) -> T): T = PostgresTestSupport.connect().use(block)

    private fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun sqlString(value: String?) = if (value == null) "NULL" else "'" + value.replace("'", "''") + "'"

    private data class Item(
        val id: Long,
        val key: String,
        val type: String,
        val level: Int,
        val parent: Long? = null,
        val category: String? = null,
        val deleted: Boolean = false,
        val movedOut: Boolean = false,
    ) {
        val project get() = key.substringBefore('-')
        val subtask get() = level < 0
    }

    private data class Worklog(
        val id: Long,
        val issueId: Long,
        val author: String?,
        val startedDays: Double,
        val seconds: Long,
        val createdDays: Double?,
    )

    /** One expected `fact_worklog` row; accounts and teams are symbolic names resolved against the run's real ids. */
    private data class Expected(
        val worklogId: Long,
        val authorTeam: String?,
        val taskDomain: String,
        val epicId: Long?,
        val epicDomain: String?,
        val activity: String,
        val category: String?,
        val sprintId: Long?,
        val sprintTeam: String?,
        val foreign: Boolean,
        val assignee: String?,
        val assigneeTeam: String?,
        val md: String,
        val lateMs: Long?,
    )

    private data class Actual(
        val worklogId: Long,
        val authorTeam: Int?,
        val taskDomain: String?,
        val epicId: Long?,
        val epicDomain: String?,
        val activity: String?,
        val category: String?,
        val sprintId: Long?,
        val sprintTeam: Int?,
        val foreign: Boolean,
        val assignee: String?,
        val assigneeTeam: Int?,
        val md: BigDecimal,
        val lateMs: Long?,
    )

    @Test
    fun `fact_worklog from the bridges matches the rules on a hand-built world`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-worklog-sql", enabled = false)
        val tag = SyncedStubFixture.unique("wl")
        val teams = listOf("TA", "TB", "TC", "TS").associateWith { TestTeams.seed("$tag-$it").toInt() }
        // aA changes team at day 2; aB is the assignee of several items; aN is in no team.
        val accounts = listOf("aA", "aB", "aC", "aS", "aN").associateWith { "$tag-$it" }
        val c = connId.toInt()

        val items = listOf(
            Item(1, "WLA-1", "Epic", 1, category = "v-run"),
            Item(2, "WLB-2", "Epic", 1),
            Item(3, "WLA-3", "Task", 0, parent = 1, category = "v-build"),
            Item(4, "WLA-4", "Sub-task", -1, parent = 3),
            Item(5, "WLA-5", "Bug", 0),
            Item(6, "WLA-6", "Task", 0, parent = 1),
            Item(7, "WLB-7", "Task", 0, parent = 1),
            Item(8, "WLA-8", "Program", 2, category = "v-build"),
            Item(9, "WLA-9", "Task", 0, deleted = true),
            Item(10, "WLA-10", "Task", 0, movedOut = true),
        )
        val worklogs = listOf(
            Worklog(1001, 3, "aA", 1.0, 7200, 1.0 + 1.0 / 24),
            Worklog(1002, 3, "aA", 3.0, 3600, 3.0),
            Worklog(1003, 3, "aS", 3.0, 1800, null),
            Worklog(1004, 4, "aC", 1.0, 36, 1.0 - 1.0 / 24),
            Worklog(1005, 5, "aC", 1.0, 3600, 1.0),
            Worklog(1006, 5, "aB", 1.0, 3600, 1.0),
            Worklog(1007, 5, "aN", 1.0, 3600, 1.0),
            Worklog(1008, 5, null, 1.0, 3600, 1.0),
            Worklog(1009, 6, "aS", 1.0, 3600, 1.0),
            Worklog(1010, 7, "aC", 1.0, 3600, 1.0),
            Worklog(1011, 7, "aC", 3.0, 3600, 3.0),
            Worklog(1012, 1, "aC", 1.0, 3600, 1.0),
            Worklog(1013, 2, "aB", 1.0, 3600, 1.0),
            Worklog(1014, 8, "aC", 1.0, 3600, 1.0),
            Worklog(1015, 9, "aC", 1.0, 3600, 1.0),
            Worklog(1016, 10, "aC", 1.0, 3600, 1.0),
        )

        try {
            jdbc { conn ->
                // (team, from day, to day): aA changes team at day 2, everyone else stays; aN is in no team.
                val memberships = mapOf(
                    "aA" to listOf(Triple("TA", -100.0, 2.0), Triple("TB", 2.0, null)),
                    "aB" to listOf(Triple("TB", -100.0, null)),
                    "aC" to listOf(Triple("TC", -100.0, null)),
                    "aS" to listOf(Triple("TS", -100.0, null)),
                )
                memberships.forEach { (name, windows) ->
                    windows.forEach { (team, from, to) ->
                        val validTo = to?.let { at(it) } ?: "NULL"
                        conn.exec(
                            "INSERT INTO metrics.team_membership (account_id, team_id, valid_from, valid_to, created_at, updated_at) " +
                                "VALUES (${sqlString(accounts.getValue(name))}, ${teams.getValue(team)}, ${at(from)}, $validTo, 0, 0)",
                        )
                    }
                }
                // The stored configuration: two domains (DOMA owned by TA, DOMB by TB), the FLO-like board 1 -> TS (board 9 unmapped),
                // two activity types and the work-category field with two values.
                val domainMap = "INSERT INTO metrics.domain_map (connection_id, project_key, domain_key, domain_name, owner_team_id) VALUES"
                conn.exec("$domainMap ($c, 'WLA', 'DOMA', 'Domain A', ${teams.getValue("TA")})")
                conn.exec("$domainMap ($c, 'WLB', 'DOMB', 'Domain B', ${teams.getValue("TB")})")
                conn.exec("INSERT INTO metrics.board_team_map (connection_id, board_id, team_id) VALUES ($c, 1, ${teams.getValue("TS")})")
                conn.exec(
                    "INSERT INTO metrics.activity_type_map (connection_id, issue_type, activity_type) " +
                        "VALUES ($c, 'Task', 'Delivery'), ($c, 'Epic', 'Planning')",
                )
                conn.exec(
                    "INSERT INTO metrics.field_config (connection_id, role, field_id) VALUES ($c, 'WORK_CATEGORY', 'customfield_90001')",
                )
                conn.exec(
                    "INSERT INTO metrics.work_category_map (connection_id, value_id, value_name, category) " +
                        "VALUES ($c, 'v-run', 'Run', 'Run'), ($c, 'v-build', 'Build', 'Build')",
                )
                conn.exec(
                    "INSERT INTO norm.sprints (connection_id, sprint_id, board_id, name, state) " +
                        "VALUES ($c, 101, 1, 's1', 'active'), ($c, 202, 9, 's2', 'active')",
                )

                items.forEach { item ->
                    val custom = item.category?.let { """{"customfield_90001": {"id": "$it"}}""" } ?: "{}"
                    val deletedAt = if (item.deleted) at(4.0) else "NULL"
                    val movedOutAt = if (item.movedOut) at(4.0) else "NULL"
                    conn.exec(
                        "INSERT INTO norm.work_items (connection_id, issue_id, issue_key, project_key, issue_type, is_subtask, " +
                            "parent_issue_id, status_id, status_name, status_category, created_at, updated_at, processed_at, " +
                            "processing_version, hierarchy_level, custom_fields, deleted_at, moved_out_at) " +
                            "VALUES ($c, ${item.id}, '${item.key}', '${item.project}', '${item.type}', ${item.subtask}, " +
                            "${item.parent ?: "NULL"}, 's1', 'To Do', 'new', ${at(-5.0)}, ${at(-5.0)}, 0, 1, " +
                            "${item.level}, '$custom'::jsonb, $deletedAt, $movedOutAt)",
                    )
                }
                worklogs.forEach { w ->
                    val author = sqlString(w.author?.let(accounts::getValue))
                    conn.exec(
                        "INSERT INTO norm.work_item_worklogs (connection_id, worklog_id, issue_id, author_account_id, started_at, " +
                            "time_spent_seconds, created_at, updated_at) VALUES ($c, ${w.id}, ${w.issueId}, $author, " +
                            "${at(w.startedDays)}, ${w.seconds}, ${w.createdDays?.let { at(it) } ?: "NULL"}, NULL)",
                    )
                }
                // Field intervals: (issue, field, seq, value, from, to).
                fun interval(issue: Long, field: String, seq: Int, value: String?, from: Double, to: Double?) = conn.exec(
                    "INSERT INTO norm.work_item_field_intervals (connection_id, issue_id, field, seq, value_id, from_at, to_at) " +
                        "VALUES ($c, $issue, '$field', $seq, ${sqlString(value)}, ${at(from)}, ${to?.let { at(it) } ?: "NULL"})",
                )
                // Item 3 sat under epic 2 (domain B) until day 2, then moved to epic 1 AND from project WLB to WLA (an issuekey change).
                interval(3, "PARENT", 1, "2", -5.0, 2.0)
                interval(3, "PARENT", 2, "1", 2.0, null)
                conn.exec(
                    "INSERT INTO norm.work_item_field_changes " +
                        "(connection_id, issue_id, seq, field, changed_at, from_value, to_value, field_id) " +
                        "VALUES ($c, 3, 1, 'Key', ${at(2.0)}, 'WLB-3', 'WLA-3', 'issuekey')",
                )
                interval(3, "SPRINT", 1, "101", -5.0, null)
                interval(3, "ASSIGNEE", 1, accounts.getValue("aB"), -5.0, null)
                // Item 5 sits in an unmapped board's sprint; its assignee is aB.
                interval(5, "SPRINT", 1, "202", -5.0, null)
                interval(5, "ASSIGNEE", 1, accounts.getValue("aB"), -5.0, null)
                interval(6, "PARENT", 1, "1", -5.0, null)
                // Item 7 had NO parent until day 2: a covering row that names no epic.
                interval(7, "PARENT", 1, null, -5.0, 2.0)
                interval(7, "PARENT", 2, "1", 2.0, null)
                interval(1, "ASSIGNEE", 1, accounts.getValue("aB"), -5.0, null)
            }

            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
                DerivedStubFixture.derivePinned(connId, jobId = 1u)
            }

            val expected = listOf(
                // Task 3 under epic 2 / domain B (as-of day 1); author aA is in TA; the sprint team is TS, so foreign.
                Expected(1001, "TA", "DOMB", 2, "DOMB", "Delivery", "Build", 101, "TS", true, "aB", "TB", "0.2500", 3_600_000),
                // Day 3: aA is in TB now; task 3 is in domain A under epic 1.
                Expected(1002, "TB", "DOMA", 1, "DOMA", "Delivery", "Build", 101, "TS", true, "aB", "TB", "0.1250", 0),
                // The sprint team wins over the assignee's team: aS is in TS, the assignee in TB, not foreign; no `created`, no lateness.
                Expected(1003, "TS", "DOMA", 1, "DOMA", "Delivery", "Build", 101, "TS", false, "aB", "TB", "0.0625", null),
                // A sub-task carries its parent's CURRENT epic (1), not the one the parent had at day 1; created before started -> 0;
                // 36 s is 0.00125 man-days, a tie that rounds up to 0.0013; the category is the epic's.
                Expected(1004, "TC", "DOMA", 1, "DOMA", "Sub-task", "Run", null, null, false, null, null, "0.0013", 0),
                // Sprint 202 is in an unmapped board: the id is kept, no sprint team, so the assignee's team (TB) decides.
                Expected(1005, "TC", "DOMA", null, null, "Bug", null, 202, null, true, "aB", "TB", "0.1250", 0),
                Expected(1006, "TB", "DOMA", null, null, "Bug", null, 202, null, false, "aB", "TB", "0.1250", 0),
                // An author in no team, and a worklog without an author: never foreign, no author team.
                Expected(1007, null, "DOMA", null, null, "Bug", null, 202, null, false, "aB", "TB", "0.1250", 0),
                Expected(1008, null, "DOMA", null, null, "Bug", null, 202, null, false, "aB", "TB", "0.1250", 0),
                // Task 6 has no category of its own: its epic's (Run).
                Expected(1009, "TS", "DOMA", 1, "DOMA", "Delivery", "Run", null, null, false, null, null, "0.1250", 0),
                // Task 7 (project WLB) had NO epic at day 1: the covering row says so, the current epic is not used.
                Expected(1010, "TC", "DOMB", null, null, "Delivery", null, null, null, false, null, null, "0.1250", 0),
                Expected(1011, "TC", "DOMB", 1, "DOMA", "Delivery", "Run", null, null, false, null, null, "0.1250", 0),
                // A worklog ON an epic: its own id and (current) domain, its own category, foreign against the domain's owner (TA).
                Expected(1012, "TC", "DOMA", 1, "DOMA", "Planning", "Run", null, null, true, "aB", "TB", "0.1250", 0),
                Expected(1013, "TB", "DOMB", 2, "DOMB", "Planning", null, null, null, false, null, null, "0.1250", 0),
                // Above the epic level (the JVM remainder): own domain, no epic, own category, foreign against the owner team.
                Expected(1014, "TC", "DOMA", null, null, "Program", "Build", null, null, true, null, null, "0.1250", 0),
            )

            val rows = jdbc { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT worklog_id, author_team_id, task_domain_key, epic_id, epic_domain_key, activity_type, work_category, " +
                            "sprint_id_at_started, sprint_team_id_at_started, foreign_work, assignee_account_id_at_started, " +
                            "assignee_team_id_at_started, md, late_ms FROM metrics.fact_worklog " +
                            "WHERE connection_id = $c ORDER BY worklog_id",
                    ).use { rs ->
                        generateSequence {
                            if (!rs.next()) {
                                null
                            } else {
                                fun long(i: Int) = rs.getLong(i).takeUnless { rs.wasNull() }
                                fun int(i: Int) = rs.getInt(i).takeUnless { rs.wasNull() }
                                Actual(
                                    rs.getLong(1), int(2), rs.getString(3), long(4), rs.getString(5), rs.getString(6), rs.getString(7),
                                    long(8), int(9), rs.getBoolean(10), rs.getString(11), int(12), rs.getBigDecimal(13), long(14),
                                )
                            }
                        }.toList()
                    }
                }
            }

            assertEquals(
                expected.map { it.worklogId },
                rows.map { it.worklogId },
                "a deleted and a moved-out item's worklogs get no row, every other one does",
            )
            expected.zip(rows).forEach { (e, a) ->
                val label = "worklog ${e.worklogId}"
                assertEquals(e.authorTeam?.let(teams::getValue), a.authorTeam, "$label author team")
                assertEquals(e.taskDomain, a.taskDomain, "$label task domain")
                assertEquals(e.epicId, a.epicId, "$label epic")
                assertEquals(e.epicDomain, a.epicDomain, "$label epic domain")
                assertEquals(e.activity, a.activity, "$label activity type")
                assertEquals(e.category, a.category, "$label work category")
                assertEquals(e.sprintId, a.sprintId, "$label sprint")
                assertEquals(e.sprintTeam?.let(teams::getValue), a.sprintTeam, "$label sprint team")
                assertEquals(e.foreign, a.foreign, "$label foreign work")
                assertEquals(e.assignee?.let(accounts::getValue), a.assignee, "$label assignee at started")
                assertEquals(e.assigneeTeam?.let(teams::getValue), a.assigneeTeam, "$label assignee team")
                assertEquals(BigDecimal(e.md), a.md, "$label md")
                assertEquals(e.lateMs, a.lateMs, "$label late_ms")
            }
        } finally {
            jdbc { conn ->
                accounts.values.forEach { conn.exec("DELETE FROM metrics.team_membership WHERE account_id = ${sqlString(it)}") }
            }
        }
    }

    @Test
    fun `the foreign-work expression answers like the isForeignWork kernel for every combination of unknown, equal and different teams`() {
        val values = listOf<Int?>(null, 1, 2)
        val combos = values.flatMap { a -> values.flatMap { s -> values.map { f -> Triple(a, s, f) } } }
        val expression = foreignWorkSql("a", "s", "f")
        val answers = jdbc { conn ->
            combos.map { (a, s, f) ->
                conn.createStatement().use { st ->
                    val inputs = "SELECT ${a ?: "NULL"}::int AS a, ${s ?: "NULL"}::int AS s, ${f ?: "NULL"}::int AS f"
                    st.executeQuery("SELECT $expression FROM ($inputs) t").use { rs ->
                        rs.next()
                        rs.getBoolean(1)
                    }
                }
            }
        }
        combos.zip(answers).forEach { (combo, answer) ->
            val (a, s, f) = combo
            assertEquals(isForeignWork(a?.toUInt(), s?.toUInt(), f?.toUInt()), answer, "author=$a sprint=$s fallback=$f")
        }
    }

    @Test
    fun `the md expression stores the very decimal the Kotlin double conversion would`() {
        val random = Random(20261023)
        val hoursPerDay = listOf(8.0, 7.5, 6.0, 7.25, 4.0, 24.0, 0.25, 3.33, 8.5)
        // Seconds that land exactly on a rounding tie at 4 places for some hoursPerDay, then a spread of ordinary and long entries.
        val seconds = listOf(36L, 3L, 1L, 60L, 1_800L, 28_800L, 90L, 7L, 45L) + List(4_000) { random.nextLong(1, 400_000) }
        jdbc { conn ->
            hoursPerDay.forEach { hpd ->
                val expression = worklogMdSql("s", hpd)
                conn.prepareStatement("SELECT s, ROUND($expression, 4) FROM unnest(?) AS s").use { st ->
                    st.setArray(1, conn.createArrayOf("bigint", seconds.toTypedArray()))
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val s = rs.getLong(1)
                            val kotlin = worklogMd(s, hpd).toBigDecimal().setScale(4, RoundingMode.HALF_UP)
                            assertEquals(kotlin, rs.getBigDecimal(2).setScale(4), "seconds=$s hoursPerDay=$hpd")
                        }
                    }
                }
            }
        }
    }
}
