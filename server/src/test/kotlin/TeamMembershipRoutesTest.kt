package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipListResponse
import ch.nokillswit.metrics.TeamMembershipResponse
import ch.nokillswit.metrics.TeamMembershipUpdateRequest
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D1's dated Jira-user team membership API (v0.3.0 M1 commit 3): the reads-any/writes-ADMIN
 * posture, the account-existence 400, the read-before-guard/active-required split, the
 * exclusion-constraint 409 (overlap, adjacency, an open-ended row blocking a later one, an
 * overlapping PUT), the config-revision discrimination and the no-op-PUT idempotence, and
 * `TeamService.delete`'s membership-closing fix.
 */
class TeamMembershipRoutesTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())

    /** A fresh connection with ONE known `norm.people` row — [TeamMembershipService]'s account-existence check. */
    private suspend fun seedKnownAccount(displayName: String = "Known Person"): String {
        val connectionId = dataSources().create(
            DataSourceRequest(
                name = unique("membership-fixture"),
                enabled = true,
                syncIntervalMinutes = 60,
                backfillFrom = "2025-01-01",
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                    email = "svc-${unique("acct")}@example.com",
                    apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = listOf("FLO"),
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )
        val accountId = "account-${UUID.randomUUID()}"
        workItems().replacePeople(connectionId, listOf(PersonRef(accountId, displayName, null, active = true)))
        return accountId
    }

    private suspend fun HttpClient.memberships(teamId: UInt) = get("/api/v1/teams/$teamId/jira-memberships")
    private suspend fun HttpClient.configRevision() = get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.memberships(1u).status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/v1/teams/1/jira-memberships", TeamMembershipCreateRequest("a", 0L)).status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.putJson("/api/v1/teams/1/jira-memberships/1", TeamMembershipUpdateRequest(0L)).status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/teams/1/jira-memberships/1").status)
    }

    @Test
    fun `non-admin may read but not write - 403 before the body decodes, even malformed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("membershipuser")
        val teamId = TestTeams.seed(unique("membershipro"))
        assertEquals(HttpStatusCode.OK, client.memberships(teamId).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest("a", 0L)).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.putJson("/api/v1/teams/999999/jira-memberships/1", TeamMembershipUpdateRequest(0L)).status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/teams/999999/jira-memberships/1").status)
        val malformed = client.post("/api/v1/teams/$teamId/jira-memberships") { setBody("{ not json") }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
    }

    @Test
    fun `create round-trips, list orders most-recent-first, update and delete, revision bumps each time`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipcrud", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("crud"))
        val accountId = seedKnownAccount()

        val beforeCreate = admin.configRevision()
        val create = admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, 1_000L, 2_000L))
        assertEquals(HttpStatusCode.Created, create.status)
        assertNotNull(create.headers["Location"])
        val created = create.body<TeamMembershipResponse>()
        assertEquals(accountId, created.accountId)
        assertEquals(1_000L, created.validFrom)
        assertEquals(2_000L, created.validTo)
        assertEquals(beforeCreate + 1, admin.configRevision(), "create must bump the shared revision")

        val listed = admin.memberships(teamId).body<TeamMembershipListResponse>().items
        assertEquals(listOf(created.id), listed.map { it.id })

        val beforeUpdate = admin.configRevision()
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/$teamId/jira-memberships/${created.id}", TeamMembershipUpdateRequest(1_500L, null)).status,
        )
        assertEquals(beforeUpdate + 1, admin.configRevision(), "an ACTUAL date change must bump the revision")
        val afterUpdate = admin.memberships(teamId).body<TeamMembershipListResponse>().items.single()
        assertEquals(1_500L, afterUpdate.validFrom)
        assertEquals(null, afterUpdate.validTo)

        val beforeDelete = admin.configRevision()
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$teamId/jira-memberships/${created.id}").status)
        assertEquals(beforeDelete + 1, admin.configRevision(), "delete must bump the revision")
        assertTrue(admin.memberships(teamId).body<TeamMembershipListResponse>().items.isEmpty())
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/teams/$teamId/jira-memberships/${created.id}").status)
    }

    @Test
    fun `a no-op PUT resubmitting the same dates bumps no revision and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipnoop", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("noop"))
        val accountId = seedKnownAccount()
        val created = admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, 10L, 20L))
            .body<TeamMembershipResponse>()

        withAuditCapture { capture ->
            val before = admin.configRevision()
            val beforeCount = capture.events.count { it.message == "team.jira_membership_updated" }
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/teams/$teamId/jira-memberships/${created.id}", TeamMembershipUpdateRequest(10L, 20L)).status,
            )
            assertEquals(before, admin.configRevision(), "identical dates must not bump the revision")
            assertEquals(
                beforeCount,
                capture.events.count { it.message == "team.jira_membership_updated" },
                "identical dates must not audit",
            )
        }
    }

    @Test
    fun `PUT overlapping another row of the same account is 409, and an unknown membershipId is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipputconflict", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("putconflict"))
        val accountId = seedKnownAccount()
        val first = admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, 0L, 100L))
            .body<TeamMembershipResponse>()
        val second = admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, 100L, 200L))
            .body<TeamMembershipResponse>()

        val overlapping = admin.putJson("/api/v1/teams/$teamId/jira-memberships/${second.id}", TeamMembershipUpdateRequest(50L, 150L))
        assertEquals(HttpStatusCode.Conflict, overlapping.status)
        // Unaffected by the failed PUT.
        val stillFirst = admin.memberships(teamId).body<TeamMembershipListResponse>().items.first { it.id == first.id }
        assertEquals(first.validFrom, stillFirst.validFrom)

        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/teams/$teamId/jira-memberships/999999", TeamMembershipUpdateRequest(500L, 600L)).status,
        )
    }

    @Test
    fun `an unknown account id is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipbadacct", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("badacct"))
        val response = admin.postJson(
            "/api/v1/teams/$teamId/jira-memberships",
            TeamMembershipCreateRequest("no-such-account-${UUID.randomUUID()}", 0L),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a never-created team is 404 on read, a soft-deleted team's history stays readable but its writes 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipnoteam", UserRole.ADMIN)
        val accountId = seedKnownAccount()
        assertEquals(
            HttpStatusCode.NotFound,
            admin.postJson("/api/v1/teams/999999/jira-memberships", TeamMembershipCreateRequest(accountId, 0L)).status,
        )
        assertEquals(HttpStatusCode.NotFound, admin.memberships(999999u).status, "an id that was NEVER a team is 404")

        val deletedTeamId = TestTeams.seed(unique("deleted"))
        val existing = admin.postJson("/api/v1/teams/$deletedTeamId/jira-memberships", TeamMembershipCreateRequest(accountId, 0L, 100L))
            .body<TeamMembershipResponse>()
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$deletedTeamId").status)

        // Read-before-guard: the team's OWN membership history stays visible after it is gone.
        val afterDelete = admin.memberships(deletedTeamId)
        assertEquals(HttpStatusCode.OK, afterDelete.status)
        assertEquals(listOf(existing.id), afterDelete.body<TeamMembershipListResponse>().items.map { it.id })

        // But every write still needs an ACTIVE team.
        assertEquals(
            HttpStatusCode.NotFound,
            admin.postJson("/api/v1/teams/$deletedTeamId/jira-memberships", TeamMembershipCreateRequest(accountId, 500L)).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/$deletedTeamId/jira-memberships/${existing.id}", TeamMembershipUpdateRequest(0L, 200L)).status,
            "PUT/DELETE of an EXISTING row need no team-active check",
        )
    }

    @Test
    fun `soft-deleting a team closes its open memberships and frees the account to be re-teamed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipteamdelete", UserRole.ADMIN)
        val accountId = seedKnownAccount()
        val oldTeamId = TestTeams.seed(unique("old-team"))
        val newTeamId = TestTeams.seed(unique("new-team"))

        // Non-overlapping by construction: a CURRENT row [0, farFuture) — active right now, to be
        // CLOSED at `now` on delete — and a FUTURE-only row [farFuture, ∞) that hasn't started yet,
        // to be REMOVED outright on delete.
        val farFuture = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        val current = admin.postJson("/api/v1/teams/$oldTeamId/jira-memberships", TeamMembershipCreateRequest(accountId, 0L, farFuture))
            .body<TeamMembershipResponse>()
        admin.postJson("/api/v1/teams/$oldTeamId/jira-memberships", TeamMembershipCreateRequest(accountId, farFuture, null))

        val beforeDelete = admin.configRevision()
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$oldTeamId").status)
        assertEquals(beforeDelete + 1, admin.configRevision(), "closing/removing memberships must bump the shared revision")

        val remaining = admin.memberships(oldTeamId).body<TeamMembershipListResponse>().items
        assertEquals(listOf(current.id), remaining.map { it.id }, "the future-only row is REMOVED outright, never left dangling")
        assertNotNull(remaining.single().validTo, "the current row is CLOSED (valid_to = now), never left open")
        assertTrue(remaining.single().validTo!! < farFuture, "closed at now, not left at its original farFuture end")

        // Without the fix, this INSERT would 409 forever (the old open interval still blocking it).
        val reTeamed = admin.postJson(
            "/api/v1/teams/$newTeamId/jira-memberships",
            TeamMembershipCreateRequest(accountId, remaining.single().validTo!!),
        )
        assertEquals(HttpStatusCode.Created, reTeamed.status, "a deleted team must never strand an account behind an open EXCLUDE interval")
    }

    @Test
    fun `overlap is 409 - adjacent intervals are fine, an open-ended row blocks a later one`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipoverlap", UserRole.ADMIN)
        val teamA = TestTeams.seed(unique("overlap-a"))
        val teamB = TestTeams.seed(unique("overlap-b"))
        val accountId = seedKnownAccount()

        val first = admin.postJson("/api/v1/teams/$teamA/jira-memberships", TeamMembershipCreateRequest(accountId, 0L, 100L))
        assertEquals(HttpStatusCode.Created, first.status)

        // Adjacent [0,100) + [100,200) for the SAME account — must be accepted (half-open ranges).
        val adjacent = admin.postJson("/api/v1/teams/$teamB/jira-memberships", TeamMembershipCreateRequest(accountId, 100L, 200L))
        assertEquals(HttpStatusCode.Created, adjacent.status)

        // Overlapping [50, 150) clashes with BOTH existing rows.
        val overlapping = admin.postJson("/api/v1/teams/$teamA/jira-memberships", TeamMembershipCreateRequest(accountId, 50L, 150L))
        assertEquals(HttpStatusCode.Conflict, overlapping.status)

        // An open-ended row (starting after the adjacent one) blocks anything later for the same account.
        val openEnded = admin.postJson("/api/v1/teams/$teamB/jira-memberships", TeamMembershipCreateRequest(accountId, 200L, null))
        assertEquals(HttpStatusCode.Created, openEnded.status)
        val blockedByOpenEnded = admin.postJson("/api/v1/teams/$teamA/jira-memberships", TeamMembershipCreateRequest(accountId, 300L, 400L))
        assertEquals(HttpStatusCode.Conflict, blockedByOpenEnded.status)
    }

    @Test
    fun `invalid payloads are 400 - blank or overlong accountId, validTo not after validFrom`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipbadbody", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("badbody"))
        val accountId = seedKnownAccount()
        val cases = listOf(
            TeamMembershipCreateRequest("   ", 0L),
            TeamMembershipCreateRequest("a".repeat(101), 0L),
            TeamMembershipCreateRequest(accountId, 100L, 100L),
            TeamMembershipCreateRequest(accountId, 100L, 50L),
        )
        for (case in cases) {
            val response = admin.postJson("/api/v1/teams/$teamId/jira-memberships", case)
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for $case")
        }
    }

    @Test
    fun `mutations audit, a failed mutation does not, and a failed create bumps no revision`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("membershipaudit", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("audit"))
        val accountId = seedKnownAccount()
        withAuditCapture { capture ->
            val created = admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(accountId, 0L, 100L))
                .body<TeamMembershipResponse>()
            val addedEvent = capture.awaitEvent { it.message == "team.jira_membership_added" && it.hasKeyValue("accountId", accountId) }
            assertNotNull(addedEvent, "create must audit")
            assertTrue(addedEvent.hasKeyValue("teamId", teamId.toLong()))

            admin.putJson("/api/v1/teams/$teamId/jira-memberships/${created.id}", TeamMembershipUpdateRequest(0L, 200L))
            assertNotNull(capture.awaitEvent { it.message == "team.jira_membership_updated" && it.hasKeyValue("accountId", accountId) })

            admin.delete("/api/v1/teams/$teamId/jira-memberships/${created.id}")
            assertNotNull(capture.awaitEvent { it.message == "team.jira_membership_removed" && it.hasKeyValue("accountId", accountId) })

            val before = capture.events.count { it.message == "team.jira_membership_added" }
            val beforeRevision = admin.configRevision()
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest("   ", 0L)).status,
            )
            assertEquals(before, capture.events.count { it.message == "team.jira_membership_added" }, "failed create must not audit")
            assertEquals(beforeRevision, admin.configRevision(), "failed create must not bump the revision")
        }
    }
}
