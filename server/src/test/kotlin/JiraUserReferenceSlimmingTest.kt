package ch.nokillswit

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraChangelogStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.RawUpsertOutcome
import ch.nokillswit.jira.slimUserReference
import ch.nokillswit.jira.slimUserReferences
import ch.nokillswit.jira.slimmedCanonicalJson
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame

private const val ISSUE_ID = 880_001L
private const val HISTORY_ID = 880_101L
private const val WORKLOG_ID = 880_201L

/** What Jira really embeds for a user in a history or a worklog (`self`, `emailAddress`, avatar URLs, ...). */
private const val FULL_USER = """{"self":"https://x.atlassian.net/rest/api/3/user?accountId=acc-7","accountId":"acc-7",""" +
    """"emailAddress":"ann@example.com","avatarUrls":{"48x48":"https://x/48.png","24x24":"https://x/24.png"},""" +
    """"displayName":"Ann","active":true,"timeZone":"Europe/Warsaw","accountType":"atlassian"}"""
private const val SLIM_USER = """{"accountId":"acc-7","displayName":"Ann","accountType":"atlassian"}"""

/** The same user, differing only in keys the slimming drops. */
private val FULL_USER_OTHER_DROPPED_KEYS = FULL_USER.replace("ann@example.com", "ann@new.example").replace("Europe/Warsaw", "UTC")

private const val WORKLOG_TAIL = """"started":"2025-09-29T03:27:48.093+0000","created":"2025-09-29T03:27:48.093+0000",""" +
    """"updated":"2025-09-29T03:27:48.093+0000","timeSpentSeconds":5400"""

/**
 * The write-path normalization that stores a changelog/worklog `author`/`updateAuthor` as `{accountId, displayName, accountType}`
 * (`jira/JiraUserReferences.kt`; `.claude/docs/persistence.md` "The Jira changelog/worklog raw store (V11)"): the pure function,
 * then the REAL CHANGELOGS/WORKLOGS streams over the shared stub (a scripted full-author answer for one private issue id) into
 * the real tables, including the re-sync of an unchanged worklog keeping its hash.
 */
class JiraUserReferenceSlimmingTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()
    private val overrides = mutableListOf<StubMapping>()

    @AfterTest
    fun cleanUp() = runBlocking {
        removeOverrides()
        connections.forEach { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Changelogs.deleteWhere { JiraRawStore.Changelogs.connectionId eq connId }
                JiraRawStore.Worklogs.deleteWhere { JiraRawStore.Worklogs.connectionId eq connId }
                JiraRawStore.Issues.deleteWhere { JiraRawStore.Issues.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // --- the pure function -------------------------------------------------------------------------------

    @Test
    fun `a full user keeps exactly accountId, displayName and accountType`() {
        assertEquals(obj(SLIM_USER), slimUserReference(obj(FULL_USER)))
    }

    @Test
    fun `a missing kept key stays missing, and an object with none of them becomes empty`() {
        assertEquals(obj("""{"accountId":"a"}"""), slimUserReference(obj("""{"accountId":"a","emailAddress":"e@x"}""")))
        assertEquals(
            obj("""{"displayName":"D","accountType":"app"}"""),
            slimUserReference(obj("""{"displayName":"D","accountType":"app","active":true}""")),
        )
        assertEquals(JsonObject(emptyMap()), slimUserReference(obj("""{"self":"s","active":false}""")))
    }

    @Test
    fun `a non-object author is left exactly as it is`() {
        val text = JsonPrimitive("someone")
        assertSame(text, slimUserReference(text))
        assertSame(JsonNull, slimUserReference(JsonNull))
        val payload = obj("""{"id":"1","author":null,"updateAuthor":"x"}""")
        assertEquals(payload, slimUserReferences(payload))
    }

    @Test
    fun `a payload without an author is unchanged, and only author and updateAuthor are touched`() {
        val noAuthor = obj("""{"id":"1","items":[{"field":"status","author":{"emailAddress":"keep@x"}}]}""")
        assertEquals(noAuthor, slimUserReferences(noAuthor))
        val both = obj("""{"id":"2","author":$FULL_USER,"updateAuthor":$FULL_USER,"creator":$FULL_USER,"timeSpentSeconds":60}""")
        val slim = slimUserReferences(both)
        assertEquals(obj(SLIM_USER), slim.getValue("author"))
        assertEquals(obj(SLIM_USER), slim.getValue("updateAuthor"))
        assertEquals(obj(FULL_USER), slim.getValue("creator"), "only the two author keys are user references here")
        assertEquals(both.getValue("timeSpentSeconds"), slim.getValue("timeSpentSeconds"))
    }

    @Test
    fun `slimmedCanonicalJson is canonical, hash-stable across key order, and slims before it hashes`() {
        val a = slimmedCanonicalJson("""{"id":"1","author":$FULL_USER}""")
        val reordered = """{"author":{"accountType":"atlassian","displayName":"Ann","accountId":"acc-7","emailAddress":"x@y"},"id":"1"}"""
        val b = slimmedCanonicalJson(reordered)
        assertEquals(canonicalJson("""{"id":"1","author":$SLIM_USER}"""), a)
        assertEquals(a, b, "a change only in a dropped key is not a content change")
        assertEquals(sha256Hex(a), sha256Hex(b))
        assertFalse(a.contains("emailAddress"))
    }

    // --- the real streams over the stub -----------------------------------------------------------------

    private suspend fun connectionWithIssue(): UInt {
        SyncedStubFixture.ensureMigrated()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-slim-author", enabled = false, projectKeys = listOf("FLO"))
        connections += connId
        store.upsertIssue(
            connId,
            RawIssueInput(
                issueId = ISSUE_ID, issueKey = "FLO-$ISSUE_ID", projectId = 1L, projectKey = "FLO", issueUpdatedAt = 1_000,
                payloadJson = """{"id":"$ISSUE_ID","key":"FLO-$ISSUE_ID","fields":{"project":{"id":"1","key":"FLO"}}}""",
            ),
            now = 1_000,
        )
        return connId
    }

    private suspend fun client(): HttpJiraClient {
        val base = JiraStubServer.start()
        val http = SyncedStubFixture.buildJiraHttp(maxRetries = 0)
        return HttpJiraClient(http, base, base, "svc@example.com", "token", JiraAuthScheme.BASIC).also { it.resolveCloudId() }
    }

    private fun context(connId: UInt) = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true })

    private fun json(body: String) = aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

    private fun override(stub: MappingBuilder) {
        overrides += JiraStubServer.addOverride(stub.atPriority(1))
    }

    private fun removeOverrides() {
        overrides.forEach(JiraStubServer::removeOverride)
        overrides.clear()
    }

    private fun worklogAnswer(author: String, updateAuthor: String) =
        get(urlPathMatching(".*/rest/api/3/issue/$ISSUE_ID/worklog")).willReturn(
            json(
                """{"startAt":0,"maxResults":100,"total":1,"worklogs":[{"id":"$WORKLOG_ID","issueId":"$ISSUE_ID",""" +
                    """"author":$author,"updateAuthor":$updateAuthor,$WORKLOG_TAIL}]}""",
            ),
        )

    private suspend fun worklogRow(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Worklogs.select(JiraRawStore.Worklogs.payload, JiraRawStore.Worklogs.sha256, JiraRawStore.Worklogs.changedAt)
            .where { (JiraRawStore.Worklogs.connectionId eq connId) and (JiraRawStore.Worklogs.worklogId eq WORKLOG_ID) }
            .map { Triple(it[JiraRawStore.Worklogs.payload], it[JiraRawStore.Worklogs.sha256], it[JiraRawStore.Worklogs.changedAt]) }
            .toList().single()
    }

    @Test
    fun `the CHANGELOGS stream stores a full-author history slim, with the author account id column intact`() = runBlocking {
        val connId = connectionWithIssue()
        override(
            post(urlPathMatching(".*/rest/api/3/changelog/bulkfetch"))
                .withRequestBody(matchingJsonPath("$.issueIdsOrKeys", equalToJson("""["$ISSUE_ID"]""", false, false)))
                .willReturn(
                    json(
                        """{"issueChangeLogs":[{"issueId":"$ISSUE_ID","changeHistories":[{"id":"$HISTORY_ID","created":1790330188061,""" +
                            """"author":$FULL_USER,"items":[{"field":"status","fieldId":"status","from":"1","to":"3",""" +
                            """"fromString":"To Do","toString":"In Progress"}]}]}]}""",
                    ),
                ),
        )
        JiraChangelogStream(client(), store, 50).run(context(connId))

        val stored = obj(store.changelogPayloadsForIssue(connId, ISSUE_ID).single())
        assertEquals(obj(SLIM_USER), stored.getValue("author"))
        assertEquals(1, (stored.getValue("items") as JsonArray).size, "the tracked items are kept whole")
        val authorColumn = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Changelogs.select(JiraRawStore.Changelogs.authorAccountId)
                .where { (JiraRawStore.Changelogs.connectionId eq connId) and (JiraRawStore.Changelogs.historyId eq HISTORY_ID) }
                .map { it[JiraRawStore.Changelogs.authorAccountId] }.toList().single()
        }
        assertEquals("acc-7", authorColumn)
    }

    @Test
    fun `the WORKLOGS stream stores both authors slim, and re-syncing the unchanged worklog keeps its hash`() = runBlocking {
        val connId = connectionWithIssue()
        override(worklogAnswer(FULL_USER, FULL_USER))
        val client = client()
        JiraWorklogStream(client, store).run(context(connId))

        val (payload, hash, changedAt) = worklogRow(connId)
        val stored = obj(payload)
        assertEquals(obj(SLIM_USER), stored.getValue("author"))
        assertEquals(obj(SLIM_USER), stored.getValue("updateAuthor"))
        assertEquals(sha256Hex(canonicalJson(payload)), hash, "the stored text and its hash agree")
        assertNotNull(stored["timeSpentSeconds"])

        // Re-sync: the issue is stale again and Jira answers the same worklog with a DIFFERENT embedded copy of the user.
        removeOverrides()
        override(worklogAnswer(FULL_USER_OTHER_DROPPED_KEYS, FULL_USER))
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.update({ (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq ISSUE_ID) }) {
                it[worklogsSyncedAt] = null
            }
        }
        JiraWorklogStream(client, store).run(context(connId))

        val (payloadAgain, hashAgain, changedAtAgain) = worklogRow(connId)
        assertEquals(hash, hashAgain, "only dropped keys differ, so the hash is identical")
        assertEquals(payload, payloadAgain)
        assertEquals(changedAt, changedAtAgain, "changed_at must not move: the worklog did not change")
    }

    @Test
    fun `upsertWorklog answers UNCHANGED for a re-write that differs only in dropped user keys`() = runBlocking {
        val connId = connectionWithIssue()
        val head = """{"id":"$WORKLOG_ID","issueId":"$ISSUE_ID","author":"""
        val first = """$head$FULL_USER,"timeSpentSeconds":60}"""
        val second = """$head$FULL_USER_OTHER_DROPPED_KEYS,"timeSpentSeconds":60}"""
        assertEquals(RawUpsertOutcome.INSERTED, store.upsertWorklog(connId, ISSUE_ID, WORKLOG_ID, 1_000, first, now = 1_000))
        assertEquals(RawUpsertOutcome.UNCHANGED, store.upsertWorklog(connId, ISSUE_ID, WORKLOG_ID, 1_000, second, now = 2_000))
        assertEquals(1L, store.countWorklogs(connId))
    }
}
