package ch.nokillswit

import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.DeepDiveEpicOption
import ch.nokillswit.reports.DeepDiveEpicPageResponse
import ch.nokillswit.reports.DeepDiveSprintOption
import ch.nokillswit.reports.DeepDiveSprintPageResponse
import ch.nokillswit.reports.DeepDiveTaskOption
import ch.nokillswit.reports.DeepDiveTaskPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sprint/epic/task facts as stored — the independent read every endpoint is graded against (never the endpoints' own queries). */
private class Stored(
    val sprints: Map<Long, DeepDiveSprintOption>,
    /** (sprint id, issue id) of every `in_scope_at_close` scope row. */
    val scope: List<Pair<Long, Long>>,
    /** issue id → (key, domain, epic issue id) of every level-0 `fact_task_delivery` row. */
    val tasks: Map<Long, Triple<String, String?, Long?>>,
    val epics: List<DeepDiveEpicOption>,
    val summaries: Map<Long, String?>,
)

private suspend fun readStored(connId: UInt): Stored = suspendTransaction(sharedDatabaseForTests()) {
    val s = MetricsTables.DimSprint
    val sprints = s.selectAll().where { s.connectionId eq connId }.toList().associate {
        it[s.sprintId] to DeepDiveSprintOption(
            it[s.sprintId], connId, it[s.name], it[s.state], it[s.startAt], it[s.endAt], it[s.completeAt], 0,
        )
    }
    val sc = MetricsTables.FactSprintScope
    val scope = sc.selectAll().where { (sc.connectionId eq connId) and (sc.inScopeAtClose eq true) }.toList()
        .map { it[sc.sprintId] to it[sc.issueId] }
    val t = MetricsTables.FactTaskDelivery
    val tasks = t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList()
        .associate { it[t.issueId] to Triple(it[t.issueKey], it[t.domainKey], it[t.epicId]) }
    val e = MetricsTables.DimEpic
    val epics = e.selectAll().where { e.connectionId eq connId }.toList()
        .map { DeepDiveEpicOption(it[e.issueId], connId, it[e.issueKey], it[e.summary], it[e.domainKey]) }
    val w = WorkItemStore.WorkItems
    val summaries = w.selectAll().where { w.connectionId eq connId }.toList().associate { it[w.issueId] to it[w.summary] }
    Stored(sprints, scope, tasks, epics, summaries)
}

/** The accent/uppercase variant of [text]: upper-cased with the Polish diacritics a Postgres `unaccent` folds back (Ą→A, Ł→L, ...). */
private fun accented(text: String): String = text.uppercase().map {
    when (it) {
        'A' -> 'Ą'
        'C' -> 'Ć'
        'E' -> 'Ę'
        'L' -> 'Ł'
        'N' -> 'Ń'
        'O' -> 'Ó'
        'S' -> 'Ś'
        'Z' -> 'Ż'
        else -> it
    }
}.joinToString("")

private fun enc(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

/**
 * The deep dive's three picker option lists (`GET /api/v1/reports/deep-dive/{sprints,epics,epics/{epicKey}/tasks}`, Report 17,
 * `.claude/docs/reports.md`). The shared derived fixture is graded read-only against independent reads of the stored facts; the
 * accent folding and the cross-connection ambiguity run on hand-built rows in a fresh DISABLED connection. Every request goes through a
 * non-admin `seededClient` (D12); the `401` comes from the spec sweep (`AnonymousAccessTest`).
 */
class ReportDeepDiveOptionsTest {

    private val base = "/api/v1/reports/deep-dive"

    private suspend fun HttpClient.sprints(query: String): DeepDiveSprintPageResponse {
        val response = get("$base/sprints?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET sprints?$query")
        return response.body()
    }

    private suspend fun HttpClient.epics(query: String): DeepDiveEpicPageResponse {
        val response = get("$base/epics?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epics?$query")
        return response.body()
    }

    private suspend fun HttpClient.epicTasks(epicKey: String, query: String): DeepDiveTaskPageResponse {
        val response = get("$base/epics/$epicKey/tasks?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epics/$epicKey/tasks?$query")
        return response.body()
    }

    private suspend fun HttpClient.assertBadRequest(path: String) {
        val response: HttpResponse = get(path)
        assertEquals(HttpStatusCode.BadRequest, response.status, "GET $path")
    }

    /** The expected sprint options of [domain]: every sprint with at least one in-scope level-0 task of that domain, with its count. */
    private fun expectedSprints(stored: Stored, domain: String): List<DeepDiveSprintOption> =
        stored.scope.filter { (_, issueId) -> stored.tasks[issueId]?.second == domain }
            .groupBy({ it.first }, { it.second })
            .map { (sprintId, issues) -> stored.sprints.getValue(sprintId).copy(taskCount = issues.distinct().size) }

    /** The domain with the most sprints in the fixture — the one the paging and `q` assertions need several rows of. */
    private fun busiestDomain(stored: Stored): String =
        stored.tasks.values.mapNotNull { it.second }.distinct()
            .maxBy { expectedSprints(stored, it).size }

    @Test
    fun `sprints - a domain's sprints with their task counts equal an independent read, paged, sorted and filtered`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-sprints")
        val stored = readStored(connId)
        val domain = busiestDomain(stored)
        val expected = expectedSprints(stored, domain)
        assertTrue(expected.size >= 3, "the fixture must hold a domain in at least three sprints (got ${expected.size})")
        val query = "domain=$domain&connectionId=$connId"

        // The whole list: default sort is -id, every field and count equal the stored facts, total before pagination.
        val all = client.sprints("$query&pageSize=100")
        assertEquals(expected.sortedByDescending { it.id }, all.items, "the sprints, newest id first")
        assertEquals(expected.size.toLong(), all.total)
        assertEquals(1, all.page)
        assertEquals(100, all.pageSize)

        // Paging: the second page of two is the matching slice; total stays the whole count.
        val second = client.sprints("$query&pageSize=2&page=2")
        assertEquals(expected.sortedByDescending { it.id }.drop(2).take(2), second.items)
        assertEquals(expected.size.toLong(), second.total)
        assertEquals(2, second.page)
        assertEquals(2, second.pageSize)
        assertTrue(client.sprints("$query&page=999").items.isEmpty(), "past the last page is empty, not an error")

        // Sorting: ascending id, descending name, and a null-bearing field answers 200.
        assertEquals(expected.sortedBy { it.id }, client.sprints("$query&sort=id&pageSize=100").items)
        assertEquals(
            expected.sortedWith(compareByDescending<DeepDiveSprintOption> { it.name }.thenBy { it.id }),
            client.sprints("$query&sort=-name&pageSize=100").items,
        )
        client.sprints("$query&sort=-startAt,completeAt")

        // q: a case- and accent-insensitive substring over the sprint name.
        val sample = expected.first().name
        val matching = expected.filter { it.name.lowercase().contains(sample.lowercase()) }
        assertEquals(matching.sortedByDescending { it.id }, client.sprints("$query&q=${enc(accented(sample))}&pageSize=100").items)
        assertEquals(matching.size.toLong(), client.sprints("$query&q=${enc(sample.lowercase())}").total)
        assertTrue(client.sprints("$query&q=no-such-sprint-name").items.isEmpty())

        // A task's domain is its own: another domain's sprints are a different (also independent) set.
        val other = stored.tasks.values.mapNotNull { it.second }.distinct().firstOrNull { it != domain }
        if (other != null) {
            assertEquals(
                expectedSprints(stored, other).sortedByDescending { it.id },
                client.sprints("domain=$other&connectionId=$connId&pageSize=100").items,
            )
        }
    }

    @Test
    fun `sprints - a missing or unknown domain, a bad sort, bad paging and an unknown connection are 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-sprints-400")
        val domain = busiestDomain(readStored(connId))
        client.assertBadRequest("$base/sprints")
        client.assertBadRequest("$base/sprints?connectionId=$connId")
        client.assertBadRequest("$base/sprints?domain=")
        client.assertBadRequest("$base/sprints?domain=NO-SUCH-DOMAIN&connectionId=$connId")
        client.assertBadRequest("$base/sprints?domain=$domain&connectionId=$connId&sort=taskCount")
        client.assertBadRequest("$base/sprints?domain=$domain&connectionId=$connId&page=0")
        client.assertBadRequest("$base/sprints?domain=$domain&connectionId=$connId&pageSize=101")
        client.assertBadRequest("$base/sprints?domain=$domain&connectionId=$connId&domain=$domain")
        client.assertBadRequest("$base/sprints?domain=$domain&connectionId=999999")
    }

    @Test
    fun `epics - the epic list equals an independent read, sliced by domain, paged, sorted and filtered`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-epics")
        val epics = readStored(connId).epics
        assertTrue(epics.size >= 3, "the fixture must hold at least three epics (got ${epics.size})")
        val query = "connectionId=$connId"

        val all = client.epics("$query&pageSize=100")
        assertEquals(epics.sortedWith(compareBy({ it.key }, { it.id })), all.items, "default sort is key ascending")
        assertEquals(epics.size.toLong(), all.total)

        val page = client.epics("$query&pageSize=2&page=2&sort=-key")
        assertEquals(epics.sortedWith(compareByDescending<DeepDiveEpicOption> { it.key }.thenBy { it.id }).drop(2).take(2), page.items)
        assertEquals(epics.size.toLong(), page.total)
        assertEquals(epics.sortedBy { it.id }, client.epics("$query&sort=id&pageSize=100").items)
        client.epics("$query&sort=summary,-domain")

        val domain = epics.mapNotNull { it.domain }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (domain != null) {
            val inDomain = epics.filter { it.domain == domain }
            val sliced = client.epics("$query&domain=$domain&pageSize=100")
            assertEquals(inDomain.sortedWith(compareBy({ it.key }, { it.id })), sliced.items)
            assertEquals(inDomain.size.toLong(), sliced.total)
        }
        assertEquals(0L, client.epics("$query&domain=NO-SUCH-DOMAIN").total, "an unknown domain is an empty slice, not an error")

        // q matches the key or the summary, case-insensitively and across accents.
        val keySample = epics.first().key
        val byKey = epics.filter {
            it.key.lowercase().contains(keySample.lowercase()) || it.summary.orEmpty().lowercase().contains(keySample.lowercase())
        }
        assertEquals(
            byKey.sortedWith(compareBy({ it.key }, { it.id })),
            client.epics("$query&q=${enc(accented(keySample))}&pageSize=100").items,
        )
        val summarySample = epics.mapNotNull { it.summary }.first { it.length >= 6 }.take(6)
        val bySummary = epics.filter {
            it.key.lowercase().contains(summarySample.lowercase()) || it.summary.orEmpty().lowercase().contains(summarySample.lowercase())
        }
        assertTrue(bySummary.isNotEmpty())
        assertEquals(
            bySummary.sortedWith(compareBy({ it.key }, { it.id })),
            client.epics("$query&q=${enc(accented(summarySample))}&pageSize=100").items,
        )
    }

    @Test
    fun `epics - a bad sort, bad paging and an unknown connection are 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-epics-400")
        client.assertBadRequest("$base/epics?connectionId=$connId&sort=plannedStart")
        client.assertBadRequest("$base/epics?connectionId=$connId&page=abc")
        client.assertBadRequest("$base/epics?connectionId=$connId&pageSize=0")
        client.assertBadRequest("$base/epics?connectionId=$connId&q=a&q=b")
        client.assertBadRequest("$base/epics?connectionId=999999")
    }

    @Test
    fun `epic tasks - an epic's level-0 tasks equal an independent read, paged, sorted and filtered`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-tasks")
        val stored = readStored(connId)
        val epicIdToKey = stored.epics.associate { it.id to it.key }
        val tasksOf = stored.tasks.entries.filter { it.value.third in epicIdToKey }.groupBy({ it.value.third!! }, { it })
        val (epicIssueId, epicTasks) = tasksOf.entries.maxBy { it.value.size }.let { it.key to it.value }
        assertTrue(epicTasks.size >= 3, "the fixture must hold an epic with at least three tasks (got ${epicTasks.size})")
        val epicKey = epicIdToKey.getValue(epicIssueId)
        val expected = epicTasks.map { (issueId, task) -> DeepDiveTaskOption(issueId, connId, task.first, stored.summaries[issueId]) }
        val byKey = compareBy<DeepDiveTaskOption>({ it.key }, { it.id })

        val all = client.epicTasks(epicKey, "connectionId=$connId&pageSize=100")
        assertEquals(expected.sortedWith(byKey), all.items, "default sort is key ascending")
        assertEquals(expected.size.toLong(), all.total)

        val page = client.epicTasks(epicKey, "connectionId=$connId&pageSize=2&page=2&sort=-key")
        assertEquals(expected.sortedWith(compareByDescending<DeepDiveTaskOption> { it.key }.thenBy { it.id }).drop(2).take(2), page.items)
        assertEquals(expected.size.toLong(), page.total)
        assertEquals(expected.sortedBy { it.id }, client.epicTasks(epicKey, "connectionId=$connId&sort=id&pageSize=100").items)
        client.epicTasks(epicKey, "connectionId=$connId&sort=summary")

        val sample = expected.first().key
        val matching = expected.filter {
            it.key.lowercase().contains(sample.lowercase()) || it.summary.orEmpty().lowercase().contains(sample.lowercase())
        }
        assertEquals(
            matching.sortedWith(byKey),
            client.epicTasks(epicKey, "connectionId=$connId&q=${enc(accented(sample))}&pageSize=100").items,
        )
        val summarySample = expected.mapNotNull { it.summary }.first { it.length >= 6 }.take(6)
        val bySummary = expected.filter {
            it.key.lowercase().contains(summarySample.lowercase()) || it.summary.orEmpty().lowercase().contains(summarySample.lowercase())
        }
        assertEquals(
            bySummary.sortedWith(byKey),
            client.epicTasks(epicKey, "connectionId=$connId&q=${enc(accented(summarySample))}&pageSize=100").items,
        )
        assertTrue(client.epicTasks(epicKey, "connectionId=$connId&q=no-such-task").items.isEmpty())
    }

    @Test
    fun `epic tasks - an unknown epic, a bad sort and bad paging are 400`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-tasks-400")
        val epicKey = readStored(connId).epics.first().key
        client.assertBadRequest("$base/epics/NO-SUCH-1/tasks?connectionId=$connId")
        client.assertBadRequest("$base/epics/NO-SUCH-1/tasks")
        client.assertBadRequest("$base/epics/$epicKey/tasks?connectionId=$connId&sort=taskCount")
        client.assertBadRequest("$base/epics/$epicKey/tasks?connectionId=$connId&page=0")
        client.assertBadRequest("$base/epics/$epicKey/tasks?connectionId=999999")
        // A task key is not an epic: the epic lookup reads `dim_epic` only.
        val taskKey = readStored(connId).tasks.values.first().first
        client.assertBadRequest("$base/epics/$taskKey/tasks?connectionId=$connId")
    }

    @Test
    fun `hand-built rows - accents fold in q and an epic key in two connections is ambiguous without a connectionId`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val tag = SyncedStubFixture.unique("ddo").uppercase()
        val key = "$tag-1"
        val connA = SyncedStubFixture.createConnection(namePrefix = "dd-options-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "dd-options-b", enabled = false)
        suspendTransaction(sharedDatabaseForTests()) {
            val polish = DimEpicRow(1, key, "Zażółć gęślą jaźń $tag", "AAA", null, "DONE", null, null)
            store.insertEpics(connA, listOf(polish), configRevision = 1L)
            store.insertEpics(connB, listOf(DimEpicRow(1, key, "Other $tag", null, null, "DONE", null, null)), configRevision = 1L)
        }
        try {
            val client = seededClient("reports-dd-hand")
            // Stored diacritics fold: the plain-ASCII, the uppercase and the accented spellings all find the summary.
            for (q in listOf("zazolc gesla jazn", "ZAŻÓŁĆ GĘŚLĄ JAŹŃ", "żółć")) {
                val found = client.epics("connectionId=$connA&q=${enc(q)}")
                assertEquals(listOf(key), found.items.map { it.key }, "q=$q")
                assertEquals("AAA", found.items.single().domain)
                assertEquals(connA, found.items.single().connectionId)
            }
            assertEquals(emptyList(), client.epics("connectionId=$connB&q=${enc("zazolc")}").items)

            // The key lives in two active connections: ambiguous without a connectionId, resolved by one (an empty epic has no tasks).
            client.assertBadRequest("$base/epics/$key/tasks")
            assertEquals(0L, client.epicTasks(key, "connectionId=$connA").total)
            assertEquals(0L, client.epicTasks(key, "connectionId=$connB").total)
            // ...and the epic list shows both: same key and same issue id, so only the connection id orders them.
            assertEquals(listOf(connA, connB), client.epics("q=${enc(tag)}").items.map { it.connectionId })
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteDims(connA)
                store.deleteDims(connB)
            }
        }
    }

    /** A minimal valid level-0 `fact_task_delivery` row: only the key, the domain and the epic matter to the option lists. */
    private fun taskRow(issueId: Long, key: String, domain: String?, epicId: Long? = null) = FactTaskDeliveryRow(
        issueId = issueId, issueKey = key, createdAt = 0, startedAt = null, doneAt = null, reopenCount = 0, estimateAtStartMd = null,
        estimateAtDoneMd = null, estimateCurrentMd = null, estimateSource = "NONE", estimateChangesAfterStart = 0, estimatedLate = false,
        actualMd = 0.0, hasWorklogs = false, blockedMs = 0, blockedWorkingDays = 0.0, cycleMs = null, cycleWorkingDays = null,
        leadMs = null, leadWorkingDays = null, activeMs = 0, waitMs = 0, assigneeAccountIdAtDone = null, assigneeTeamIdAtDone = null,
        sprintIdAtDone = null, sprintTeamIdAtDone = null, creditTeamId = null, currentTeamId = null, currentAssigneeAccountId = null,
        domainKey = domain, epicId = epicId, epicDomainKey = null, crossDomain = false, activityType = "Story", workCategory = null,
        isSubtask = false, parentTaskId = null, currentStage = "NOT_STARTED", flags = emptyList(),
    )

    private fun scopeRow(sprintId: Long, issueId: Long, inScope: Boolean = true) = FactSprintScopeRow(
        sprintId,
        SprintScopeItem(
            issueId = issueId, addedAtMs = null, removedAtMs = null, committed = inScope, inScopeAtClose = inScope,
            estimateAtCommitmentMd = null, estimateAtCloseMd = null, estimateAtDoneMd = null, assigneeAtCommitment = null,
            doneInSprint = false, carriedOver = false, dropped = false,
        ),
    )

    @Test
    fun `hand-built rows - the same sprint id in two connections keeps each connection's own task count`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val domain = SyncedStubFixture.unique("DDA").uppercase()
        val otherDomain = SyncedStubFixture.unique("DDB").uppercase()
        val connA = SyncedStubFixture.createConnection(namePrefix = "dd-sprints-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "dd-sprints-b", enabled = false)
        fun sprint(name: String) = DimSprintRow(5, null, null, name, "closed", 1_000L, 2_000L, 2_000L, null, null)
        suspendTransaction(sharedDatabaseForTests()) {
            for (conn in listOf(connA, connB)) {
                val domains = listOf(DimDomainRow(domain, "A", emptyList(), null), DimDomainRow(otherDomain, "B", emptyList(), null))
                store.insertDomains(conn, domains, 1L)
            }
            store.insertDimSprints(connA, listOf(sprint("Sprint five A")), 1L)
            store.insertDimSprints(connB, listOf(sprint("Sprint five B")), 1L)
            // The SAME issue ids in both connections, in different domains: only a (connection, issue) join keeps the counts apart.
            // A: issues 101 and 102 in `domain`. B: 101 in `otherDomain`, 102 in `domain`, 103 in `domain` but out of scope at close.
            store.insertFactTaskDelivery(connA, listOf(taskRow(101, "A-101", domain), taskRow(102, "A-102", domain)), 1L)
            store.insertFactTaskDelivery(
                connB, listOf(taskRow(101, "B-101", otherDomain), taskRow(102, "B-102", domain), taskRow(103, "B-103", domain)), 1L,
            )
            store.insertFactSprintScope(connA, listOf(scopeRow(5, 101), scopeRow(5, 102)), 1L)
            val scopeB = listOf(scopeRow(5, 101), scopeRow(5, 102), scopeRow(5, 103, inScope = false))
            store.insertFactSprintScope(connB, scopeB, 1L)
        }
        try {
            val client = seededClient("reports-dd-pair")
            val both = client.sprints("domain=$domain")
            assertEquals(2L, both.total)
            assertEquals(
                listOf(Triple(connA, "Sprint five A", 2), Triple(connB, "Sprint five B", 1)),
                both.items.sortedBy { it.connectionId }.map { Triple(it.connectionId, it.name, it.taskCount) },
            )
            assertEquals(listOf(connB), client.sprints("domain=$otherDomain").items.map { it.connectionId })
            assertEquals(1, client.sprints("domain=$otherDomain").items.single().taskCount)
            assertEquals(listOf(connA), client.sprints("domain=$domain&connectionId=$connA").items.map { it.connectionId })
            // Equal sprint ids: the connection id is the final tiebreaker.
            assertEquals(listOf(connA, connB), client.sprints("domain=$domain&sort=id").items.map { it.connectionId })
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                for (conn in listOf(connA, connB)) {
                    store.deleteSprintFacts(conn)
                    store.deleteFactTaskDelivery(conn)
                    store.deleteDims(conn)
                }
            }
        }
    }

    @Test
    fun `control characters in domain or q are 400 on every option list`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-dd-nul")
        val domain = busiestDomain(readStored(connId))
        val epicKey = readStored(connId).epics.first().key
        client.assertBadRequest("$base/sprints?connectionId=$connId&domain=%00")
        client.assertBadRequest("$base/sprints?connectionId=$connId&domain=$domain&q=a%00b")
        client.assertBadRequest("$base/epics?connectionId=$connId&domain=a%00b")
        client.assertBadRequest("$base/epics?connectionId=$connId&q=%00")
        client.assertBadRequest("$base/epics/$epicKey/tasks?connectionId=$connId&q=a%00b")
        client.assertBadRequest("$base/epics/AB%00-1/tasks?connectionId=$connId")
    }
}
