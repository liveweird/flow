package ch.nokillswit

import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.norm.BoardColumnRef
import ch.nokillswit.norm.BoardRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.WipReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val STAGES = listOf("NOT_STARTED", "IN_PROGRESS", "DONE", "UNMAPPED")

/** The kinds an `itemKind` value stands for. */
private fun kindsOf(itemKind: String): Set<String> = if (itemKind == "BOTH") setOf("TASK", "EPIC") else setOf(itemKind)

/** The one team the fixture maps the FLO board to — the only numeric TEAM scope id in the aggregate. */
private fun fixtureTeamId(rows: List<WipRow>): String =
    rows.filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()

/** Per day, the summed count of [rows] under [key] — the independent oracle every series is graded against. */
private fun expectedCounts(rows: List<WipRow>, key: (WipRow) -> String): Map<String, Map<String, Int>> =
    rows.groupBy { it.day }.mapValues { (_, dayRows) -> dayRows.groupBy(key).mapValues { (_, group) -> group.sumOf { it.count } } }

/**
 * `GET /api/v1/reports/wip` (v0.3.0 M5 commit 15, Report 9, `.claude/docs/measures.md` "Report 9 — WIP"). The stub
 * fixture grades every day of every series against a SUM taken straight off the persisted `agg_daily_wip` rows (for the
 * scope, item kind and grouping under test); the zero-fill, the stage/status/column keys, the UNASSIGNED/UNOWNED split and
 * the scope isolation are pinned exactly on hand-built rows in a fresh DISABLED connection.
 */
class ReportWipTest {

    private suspend fun HttpClient.wip(query: String): WipReport {
        val response = get("/api/v1/reports/wip?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET wip?$query")
        return response.body()
    }

    /** Every point lists every key (zero-filled) and equals [expected] for the days it lists. */
    private fun WipReport.assertCounts(label: String, expected: Map<String, Map<String, Int>>) {
        val keyNames = keys.map { it.key }
        for (point in series) {
            assertEquals(keyNames.toSet(), point.counts.keys, "$label ${point.day} carries exactly the legend's keys")
            val want = expected[point.day].orEmpty()
            for (key in keyNames) assertEquals(want[key] ?: 0, point.counts.getValue(key), "$label ${point.day} $key")
        }
        val outsideKeys = expected.values.flatMap { it.keys }.toSet() - keyNames.toSet()
        // A cell whose key is missing from the legend would vanish silently — only allowed on days the series does not list.
        val listed = series.map { it.day }.toSet()
        val listedKeys = expected.filterKeys { it in listed }.values.flatMap { it.keys }
        assertTrue(listedKeys.all { it in keyNames }, "$label: unlisted keys $outsideKeys")
    }

    private val from = "2025-06-01"
    private val to = "2026-03-05"

    @Test
    fun `STAGE series for every item kind equal an independent sum of agg_daily_wip and BOTH is TASK plus EPIC`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-stage")
        val rows = readWipRows(connId).filter { it.scopeKind == "TEAM" }
        val zone = reportZone()
        assertEquals(dayOfInstant(DerivedStubFixture.PINNED_NOW, zone), to, "the test range must end on the pinned DERIVE day")

