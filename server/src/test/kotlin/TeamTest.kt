package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.teams.TeamCreateRequest
import ch.nokillswit.teams.TeamPageResponse
import ch.nokillswit.teams.TeamResponse
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The flat-teams surface (V6): CRUD, the roster, the list filters, the authz split (any
 * authenticated reads, ADMIN writes with guard-before-read), and the soft-delete/partial-index
 * semantics. Teams are SHARED suite state — every test mints UNIQUE names.
 */
class TeamTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/teams").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/teams/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/teams").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/teams/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/teams/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/teams/1/members/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/teams/1/members/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("teamuser")
        val id = TestTeams.seed(name("teamro"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams/$id").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/teams", TeamCreateRequest(name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/teams/999999", TeamUpdateRequest(name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/teams/999999").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/teams/999999/members/1").status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/teams/999999/members/1").status)
        // The guard runs before the body decodes: a non-admin's malformed body stays 403.
        val malformed = client.post("/api/v1/teams") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
    }

    @Test
    fun `admin CRUD round-trips - create with a roster, read, list, rename, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamcrud", UserRole.ADMIN)
        val memberA = TestUsers.seed(email = uniqueEmail("tm-a"), password = "pw", role = UserRole.USER, name = "Ann Member")
        val memberB = TestUsers.seed(email = uniqueEmail("tm-b"), password = "pw", role = UserRole.USER, name = "Bob Member")
        val teamName = name("Payments")

        val create = admin.postJson(
            "/api/v1/teams",
            TeamCreateRequest(name = "  $teamName  ", description = "  Money movers  ", memberIds = listOf(memberA, memberB, memberA)),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        assertNotNull(create.headers["Location"])
        val created = create.body<TeamResponse>()
        assertEquals(teamName, created.name, "the sanitizer trims")
        assertEquals("Money movers", created.description)
        assertEquals(listOf(memberA, memberB), created.members.map { it.userId }, "roster deduped, name-ordered")
        assertEquals("Ann Member", created.members.first().name)
        assertFalse(created.members.any { it.deleted })

        val read = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(created, read)

        val page = admin.get("/api/v1/teams?name=${teamName.lowercase()}&sort=-name").body<TeamPageResponse>()
        assertEquals(1, page.total)
        assertEquals(2, page.items.single().memberCount)

        val renamed = name("Treasury")
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = renamed, description = null)).status,
        )
        val afterRename = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(renamed, afterRename.name)
        assertEquals(null, afterRename.description, "PUT is a full replace — an omitted description clears")
        assertEquals(created.members, afterRename.members, "the roster is untouched by the rename")
        assertTrue(afterRename.updatedAt >= created.updatedAt)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/teams/${created.id}").status)
        assertTrue(TestTeams.rawRows().single { it.id == created.id }.markedAsDeleted, "delete must soft-delete")
        assertEquals(setOf(memberA, memberB), TestTeams.rawMemberIds(created.id), "the roster stays for the record")
    }

    @Test
    fun `the roster - add, duplicate 409, unknown user 404, remove, remove-again 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamroster", UserRole.ADMIN)
        val member = TestUsers.seed(email = uniqueEmail("tm-r"), password = "pw", role = UserRole.USER)
        val id = TestTeams.seed(name("roster"))

        assertEquals(HttpStatusCode.NoContent, admin.post("/api/v1/teams/$id/members/$member").status)
        assertEquals(HttpStatusCode.Conflict, admin.post("/api/v1/teams/$id/members/$member").status)
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/teams/$id/members/999999").status)
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/teams/999999/members/$member").status)
        assertEquals(listOf(member), admin.get("/api/v1/teams/$id").body<TeamResponse>().members.map { it.userId })

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$id/members/$member").status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/teams/$id/members/$member").status)
        assertTrue(admin.get("/api/v1/teams/$id").body<TeamResponse>().members.isEmpty())
    }

    @Test
    fun `a soft-deleted member stays on the roster flagged and leaves the active count`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamgone", UserRole.ADMIN)
        val member = TestUsers.seed(email = uniqueEmail("tm-g"), password = "pw", role = UserRole.USER, name = "Gone Soon")
        val teamName = name("ghosts")
        val id = TestTeams.seed(teamName, listOf(member))
        TestUsers.softDelete(member)

        val roster = admin.get("/api/v1/teams/$id").body<TeamResponse>().members
        assertEquals(1, roster.size)
        assertTrue(roster.single().deleted)
        assertEquals("Gone Soon", roster.single().name)
        val row = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(0, row.memberCount, "the list counts ACTIVE members only")
        // A deleted user's id is no longer addable — but the stale row can still be removed.
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$id/members/$member").status)
    }

    @Test
    fun `memberId filters the list to the user's teams`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamfilter", UserRole.ADMIN)
        val member = TestUsers.seed(email = uniqueEmail("tm-f"), password = "pw", role = UserRole.USER)
        val mine = TestTeams.seed(name("mine"), listOf(member))
        TestTeams.seed(name("theirs"))
        val page = admin.get("/api/v1/teams?memberId=$member&pageSize=100").body<TeamPageResponse>()
        assertEquals(listOf(mine), page.items.map { it.id })
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/teams?memberId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/teams?sort=description").status)
    }

    @Test
    fun `invalid payloads are 400 - blank name, overlong fields, unknown member, oversize roster`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teambad", UserRole.ADMIN)
        val cases = listOf(
            TeamCreateRequest(name = "   "),
            TeamCreateRequest(name = "x".repeat(101)),
            TeamCreateRequest(name = name("ok"), description = "d".repeat(501)),
            TeamCreateRequest(name = name("ok"), memberIds = listOf(999999u)),
            TeamCreateRequest(name = name("ok"), memberIds = (1u..201u).toList()),
            TeamCreateRequest(name = "tab\tcontrol"),
        )
        for (case in cases) {
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/teams", case).status, "expected 400 for $case")
        }
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/v1/teams/999999", TeamUpdateRequest(name = "")).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/teams/999999", TeamUpdateRequest(name = name("nf"))).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/teams/999999").status)
    }

    @Test
    fun `an active name clash is 409 case-insensitively and a soft-deleted team frees its name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamdup", UserRole.ADMIN)
        val n = name("Dup")
        val first = admin.postJson("/api/v1/teams", TeamCreateRequest(n)).body<TeamResponse>()
        val clash = admin.postJson("/api/v1/teams", TeamCreateRequest(n.uppercase()))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("team with this name"))
        val other = admin.postJson("/api/v1/teams", TeamCreateRequest(name("other"))).body<TeamResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.putJson("/api/v1/teams/${other.id}", TeamUpdateRequest(n)).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${first.id}").status)
        val second = admin.postJson("/api/v1/teams", TeamCreateRequest(n))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<TeamResponse>().id != first.id)
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamaudit", UserRole.ADMIN)
        val member = TestUsers.seed(email = uniqueEmail("tm-au"), password = "pw", role = UserRole.USER)
        withAuditCapture { capture ->
            val n = name("audited")
            val created = admin.postJson("/api/v1/teams", TeamCreateRequest(n)).body<TeamResponse>()
            val event = capture.awaitEvent { it.message == "team.created" && it.hasKeyValue("teamId", created.id.toLong()) }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("name", n))

            admin.post("/api/v1/teams/${created.id}/members/$member")
            assertNotNull(capture.awaitEvent { it.message == "team.member_added" && it.hasKeyValue("targetUserId", member.toLong()) })
            admin.delete("/api/v1/teams/${created.id}/members/$member")
            assertNotNull(capture.awaitEvent { it.message == "team.member_removed" && it.hasKeyValue("targetUserId", member.toLong()) })
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name("renamed")))
            assertNotNull(capture.awaitEvent { it.message == "team.updated" && it.hasKeyValue("teamId", created.id.toLong()) })
            admin.delete("/api/v1/teams/${created.id}")
            assertNotNull(capture.awaitEvent { it.message == "team.deleted" && it.hasKeyValue("teamId", created.id.toLong()) })

            val before = capture.events.count { it.message == "team.created" }
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/teams", TeamCreateRequest("")).status)
            assertEquals(before, capture.events.count { it.message == "team.created" }, "failed create must not audit")
        }
    }
}
