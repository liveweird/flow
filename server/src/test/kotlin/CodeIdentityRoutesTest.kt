package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.identities.CodeIdentityConfirmResult
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityDecisionRequest
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.CodeIdentityPageResponse
import ch.nokillswit.identities.CodeIdentityResponse
import ch.nokillswit.identities.CodeIdentityStatus
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.identities.IdentityResolverKey
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.ingest.DATA_SOURCE_KIND_UNSUPPORTED
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four ADMIN routes of `identities/CodeIdentityRoutes.kt` over HTTP, against the processed stub: ONE own ACTIVE GitLab connection holds
 * a clone of the shared GitLab fixture's `norm.code_*` rows, and the Jira stub fixture's 30 people are the match candidates, so the
 * proposals case is the end-to-end alignment check against `sample-data/gitlab/expected.json`. Decisions are stored by HOST, so every test
 * starts and ends with the stub host's links emptied.
 */
class CodeIdentityRoutesTest {
    private val links = CodeIdentityLinkStore(sharedDatabaseForTests())
    private val host = sourceHostOf(GitLabStubServer.baseUrl)
    private val base get() = "/api/v1/data-sources/$ownId/code-identities"

    @BeforeTest
    @AfterTest
    fun clearDecisions() = runBlocking<Unit> { links.deleteHost(host) }

    private fun app(block: suspend ApplicationTestBuilder.(admin: HttpClient, adminId: UInt) -> Unit) = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val email = uniqueEmail("ci-admin")
        val adminId = TestUsers.seed(email, "pw-ci-admin-1")
        block(authedClient(email, "pw-ci-admin-1"), adminId)
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private suspend fun HttpClient.page(query: Map<String, String> = emptyMap(), path: String = base): CodeIdentityPageResponse {
        val response = get(path) { query.forEach { (k, v) -> parameter(k, v) } }
        assertEquals(HttpStatusCode.OK, response.status, response.toString())
        return response.body()
    }

    private suspend fun HttpClient.decide(key: String, decision: CodeIdentityDecision, account: String? = null, path: String = base) =
        putJson("$path/$key", CodeIdentityDecisionRequest(decision, account))

    private suspend fun HttpResponse.problem(status: HttpStatusCode): ProblemDetail {
        assertEquals(status, this.status)
        return body()
    }

    private fun account(number: Int) = "5f8a1b2c3d4e5f6a7b8c9d" + number.toString().padStart(2, '0')

    private fun auditOf(capture: LogCapture, event: String) = capture.events.filter { it.message == event }

    private fun ch.qos.logback.classic.spi.ILoggingEvent.fields() = keyValuePairs.associate { it.key to it.value }

    // ---- the guard and the lookups -----------------------------------------------------------------------------------------------

