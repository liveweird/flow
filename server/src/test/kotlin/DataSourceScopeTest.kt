package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceScopeConflictException
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.ScopeCandidate
import ch.nokillswit.ingest.ScopeConflictReason
import ch.nokillswit.ingest.ScopeRules
import ch.nokillswit.ingest.findScopeConflict
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two data-source scope invariants (`ingest/DataSourceScope.kt`, `.claude/docs/ingestion.md` "Data sources"):
 * every ACTIVE data source reads the same Jira site, and no project key sits in two active data sources. Every row here
 * is on [TEST_JIRA_SITE_URL] with [uniqueProjectKeys], so the cases never depend on another class's rows (the shared
 * table holds them all); where the lowest-id conflicting row is not necessarily ours (another SITE conflicts with
 * every row), the assertions follow the `instance` the problem names instead of an id we cannot know.
 */
class DataSourceScopeTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun request(
        name: String,
        keys: List<String>,
        siteUrl: String = TEST_JIRA_SITE_URL,
        token: String? = "token-${UUID.randomUUID()}",
    ) = DataSourceRequest(
        name = name,
        enabled = true,
        syncIntervalMinutes = 60,
        backfillFrom = "2025-01-01",
        reconcileHourUtc = 3,
        jira = JiraConnectionRequest(siteUrl, "svc-${unique("acct")}@example.com", token, keys, JiraAuthScheme.BASIC),
    )

    private suspend fun HttpClient.createSource(keys: List<String>, name: String = unique("scope")): DataSourceResponse {
        val response = postJson("/api/v1/data-sources", request(name, keys))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpResponse.assertScopeConflict(type: String): ProblemDetail {
        assertEquals(HttpStatusCode.Conflict, status)
        val problem = body<ProblemDetail>()
        assertEquals(type, problem.type)
        return problem
    }

    @Test
    fun `a data source on another Jira site is 409 with the other-site problem type and the conflicting source as instance`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("scope-site", UserRole.ADMIN)
            admin.createSource(uniqueProjectKeys(1)) // at least one active source on the test site exists

            val response = admin.postJson(
                "/api/v1/data-sources",
                request(unique("scope-other"), uniqueProjectKeys(1), siteUrl = "https://${unique("elsewhere").lowercase()}.atlassian.net"),
            )
            val problem = response.assertScopeConflict("urn:flow:problem:data-source-other-site")

            val instance = assertNotNull(problem.instance)
            assertTrue(Regex("^/api/v1/data-sources/\\d+$").matches(instance), "instance names the conflicting source: $instance")
            val conflicting = admin.get(instance).body<DataSourceResponse>()
            assertTrue(conflicting.name in problem.detail!!, "the detail names the other source: ${problem.detail}")
            assertTrue("flow-test.atlassian.net" in problem.detail!!, "and the site every source uses: ${problem.detail}")
        }

    @Test
    fun `a create sharing a project key with an active source is 409 and names the source and the shared keys`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("scope-create", UserRole.ADMIN)
        val (shared, mine, theirs) = uniqueProjectKeys(3)
        val first = admin.createSource(listOf(shared, mine))

        val problem = admin.postJson("/api/v1/data-sources", request(unique("scope-dup"), listOf(shared, theirs)))
            .assertScopeConflict("urn:flow:problem:data-source-project-overlap")

        assertEquals("/api/v1/data-sources/${first.id}", problem.instance)
        val detail = problem.detail!!
        assertTrue(first.name in detail && shared in detail, "the detail names the other source and the shared key: $detail")
        assertFalse(mine in detail || theirs in detail, "only the shared keys are named: $detail")

        // A different, free key is fine - the rejected request left nothing behind.
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/v1/data-sources", request(unique("scope-ok"), listOf(theirs))).status)
    }

    @Test
    fun `an update taking a project key of another active source is 409, a self-update with its own keys is 204`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("scope-update", UserRole.ADMIN)
        val (keyA, keyB, keyFree) = uniqueProjectKeys(3)
        val a = admin.createSource(listOf(keyA))
        val b = admin.createSource(listOf(keyB))

        val taken = admin.putJson("/api/v1/data-sources/${b.id}", request(b.name, listOf(keyB, keyA), token = null))
        val problem = taken.assertScopeConflict("urn:flow:problem:data-source-project-overlap")
        assertEquals("/api/v1/data-sources/${a.id}", problem.instance)
        assertTrue(keyA in problem.detail!! && a.name in problem.detail!!, problem.detail)
        assertEquals(listOf(keyB), admin.get("/api/v1/data-sources/${b.id}").body<DataSourceResponse>().jira.projectKeys, "unchanged")

        // Its own keys (and a new free one) never conflict with itself; a rename alone is as good.
        val self = admin.putJson("/api/v1/data-sources/${b.id}", request(unique("scope-renamed"), listOf(keyB, keyFree), token = null))
        assertEquals(HttpStatusCode.NoContent, self.status)
        val same = admin.putJson("/api/v1/data-sources/${b.id}", request(unique("scope-again"), listOf(keyB, keyFree), token = null))
        assertEquals(HttpStatusCode.NoContent, same.status)
    }

    @Test
    fun `a soft-deleted source frees its project keys`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("scope-free", UserRole.ADMIN)
        val keys = uniqueProjectKeys(2)
        val first = admin.createSource(keys)
        assertEquals(HttpStatusCode.Conflict, admin.postJson("/api/v1/data-sources", request(unique("scope-blocked"), keys)).status)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${first.id}").status)
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/v1/data-sources", request(unique("scope-reuse"), keys)).status)
    }

    @Test
    fun `a rejected create or update audits scope_rejected without a token`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("scope-audit", UserRole.ADMIN)
        val (shared, other) = uniqueProjectKeys(2)
        val a = admin.createSource(listOf(shared))
        val b = admin.createSource(listOf(other))
        val token = "top-secret-${UUID.randomUUID()}"
        withAuditCapture { capture ->
            val create = admin.postJson("/api/v1/data-sources", request(unique("scope-audit-dup"), listOf(shared), token = token))
            assertEquals(HttpStatusCode.Conflict, create.status)
            val createEvent = assertNotNull(
                capture.awaitEvent { it.message == "data_source.scope_rejected" && it.hasKeyValue("dataSourceId", null) },
                "the create rejection is audited with no data source id",
            )
            assertTrue(createEvent.hasKeyValue("reason", "PROJECT_OVERLAP"))
            assertTrue(createEvent.hasKeyValue("conflictingDataSourceId", a.id.toLong()))
            assertTrue(createEvent.hasKeyValue("siteHost", "flow-test.atlassian.net"))
            assertTrue(createEvent.hasKeyValue("projectKeys", listOf(shared)))
            assertTrue(createEvent.keyValuePairs.none { token in "${it.value}" }, "no audit field may carry the token")

            val update = admin.putJson("/api/v1/data-sources/${b.id}", request(b.name, listOf(shared), token = token))
            assertEquals(HttpStatusCode.Conflict, update.status)
            val updateEvent = assertNotNull(
                capture.awaitEvent { it.message == "data_source.scope_rejected" && it.hasKeyValue("dataSourceId", b.id.toLong()) },
                "the update rejection is audited with the updated source's id",
            )
            assertTrue(updateEvent.hasKeyValue("conflictingDataSourceId", a.id.toLong()))
            assertTrue(capture.events.none { it.message == "data_source.updated" && it.hasKeyValue("dataSourceId", b.id.toLong()) })
        }
    }

    @Test
    fun `concurrent creates sharing a key leave exactly one row - the scope lock serializes them`() = runBlocking {
        val service = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
        val shared = uniqueProjectKey()
        val outcomes = (1..CONCURRENT_CREATES).map { n ->
            async(Dispatchers.Default) {
                runCatching { service.create(request(unique("scope-race-$n"), listOf(shared))) }
            }
        }.awaitAll()
        try {
            assertEquals(1, outcomes.count { it.isSuccess }, "exactly one create wins: $outcomes")
            outcomes.filter { it.isFailure }.forEach {
                val failure = assertNotNull(it.exceptionOrNull())
                assertTrue(failure is DataSourceScopeConflictException, "the rest are scope conflicts: $failure")
                assertEquals(ScopeConflictReason.PROJECT_OVERLAP, failure.reason)
                assertEquals(listOf(shared), failure.keys)
            }
        } finally {
            outcomes.mapNotNull { it.getOrNull() }.forEach { service.delete(it) }
        }
    }

    @Test
    fun `persistCloudId waits out a concurrent edit and keeps its project keys instead of restoring a stale copy`() = runBlocking {
        val service = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
        val (kept, removed) = uniqueProjectKeys(2)
        val id = service.create(request(unique("scope-cloud"), listOf(kept, removed)))
        try {
            DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { editor ->
                editor.autoCommit = false
                // The "PUT": it holds the row lock and removes a key, uncommitted, while the SYNC's persistCloudId starts.
                editor.createStatement().use {
                    it.execute("SELECT id FROM source_connections WHERE id = $id FOR NO KEY UPDATE")
                    it.execute(
                        "UPDATE source_connections SET settings = jsonb_set(settings, '{projectKeys}', '[\"$kept\"]'::jsonb) " +
                            "WHERE id = $id",
                    )
                }
                val persist = async(Dispatchers.Default) { service.persistCloudId(id, "cloud-$id") }
                delay(PERSIST_WAIT_MS) // long enough for it to read (a stale copy) or block on the row lock (the fix)
                editor.commit()
                withTimeout(PERSIST_TIMEOUT_MS) { persist.await() }
            }
            val stored = assertNotNull(service.read(id))
            assertEquals(listOf(kept), stored.jira.projectKeys, "the edit's keys survive; the removed key is not restored")
            assertEquals("cloud-$id", stored.jira.cloudId)
        } finally {
            service.delete(id)
        }
    }

    @Test
    fun `with the rules OFF the service accepts another site and a repeated project key`() = runBlocking {
        val service = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY), ScopeRules.OFF)
        val shared = uniqueProjectKey()
        val first = service.create(request(unique("scope-off"), listOf(shared)))
        val second = service.create(
            request(unique("scope-off"), listOf(shared), siteUrl = "https://${unique("elsewhere").lowercase()}.atlassian.net"),
        )
        try {
            assertEquals(2, listOf(first, second).count { service.read(it) != null }, "both rows exist")
        } finally {
            service.delete(first)
            service.delete(second)
        }
    }

    @Test
    fun `the scope rules parse strictly - case-insensitive, anything else fails startup`() {
        assertEquals(ScopeRules.OFF, ScopeRules.parse(" Off "))
        assertEquals(ScopeRules.ENFORCED, ScopeRules.parse("ENFORCED"))
        val failure = assertFailsWith<IllegalStateException> { ScopeRules.parse("sometimes") }
        assertTrue("ingest.scopeRules" in failure.message!!)
    }

    @Test
    fun `the check reports the lowest-id conflict first, a foreign site only on create, and an update checks keys alone`() {
        val site = TEST_JIRA_SITE_URL
        val others = listOf(
            ScopeCandidate(9u, "late", site, listOf("AAA", "BBB")),
            ScopeCandidate(3u, "early", site, listOf("BBB", "CCC")),
            ScopeCandidate(5u, "foreign", "https://elsewhere.atlassian.net", listOf("DDD")),
        )

        val overlap = assertNotNull(findScopeConflict(others, site, listOf("CCC", "BBB", "ZZZ"), checkSite = true))
        assertEquals(3u, overlap.conflictingId, "the lowest id among the conflicts")
        assertEquals(ScopeConflictReason.PROJECT_OVERLAP, overlap.reason)
        assertEquals(listOf("CCC", "BBB"), overlap.keys, "the shared keys, in the request's order")

        val foreignFirst = assertNotNull(findScopeConflict(others.filter { it.id > 3u }, site, listOf("AAA"), checkSite = true))
        assertEquals(5u, foreignFirst.conflictingId)
        assertEquals(ScopeConflictReason.OTHER_SITE, foreignFirst.reason)
        assertTrue(foreignFirst.keys.isEmpty())

        // An update never changes the site (the identity 409), so a foreign-site row is no conflict for it - only keys are.
        assertNull(findScopeConflict(others.filter { it.id == 5u }, site, listOf("AAA"), checkSite = false))
        assertEquals(9u, assertNotNull(findScopeConflict(others, site, listOf("AAA"), checkSite = false)).conflictingId)
        assertNull(findScopeConflict(emptyList(), site, listOf("AAA"), checkSite = true))
    }

    private companion object {
        const val CONCURRENT_CREATES = 8
        const val PERSIST_WAIT_MS = 500L
        const val PERSIST_TIMEOUT_MS = 10_000L
    }
}
