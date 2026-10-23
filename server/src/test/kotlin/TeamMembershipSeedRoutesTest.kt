package ch.nokillswit

import ch.nokillswit.metrics.JiraTeamListResponse
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.SeedCandidateListResponse
import ch.nokillswit.metrics.SeedSkipReason
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipListResponse
import ch.nokillswit.metrics.TeamMembershipSeedRequest
import ch.nokillswit.metrics.TeamMembershipSeedResponse
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * Seeding a team's dated Jira memberships from the Atlassian Team field (D1): `GET /jira-teams`, `GET
 * /teams/{id}/jira-memberships/seed-candidates` and `POST …/seed`. Every test builds its OWN disabled connection with a UNIQUE Atlassian
 * team id (memberships are global by account, the shared stub connection is read-only), and removes what it made in a `finally`
 * (`TeamService.delete` closes the team's memberships; the connection is soft-deleted so its issues leave every picker).
 */
class TeamMembershipSeedRoutesTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private val day = 86_400_000L
    private val march10Late = Instant.parse("2026-03-10T23:30:00Z").toEpochMilli() // 00:30 on 2026-03-11 in Warsaw: the zone day matters

    private class Person(val accountId: String, val name: String)

    /** One disabled connection and a UNIQUE Atlassian team id; [known] mirrors the connection's `norm.people` rows. */
    private class Scenario(val connectionId: UInt, val atlassianTeamId: String) {
        val known = mutableListOf<PersonRef>()
    }

    /** A new assignee; [known] false leaves it out of `norm.people`. `replacePeople` rebuilds the rows wholesale, so all are re-sent. */
    private suspend fun person(s: Scenario, name: String, known: Boolean = true, active: Boolean = true): Person {
        val p = Person("seed-acct-${UUID.randomUUID()}", name)
        if (known) {
            s.known += PersonRef(p.accountId, name, null, active = active)
            SyncedStubFixture.workItems().replacePeople(s.connectionId, s.known)
        }
        return p
    }

    private suspend fun issue(
        connectionId: UInt,
        issueId: Long,
        assignee: String?,
        atlassianTeamId: String?,
        teamName: String = "Seed Team",
        createdAtMs: Long,
        updatedAtMs: Long = createdAtMs,
        tombstone: TombstoneKind = TombstoneKind.NONE,
        hierarchyLevel: Int = 0,
    ) {
        val facts = WorkItemFacts(
            issueKey = "SEED-$issueId", projectKey = "FLO", issueType = "Story", isSubtask = false, parentIssueId = null,
            summary = "fixture", currentStatusId = "3", resolution = null, priority = null, assigneeAccountId = assignee,
            reporterAccountId = null, createdAtMs = createdAtMs, updatedAtMs = updatedAtMs, resolvedAtMs = null, storyPoints = null,
            originalEstimateSeconds = null, timeSpentSeconds = 0, labels = emptyList(), components = emptyList(), fixVersions = emptyList(),
            teamValueJson = atlassianTeamId?.let { """{"id":"$it","name":"$teamName"}""" },
            rank = null, hierarchyLevel = hierarchyLevel, dueAtMs = null, customFieldsJson = "{}", tombstone = tombstone,
        )
        val normalized = NormalizedIssue(
            issueId = issueId, facts = facts, currentStatusName = "In Progress", currentStatusCategory = StatusCategory.IN_PROGRESS,
            statusIntervals = listOf(
                NormalizedStatusInterval(1, "3", "In Progress", StatusCategory.IN_PROGRESS, createdAtMs, null, IntervalSource.CREATED),
            ),
            fieldIntervals = emptyList(), fieldChanges = emptyList(), worklogs = emptyList(), currentSprintIds = emptyList(),
            flagged = false, anomalies = emptyList(),
        )
        SyncedStubFixture.workItems().replaceWorkItem(connectionId, normalized, now = updatedAtMs)
    }

    private suspend fun scenario(block: suspend (Scenario) -> Unit) {
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "seed-fixture", enabled = false)
        try {
            block(Scenario(connectionId, unique("atl-team")))
        } finally {
            SyncedStubFixture.dataSources().delete(connectionId)
        }
    }

    private suspend fun withTeams(vararg names: String, block: suspend (List<UInt>) -> Unit) {
        val ids = names.map { TestTeams.seed(unique(it)) }
        try {
            block(ids)
        } finally {
            ids.forEach { TestTeams.service.delete(it) }
        }
    }

    private fun candidatesUrl(teamId: UInt, atlassianTeamId: String) =
        "/api/v1/teams/$teamId/jira-memberships/seed-candidates?atlassianTeamId=$atlassianTeamId"

    private suspend fun HttpClient.seed(teamId: UInt, atlassianTeamId: String, vararg accountIds: String) =
        postJson("/api/v1/teams/$teamId/jira-memberships/seed", TeamMembershipSeedRequest(atlassianTeamId, accountIds.toList()))

    private suspend fun HttpClient.revision() = get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision
    private suspend fun HttpClient.zone() = WorkingCalendar.zoneOf(get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().timeZone)
    private suspend fun HttpClient.roster(teamId: UInt) =
        get("/api/v1/teams/$teamId/jira-memberships").body<TeamMembershipListResponse>().items

    private fun dayStart(instantMs: Long, zone: java.time.ZoneId) =
        Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/jira-teams").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(candidatesUrl(1u, "x")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.seed(1u, "x", "a").status)
    }

    @Test
    fun `a non-admin gets 403 on all three, before the id lookup, the query and the body decode`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("seeduser")
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/jira-teams").status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/teams/999999/jira-memberships/seed-candidates").status)
        assertEquals(HttpStatusCode.Forbidden, user.get(candidatesUrl(1u, "x")).status)
        assertEquals(HttpStatusCode.Forbidden, user.post("/api/v1/teams/999999/jira-memberships/seed") { setBody("{ not json") }.status)
        assertEquals(HttpStatusCode.Forbidden, user.seed(999999u, "x", "a").status)
    }

    @Test
    fun `jira-teams counts live issues per Atlassian team and names it after the most recently updated issue`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedteams", FlowUserRole.ADMIN)
        scenario { s ->
            val a = person(s, "Anna")
            issue(s.connectionId, 1, a.accountId, s.atlassianTeamId, teamName = "Old Name", createdAtMs = 1_000L, updatedAtMs = 2_000L)
            issue(s.connectionId, 2, a.accountId, s.atlassianTeamId, teamName = "New Name", createdAtMs = 1_500L, updatedAtMs = 9_000L)
            issue(s.connectionId, 3, a.accountId, s.atlassianTeamId, createdAtMs = 1_600L, tombstone = TombstoneKind.DELETED)
            issue(s.connectionId, 4, a.accountId, s.atlassianTeamId, createdAtMs = 1_700L, tombstone = TombstoneKind.MOVED_OUT)
            issue(s.connectionId, 5, a.accountId, null, createdAtMs = 1_800L) // no team at all

            val mine = admin.get("/api/v1/jira-teams").body<JiraTeamListResponse>().items.single { it.id == s.atlassianTeamId }
            assertEquals("New Name", mine.name)
            assertEquals(2L, mine.issueCount, "tombstoned and moved-out issues must not count")
        }
    }

    @Test
    fun `jira-teams lists the four Atlassian teams of the stub and their assignees are candidates`() = testApplication {
        usePostgresTestcontainer()
        SyncedStubFixture.connectionId() // the shared, READ-only synced stub (it carries the four Atlassian Teams on ~30% of issues)
        val admin = seededClient("seedstub", FlowUserRole.ADMIN)
        val teams = admin.get("/api/v1/jira-teams").body<JiraTeamListResponse>().items.associateBy { it.id }
        val payments = assertNotNull(teams["8e6f8c9a-1a2b-4c3d-9e0f-111111111111"])
        assertEquals("Payments", payments.name)
        listOf("Payments", "Platform Core", "Growth", "Reliability").forEach { name ->
            assertTrue(teams.values.any { it.name == name && it.issueCount > 0 }, "stub team $name")
        }
        withTeams("stubseed") { (teamId) ->
            val candidates = admin.get(candidatesUrl(teamId, payments.id)).body<SeedCandidateListResponse>().items
            assertTrue(candidates.isNotEmpty())
            val zone = admin.zone()
            candidates.forEach {
                assertTrue(it.firstSeen <= it.lastSeen && it.issueCount >= 1)
                assertEquals(dayStart(it.firstSeen, zone), it.validFrom)
            }
            assertEquals(candidates.map { it.displayName.lowercase() }, candidates.map { it.displayName.lowercase() }.sorted())
        }
    }

    @Test
    fun `candidates carry counts, first and last seen and the zone-day validFrom, and skip dead, foreign and unknown assignees`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("seedcand", FlowUserRole.ADMIN)
            val zone = admin.zone()
            scenario { s ->
                val anna = person(s, "Anna")
                val bob = person(s, "Bob")
                val carl = person(s, "Carl")
                val ghost = person(s, "Ghost", known = false) // assignee without a norm.people row
                val otherTeam = unique("other-atl")
                issue(s.connectionId, 1, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late)
                issue(s.connectionId, 2, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late + 5 * day)
                // Anna's EARLIER issues that must not move her firstSeen: a deleted one and one of another team.
                issue(
                    s.connectionId, 3, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late - 30 * day,
                    tombstone = TombstoneKind.DELETED,
                )
                issue(s.connectionId, 4, anna.accountId, otherTeam, createdAtMs = march10Late - 40 * day)
                issue(s.connectionId, 5, bob.accountId, s.atlassianTeamId, createdAtMs = march10Late, tombstone = TombstoneKind.MOVED_OUT)
                issue(s.connectionId, 6, carl.accountId, otherTeam, createdAtMs = march10Late)
                issue(s.connectionId, 7, null, s.atlassianTeamId, createdAtMs = march10Late) // unassigned
                issue(s.connectionId, 8, ghost.accountId, s.atlassianTeamId, createdAtMs = march10Late)

                withTeams("cand") { (teamId) ->
                    val items = admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items
                    val only = items.single()
                    assertEquals(anna.accountId, only.accountId)
                    assertEquals("Anna", only.displayName)
                    assertEquals(2L, only.issueCount)
                    assertEquals(march10Late, only.firstSeen)
                    assertEquals(march10Late + 5 * day, only.lastSeen)
                    assertEquals(dayStart(march10Late, zone), only.validFrom)
                    assertNull(only.conflict)
                }
            }
        }

    @Test
    fun `candidates flag a conflict with any team, this one included, but not one that ended before validFrom`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedconflict", FlowUserRole.ADMIN)
        val zone = admin.zone()
        scenario { s ->
            val open = person(s, "Aaa Open Elsewhere")
            val sameTeam = person(s, "Bbb Same Team")
            val ended = person(s, "Ccc Ended Before")
            val later = person(s, "Ddd Starts Later")
            listOf(open, sameTeam, ended, later).forEachIndexed { i, p ->
                issue(s.connectionId, i + 1L, p.accountId, s.atlassianTeamId, createdAtMs = march10Late)
            }
            val validFrom = dayStart(march10Late, zone)
            withTeams("conflict-this", "conflict-other") { (teamId, otherId) ->
                val otherName = TestTeams.rawRows().single { it.id == otherId }.name
                admin.postJson(
                    "/api/v1/teams/$otherId/jira-memberships",
                    TeamMembershipCreateRequest(open.accountId, validFrom - 100 * day),
                )
                admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(sameTeam.accountId, validFrom - 1))
                admin.postJson(
                    "/api/v1/teams/$otherId/jira-memberships",
                    // half-open: ends AT validFrom
                    TeamMembershipCreateRequest(ended.accountId, validFrom - 100 * day, validTo = validFrom),
                )
                admin.postJson(
                    "/api/v1/teams/$otherId/jira-memberships",
                    TeamMembershipCreateRequest(later.accountId, validFrom + 10 * day, validTo = validFrom + 20 * day),
                )

                val byName = admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items
                    .associateBy { it.displayName }
                assertEquals(otherId, byName.getValue("Aaa Open Elsewhere").conflict?.teamId)
                assertEquals(otherName, byName.getValue("Aaa Open Elsewhere").conflict?.teamName)
                assertEquals(teamId, byName.getValue("Bbb Same Team").conflict?.teamId, "the same team conflicts too")
                assertNull(byName.getValue("Ccc Ended Before").conflict)
                // A CLOSED window reaching past the first issue is stepped over, not a conflict: the start moves to its end.
                assertNull(byName.getValue("Ddd Starts Later").conflict)
                assertEquals(validFrom + 20 * day, byName.getValue("Ddd Starts Later").validFrom)
            }
        }
    }

    @Test
    fun `candidates validate - 400 for a missing or blank team id, 404 for an unknown or deleted team`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedcand400", FlowUserRole.ADMIN)
        withTeams("cand400") { (teamId) ->
            assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/teams/$teamId/jira-memberships/seed-candidates").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get(candidatesUrl(teamId, "%20%20")).status)
            assertEquals(HttpStatusCode.NotFound, admin.get(candidatesUrl(999999u, "x")).status)
            val gone = TestTeams.seed(unique("cand-gone"))
            TestTeams.service.delete(gone)
            assertEquals(HttpStatusCode.NotFound, admin.get(candidatesUrl(gone, "x")).status)
            assertEquals(HttpStatusCode.OK, admin.get(candidatesUrl(teamId, "no-such-atlassian-team")).status)
        }
    }

    @Test
    fun `seed creates open-ended memberships, skips conflicts and non-candidates, bumps once and audits counts`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedapply", FlowUserRole.ADMIN)
        val zone = admin.zone()
        scenario { s ->
            val anna = person(s, "Anna")
            val bob = person(s, "Bob")
            val blocked = person(s, "Blocked")
            val elsewhere = person(s, "Elsewhere") // a candidate of ANOTHER Atlassian team only
            issue(s.connectionId, 1, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late)
            issue(s.connectionId, 2, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late - 3 * day)
            issue(s.connectionId, 3, bob.accountId, s.atlassianTeamId, createdAtMs = march10Late + day)
            issue(s.connectionId, 4, blocked.accountId, s.atlassianTeamId, createdAtMs = march10Late)
            issue(s.connectionId, 5, elsewhere.accountId, unique("other-atl"), createdAtMs = march10Late)
            withTeams("apply", "apply-other") { (teamId, otherId) ->
                admin.postJson("/api/v1/teams/$otherId/jira-memberships", TeamMembershipCreateRequest(blocked.accountId, 0L))

                withAuditCapture { capture ->
                    val before = admin.revision()
                    val response = admin.seed(
                        teamId, s.atlassianTeamId, anna.accountId, bob.accountId, blocked.accountId, elsewhere.accountId, "nobody-at-all",
                    )
                    assertEquals(HttpStatusCode.OK, response.status)
                    val result = response.body<TeamMembershipSeedResponse>()
                    assertEquals(before + 1, admin.revision(), "exactly ONE revision bump for the whole batch")

                    assertEquals(listOf(anna.accountId, bob.accountId), result.created.map { it.accountId })
                    assertEquals(dayStart(march10Late - 3 * day, zone), result.created[0].validFrom, "earliest issue of the team wins")
                    assertEquals(dayStart(march10Late + day, zone), result.created[1].validFrom)
                    assertEquals(
                        mapOf(
                            blocked.accountId to SeedSkipReason.CONFLICT,
                            elsewhere.accountId to SeedSkipReason.NOT_A_CANDIDATE,
                            "nobody-at-all" to SeedSkipReason.NOT_A_CANDIDATE,
                        ),
                        result.skipped.associate { it.accountId to it.reason },
                    )

                    val roster = admin.roster(teamId).associateBy { it.accountId }
                    assertEquals(setOf(anna.accountId, bob.accountId), roster.keys)
                    assertEquals(result.created[0].validFrom, roster.getValue(anna.accountId).validFrom)
                    assertNull(roster.getValue(anna.accountId).validTo, "seeded memberships are open-ended")

                    val event = assertNotNull(
                        capture.awaitEvent { it.message == "team.jira_memberships_seeded" && it.hasKeyValue("teamId", teamId.toLong()) },
                    )
                    assertTrue(event.hasKeyValue("created", 2) && event.hasKeyValue("skipped", 3) && event.hasKeyValue("requested", 5))
                    assertTrue(event.keyValuePairs.none { it.value.toString().contains("Anna") }, "an audit line never carries names")

                    // A second apply finds Anna and Bob already on THIS team: skipped, nothing created, no bump, no new audit line.
                    val auditCount = capture.events.count { it.message == "team.jira_memberships_seeded" }
                    val revision = admin.revision()
                    val again = admin.seed(teamId, s.atlassianTeamId, anna.accountId).body<TeamMembershipSeedResponse>()
                    assertTrue(again.created.isEmpty())
                    assertEquals(SeedSkipReason.CONFLICT, again.skipped.single().reason)
                    assertEquals(revision, admin.revision(), "a seed that creates nothing must not bump")
                    assertEquals(auditCount, capture.events.count { it.message == "team.jira_memberships_seeded" })
                }
            }
        }
    }

    @Test
    fun `seed validates - 400 for an empty list, more than 100, duplicates, a blank team id, 404 for an unknown or deleted team`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("seed400", FlowUserRole.ADMIN)
            withTeams("seed400") { (teamId) ->
                val before = admin.revision()
                assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "x").status, "empty accountIds")
                val many = Array(101) { "acct-$it" }
                assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "x", *many).status, ">100 accountIds")
                assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "x", "a", "a").status, "duplicate accountIds")
                assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "x", " ").status, "blank accountId")
                assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "  ", "a").status, "blank atlassianTeamId")
                assertEquals(HttpStatusCode.NotFound, admin.seed(999999u, "x", "a").status)
                val gone = TestTeams.seed(unique("seed-gone"))
                TestTeams.service.delete(gone)
                assertEquals(HttpStatusCode.NotFound, admin.seed(gone, "x", "a").status)
                assertEquals(before, admin.revision(), "a rejected seed changes nothing")
                // The cap itself is fine: 100 unknown accounts are 100 NOT_A_CANDIDATE skips, not an error.
                val hundred = admin.seed(teamId, "x", *Array(100) { "acct-$it" }).body<TeamMembershipSeedResponse>()
                assertEquals(100, hundred.skipped.size)
                assertEquals(before, admin.revision())
            }
        }

    @Test
    fun `a closed membership past the first issue moves the start forward, an open one stays a conflict, and the clamped seed is stored`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("seedclamp", FlowUserRole.ADMIN)
            val zone = admin.zone()
            scenario { s ->
                val mover = person(s, "Mover")
                val twice = person(s, "Twice Moved")
                val stayer = person(s, "Stayer")
                listOf(mover, twice, stayer).forEachIndexed { i, p ->
                    issue(s.connectionId, i + 1L, p.accountId, s.atlassianTeamId, createdAtMs = march10Late)
                }
                val firstDay = dayStart(march10Late, zone)
                withTeams("clamp", "clamp-old") { (teamId, oldId) ->
                    admin.postJson(
                        "/api/v1/teams/$oldId/jira-memberships",
                        TeamMembershipCreateRequest(mover.accountId, firstDay - 50 * day, validTo = firstDay + 5 * day),
                    )
                    admin.postJson(
                        "/api/v1/teams/$oldId/jira-memberships",
                        TeamMembershipCreateRequest(twice.accountId, firstDay - 50 * day, validTo = firstDay + 5 * day),
                    )
                    admin.postJson(
                        "/api/v1/teams/$oldId/jira-memberships",
                        TeamMembershipCreateRequest(twice.accountId, firstDay + 5 * day, validTo = firstDay + 9 * day),
                    )
                    admin.postJson(
                        "/api/v1/teams/$oldId/jira-memberships",
                        TeamMembershipCreateRequest(stayer.accountId, firstDay - 50 * day),
                    )

                    val byName = admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items
                        .associateBy { it.displayName }
                    assertNull(byName.getValue("Mover").conflict)
                    assertEquals(firstDay + 5 * day, byName.getValue("Mover").validFrom)
                    assertEquals(march10Late, byName.getValue("Mover").firstSeen, "firstSeen stays the issue's instant")
                    assertEquals(firstDay + 9 * day, byName.getValue("Twice Moved").validFrom, "the LATEST closed end wins")
                    assertNull(byName.getValue("Twice Moved").conflict)
                    assertEquals(oldId, byName.getValue("Stayer").conflict?.teamId)

                    val result = admin.seed(teamId, s.atlassianTeamId, mover.accountId, twice.accountId, stayer.accountId)
                        .body<TeamMembershipSeedResponse>()
                    assertEquals(
                        mapOf(mover.accountId to firstDay + 5 * day, twice.accountId to firstDay + 9 * day),
                        result.created.associate { it.accountId to it.validFrom },
                    )
                    assertEquals(listOf(SeedSkipReason.CONFLICT), result.skipped.map { it.reason })
                    assertEquals(
                        mapOf(mover.accountId to firstDay + 5 * day, twice.accountId to firstDay + 9 * day),
                        admin.roster(teamId).associate { it.accountId to it.validFrom },
                    )
                }
            }
        }

    @Test
    fun `epics and issues above them are no candidates and do not count`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedhier", FlowUserRole.ADMIN)
        scenario { s ->
            val dev = person(s, "Dev")
            val lead = person(s, "Epic Lead")
            val program = person(s, "Program Owner")
            val sub = person(s, "Sub Task Worker")
            issue(s.connectionId, 1, dev.accountId, s.atlassianTeamId, createdAtMs = march10Late, hierarchyLevel = 0)
            issue(s.connectionId, 2, dev.accountId, s.atlassianTeamId, createdAtMs = march10Late - 5 * day, hierarchyLevel = 1)
            issue(s.connectionId, 3, lead.accountId, s.atlassianTeamId, createdAtMs = march10Late, hierarchyLevel = 1)
            issue(s.connectionId, 4, program.accountId, s.atlassianTeamId, createdAtMs = march10Late, hierarchyLevel = 2)
            issue(s.connectionId, 5, sub.accountId, s.atlassianTeamId, createdAtMs = march10Late, hierarchyLevel = -1)
            withTeams("hier") { (teamId) ->
                val items = admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items
                assertEquals(listOf("Dev", "Sub Task Worker"), items.map { it.displayName })
                val dev1 = items.first()
                assertEquals(1L, dev1.issueCount, "the epic is not counted")
                assertEquals(march10Late, dev1.firstSeen, "nor does the earlier epic move firstSeen")
                val mine = admin.get("/api/v1/jira-teams").body<JiraTeamListResponse>().items.single { it.id == s.atlassianTeamId }
                assertEquals(2L, mine.issueCount, "the team's count is task-level too")
            }
        }
    }

    @Test
    fun `an inactive person is a candidate, can be seeded and is named on the roster`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedinactive", FlowUserRole.ADMIN)
        scenario { s ->
            val departed = person(s, "Departed Dev", active = false)
            issue(s.connectionId, 1, departed.accountId, s.atlassianTeamId, createdAtMs = march10Late)
            withTeams("inactive") { (teamId) ->
                val items = admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items
                assertEquals(listOf(departed.accountId), items.map { it.accountId })
                val result = admin.seed(teamId, s.atlassianTeamId, departed.accountId).body<TeamMembershipSeedResponse>()
                assertEquals(listOf(departed.accountId), result.created.map { it.accountId })
                // The directory picker (UNIT scope) does not list a departed person - the roster names them itself.
                assertEquals("Departed Dev", admin.roster(teamId).single().displayName)
            }
        }
    }

    @Test
    fun `issues of a soft-deleted connection are neither teams nor candidates`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seeddeadconn", FlowUserRole.ADMIN)
        scenario { s ->
            val anna = person(s, "Anna")
            issue(s.connectionId, 1, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late)
            withTeams("deadconn") { (teamId) ->
                assertEquals(1, admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items.size)
                SyncedStubFixture.dataSources().delete(s.connectionId)
                assertTrue(admin.get(candidatesUrl(teamId, s.atlassianTeamId)).body<SeedCandidateListResponse>().items.isEmpty())
                assertTrue(admin.get("/api/v1/jira-teams").body<JiraTeamListResponse>().items.none { it.id == s.atlassianTeamId })
                val result = admin.seed(teamId, s.atlassianTeamId, anna.accountId).body<TeamMembershipSeedResponse>()
                assertEquals(SeedSkipReason.NOT_A_CANDIDATE, result.skipped.single().reason)
            }
        }
    }

    @Test
    fun `control characters in the Atlassian team id are a 400 on the query and in the body`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedctrl", FlowUserRole.ADMIN)
        withTeams("ctrl") { (teamId) ->
            assertEquals(HttpStatusCode.BadRequest, admin.get(candidatesUrl(teamId, "a%01b")).status)
            assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "a\u0001b", "acct").status)
            assertEquals(HttpStatusCode.BadRequest, admin.seed(teamId, "x", "ac\u0001ct").status)
        }
    }

    @Test
    fun `a concurrent overlapping insert makes the seed a 409 and nothing is created`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("seedrace", FlowUserRole.ADMIN)
        scenario { s ->
            val anna = person(s, "Anna")
            issue(s.connectionId, 1, anna.accountId, s.atlassianTeamId, createdAtMs = march10Late)
            withTeams("race", "race-other") { (teamId, otherId) ->
                val before = admin.revision()
                // A raw transaction inserts an OVERLAPPING open row for the candidate and stays open: invisible to the seed's
                // read-committed recompute, so the seed proceeds to its insert, where the exclusion constraint makes it WAIT for this
                // transaction. Once it is waiting (pg_stat_activity), committing turns that wait into SQLSTATE 23P01 -> 409.
                rawJdbc().use { raw ->
                    raw.autoCommit = false
                    raw.prepareStatement(
                        "INSERT INTO metrics.team_membership (account_id, team_id, valid_from, valid_to, created_at, updated_at) " +
                            "VALUES (?, ?, 0, NULL, 0, 0)",
                    ).use { insert ->
                        insert.setString(1, anna.accountId)
                        insert.setInt(2, otherId.toInt())
                        insert.executeUpdate()
                    }
                    try {
                        coroutineScope {
                            val seeding = async { admin.seed(teamId, s.atlassianTeamId, anna.accountId) }
                            awaitLockWait("team_membership")
                            raw.commit()
                            assertEquals(HttpStatusCode.Conflict, seeding.await().status)
                        }
                    } finally {
                        raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
                    }
                }
                assertTrue(admin.roster(teamId).isEmpty(), "the rolled-back seed created nothing")
                assertEquals(before, admin.revision(), "and bumped nothing")
            }
        }
    }

    private fun rawJdbc() = PostgresTestSupport.connect()

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
