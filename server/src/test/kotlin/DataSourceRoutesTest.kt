package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.ingest.DataSourcePageResponse
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.DataSourceState
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Data sources (v0.2.0 plan §9): ADMIN-only CRUD over `source_connections` (V8), the first
 * `EncryptedAtRest` consumer. Every route guards with `requireAdmin` BEFORE `call.receive()`, so
 * a non-admin's malformed body still 403s (mirrors `TeamTest`'s equivalent case). The Jira API
 * token is write-only: responses/audit only ever carry `jira.hasApiToken`.
 */
class DataSourceRoutesTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    /** The raw `secret` column value — for asserting on the ciphertext envelope directly, past the write-only wire shape. */
    private suspend fun rawSecret(id: UInt): String = suspendTransaction(sharedDatabaseForTests()) {
        val connections = DataSourceService.Connections
        connections.selectAll().where { connections.id eq id }.toList().single()[connections.secret]
    }

    private fun jira(
        siteUrl: String = "https://${unique("site").lowercase()}.atlassian.net",
        email: String = "svc-${unique("acct")}@example.com",
        apiToken: String? = "token-${UUID.randomUUID()}",
        projectKeys: List<String> = listOf("ENG"),
        authScheme: JiraAuthScheme = JiraAuthScheme.BASIC,
    ) = JiraConnectionRequest(siteUrl, email, apiToken, projectKeys, authScheme)

    private fun request(
        name: String,
        jira: JiraConnectionRequest = jira(),
        enabled: Boolean = true,
        syncIntervalMinutes: Int = 60,
        backfillFrom: String? = null,
        reconcileHourUtc: Int = 3,
    ) = DataSourceRequest(name, enabled, syncIntervalMinutes, backfillFrom, reconcileHourUtc, jira)

    @Test
    fun `non-admin gets 403 on every operation, guard before body decode`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("dsuser")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/data-sources").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/data-sources/999999").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/data-sources", request(unique("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/data-sources/999999", request(unique("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/data-sources/999999").status)
        val malformedPost = client.post("/api/v1/data-sources") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformedPost.status, "the guard runs before the body decodes")
        val malformedPut = client.put("/api/v1/data-sources/999999") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformedPut.status)
    }

    @Test
    fun `admin CRUD round-trips - create hides the token, read, rename, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dscrud", UserRole.ADMIN)
        val name = unique("Jira Prod")
        val token = "super-secret-${UUID.randomUUID()}"

        val create = admin.postJson("/api/v1/data-sources", request(name, jira(apiToken = token)))
        assertEquals(HttpStatusCode.Created, create.status)
        assertNotNull(create.headers["Location"])
        val createdBody = create.bodyAsText()
        assertFalse(token in createdBody, "the token must never appear in the response")
        val created = create.body<DataSourceResponse>()
        assertEquals(name, created.name)
        assertTrue(created.jira.hasApiToken)
        assertEquals(DataSourceState.NEVER_SYNCED, created.status.state)
        assertEquals(1L, created.configRevision)

        val read = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>()
        assertEquals(created, read)

        val renamed = unique("Jira Renamed")
        val update = admin.putJson(
            "/api/v1/data-sources/${created.id}",
            request(renamed, jira(siteUrl = created.jira.siteUrl, apiToken = null, projectKeys = listOf("ENG", "OPS"))),
        )
        assertEquals(HttpStatusCode.NoContent, update.status)
        val afterRename = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>()
        assertEquals(renamed, afterRename.name)
        assertEquals(listOf("ENG", "OPS"), afterRename.jira.projectKeys)
        assertTrue(afterRename.jira.hasApiToken, "omitting apiToken keeps the current token")
        assertEquals(2L, afterRename.configRevision, "any successful update bumps configRevision")

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/data-sources/${created.id}", request(renamed)).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/data-sources/${created.id}").status)
    }

    @Test
    fun `a changed siteUrl on update is 409 - it is the connection identity`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsidentity", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", request(unique("id-rule"))).body<DataSourceResponse>()
        val otherSiteUrl = "https://${unique("other").lowercase()}.atlassian.net"
        val response = admin.putJson(
            "/api/v1/data-sources/${created.id}",
            request(created.name, jira(siteUrl = otherSiteUrl)),
        )
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `an active name clash is 409 case-insensitively and a soft-deleted source frees its name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsdup", UserRole.ADMIN)
        val n = unique("Dup")
        val first = admin.postJson("/api/v1/data-sources", request(n)).body<DataSourceResponse>()
        val clash = admin.postJson("/api/v1/data-sources", request(n.uppercase()))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("data source with this name"))

        val other = admin.postJson("/api/v1/data-sources", request(unique("other"))).body<DataSourceResponse>()
        val renameOther = admin.putJson(
            "/api/v1/data-sources/${other.id}",
            request(n, jira(siteUrl = other.jira.siteUrl)),
        )
        assertEquals(HttpStatusCode.Conflict, renameOther.status)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${first.id}").status)
        val second = admin.postJson("/api/v1/data-sources", request(n))
        assertEquals(HttpStatusCode.Created, second.status)
    }

    @Test
    fun `invalid payloads are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsbad", UserRole.ADMIN)
        val cases = listOf(
            "blank name" to request("   "),
            "overlong name" to request("x".repeat(101)),
            "sync interval too low" to request(unique("ok"), syncIntervalMinutes = 1),
            "sync interval too high" to request(unique("ok"), syncIntervalMinutes = 2000),
            "reconcile hour too high" to request(unique("ok"), reconcileHourUtc = 24),
            "backfill in the future" to request(unique("ok"), backfillFrom = "2999-01-01"),
            "backfill not ISO" to request(unique("ok"), backfillFrom = "not-a-date"),
            "backfill with a signed (negative) year" to request(unique("ok"), backfillFrom = "-0001-01-01"),
            "backfill before the 10-year/2000-01-01 floor" to request(unique("ok"), backfillFrom = "1999-01-01"),
            "siteUrl with a path" to request(unique("ok"), jira = jira(siteUrl = "https://acme.atlassian.net/jira")),
            "siteUrl wrong domain" to request(unique("ok"), jira = jira(siteUrl = "https://acme.example.com")),
            "no project keys" to request(unique("ok"), jira = jira(projectKeys = emptyList())),
            "invalid project key" to request(unique("ok"), jira = jira(projectKeys = listOf("lowercase"))),
            "missing api token on create" to request(unique("ok"), jira = jira(apiToken = null)),
            "blank api token on create" to request(unique("ok"), jira = jira(apiToken = "   ")),
        )
        for ((label, case) in cases) {
            val status = admin.postJson("/api/v1/data-sources", case).status
            assertEquals(HttpStatusCode.BadRequest, status, "expected 400 for $label")
        }
    }

    @Test
    fun `siteUrl allow-list rejects SSRF-adjacent variants and Atlassian-owned reserved labels`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dssiteurl", UserRole.ADMIN)
        val cases = listOf(
            "userinfo before the host" to "https://u@acme.atlassian.net",
            "explicit port" to "https://acme.atlassian.net:443",
            "plain http, not https" to "http://acme.atlassian.net",
            "trailing slash" to "https://acme.atlassian.net/",
            "trailing dot" to "https://acme.atlassian.net.",
            "uppercase host" to "https://ACME.atlassian.net",
            "look-alike suffix domain" to "https://acme.atlassian.net.evil.com",
            "path smuggling a real tenant host" to "https://evil.com/acme.atlassian.net",
            "reserved label api" to "https://api.atlassian.net",
            "reserved label id" to "https://id.atlassian.net",
            "reserved label admin" to "https://admin.atlassian.net",
            "reserved label www" to "https://www.atlassian.net",
            "reserved label auth" to "https://auth.atlassian.net",
            "reserved label start" to "https://start.atlassian.net",
            "reserved label home" to "https://home.atlassian.net",
            "reserved label status" to "https://status.atlassian.net",
            "reserved label developer" to "https://developer.atlassian.net",
            "reserved label support" to "https://support.atlassian.net",
            "reserved label community" to "https://community.atlassian.net",
            "reserved label marketplace" to "https://marketplace.atlassian.net",
            "reserved label my" to "https://my.atlassian.net",
            "reserved label team" to "https://team.atlassian.net",
        )
        for ((label, siteUrl) in cases) {
            val status = admin.postJson("/api/v1/data-sources", request(unique("ok"), jira = jira(siteUrl = siteUrl))).status
            assertEquals(HttpStatusCode.BadRequest, status, "expected 400 for $label ($siteUrl)")
        }
        // A genuine tenant site (lowercase, a label outside the denylist) is still accepted.
        val ok = admin.postJson("/api/v1/data-sources", request(unique("ok"), jira = jira(siteUrl = "https://acme-corp.atlassian.net")))
        assertEquals(HttpStatusCode.Created, ok.status)
    }

    @Test
    fun `list supports paging sort and the name filter`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dslist", UserRole.ADMIN)
        val prefix = unique("Findme")
        admin.postJson("/api/v1/data-sources", request("$prefix Alpha"))
        admin.postJson("/api/v1/data-sources", request("$prefix Beta"))
        admin.postJson("/api/v1/data-sources", request(unique("Elsewhere")))

        val page = admin.get("/api/v1/data-sources?name=$prefix&sort=-name&pageSize=100").body<DataSourcePageResponse>()
        assertEquals(2, page.total)
        assertEquals(listOf("$prefix Beta", "$prefix Alpha"), page.items.map { it.name })
        val badSort = admin.get("/api/v1/data-sources?sort=unknownField")
        assertEquals(HttpStatusCode.BadRequest, badSort.status)
    }

    @Test
    fun `mutations audit and never carry the token`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val token = "top-secret-${UUID.randomUUID()}"
            val created = admin.postJson("/api/v1/data-sources", request(unique("audited"), jira(apiToken = token)))
                .body<DataSourceResponse>()
            val createdEvent = capture.awaitEvent {
                it.message == "data_source.created" && it.hasKeyValue("dataSourceId", created.id.toLong())
            }
            assertNotNull(createdEvent)
            assertFalse(createdEvent.formattedMessage.contains(token))
            assertTrue(createdEvent.keyValuePairs.none { it.value.toString().contains(token) }, "no audit field may carry the token")

            admin.putJson(
                "/api/v1/data-sources/${created.id}",
                request(created.name, jira(siteUrl = created.jira.siteUrl, apiToken = "rotated-${UUID.randomUUID()}")),
            )
            val updatedEvent = capture.awaitEvent {
                it.message == "data_source.updated" && it.hasKeyValue("dataSourceId", created.id.toLong())
            }
            assertNotNull(updatedEvent)
            val rotatedEvent = capture.awaitEvent {
                it.message == "data_source.token_rotated" && it.hasKeyValue("dataSourceId", created.id.toLong())
            }
            assertNotNull(rotatedEvent)

            admin.delete("/api/v1/data-sources/${created.id}")
            val deletedEvent = capture.awaitEvent {
                it.message == "data_source.deleted" && it.hasKeyValue("dataSourceId", created.id.toLong())
            }
            assertNotNull(deletedEvent)
        }
    }

    @Test
    fun `an update that omits apiToken does not audit a rotation`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsnorotate", UserRole.ADMIN)
        withAuditCapture { capture ->
            val created = admin.postJson("/api/v1/data-sources", request(unique("norotate"))).body<DataSourceResponse>()
            admin.putJson(
                "/api/v1/data-sources/${created.id}",
                request(created.name, jira(siteUrl = created.jira.siteUrl, apiToken = null)),
            )
            val updatedEvent = capture.awaitEvent {
                it.message == "data_source.updated" && it.hasKeyValue("dataSourceId", created.id.toLong())
            }
            assertNotNull(updatedEvent)
            val hasRotationEvent = capture.events.any {
                it.message == "data_source.token_rotated" && it.hasKeyValue("dataSourceId", created.id.toLong())
            }
            assertTrue(!hasRotationEvent)
        }
    }

    @Test
    fun `PUT with a new apiToken changes the raw stored secret - omitted or blank leaves it byte-identical`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsrawrotate", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", request(unique("rawrotate"))).body<DataSourceResponse>()
        val rawAfterCreate = rawSecret(created.id)
        assertTrue(rawAfterCreate.startsWith(FieldCipher.PREFIX))

        val newToken = "rotated-${UUID.randomUUID()}"
        val rotatePut = admin.putJson(
            "/api/v1/data-sources/${created.id}",
            request(created.name, jira(siteUrl = created.jira.siteUrl, apiToken = newToken)),
        )
        assertEquals(HttpStatusCode.NoContent, rotatePut.status)
        val rawAfterRotate = rawSecret(created.id)
        assertNotEquals(rawAfterCreate, rawAfterRotate, "a present apiToken must change the raw stored secret")
        assertTrue(rawAfterRotate.startsWith(FieldCipher.PREFIX))
        assertEquals(
            newToken,
            FieldCipher(DEV_DATA_ENCRYPTION_KEY).decrypt(rawAfterRotate),
            "the raw secret must decrypt to the NEW token under the dev default key",
        )

        val omitPut = admin.putJson(
            "/api/v1/data-sources/${created.id}",
            request(created.name, jira(siteUrl = created.jira.siteUrl, apiToken = null)),
        )
        assertEquals(HttpStatusCode.NoContent, omitPut.status, "an omitted apiToken must be accepted, not 400")
        val rawAfterOmitted = rawSecret(created.id)
        assertEquals(rawAfterRotate, rawAfterOmitted, "an omitted apiToken must leave the raw secret byte-identical")

        val blankPut = admin.putJson(
            "/api/v1/data-sources/${created.id}",
            request(created.name, jira(siteUrl = created.jira.siteUrl, apiToken = "   ")),
        )
        assertEquals(HttpStatusCode.NoContent, blankPut.status, "a blank apiToken must be accepted (treated as unchanged), not 400")
        val rawAfterBlank = rawSecret(created.id)
        assertEquals(rawAfterRotate, rawAfterBlank, "a blank apiToken must leave the raw secret byte-identical, like an omitted one")
    }

    @Test
    fun `JiraConnectionRequest and DataSourceRequest never leak the token via toString`() {
        val token = "super-secret-${UUID.randomUUID()}"
        val withToken = jira(apiToken = token)
        assertFalse(token in withToken.toString(), "the raw token must never appear in toString()")
        assertTrue(withToken.toString().contains("apiToken=***"), "a present token must render as ***")

        val withoutToken = jira(apiToken = null)
        assertTrue(withoutToken.toString().contains("apiToken=null"), "a null token must render as null")

        val wrapped = request(unique("tostring"), jira = withToken)
        assertFalse(token in wrapped.toString(), "DataSourceRequest embeds JiraConnectionRequest and must not leak it either")
    }

    @Test
    fun `an unknown key in the stored settings jsonb does not fail decoding - rolling-deploy tolerance`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("dsunknownkey", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", request(unique("unknownkey"))).body<DataSourceResponse>()

        suspendTransaction(sharedDatabaseForTests()) {
            val connections = DataSourceService.Connections
            val current = connections.selectAll().where { connections.id eq created.id }.toList().single()[connections.settings]
            val withExtraKey = current.removeSuffix("}") + ",\"futureField\":\"from-a-newer-replica\"}"
            connections.update({ connections.id eq created.id }) { it[settings] = canonicalJson(withExtraKey) }
        }

        val read = admin.get("/api/v1/data-sources/${created.id}")
        assertEquals(HttpStatusCode.OK, read.status, "an unknown settings key must not 500 a rolling-deploy read")
        assertEquals(created.jira.siteUrl, read.body<DataSourceResponse>().jira.siteUrl)
    }
}
