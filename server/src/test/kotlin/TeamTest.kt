package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.WorkItemStore
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
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
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

    @Test
    fun `a manager round-trips on create, read and list and a PUT replaces or clears it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgr", UserRole.ADMIN)
        val ann = TestUsers.seed(email = uniqueEmail("mgr-a"), password = "pw", role = UserRole.USER, name = "Ann Manager")
        val bob = TestUsers.seed(email = uniqueEmail("mgr-b"), password = "pw", role = UserRole.USER, name = "Bob Manager")
        val teamName = name("managed")

        val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerUserId = ann))
            .body<TeamResponse>()
        assertEquals(ann, created.managerUserId)
        assertEquals("Ann Manager", created.managerName)
        assertEquals(created, admin.get("/api/v1/teams/${created.id}").body<TeamResponse>())
        val listed = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(ann, listed.managerUserId)
        assertEquals("Ann Manager", listed.managerName)

        // The manager is not a member: the roster and the manager are independent.
        assertTrue(created.members.isEmpty())

        // One user may manage several teams.
        val second = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("managed2"), managerUserId = ann))
            .body<TeamResponse>()
        assertEquals(ann, second.managerUserId)

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName, managerUserId = bob)).status,
        )
        val replaced = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(bob, replaced.managerUserId)
        assertEquals("Bob Manager", replaced.managerName)

        // PUT is a full replace: an omitted managerUserId clears it.
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName)).status)
        val cleared = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(null, cleared.managerUserId)
        assertEquals(null, cleared.managerName)
        val clearedListed = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(null, clearedListed.managerUserId)
    }

    @Test
    fun `an unknown or soft-deleted manager is 400 on create and on update and changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrbad", UserRole.ADMIN)
        val gone = TestUsers.seed(email = uniqueEmail("mgr-gone"), password = "pw", role = UserRole.USER)
        TestUsers.softDelete(gone)
        val good = TestUsers.seed(email = uniqueEmail("mgr-good"), password = "pw", role = UserRole.USER)
        val teamName = name("mgrbad")

        for (bad in listOf(999999u, gone)) {
            val create = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerUserId = bad))
            assertEquals(HttpStatusCode.BadRequest, create.status, "create with manager $bad")
            assertTrue(create.body<ProblemDetail>().detail!!.contains("manager"))
        }
        assertTrue(TestTeams.rawRows().none { it.name == teamName }, "a rejected create leaves no team behind")

        val id = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerUserId = good)).body<TeamResponse>().id
        for (bad in listOf(999999u, gone)) {
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson("/api/v1/teams/$id", TeamUpdateRequest(name = name("renamed"), managerUserId = bad)).status,
                "update with manager $bad",
            )
        }
        val after = admin.get("/api/v1/teams/$id").body<TeamResponse>()
        assertEquals(good, after.managerUserId, "a rejected update changes nothing")
        assertEquals(teamName, after.name)
    }

    @Test
    fun `a soft-deleted manager reads as no manager and stops being the active manager`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrdel", UserRole.ADMIN)
        val manager = TestUsers.seed(email = uniqueEmail("mgr-del"), password = "pw", role = UserRole.USER, name = "Soon Gone")
        val teamName = name("mgrdel")
        val id = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerUserId = manager))
            .body<TeamResponse>().id
        assertTrue(TestTeams.service.isActiveManager(id, manager))

        TestUsers.softDelete(manager)

        val read = admin.get("/api/v1/teams/$id").body<TeamResponse>()
        assertEquals(null, read.managerUserId)
        assertEquals(null, read.managerName)
        val listed = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(null, listed.managerUserId)
        assertEquals(null, listed.managerName)
        assertFalse(TestTeams.service.isActiveManager(id, manager), "a deleted manager holds no rights")
    }

    @Test
    fun `isActiveManager is true only for the active manager of an active team`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrchk", UserRole.ADMIN)
        val manager = TestUsers.seed(email = uniqueEmail("mgr-chk"), password = "pw", role = UserRole.USER)
        val other = TestUsers.seed(email = uniqueEmail("mgr-oth"), password = "pw", role = UserRole.USER)
        val managed = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("chk"), managerUserId = manager))
            .body<TeamResponse>().id
        val unmanaged = TestTeams.seed(name("chk-none"))

        assertTrue(TestTeams.service.isActiveManager(managed, manager))
        assertFalse(TestTeams.service.isActiveManager(managed, other), "somebody else")
        assertFalse(TestTeams.service.isActiveManager(unmanaged, manager), "a team they do not manage")
        assertFalse(TestTeams.service.isActiveManager(999999u, manager), "a missing team")
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$managed").status)
        assertFalse(TestTeams.service.isActiveManager(managed, manager), "a deleted team")
    }

    @Test
    fun `being a team's manager grants no write on the team itself`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrrw", UserRole.ADMIN)
        val email = uniqueEmail("mgr-rw")
        val manager = TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        val id = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("rw"), managerUserId = manager))
            .body<TeamResponse>().id
        val client = authedClient(email, "pw")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams/$id").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.putJson("/api/v1/teams/$id", TeamUpdateRequest(name = name("hijack"), managerUserId = manager)).status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/teams/$id").status)
    }

    @Test
    fun `team manager_changed is audited on an actual change and not on a same-id re-PUT`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("mgr-audit-admin")
        val adminId = TestUsers.seed(email = adminEmail, password = "pw", role = UserRole.ADMIN)
        val admin = authedClient(adminEmail, "pw")
        val ann = TestUsers.seed(email = uniqueEmail("mgr-au"), password = "pw", role = UserRole.USER)
        withAuditCapture { capture ->
            val teamName = name("mgraudit")
            val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerUserId = ann))
                .body<TeamResponse>()
            val teamId = created.id.toLong()
            val createdEvent = capture.awaitEvent { it.message == "team.created" && it.hasKeyValue("teamId", teamId) }
            assertNotNull(createdEvent)
            assertTrue(createdEvent.hasKeyValue("managerUserId", ann.toLong()))

            // Same manager again: team.updated fires, team.manager_changed does not.
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName, managerUserId = ann))
            assertNotNull(capture.awaitEvent { it.message == "team.updated" && it.hasKeyValue("teamId", teamId) })

            // A real change (clear) — and only now does the trail carry a manager line.
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName))
            val changed = capture.awaitEvent { it.message == "team.manager_changed" && it.hasKeyValue("teamId", teamId) }
            assertNotNull(changed)
            assertTrue(changed.hasKeyValue("byUserId", adminId.toLong()))
            assertTrue(changed.hasKeyValue("managerFrom", ann.toLong()))
            assertTrue(changed.hasKeyValue("managerTo", null))
            assertEquals(
                1,
                capture.events.count { it.message == "team.manager_changed" && it.hasKeyValue("teamId", teamId) },
                "only the actual change is audited",
            )
        }
    }

    /** Direct raw-table read of `metrics.team_membership` — this is a `teams` test asserting a cross-feature effect. */
    private suspend fun rawMetricsMembershipRows(teamId: UInt): List<Pair<Long, Long?>> = suspendTransaction(sharedDatabaseForTests()) {
        val m = TeamMembershipService.TeamMembership
        m.selectAll().where { m.teamId eq teamId }.toList().map { it[m.validFrom] to it[m.validTo] }
    }

    @Test
    fun `deleting a team closes its OPEN metrics team_membership rows and removes future-only ones`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamdeletemembership", UserRole.ADMIN)
        val dataSources = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
        val connectionId = dataSources.create(
            DataSourceRequest(
                name = name("team-delete-membership"),
                enabled = true,
                syncIntervalMinutes = 60,
                backfillFrom = "2025-01-01",
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = "https://${name("site").lowercase()}.atlassian.net",
                    email = "svc-${name("acct")}@example.com",
                    apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = listOf("FLO"),
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )
        val accountId = "account-${UUID.randomUUID()}"
        val person = PersonRef(accountId, "Cross Feature Person", null, active = true)
        WorkItemStore(sharedDatabaseForTests()).replacePeople(connectionId, listOf(person))
        val teamId = TestTeams.seed(name("cross-feature"))

        // Non-overlapping by construction: the CURRENT row runs [0, farFuture) — active right now,
        // to be CLOSED at `now` on delete — and the FUTURE-only row starts exactly where it ends
        // and hasn't begun yet (`farFuture` > now), to be REMOVED outright on delete.
        val farFuture = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, 0L, farFuture))
        admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, farFuture, null))
        assertEquals(2, rawMetricsMembershipRows(teamId).size, "fixture: two rows before delete")

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$teamId").status)

        val remaining = rawMetricsMembershipRows(teamId)
        assertEquals(1, remaining.size, "the future-only row is removed outright")
        assertEquals(0L, remaining.single().first, "the surviving row's own validFrom is untouched")
        assertNotNull(remaining.single().second, "the surviving row is CLOSED at now (valid_to set), never left open")
        assertTrue(TestTeams.rawRows().single { it.id == teamId }.markedAsDeleted, "the team itself is soft-deleted, as ever")
    }
}