        val bodies = listOf("TASK", "EPIC", "BOTH").associateWith { kind ->
            client.wip("connectionId=$connId&from=$from&to=$to&itemKind=$kind").also { body ->
                assertEquals("STAGE", body.by.name)
                assertEquals(kind, body.itemKind.name)
                assertEquals(STAGES, body.keys.map { it.key })
                assertEquals(isoDays(from, to), body.series.map { it.day }, "one point per calendar day of the period")
                body.assertCounts(kind, expectedCounts(rows.filter { it.itemKind in kindsOf(kind) }) { it.stage })
                assertEquals("UNIT", body.meta.level.name)
            }
        }
        val tasks = bodies.getValue("TASK").series
        val epics = bodies.getValue("EPIC").series
        val both = bodies.getValue("BOTH").series
        assertTrue(tasks.sumOf { it.counts.getValue("IN_PROGRESS") } > 0, "the fixture must carry in-progress tasks")
        assertTrue(tasks.sumOf { it.counts.getValue("NOT_STARTED") } > 0)
        assertTrue(epics.sumOf { it.counts.values.sum() } > 0, "the fixture must carry epics")
        for (index in both.indices) {
            for (stage in STAGES) {
                val sum = tasks[index].counts.getValue(stage) + epics[index].counts.getValue(stage)
                assertEquals(sum, both[index].counts.getValue(stage), "BOTH = TASK + EPIC (${both[index].day} $stage)")
            }
        }
        val default = client.wip("connectionId=$connId&from=$from&to=$to")
        assertEquals(tasks, default.series, "TASK and STAGE are the defaults")
        assertEquals("TASK", default.itemKind.name)
        assertEquals("STAGE", default.by.name)
        // The report reads the task's own domain whatever `domainView` says, and echoes TASK.
        assertEquals("TASK", client.wip("connectionId=$connId&from=$from&to=$to&domainView=EPIC").meta.domainView.name)
    }

    @Test
    fun `STATUS series equal an independent per-status sum and the legend names every status seen`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-status")
        val rows = readWipRows(connId).filter { it.scopeKind == "TEAM" && it.day in isoDays(from, to) }
        val body = client.wip("connectionId=$connId&from=$from&to=$to&by=STATUS&itemKind=BOTH")

        body.assertCounts("STATUS", expectedCounts(rows) { it.statusId })
        assertEquals(rows.map { it.statusId }.toSet(), body.keys.map { it.key }.toSet(), "the legend is the statuses seen in the period")
        val names = suspendTransaction(sharedDatabaseForTests()) {
            WorkItemStore.Statuses.selectAll().where { WorkItemStore.Statuses.connectionId eq connId }.toList()
                .associate { it[WorkItemStore.Statuses.statusId] to it[WorkItemStore.Statuses.name] }
        }
        assertTrue(body.keys.all { it.label == names.getValue(it.key) }, "labels are the norm.statuses names")
        val stageOf = rows.groupBy { it.statusId }.mapValues { (_, group) -> group.first().stage }
        assertEquals(
            body.keys.map { it.key },
            body.keys.map { it.key }
                .sortedWith(compareBy<String> { STAGES.indexOf(stageOf.getValue(it)) }.thenBy { names.getValue(it) }.thenBy { it }),
            "keys are ordered by stage, then name",
        )
        assertTrue(body.series.sumOf { it.counts.values.sum() } > 0)
    }

    @Test
    fun `a domain reads its DOMAIN scope and the domain scopes together equal the TEAM scopes`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-domain")
        val all = readWipRows(connId)
        val inRange = all.filter { it.day in isoDays(from, to) }
        val domain = inRange.filter { it.scopeKind == "DOMAIN" && it.itemKind == "TASK" }
            .groupBy { it.scopeId }.maxBy { (_, group) -> group.sumOf { it.count } }.key

        for (kind in listOf("TASK", "EPIC", "BOTH")) {
            val body = client.wip("connectionId=$connId&from=$from&to=$to&domain=$domain&itemKind=$kind")
            val domainRows = inRange.filter { it.scopeKind == "DOMAIN" && it.scopeId == domain && it.itemKind in kindsOf(kind) }
            body.assertCounts("$domain/$kind", expectedCounts(domainRows) { it.stage })
        }
        // Invariant: the tasks of all DOMAIN scopes are exactly the tasks of all TEAM scopes (UNIT level).
        val unitTasks = client.wip("connectionId=$connId&from=$from&to=$to&itemKind=TASK")
        val domainTasks = inRange.filter { it.scopeKind == "DOMAIN" && it.itemKind == "TASK" }
        unitTasks.assertCounts("Σ DOMAIN = UNIT", expectedCounts(domainTasks) { it.stage })
    }

    @Test
    fun `TEAM level reads that team's scope and COLUMN keys counts by the mapped board's columns`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-team")
        val inRange = readWipRows(connId).filter { it.day in isoDays(from, to) }
        val teamId = fixtureTeamId(inRange)
        val teamRows = inRange.filter { it.scopeKind == "TEAM" && it.scopeId == teamId }

        val stage = client.wip("connectionId=$connId&from=$from&to=$to&teamId=$teamId&itemKind=BOTH")
        stage.assertCounts("team $teamId", expectedCounts(teamRows) { it.stage })
        assertEquals("TEAM", stage.meta.level.name)
        assertTrue(stage.series.sumOf { it.counts.getValue("IN_PROGRESS") } > 0, "the mapped team must have work in progress")

        // COLUMN: the FLO board's columns, read straight from norm.board_columns and parsed here independently.
        val board = suspendTransaction(sharedDatabaseForTests()) {
            val c = WorkItemStore.BoardColumns
            c.selectAll().where { (c.connectionId eq connId) and (c.boardId eq 1L) }.toList().sortedBy { it[c.seq] }
                .map { it[c.name] to Json.parseToJsonElement(it[c.statusIds]).jsonArray.map { id -> id.jsonPrimitive.content } }
        }
        assertTrue(board.isNotEmpty(), "the FLO board must have columns")
        val columnOf = board.flatMap { (name, ids) -> ids.map { it to name } }.toMap()
        val column = client.wip("connectionId=$connId&from=$from&to=$to&teamId=$teamId&by=COLUMN&itemKind=BOTH")
        column.assertCounts("COLUMN", expectedCounts(teamRows) { columnOf[it.statusId] ?: "(no column)" })
        val columnKeys = column.keys.map { it.key }.filter { it != "(no column)" }
        assertEquals(board.map { it.first }.distinct(), columnKeys, "board columns in board order")
        assertTrue(column.series.sumOf { point -> board.sumOf { point.counts.getValue(it.first) } } > 0, "some work sits in a board column")
        for (point in stage.series.zip(column.series)) {
            val label = "COLUMN and STAGE partition the same items (${point.first.day})"
            assertEquals(point.first.counts.values.sum(), point.second.counts.values.sum(), label)
        }

        // by=COLUMN needs a team with a board: UNIT level, UNASSIGNED and a team with no board are 400.
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/wip?connectionId=$connId&by=COLUMN").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/wip?connectionId=$connId&by=COLUMN&teamId=0").status)
        val boardless = TestTeams.seed(SyncedStubFixture.unique("wip-boardless"))
        try {
            val boardlessColumn = client.get("/api/v1/reports/wip?connectionId=$connId&by=COLUMN&teamId=$boardless")
            assertEquals(HttpStatusCode.BadRequest, boardlessColumn.status)
            // ... but a board-less team is fine for the other groupings: an empty-but-listed series.
            val empty = client.wip("connectionId=$connId&from=$from&to=$to&teamId=$boardless")
            assertEquals(isoDays(from, to), empty.series.map { it.day })
            assertTrue(empty.series.all { point -> point.counts.values.all { it == 0 } })
        } finally {
            cleanUpTeams(listOf(boardless))
        }
    }

    @Test
    fun `teamId 0 is UNASSIGNED tasks plus UNOWNED epics`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-unassigned")
        val rows = readWipRows(connId).filter { it.scopeKind == "TEAM" && it.day in isoDays(from, to) }
        val tasks = rows.filter { it.itemKind == "TASK" && it.scopeId == "UNASSIGNED" }
        val epics = rows.filter { it.itemKind == "EPIC" && it.scopeId == "UNOWNED" }
        assertTrue(tasks.isNotEmpty() && epics.isNotEmpty(), "the fixture must carry unassigned tasks and unowned epics")

        val query = "connectionId=$connId&from=$from&to=$to&teamId=0"
        client.wip("$query&itemKind=TASK").assertCounts("UNASSIGNED tasks", expectedCounts(tasks) { it.stage })
        client.wip("$query&itemKind=EPIC").assertCounts("UNOWNED epics", expectedCounts(epics) { it.stage })
        client.wip("$query&itemKind=BOTH").assertCounts("both", expectedCounts(tasks + epics) { it.stage })
    }

    @Test
    fun `the series stops at the last derived day, sprint periods read the envelope and weekends are flagged`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-window")

        val clamped = client.wip("connectionId=$connId&from=2026-02-20&to=2026-12-31")
        assertEquals(isoDays("2026-02-20", to), clamped.series.map { it.day }, "days past the last derived day are cut off, not zero")
        val beyond = client.wip("connectionId=$connId&from=2026-06-01&to=2026-06-30")
        assertTrue(beyond.series.isEmpty(), "a period entirely past the last derived day lists nothing")
        assertEquals(STAGES, beyond.keys.map { it.key })
        assertNull(beyond.note)

        for (point in client.wip("connectionId=$connId&from=2026-02-23&to=2026-03-01").series) {
            val weekend = LocalDate.parse(point.day).dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            assertEquals(!weekend, point.isWorkingDay, "${point.day} isWorkingDay")
        }

        // lastSprints=2 resolves the two newest sprints — closed in August, past the derived coverage: nothing to read yet.
        val newest = client.wip("connectionId=$connId&lastSprints=2&itemKind=TASK")
        assertTrue(newest.meta.resolvedSprints.flatMap { it.sprintIds }.isNotEmpty() && newest.series.isEmpty())

        // sprintId reads that sprint's own envelope, day by day.
        val zone = reportZone()
        val sprint = readClosedSprints(connId).filter { dayOfInstant(it.completeAt, zone) < to }[4]
        val body = client.wip("connectionId=$connId&sprintId=${sprint.sprintId}&itemKind=TASK")
        assertEquals(listOf(sprint.teamId), body.meta.resolvedSprints.map { it.teamId })
        assertNull(body.meta.from)
        val envelope = isoDays(dayOfInstant(sprint.startAt ?: sprint.completeAt, zone), dayOfInstant(sprint.completeAt, zone))
        assertEquals(envelope, body.series.map { it.day })
        val rows = readWipRows(connId).filter { it.scopeKind == "TEAM" && it.itemKind == "TASK" }
        body.assertCounts("sprintId", expectedCounts(rows) { it.stage })
        assertTrue(body.series.sumOf { it.counts.values.sum() } > 0)
    }

    @Test
    fun `USER level answers an empty series with a note`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-wip-user")
        val teamId = fixtureTeamId(readWipRows(connId))

        val user = client.wip("connectionId=$connId&from=$from&to=$to&teamId=$teamId&accountId=nobody")
        assertTrue(user.series.isEmpty())
        assertNotNull(user.note)
        assertEquals("USER", user.meta.level.name)
        // The same note-and-empty answer for by=COLUMN, whose board check still runs for the team.
        assertTrue(client.wip("connectionId=$connId&from=$from&to=$to&teamId=$teamId&accountId=nobody&by=COLUMN").series.isEmpty())
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-wip-400")
        val team = TestTeams.seed(SyncedStubFixture.unique("wip-400"))
        try {
            for (query in listOf(
                "by=WEEK", "itemKind=NONE", "from=2026-01-01&to=2025-01-01", "accountId=abc", "connectionId=999999", "teamId=999999",
                "domain=AAA&teamId=$team", "activityType=Story", "workCategory=UNCATEGORIZED",
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/wip?$query").status, query)
            }
        } finally {
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `a connection that never derived reads as not derived yet, never as zeros`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-underived", enabled = false)
        insertWipRows(connId, listOf(WipRow("DOMAIN", "ZZ-underived", "2026-01-05", "TASK", "s1", "NOT_STARTED", 4)))
        try {
            val client = seededClient("reports-wip-underived")
            val body = client.wip("connectionId=$connId&from=2026-01-01&to=2026-01-31&domain=ZZ-underived")
            assertTrue(body.series.isEmpty(), "no successful DERIVE: nothing to list, not 31 zeros")
            assertTrue(assertNotNull(body.note).startsWith("Not derived yet"))
            assertEquals(STAGES, body.keys.map { it.key })
            // Once a run succeeded past the period the same rows are listed.
            insertSucceededDerive(connId, noonUtc("2026-06-01"))
            val derived = client.wip("connectionId=$connId&from=2026-01-01&to=2026-01-31&domain=ZZ-underived")
            assertEquals(31, derived.series.size)
            assertEquals(4, derived.series.single { it.day == "2026-01-05" }.counts.getValue("NOT_STARTED"))
            assertNull(derived.note)
        } finally {
            deleteWipRows(connId)
            deleteDeriveRuns(connId)
        }
    }

    /**
     * Two connections with DIFFERENT last-derived days (A through 2025-01-09, B through 2025-01-07) and a third that never
     * derived, read together (no `connectionId`) through a domain only they carry. The series ends at the OLDEST derived day
     * — B's — so A's newer days are not shown against B's missing ones as if B had zeros; the never-derived one is named
     * in the note. (The days sit in 2025 so no other test's connection can derive earlier than they do.)
     */
    @Test
    fun `the cut-off is the oldest last derived day across the connections in scope`() = testApplication {
        usePostgresTestcontainer()
        val connA = SyncedStubFixture.createConnection(namePrefix = "wip-lag-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "wip-lag-b", enabled = false)
        val connC = SyncedStubFixture.createConnection(namePrefix = "wip-lag-c", enabled = false)
        val domain = "ZZ-lag"
        insertWipRows(connA, (5..9).map { WipRow("DOMAIN", domain, "2025-01-%02d".format(it), "TASK", "s1", "NOT_STARTED", it - 4) })
        insertWipRows(connB, (5..7).map { WipRow("DOMAIN", domain, "2025-01-%02d".format(it), "TASK", "s1", "NOT_STARTED", 100) })
        insertSucceededDerive(connA, noonUtc("2025-01-09"))
        insertSucceededDerive(connB, noonUtc("2025-01-07"))
        try {
            val client = seededClient("reports-wip-lag")
            val body = client.wip("from=2025-01-05&to=2025-01-11&domain=$domain")
            assertEquals(listOf("2025-01-05", "2025-01-06", "2025-01-07"), body.series.map { it.day })
            assertEquals(listOf(101, 102, 103), body.series.map { it.counts.getValue("NOT_STARTED") })
            assertTrue(assertNotNull(body.note).contains(connC.toString()), "the never-derived connection is named")
            // Narrowed to the connection that derived further, its own days are all listed and nothing is noted.
            val onlyA = client.wip("connectionId=$connA&from=2025-01-05&to=2025-01-11&domain=$domain")
            assertEquals((5..9).map { "2025-01-%02d".format(it) }, onlyA.series.map { it.day })
            assertNull(onlyA.note)
        } finally {
            deleteWipRows(connA)
            deleteWipRows(connB)
            deleteDeriveRuns(connA)
            deleteDeriveRuns(connB)
        }
    }

    @Test
    fun `teamId 0 with a sprint-relative period is empty with a note`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val body = seededClient("reports-wip-unassigned-sprints").wip("connectionId=$connId&teamId=0&lastSprints=2")
        assertTrue(body.series.isEmpty())
        assertTrue(assertNotNull(body.note).contains("UNASSIGNED"))
    }

    /**
     * Hand-built cells (fresh DISABLED connection, Mon 2026-01-05 .. Sun 2026-01-11): team X owns a board with columns
     * "To do" [s1] and "Doing" [s2, s3]; status s9 sits in no column. Every value below is hand-computed.
     */
    @Test
    fun `hand-built cells pin the zero-fill, the stage, status and column keys, the item kinds and the scope split`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-hand", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = SyncedStubFixture.workItems()
        val teamX = TestTeams.seed(SyncedStubFixture.unique("wip-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("wip-y"))
        val x = teamX.toString()
        fun cell(kind: String, id: String, day: String, item: String, status: String, stage: String, count: Int) =
            WipRow(kind, id, day, item, status, stage, count)
        val d5 = "2026-01-05"
        val d7 = "2026-01-07"
        insertWipRows(
            connId,
            listOf(
                // team X: day 5 = 3 to do, 2 (+1 epic) doing, 1 unmapped; day 6 nothing; day 7 = 4 + 1 doing
                cell("TEAM", x, d5, "TASK", "s1", "NOT_STARTED", 3),
                cell("TEAM", x, d5, "TASK", "s2", "IN_PROGRESS", 2),
                cell("TEAM", x, d5, "TASK", "s9", "UNMAPPED", 1),
                cell("TEAM", x, d5, "EPIC", "s2", "IN_PROGRESS", 1),
                cell("TEAM", x, d7, "TASK", "s2", "IN_PROGRESS", 4),
                cell("TEAM", x, d7, "TASK", "s3", "IN_PROGRESS", 1),
                // another team, the unassigned/unowned buckets, and a domain: never leak into team X
                cell("TEAM", teamY.toString(), d5, "TASK", "s2", "IN_PROGRESS", 10),
                cell("TEAM", "UNASSIGNED", d5, "TASK", "s1", "NOT_STARTED", 2),
                cell("TEAM", "UNOWNED", d5, "EPIC", "s1", "NOT_STARTED", 5),
                cell("TEAM", "UNASSIGNED", d5, "EPIC", "s1", "NOT_STARTED", 100), // an epic is never UNASSIGNED
                cell("DOMAIN", "AAA", d5, "TASK", "s2", "IN_PROGRESS", 7),
                cell("DOMAIN", "AAA", d5, "EPIC", "s2", "DONE", 3),
            ),
        )
        val columnsKey = listOf(BoardColumnRef("To do", listOf("s1")), BoardColumnRef("Doing", listOf("s2", "s3")))
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceStatuses(
                connId,
                listOf(
                    StatusRef("s1", "Open", StatusCategory.TODO), StatusRef("s2", "Dev", StatusCategory.IN_PROGRESS),
                    StatusRef("s3", "Review", StatusCategory.IN_PROGRESS), StatusRef("s9", "Weird", StatusCategory.UNKNOWN),
                ),
            )
            store.replaceBoards(connId, listOf(BoardRef(77L, "X board", "scrum", null, columnsKey)))
            MetricsConfigService.BoardTeamMap.insert {
                it[MetricsConfigService.BoardTeamMap.connectionId] = connId
                it[MetricsConfigService.BoardTeamMap.boardId] = 77L
                it[MetricsConfigService.BoardTeamMap.teamId] = teamX
            }
        }
        try {
            val client = seededClient("reports-wip-hand-built")
            val base = "connectionId=$connId&from=$d5&to=2026-01-11"
            val both = "$base&itemKind=BOTH"

            // STAGE, BOTH: zero-filled across the whole week, weekend flagged.
            val stage = client.wip("$both&teamId=$teamX")
            assertEquals(isoDays(d5, "2026-01-11"), stage.series.map { it.day })
            assertEquals(listOf(true, true, true, true, true, false, false), stage.series.map { it.isWorkingDay })
            fun WipReport.day(day: String) = series.single { it.day == day }.counts
            assertEquals(mapOf("NOT_STARTED" to 3, "IN_PROGRESS" to 3, "DONE" to 0, "UNMAPPED" to 1), stage.day(d5))
            assertEquals(STAGES.associateWith { 0 }, stage.day("2026-01-06"))
            assertEquals(mapOf("NOT_STARTED" to 0, "IN_PROGRESS" to 5, "DONE" to 0, "UNMAPPED" to 0), stage.day(d7))
            assertEquals(STAGES.associateWith { 0 }, stage.day("2026-01-11"))
            assertEquals(2, client.wip("$base&teamId=$teamX").day(d5).getValue("IN_PROGRESS"), "TASK is the default item kind")
            assertEquals(2, client.wip("$base&teamId=$teamX&itemKind=TASK").day(d5).getValue("IN_PROGRESS"))
            assertEquals(1, client.wip("$base&teamId=$teamX&itemKind=EPIC").day(d5).getValue("IN_PROGRESS"))

            // STATUS: keys ordered by stage then name, labelled from norm.statuses.
            val status = client.wip("$both&teamId=$teamX&by=STATUS")
            assertEquals(listOf("s1", "s2", "s3", "s9"), status.keys.map { it.key })
            assertEquals(listOf("Open", "Dev", "Review", "Weird"), status.keys.map { it.label })
            assertEquals(mapOf("s1" to 3, "s2" to 3, "s3" to 0, "s9" to 1), status.day(d5))
            assertEquals(mapOf("s1" to 0, "s2" to 4, "s3" to 1, "s9" to 0), status.day(d7))

            // COLUMN: board order, the epic's s2 counts in Doing, s9 has no column.
            val column = client.wip("$both&teamId=$teamX&by=COLUMN")
            assertEquals(listOf("To do", "Doing", "(no column)"), column.keys.map { it.key })
            assertEquals(mapOf("To do" to 3, "Doing" to 3, "(no column)" to 1), column.day(d5))
            assertEquals(mapOf("To do" to 0, "Doing" to 5, "(no column)" to 0), column.day(d7))
            // ... and without any status outside the columns the "(no column)" key is not offered.
            val tasksOnly = client.wip("connectionId=$connId&from=$d7&to=$d7&teamId=$teamX&by=COLUMN&itemKind=BOTH")
            assertEquals(listOf("To do", "Doing"), tasksOnly.keys.map { it.key })

            // UNIT sums every TEAM scope (X + Y + UNASSIGNED + UNOWNED) and none of the DOMAIN scopes.
            val unit = client.wip(both)
            assertEquals(mapOf("NOT_STARTED" to 3 + 2 + 5 + 100, "IN_PROGRESS" to 3 + 10, "DONE" to 0, "UNMAPPED" to 1), unit.day(d5))
            // A domain reads its own scope only.
            val domain = client.wip("$both&domain=AAA")
            assertEquals(mapOf("NOT_STARTED" to 0, "IN_PROGRESS" to 7, "DONE" to 3, "UNMAPPED" to 0), domain.day(d5))
            assertEquals(7, client.wip("$base&domain=AAA&itemKind=TASK").day(d5).getValue("IN_PROGRESS"))
            assertEquals(STAGES.associateWith { 0 }, client.wip("$both&domain=NOPE").day(d5))

            // teamId=0: UNASSIGNED tasks (2) + UNOWNED epics (5); the UNASSIGNED epics never count.
            assertEquals(2, client.wip("$base&teamId=0&itemKind=TASK").day(d5).getValue("NOT_STARTED"))
            assertEquals(5, client.wip("$base&teamId=0&itemKind=EPIC").day(d5).getValue("NOT_STARTED"))
            assertEquals(7, client.wip("$both&teamId=0").day(d5).getValue("NOT_STARTED"))
            assertEquals(2, client.wip("$base&teamId=0").day(d5).getValue("NOT_STARTED"), "the default reads UNASSIGNED tasks only")
        } finally {
            deleteWipRows(connId)
            deleteDeriveRuns(connId)
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsConfigService.BoardTeamMap.deleteWhere { MetricsConfigService.BoardTeamMap.connectionId eq connId }
                store.replaceBoards(connId, emptyList())
                store.replaceStatuses(connId, emptyList())
            }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }
}