    @Test
    fun `a non-admin gets 403 on every operation whatever the id, key or body, and the denial is audited`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val user = seededClient("ci-user", FlowUserRole.USER)
        val jira = SyncedStubFixture.connectionId()
        val ids = listOf(ownId, 999_999u, jira)
        withAuditCapture { capture ->
            for (id in ids) {
                val root = "/api/v1/data-sources/$id/code-identities"
                assertEquals(HttpStatusCode.Forbidden, user.get(root).status, "GET $id")
                assertEquals(HttpStatusCode.Forbidden, user.decide("u1", CodeIdentityDecision.IGNORED, path = root).status, "PUT $id")
                assertEquals(HttpStatusCode.Forbidden, user.delete("$root/u1").status, "DELETE $id")
                assertEquals(HttpStatusCode.Forbidden, user.post("$root/confirm-proposals").status, "POST $id")
            }
            // A malformed key and a malformed body are still 403: the guard runs before either is read.
            assertEquals(HttpStatusCode.Forbidden, user.decide("not-a-key", CodeIdentityDecision.BOT).status)
            assertEquals(HttpStatusCode.Forbidden, user.delete("$base/not-a-key").status)
            val broken = user.put("$base/u1") {
                contentType(ContentType.Application.Json)
                setBody("{broken")
            }
            assertEquals(HttpStatusCode.Forbidden, broken.status)
            assertEquals(HttpStatusCode.Forbidden, user.get(base) { parameter("status", "bogus") }.status, "403 outranks a bad query")
            assertNotNull(capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "$base/u1") })
            assertTrue(auditOf(capture, "code_identity.decided").isEmpty())
        }
        assertEquals(emptyList(), links.forHost(host), "a denied call writes nothing")
    }

    @Test
    fun `an unknown or deleted data source is 404 and a Jira data source is 409 on all four operations`() = app { admin, _ ->
        val deletedGitLab = SyncedGitLabFixture.connectionId()
        val jira = SyncedStubFixture.connectionId()
        for (id in listOf(999_999u, deletedGitLab)) {
            val root = "/api/v1/data-sources/$id/code-identities"
            admin.get(root).problem(HttpStatusCode.NotFound)
            admin.decide("u1", CodeIdentityDecision.IGNORED, path = root).problem(HttpStatusCode.NotFound)
            admin.delete("$root/u1").problem(HttpStatusCode.NotFound)
            admin.post("$root/confirm-proposals").problem(HttpStatusCode.NotFound)
        }
        // The kind check comes before the key, the query and the body: a malformed key on a Jira source is 409, not 400.
        val root = "/api/v1/data-sources/$jira/code-identities"
        val refusals = listOf(
            admin.get(root),
            admin.decide("u1", CodeIdentityDecision.IGNORED, path = root),
            admin.decide("not-a-key", CodeIdentityDecision.IGNORED, path = root),
            admin.delete("$root/u1"),
            admin.post("$root/confirm-proposals"),
        )
        for (refusal in refusals) {
            assertEquals(DATA_SOURCE_KIND_UNSUPPORTED, refusal.problem(HttpStatusCode.Conflict).type)
        }
    }

    @Test
    fun `the order is 404 data source, 400 key or query or body, 404 identity, 400 unknown account`() = app { admin, _ ->
        admin.decide("not-a-key", CodeIdentityDecision.IGNORED).problem(HttpStatusCode.BadRequest)
        admin.decide("U1", CodeIdentityDecision.IGNORED).problem(HttpStatusCode.BadRequest)
        admin.decide("e${"A".repeat(32)}", CodeIdentityDecision.IGNORED).problem(HttpStatusCode.BadRequest)
        admin.delete("$base/not-a-key").problem(HttpStatusCode.BadRequest)
        val broken = admin.put("$base/u1") {
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }
        broken.problem(HttpStatusCode.BadRequest)
        // A malformed body shape is a 400 before any identity or account is looked at.
        admin.decide("u999999", CodeIdentityDecision.LINKED).problem(HttpStatusCode.BadRequest)
        admin.decide("u999999", CodeIdentityDecision.IGNORED).problem(HttpStatusCode.NotFound)
        admin.decide("u999999", CodeIdentityDecision.LINKED, "nobody").problem(HttpStatusCode.NotFound)
        assertEquals(emptyList(), links.forHost(host))
    }

    // ---- the list --------------------------------------------------------------------------------------------------------------

    private val expected: JsonObject by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).first { it.isFile }
        json(file.readText())
    }

    @Test
    fun `the proposals are exactly the alignment oracle, and a status filter partitions the nine identities`() = app { admin, _ ->
        val users = expected.getValue("users").jsonObject
        val matches = users.getValue("jiraAlignment").jsonObject.getValue("matches").jsonArray.map { it.jsonObject }
        val states = users.getValue("list").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.int to it.jsonObject }
        val contractorKey = users.getValue("unmatchedAuthors").jsonArray[0].jsonObject.getValue("identityKey").jsonPrimitive.content

        val proposed = admin.page(mapOf("status" to "PROPOSED", "pageSize" to "100"))
        assertEquals(7L, proposed.total)
        val byKey = proposed.items.associateBy { it.identityKey }
        for (match in matches) {
            val id = match.getValue("gitlabUserId").jsonPrimitive.int
            val row = assertNotNull(byKey["u$id"], "u$id is proposed")
            assertEquals(CodeIdentityDecision.LINKED, row.proposal?.decision)
            assertEquals(match.getValue("jiraAccountId").jsonPrimitive.content, row.proposal?.jiraAccountId, "u$id")
            assertEquals(states.getValue(id).getValue("state").jsonPrimitive.content, row.state, "the GitLab state is echoed")
        }
        val bot = assertNotNull(byKey["u8"])
        assertTrue(bot.bot)
        assertEquals(CodeIdentityDecision.BOT, bot.proposal?.decision)
        assertNull(bot.proposal?.jiraAccountId)
        assertTrue(proposed.items.all { it.seen && it.decision == null && it.emails.isNotEmpty() })

        val unmapped = admin.page(mapOf("status" to "UNMAPPED")).items.associateBy { it.identityKey }
        assertEquals(setOf("u1", contractorKey), unmapped.keys)
        val contractor = unmapped.getValue(contractorKey)
        assertEquals(IdentityKind.EMAIL, contractor.kind)
        assertEquals("Sample Contractor", contractor.displayName)
        assertEquals(listOf("contractor.sample@mail.example"), contractor.emails)
        assertNull(contractor.userId)

        assertEquals(0L, admin.page(mapOf("status" to "LINKED")).total)
        assertEquals(0L, admin.page(mapOf("status" to "IGNORED_OR_BOT")).total)
        assertEquals(9L, admin.page().total)
    }

    @Test
    fun `sorting, searching and paging are honoured and a bad query is a 400`() = app { admin, _ ->
        fun List<CodeIdentityResponse>.names() = map { it.displayName }
        val byName = admin.page(mapOf("pageSize" to "100")).items
        val named = byName.names().filterNotNull()
        assertEquals(named.sortedWith(String.CASE_INSENSITIVE_ORDER), named, "the default sort is displayName")
        assertEquals(byName.size, byName.map { it.identityKey }.toSet().size)

        val descending = admin.page(mapOf("sort" to "-username,id", "pageSize" to "100")).items.mapNotNull { it.username }
        assertEquals(descending.sortedWith(String.CASE_INSENSITIVE_ORDER.reversed()), descending)
        val byKey = admin.page(mapOf("sort" to "id", "pageSize" to "100")).items.map { it.identityKey }
        assertEquals(byKey.sorted(), byKey)

        assertEquals(1L, admin.page(mapOf("q" to "contractor")).total)
        assertEquals(1L, admin.page(mapOf("q" to "SAMPLE CONTRACTOR")).total, "case-insensitive")
        assertEquals(1L, admin.page(mapOf("q" to "contrácto")).total, "accent-insensitive")
        assertEquals(listOf("u4"), admin.page(mapOf("q" to "user3@")).items.map { it.identityKey }, "an address, here the secondary one")
        assertEquals(0L, admin.page(mapOf("q" to "no-such-person")).total)

        val second = admin.page(mapOf("pageSize" to "2", "page" to "2"))
        assertEquals(2, second.items.size)
        assertEquals(9L, second.total)
        assertEquals(emptyList(), admin.page(mapOf("page" to "99")).items)

        for (bad in listOf(
            mapOf("sort" to "bogus"), mapOf("status" to "bogus"), mapOf("q" to "a\u0001b"), mapOf("q" to "x".repeat(101)),
            mapOf("pageSize" to "101"), mapOf("page" to "0"),
        )) {
            assertEquals(HttpStatusCode.BadRequest, admin.get(base) { bad.forEach { (k, v) -> parameter(k, v) } }.status, "$bad")
        }
        val repeated = admin.get("$base?status=PROPOSED&status=UNMAPPED")
        assertEquals(HttpStatusCode.BadRequest, repeated.status, "a repeated scalar key")
        val response = admin.get(base)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    // ---- PUT -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `PUT stores and audits a decision, a re-PUT is silent, a change names what it overwrote`() = app { admin, adminId ->
        withAuditCapture { capture ->
            val first = admin.decide("u1", CodeIdentityDecision.LINKED, account(2))
            assertEquals(HttpStatusCode.OK, first.status)
            val linked = first.body<CodeIdentityResponse>()
            assertEquals(CodeIdentityStatus.LINKED, linked.status)
            assertEquals("Sample User 2", linked.decision?.jiraDisplayName)
            assertEquals(account(2), linked.decision?.jiraAccountId)
            assertEquals(adminId.toLong(), linked.decision?.decidedByUserId)
            assertTrue(linked.seen)
            val event = auditOf(capture, "code_identity.decided").single().fields()
            assertEquals(
                mapOf(
                    "byUserId" to adminId.toLong(), "dataSourceId" to ownId.toLong(), "host" to host, "identityKey" to "u1",
                    "decision" to "LINKED", "jiraAccountId" to account(2), "decisionFrom" to null, "jiraAccountIdFrom" to null,
                    "via" to "PUT",
                ),
                event,
            )

            assertEquals(HttpStatusCode.OK, admin.decide("u1", CodeIdentityDecision.LINKED, account(2)).status)
            assertEquals(1, auditOf(capture, "code_identity.decided").size, "an identical re-PUT audits nothing")

            val ignored = admin.decide("u1", CodeIdentityDecision.IGNORED).body<CodeIdentityResponse>()
            assertEquals(CodeIdentityStatus.IGNORED_OR_BOT, ignored.status)
            assertNull(ignored.decision?.jiraAccountId)
            val change = auditOf(capture, "code_identity.decided").last().fields()
            assertEquals("LINKED", change["decisionFrom"])
            assertEquals(account(2), change["jiraAccountIdFrom"])
            assertEquals("IGNORED", change["decision"])
            assertNull(change["jiraAccountId"])
        }
        assertEquals(CodeIdentityDecision.IGNORED, links.find(host, "u1")?.decision)
    }

    @Test
    fun `PUT keeps the proposal on a decided row, validates the shape and the account, and accepts BOT on an address-only identity`() =
        app { admin, _ ->
            // u2 has a proposal (sample.dev1 -> user 1); an ADMIN overrides it and the row still shows it.
            val overridden = admin.decide("u2", CodeIdentityDecision.IGNORED).body<CodeIdentityResponse>()
            assertEquals(CodeIdentityStatus.IGNORED_OR_BOT, overridden.status)
            assertEquals(account(1), overridden.proposal?.jiraAccountId)

            admin.decide("u1", CodeIdentityDecision.LINKED).problem(HttpStatusCode.BadRequest)
            admin.decide("u1", CodeIdentityDecision.IGNORED, account(2)).problem(HttpStatusCode.BadRequest)
            admin.decide("u1", CodeIdentityDecision.BOT, account(2)).problem(HttpStatusCode.BadRequest)
            admin.decide("u1", CodeIdentityDecision.LINKED, "  ").problem(HttpStatusCode.BadRequest)
            admin.decide("u1", CodeIdentityDecision.LINKED, "a".repeat(101)).problem(HttpStatusCode.BadRequest)
            admin.decide("u1", CodeIdentityDecision.LINKED, "a\u0001b").problem(HttpStatusCode.BadRequest)
            val unknown = admin.decide("u1", CodeIdentityDecision.LINKED, "nobody").problem(HttpStatusCode.BadRequest)
            assertTrue("Unknown Jira account id" in unknown.detail.orEmpty())
            assertFalse("nobody" in unknown.detail.orEmpty(), "the id is never echoed")
            assertNull(links.find(host, "u1"))

            admin.decide("u999999", CodeIdentityDecision.IGNORED).problem(HttpStatusCode.NotFound)

            val contractorKey = expected.getValue("users").jsonObject.getValue("unmatchedAuthors").jsonArray[0].jsonObject
                .getValue("identityKey").jsonPrimitive.content
            val bot = admin.decide(contractorKey, CodeIdentityDecision.BOT)
            assertEquals(HttpStatusCode.OK, bot.status)
            assertEquals(IdentityKind.EMAIL, bot.body<CodeIdentityResponse>().kind)
        }

    @Test
    fun `an orphaned decision is listed unseen on its decided tab, never matched by q, changeable and removable`() = app { admin, adminId ->
        val orphan = "u777777"
        links.insertIfAbsent(IdentityLink(host, orphan, CodeIdentityDecision.LINKED, account(2), adminId.toLong(), nowMillis()))
        val listed = admin.page(mapOf("status" to "LINKED")).items.single()
        assertEquals(orphan, listed.identityKey)
        assertFalse(listed.seen)
        assertEquals(IdentityKind.USER, listed.kind)
        assertNull(listed.userId)
        assertNull(listed.displayName)
        assertNull(listed.username)
        assertTrue(listed.emails.isEmpty())
        assertNull(listed.proposal)
        assertEquals("Sample User 2", listed.decision?.jiraDisplayName)
        assertEquals(10L, admin.page().total, "the nine identities and the orphan")
        assertEquals(0, admin.page(mapOf("q" to "sample")).items.count { it.identityKey == orphan }, "q never matches an orphan")
        assertEquals(1, admin.page(mapOf("q" to "   ")).items.count { it.identityKey == orphan }, "a blank q is no q")

        withAuditCapture { capture ->
            val changed = admin.decide(orphan, CodeIdentityDecision.IGNORED)
            assertEquals(HttpStatusCode.OK, changed.status, "a decision with no identity row may be changed")
            val row = changed.body<CodeIdentityResponse>()
            assertFalse(row.seen)
            assertEquals(CodeIdentityStatus.IGNORED_OR_BOT, row.status)
            assertEquals("LINKED", auditOf(capture, "code_identity.decided").single().fields()["decisionFrom"])

            assertEquals(HttpStatusCode.NoContent, admin.delete("$base/$orphan").status)
            assertEquals("IGNORED", auditOf(capture, "code_identity.decision_removed").single().fields()["decision"])
        }
        assertEquals(9L, admin.page().total)
        admin.decide("u777777", CodeIdentityDecision.IGNORED).problem(HttpStatusCode.NotFound)
    }

    // ---- DELETE ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `DELETE removes a decision and audits the row it removed, a second DELETE is 404`() = app { admin, adminId ->
        admin.decide("u1", CodeIdentityDecision.IGNORED)
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, admin.delete("$base/u1").status)
            assertEquals(
                mapOf(
                    "byUserId" to adminId.toLong(), "dataSourceId" to ownId.toLong(), "host" to host, "identityKey" to "u1",
                    "decision" to "IGNORED", "jiraAccountId" to null,
                ),
                auditOf(capture, "code_identity.decision_removed").single().fields(),
            )
            admin.delete("$base/u1").problem(HttpStatusCode.NotFound)
            assertEquals(1, auditOf(capture, "code_identity.decision_removed").size, "a 404 audits nothing")
        }
        assertTrue(admin.page(mapOf("status" to "UNMAPPED")).items.any { it.identityKey == "u1" })
    }

    // ---- confirm-proposals -----------------------------------------------------------------------------------------------------

    @Test
    fun `confirm-proposals writes every proposal once, audits each link, the resolver reads only those`() = app { admin, adminId ->
        // A stored decision is kept: u2's proposal is not confirmed over it.
        admin.decide("u2", CodeIdentityDecision.IGNORED)
        withAuditCapture { capture ->
            val first = admin.post("$base/confirm-proposals")
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(CodeIdentityConfirmResult(linked = 5, bots = 1), first.body<CodeIdentityConfirmResult>())
            val events = auditOf(capture, "code_identity.decided").map { it.fields() }.filter { it["via"] == "CONFIRM_PROPOSALS" }
            assertEquals(6, events.size, "one event per link written, none for the kept decision")
            assertTrue(events.all { it["byUserId"] == adminId.toLong() && it["host"] == host && it["decisionFrom"] == null })
            assertEquals(setOf("u3", "u4", "u5", "u6", "u7", "u8"), events.map { it["identityKey"] }.toSet())

            val second = admin.post("$base/confirm-proposals").body<CodeIdentityConfirmResult>()
            assertEquals(CodeIdentityConfirmResult(0, 0), second)
            assertEquals(6, auditOf(capture, "code_identity.decided").count { it.fields()["via"] == "CONFIRM_PROPOSALS" })
        }
        assertEquals(5L, admin.page(mapOf("status" to "LINKED")).total)
        val ignoredOrBot = admin.page(mapOf("status" to "IGNORED_OR_BOT")).items.associateBy { it.identityKey }
        assertEquals(setOf("u2", "u8"), ignoredOrBot.keys)
        assertEquals(CodeIdentityDecision.BOT, ignoredOrBot.getValue("u8").decision?.decision)
        assertEquals(0L, admin.page(mapOf("status" to "PROPOSED")).total)

        val resolver = IdentityResolver(links)
        assertEquals(mapOf("u3" to account(4)), resolver.accountsByKey(host, listOf("u3", "u8", "u2", "u1")))
        assertEquals(mapOf(account(3) to listOf("u4")), resolver.keysByAccount(host, listOf(account(3), account(1))))
    }

    @Test
    fun `decisions survive a re-created data source because they are keyed by the instance host`() = app { admin, _ ->
        val first = newOwnConnection()
        try {
            val root = "/api/v1/data-sources/$first/code-identities"
            val written = admin.post("$root/confirm-proposals").body<CodeIdentityConfirmResult>()
            assertEquals(CodeIdentityConfirmResult(linked = 6, bots = 1), written)
        } finally {
            removeConnection(first)
        }
        val second = newOwnConnection()
        try {
            val page = admin.page(mapOf("status" to "LINKED"), "/api/v1/data-sources/$second/code-identities")
            assertEquals(6L, page.total, "the new data source on the same instance finds the decisions")
        } finally {
            removeConnection(second)
        }
    }

    // ---- roles -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a worker publishes the resolver but serves no route`() = testApplication {
        configureApp("app.role" to "worker")
        var resolverPublished = false
        application { resolverPublished = attributes.contains(IdentityResolverKey) }
        startApplication()
        assertTrue(resolverPublished, "DERIVE and the reports read links in every role")
        // The default client on purpose (no validation): a worker answers 404 for an API route the spec declares.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/data-sources/$ownId/code-identities").status)
    }

    companion object {
        private val dataSources = GitLabFixtures.dataSources()
        private lateinit var ownConnections: MutableList<UInt>

        /** The connection the tests read: ACTIVE (the routes only skip soft-deleted rows), DISABLED (no worker syncs it). */
        private var ownId: UInt = 0u

        private suspend fun newOwnConnection(): UInt {
            val id = GitLabFixtures.createConnection(dataSources, namePrefix = "code-identities")
            SyncedGitLabFixture.cloneProcessedData(SyncedGitLabFixture.connectionId(), id)
            synchronized(ownConnections) { ownConnections.add(id) }
            return id
        }

        private suspend fun removeConnection(id: UInt) {
            GitLabFixtures.purgeAll(id)
            dataSources.delete(id)
            synchronized(ownConnections) { ownConnections.remove(id) }
        }

        @JvmStatic @BeforeClass
        fun warmFixtures() {
            warmStubFixtures()
            warmGitLabFixture()
            ownConnections = mutableListOf()
            runBlocking { ownId = newOwnConnection() }
        }

        @JvmStatic @AfterClass
        fun removeOwnConnections() = runBlocking {
            sharedDatabaseForTests().let { CodeIdentityLinkStore(it).deleteHost(sourceHostOf(GitLabStubServer.baseUrl)) }
            ownConnections.toList().forEach { removeConnection(it) }
        }
    }
}
