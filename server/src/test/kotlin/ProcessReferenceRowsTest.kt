package ch.nokillswit

import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.metrics.MetricsTables
import io.r2dbc.spi.R2dbcException
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PROCESS's reference rows (`norm.statuses`/`people`/`boards`/`board_columns`/`sprints`) are rebuilt
 * WHOLESALE once per run, OUTSIDE the per-issue bad-row classifier, so one over-long Jira value used to
 * fail every PROCESS (Exposed's client-side `varchar(n)` check threw out of `run()`), fixed by V19 and
 * `WorkItemStore`'s skip-and-log guard (`.claude/docs/ingestion.md` "Reference-row robustness").
 *
 * Every test clones the shared synced connection's RAW rows into its own DISABLED connection
 * ([SyncedStubFixture.cloneRawData] — `.claude/docs/testing.md`, the shared connection is never mutated),
 * plants the value in `raw.jira_entities` and flags ONE issue, so a run is a page of one plus the
 * reference rebuild — cheap, yet it proves the rest of the run still lands.
 */
class ProcessReferenceRowsTest {
    private companion object {
        const val STORE_LOGGER = "ch.nokillswit.norm.WorkItemStore"
        const val PROCESS_LOGGER = "ch.nokillswit.jira.JiraProcessStream"
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use(block)

    private fun update(sql: String): Int = jdbc { conn -> conn.createStatement().use { it.executeUpdate(sql) } }

    private fun execute(sql: String) {
        jdbc { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    /** First column of every row as text. */
    private fun query(sql: String): List<String?> = jdbc { conn ->
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    private suspend fun clonedConnectionWithOneFlaggedIssue(): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-refrows", enabled = false)
        SyncedStubFixture.cloneRawData(SyncedStubFixture.connectionId(), connId)
        update("UPDATE raw.jira_issues SET needs_processing = false WHERE connection_id = $connId")
        update(
            "UPDATE raw.jira_issues SET needs_processing = true WHERE connection_id = $connId AND issue_id = " +
                "(SELECT min(issue_id) FROM raw.jira_issues WHERE connection_id = $connId)",
        )
        return connId
    }

    /**
     * Sets [path] (a `jsonb_set` path like `{name}`) of ONE [kind] entity of [connId] to [value] — the
     * entity with the lowest (`first`) or highest `entity_id`. Asserts a row matched.
     */
    private fun plant(connId: UInt, kind: String, path: String, value: String, first: Boolean = true) {
        val pick = if (first) "min" else "max"
        val changed = update(
            "UPDATE raw.jira_entities SET payload = jsonb_set(payload, '$path', to_jsonb('$value'::text)) " +
                "WHERE connection_id = $connId AND kind = '$kind' AND entity_id = " +
                "(SELECT $pick(entity_id) FROM raw.jira_entities WHERE connection_id = $connId AND kind = '$kind')",
        )
        assertEquals(1, changed, "the stub must carry a $kind entity to plant $path on")
    }

    /**
     * A SYNTHETIC extra STATUS entity (a copy of an existing one under a `zz-` entity id, so `plant(first = false)` finds it) —
     * no issue references it, so a planted status value exercises the reference rebuild alone. Status names/ids also flow
     * through the per-issue normalization into `norm.work_items.status_name`/`status_id` (still bounded; the BACKLOG keeps
     * that, counted-bad-row path), which would otherwise fail the flagged issue and blur what this class pins.
     */
    private fun addSyntheticStatus(connId: UInt, statusId: String) {
        val changed = update(
            "INSERT INTO raw.jira_entities (connection_id, kind, entity_id, payload, sha256, first_seen_at, last_seen_at, changed_at) " +
                "SELECT connection_id, kind, 'zz-synthetic', jsonb_set(payload, '{id}', to_jsonb('$statusId'::text)), sha256, " +
                "first_seen_at, last_seen_at, changed_at FROM raw.jira_entities WHERE connection_id = $connId AND kind = 'STATUS' " +
                "AND entity_id = (SELECT min(entity_id) FROM raw.jira_entities WHERE connection_id = $connId AND kind = 'STATUS')",
        )
        assertEquals(1, changed)
    }

    private fun installTrigger(connId: UInt, table: String, sqlState: String, message: String): () -> Unit {
        val trigger = "trg_ref_${sqlState}_$connId"
        val function = "public.fn_ref_${sqlState}_$connId"
        execute(
            "CREATE OR REPLACE FUNCTION $function() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN IF NEW.connection_id = $connId THEN RAISE EXCEPTION '$message' USING ERRCODE = '$sqlState'; END IF; " +
                "RETURN NEW; END \$\$",
        )
        execute("CREATE TRIGGER $trigger BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION $function()")
        return {
            execute("DROP TRIGGER IF EXISTS $trigger ON $table")
            execute("DROP FUNCTION IF EXISTS $function()")
        }
    }

    private fun Throwable.causeChain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun sprintIds(connId: UInt): List<String?> =
        query("SELECT sprint_id FROM norm.sprints WHERE connection_id = $connId ORDER BY sprint_id")

    /** Plants long names on the flagged (lowest id) issue — issue type, priority, resolution — and on the STATUS it is in. */
    private fun plantReferencedNames(connId: UInt) {
        val flagged = "(SELECT min(issue_id) FROM raw.jira_issues WHERE connection_id = $connId)"
        assertEquals(
            1,
            update(
                "UPDATE raw.jira_entities SET payload = jsonb_set(payload, '{name}', to_jsonb(repeat('S', 300))) " +
                    "WHERE connection_id = $connId AND kind = 'STATUS' AND payload->>'id' = " +
                    "(SELECT payload->'fields'->'status'->>'id' FROM raw.jira_issues " +
                    "WHERE connection_id = $connId AND issue_id = $flagged)",
            ),
            "the flagged issue's current status must be a STATUS entity",
        )
        assertEquals(
            1,
            update(
                "UPDATE raw.jira_issues SET payload = jsonb_set(jsonb_set(jsonb_set(payload, " +
                    "'{fields,issuetype,name}', to_jsonb(repeat('T', 120))), " +
                    "'{fields,priority}', jsonb_build_object('name', repeat('P', 120))), " +
                    "'{fields,resolution}', jsonb_build_object('name', repeat('R', 150))) " +
                    "WHERE connection_id = $connId AND issue_id = $flagged",
            ),
        )
    }

    private fun maxLength(table: String, column: String, connId: UInt): Int =
        query("SELECT coalesce(max(length($column)), 0) FROM $table WHERE connection_id = $connId").single()!!.toInt()

    private fun count(table: String, connId: UInt, extra: String = ""): Int =
        query("SELECT count(*) FROM $table WHERE connection_id = $connId $extra").single()!!.toInt()

    private fun rawEntityCount(connId: UInt, kind: String): Int =
        count("raw.jira_entities", connId, "AND kind = '$kind'")

    @Test
    fun `over-long referenced names (status, issue type, priority, resolution, board, sprint, person) are stored in full`() =
        runBlocking<Unit> {
            val connId = clonedConnectionWithOneFlaggedIssue()
            plantReferencedNames(connId)
            plant(connId, "USER", "{displayName}", "P".repeat(400))
            plant(connId, "USER", "{emailAddress}", "e".repeat(300) + "@example.com")
            plant(connId, "BOARD", "{name}", "B".repeat(300))
            plant(connId, "BOARD_CONFIGURATION", "{columnConfig,columns,0,name}", "C".repeat(300))
            plant(connId, "SPRINT", "{name}", "X".repeat(500))
            val context = SyncedStubFixture.freshContext(connId)

            JiraProcessStream(SyncedStubFixture.rawStore(), SyncedStubFixture.workItems()).run(context)

            assertEquals(300, maxLength("norm.statuses", "name", connId))
            // The flagged issue references that status (and carries the long issue type / priority / resolution names), so its
            // per-issue copies must hold them in full — else the issue would fail every PROCESS pass.
            assertEquals(300, maxLength("norm.work_items", "status_name", connId))
            assertEquals(300, maxLength("norm.work_item_status_intervals", "status_name", connId))
            assertEquals(120, maxLength("norm.work_items", "issue_type", connId))
            assertEquals(120, maxLength("norm.work_items", "priority", connId))
            assertEquals(150, maxLength("norm.work_items", "resolution", connId))
            assertEquals(400, maxLength("norm.people", "display_name", connId))
            assertEquals(312, maxLength("norm.people", "email", connId))
            assertEquals(300, maxLength("norm.boards", "name", connId))
            assertEquals(300, maxLength("norm.board_columns", "name", connId))
            assertEquals(500, maxLength("norm.sprints", "name", connId))
            assertEquals(mapOf("issuesProcessed" to 1L), context.progressSnapshot(), "nothing skipped, the flagged issue landed")
            assertEquals(1, count("norm.work_items", connId))
            assertEquals(0L, SyncedStubFixture.rawStore().countNeedsProcessing(connId))
        }

    @Test
    fun `an issue whose changelog changed a custom field with a 120-character display name processes`() = runBlocking<Unit> {
        val connId = clonedConnectionWithOneFlaggedIssue()
        val longName = "F".repeat(120)
        // The changelog item's `field` is the custom field's DISPLAY name (Jira allows up to 255 characters); it lands in
        // `norm.work_item_field_changes.field`, which used to be varchar(50) and failed that issue on every PROCESS pass.
        assertEquals(
            1,
            update(
                "INSERT INTO raw.jira_changelogs (connection_id, history_id, issue_id, created_at, payload, fetched_at) " +
                    "SELECT $connId, 9000000001, min(issue_id), 1714557600000, jsonb_build_object('id', '9000000001', " +
                    "'created', '2024-05-01T10:00:00.000+0000', 'items', jsonb_build_array(jsonb_build_object(" +
                    "'field', '$longName', 'fieldId', 'customfield_99999', 'fieldtype', 'custom', " +
                    "'fromString', 'a', 'toString', 'b'))), 1714557600000 FROM raw.jira_issues WHERE connection_id = $connId",
            ),
        )
        val context = SyncedStubFixture.freshContext(connId)

        JiraProcessStream(SyncedStubFixture.rawStore(), SyncedStubFixture.workItems()).run(context)

        assertEquals(mapOf("issuesProcessed" to 1L), context.progressSnapshot())
        assertEquals(120, maxLength("norm.work_item_field_changes", "field", connId))
        assertEquals(0L, SyncedStubFixture.rawStore().countNeedsProcessing(connId))
    }

    @Test
    fun `a sprint name longer than the old limit also fits the metrics dim_sprint copy DERIVE writes from it`() = runBlocking<Unit> {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-refrows-dim", enabled = false)
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DimSprint.insert {
                it[connectionId] = connId
                it[sprintId] = 910_001L
                it[name] = "D".repeat(500)
                it[state] = "closed"
                it[configRevision] = 1L
            }
        }
        try {
            assertEquals(500, maxLength("metrics.dim_sprint", "name", connId))
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.DimSprint.deleteWhere { MetricsTables.DimSprint.connectionId eq connId }
            }
        }
    }

    @Test
    fun `the widened columns are TEXT while keys and enum values stay bounded`() {
        fun dataType(table: String, column: String): String = query(
            "SELECT data_type FROM information_schema.columns " +
                "WHERE table_schema = '${table.substringBefore('.')}' AND table_name = '${table.substringAfter('.')}' " +
                "AND column_name = '$column'",
        ).single()!!

        listOf(
            "norm.statuses" to "name", "norm.people" to "display_name", "norm.people" to "email", "norm.boards" to "name",
            "norm.board_columns" to "name", "norm.sprints" to "name", "metrics.dim_sprint" to "name",
            "norm.work_items" to "issue_type", "norm.work_items" to "status_name", "norm.work_items" to "resolution",
            "norm.work_items" to "priority", "norm.work_item_status_intervals" to "status_name",
            "metrics.dim_task" to "issue_type", "metrics.dim_task" to "activity_type",
            "metrics.fact_task_delivery" to "activity_type", "metrics.fact_worklog" to "activity_type",
            "metrics.activity_type_map" to "issue_type", "metrics.activity_type_map" to "activity_type",
            "norm.work_item_field_changes" to "field",
            "metrics.work_category_map" to "value_id", "metrics.work_category_map" to "value_name",
        ).forEach { (table, column) -> assertEquals("text", dataType(table, column), "$table.$column") }
        listOf(
            "norm.statuses" to "status_id", "norm.people" to "account_id", "norm.boards" to "board_type",
            "norm.boards" to "project_key", "norm.sprints" to "state", "norm.work_items" to "status_id",
            "norm.work_items" to "issue_key", "norm.work_items" to "assignee_account_id",
            "norm.work_item_field_intervals" to "field", // an internal tag (ASSIGNEE/SPRINT/…), not a Jira name
        ).forEach { (table, column) -> assertEquals("character varying", dataType(table, column), "$table.$column") }
    }

    @Test
    fun `a reference row whose bounded key overflows its column is skipped and logged - every other row and the run land`() =
        runBlocking<Unit> {
            val connId = clonedConnectionWithOneFlaggedIssue()
            addSyntheticStatus(connId, "9".repeat(60))
            val statusesBefore = rawEntityCount(connId, "STATUS")
            val usersBefore = rawEntityCount(connId, "USER")
            val boardsBefore = rawEntityCount(connId, "BOARD")
            val sprintsBefore = rawEntityCount(connId, "SPRINT")
            assertTrue(
                boardsBefore >= 3 && statusesBefore > 1 && usersBefore > 1 && sprintsBefore > 1,
                "the stub needs several rows of each kind",
            )
            val badAccountId = "A".repeat(150)
            plant(connId, "USER", "{accountId}", badAccountId, first = false)
            plant(connId, "BOARD", "{type}", "t".repeat(30), first = false)
            // `{location}` is replaced whole (jsonb_set cannot create a nested missing key) — a different board than the type one.
            update(
                "UPDATE raw.jira_entities SET payload = jsonb_set(payload, '{location}', " +
                    "jsonb_build_object('projectKey', repeat('K', 25))) WHERE connection_id = $connId AND kind = 'BOARD' AND entity_id = " +
                    "(SELECT min(entity_id) FROM raw.jira_entities WHERE connection_id = $connId AND kind = 'BOARD')",
            )
            plant(connId, "SPRINT", "{state}", "s".repeat(25), first = false)
            val context = SyncedStubFixture.freshContext(connId)
            val storeLogs = LogCapture(STORE_LOGGER)

            try {
                JiraProcessStream(SyncedStubFixture.rawStore(), SyncedStubFixture.workItems()).run(context)
            } finally {
                storeLogs.detach()
            }

            assertEquals(statusesBefore - 1, count("norm.statuses", connId))
            assertEquals(usersBefore - 1, count("norm.people", connId))
            assertEquals(boardsBefore - 2, count("norm.boards", connId))
            assertEquals(sprintsBefore - 1, count("norm.sprints", connId))
            assertEquals(mapOf("issuesProcessed" to 1L, "referenceRowsSkipped" to 5L), context.progressSnapshot())
            assertEquals(1, count("norm.work_items", connId), "the flagged issue still lands")
            val lines = storeLogs.events.map { it.formattedMessage }
            listOf("norm.statuses", "norm.people", "norm.boards", "norm.sprints").forEach { table ->
                assertTrue(lines.any { it.contains(table) && it.contains("skipped") }, "a log line must name $table")
            }
            assertFalse(lines.any { it.contains(badAccountId) }, "a person's account id must not be logged")
        }

    @Test
    fun `a bad value that still escapes a reference rebuild keeps that table's previous rows and ends the run normally`() =
        runBlocking<Unit> {
            val connId = clonedConnectionWithOneFlaggedIssue()
            val rawStore = SyncedStubFixture.rawStore()
            val process = JiraProcessStream(rawStore, SyncedStubFixture.workItems())
            process.run(SyncedStubFixture.freshContext(connId)) // a clean first run builds the reference rows
            val sprintsBefore = sprintIds(connId)
            val statusesBefore = count("norm.statuses", connId)
            assertTrue(sprintsBefore.size > 1 && statusesBefore > 0, "the first run must have written sprints and statuses")
            // A second run, with a data-exception trigger on norm.sprints and a changed sprint payload that WOULD land if the
            // rebuild committed — the sprint rows must stay exactly as the first run left them.
            plant(connId, "SPRINT", "{name}", "changed-by-the-second-run")
            update(
                "UPDATE raw.jira_issues SET needs_processing = true WHERE connection_id = $connId AND issue_id = " +
                    "(SELECT min(issue_id) FROM raw.jira_issues WHERE connection_id = $connId)",
            )
            val dropTrigger = installTrigger(connId, "norm.sprints", "22001", "simulated bad value")
            val context = SyncedStubFixture.freshContext(connId)
            val logs = LogCapture(PROCESS_LOGGER)

            try {
                process.run(context)
            } finally {
                logs.detach()
                dropTrigger()
            }

            assertEquals(mapOf("issuesProcessed" to 1L, "referenceRowsSkipped" to 1L), context.progressSnapshot())
            assertEquals(sprintsBefore, sprintIds(connId), "the failed rebuild rolled back — the previous sprint rows are untouched")
            assertEquals(0, count("norm.sprints", connId, "AND name = 'changed-by-the-second-run'"))
            assertEquals(statusesBefore, count("norm.statuses", connId), "the other reference tables still rebuilt")
            assertNotNull(logs.events.firstOrNull { it.formattedMessage.contains("norm.sprints rebuild failed") })
            assertEquals(0L, rawStore.countNeedsProcessing(connId), "the page after the reference step still ran")
        }

    @Test
    fun `a non-data database error in a reference rebuild still fails the run - an outage is not a row`() = runBlocking<Unit> {
        val connId = clonedConnectionWithOneFlaggedIssue()
        val dropTrigger = installTrigger(connId, "norm.sprints", "55000", "simulated outage")
        val context = SyncedStubFixture.freshContext(connId)

        val failure = try {
            assertFailsWith<Exception> { JiraProcessStream(SyncedStubFixture.rawStore(), SyncedStubFixture.workItems()).run(context) }
        } finally {
            dropTrigger()
        }

        assertTrue(
            failure.causeChain().any { it is R2dbcException && it.sqlState == "55000" && it.message?.contains("simulated outage") == true },
            "the failure must carry the simulated outage's SQLSTATE 55000, was $failure",
        )
        assertEquals(
            1L, SyncedStubFixture.rawStore().countNeedsProcessing(connId),
            "no issue was processed, so it stays flagged for the retry",
        )
    }

    @Test
    fun `an integrity violation in a reference rebuild fails the run - it is a writer bug, not a bad Jira value`() = runBlocking<Unit> {
        val connId = clonedConnectionWithOneFlaggedIssue()
        val dropTrigger = installTrigger(connId, "norm.sprints", "23505", "simulated duplicate key")
        val context = SyncedStubFixture.freshContext(connId)

        val failure = try {
            assertFailsWith<Exception> { JiraProcessStream(SyncedStubFixture.rawStore(), SyncedStubFixture.workItems()).run(context) }
        } finally {
            dropTrigger()
        }

        assertTrue(
            failure.causeChain().any { it is R2dbcException && it.sqlState == "23505" },
            "the failure must carry SQLSTATE 23505, was $failure",
        )
        assertEquals(emptyMap(), context.progressSnapshot(), "no page ran")
    }
}
