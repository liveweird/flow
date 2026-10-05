package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.metrics.JiraUserPageResponse
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.WorklogFact
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/v1/jira-users` (v0.3.0 M1 commit 3, D12 + the scope amendment): `scope=UNIT`
 * (default, any authenticated) restricts to accounts already relevant to this unit's own data
 * (assignee, worklog author or ANY team membership row); `scope=SITE` (ADMIN only, guard before
 * any read) is the whole site directory. Distinct-by-account-id across connections, the `q`/
 * `teamId` filters (an expired membership excluded) and the `displayName` SQL sort/paging.
 */
class JiraUsersRoutesTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"
    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())

    private suspend fun seedConnection(): UInt = dataSources().create(
        DataSourceRequest(
            name = unique("jira-users-fixture"),
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

    /** A minimal live work item — [assigneeAccountId]/a worklog by [worklogAuthorAccountId] make an account UNIT-relevant. */
    private suspend fun seedWorkItem(
        connectionId: UInt,
        issueId: Long,
        assigneeAccountId: String? = null,
        worklogAuthorAccountId: String? = null,
    ) {
        val facts = WorkItemFacts(
            issueKey = "FLO-$issueId",
            projectKey = "FLO",
            issueType = "Story",
            isSubtask = false,
            parentIssueId = null,
            summary = "fixture",
            currentStatusId = "3",
            resolution = null,
            priority = null,
            assigneeAccountId = assigneeAccountId,
            reporterAccountId = null,
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
            resolvedAtMs = null,
            storyPoints = null,
            originalEstimateSeconds = null,
            timeSpentSeconds = 0,
            labels = emptyList(),
            components = emptyList(),
            fixVersions = emptyList(),
            teamValueJson = null,
            rank = null,
            hierarchyLevel = 0,
            dueAtMs = null,
            customFieldsJson = "{}",
            tombstone = TombstoneKind.NONE,
        )
        val statusInterval = NormalizedStatusInterval(
            seq = 1, statusId = "3", statusName = "In Progress", category = StatusCategory.IN_PROGRESS,
            fromAtMs = 1_000L, toAtMs = null, source = IntervalSource.CREATED,
        )
        val fieldIntervals = if (assigneeAccountId != null) {
            listOf(NormalizedFieldInterval(TrackedField.ASSIGNEE, seq = 1, assigneeAccountId, "Assignee", 1_000L, null))
        } else {
            emptyList()
        }
        val worklogs = if (worklogAuthorAccountId != null) {
            listOf(
                WorklogFact(
                    worklogId = issueId * 100,
                    authorAccountId = worklogAuthorAccountId,
                    startedAtMs = 1_000L,
                    timeSpentSeconds = 3600,
                ),
            )
        } else {
            emptyList()
        }
        val normalized = NormalizedIssue(
            issueId = issueId,
            facts = facts,
            currentStatusName = "In Progress",
            currentStatusCategory = StatusCategory.IN_PROGRESS,
            statusIntervals = listOf(statusInterval),
            fieldIntervals = fieldIntervals,
            fieldChanges = emptyList(),
            worklogs = worklogs,
            currentSprintIds = emptyList(),
            flagged = false,
            anomalies = emptyList(),
        )
        workItems().replaceWorkItem(connectionId, normalized, now = 5_000L)
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/jira-users").status)
    }

    @Test
    fun `scope=SITE is ADMIN only, guard before any read`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("jirausersscope")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/jira-users?scope=SITE").status)
        val admin = seededClient("jirausersscopeadmin", UserRole.ADMIN)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/jira-users?scope=SITE").status)
    }

    @Test
    fun `a non-admin gets 403 before any 400 - a bogus or repeated scope, or a bad page, never reaches validation`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("jirausers403first")
        // The guard reads the RAW scope: anything that is not plainly UNIT is ADMIN-gated first.
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/jira-users?scope=bogus").status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/jira-users?scope=site").status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/jira-users?scope=UNIT&scope=SITE").status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/jira-users?scope=SITE&pageSize=0").status)
        // The default scope stays any-authenticated, and its own 400s are unchanged.
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/jira-users?scope=UNIT").status)
        assertEquals(HttpStatusCode.BadRequest, user.get("/api/v1/jira-users?pageSize=0").status)
        // An ADMIN still sees the enum's own 400 for a bogus value.
        val admin = seededClient("jirausers403firstadmin", UserRole.ADMIN)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/jira-users?scope=bogus").status)
    }

    @Test
    fun `default UNIT scope excludes a merely-known account, SITE scope includes it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirausersunit", UserRole.ADMIN)
        val marker = unique("Uu")
        val connectionId = seedConnection()
        val irrelevantAccountId = "irrelevant-${UUID.randomUUID()}"
        workItems().replacePeople(connectionId, listOf(PersonRef(irrelevantAccountId, "$marker Nobody Cares", null, active = true)))

        val unitPage = admin.get("/api/v1/jira-users?q=$marker").body<JiraUserPageResponse>()
        assertTrue(unitPage.items.isEmpty(), "known to norm.people alone is not unit-relevant under the default scope")

        val sitePage = admin.get("/api/v1/jira-users?q=$marker&scope=SITE").body<JiraUserPageResponse>()
        assertEquals(listOf(irrelevantAccountId), sitePage.items.map { it.accountId })
    }

    @Test
    fun `UNIT scope includes an assignee, a worklog author, and a team member`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirausersrelevant", UserRole.ADMIN)
        val marker = unique("Rr")
        val connectionId = seedConnection()
        val assigneeId = "assignee-${UUID.randomUUID()}"
        val worklogAuthorId = "worklog-${UUID.randomUUID()}"
        val memberOnlyId = "member-${UUID.randomUUID()}"
        workItems().replacePeople(
            connectionId,
            listOf(
                PersonRef(assigneeId, "$marker Assignee", null, active = true),
                PersonRef(worklogAuthorId, "$marker Worklog Author", null, active = true),
                PersonRef(memberOnlyId, "$marker Member Only", null, active = true),
            ),
        )
        seedWorkItem(connectionId, issueId = 1001L, assigneeAccountId = assigneeId)
        seedWorkItem(connectionId, issueId = 1002L, worklogAuthorAccountId = worklogAuthorId)
        val teamId = TestTeams.seed(unique("relevant-team"))
        admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(memberOnlyId, 0L))

        val page = admin.get("/api/v1/jira-users?q=$marker&pageSize=100").body<JiraUserPageResponse>()
        assertEquals(setOf(assigneeId, worklogAuthorId, memberOnlyId), page.items.map { it.accountId }.toSet())
    }

    @Test
    fun `q filters by display name and results are distinct across connections`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirausers", UserRole.ADMIN)
        val marker = unique("Zz")
        val connectionA = seedConnection()
        val connectionB = seedConnection()
        val sharedAccountId = "shared-${UUID.randomUUID()}"
        workItems().replacePeople(connectionA, listOf(PersonRef(sharedAccountId, "$marker Shared Person", null, active = true)))
        workItems().replacePeople(connectionB, listOf(PersonRef(sharedAccountId, "$marker Shared Person", null, active = true)))
        workItems().replacePeople(
            connectionA,
            listOf(
                PersonRef(sharedAccountId, "$marker Shared Person", null, active = true),
                PersonRef("solo-${UUID.randomUUID()}", "$marker Solo Person", null, active = true),
            ),
        )

        val page = admin.get("/api/v1/jira-users?q=$marker&pageSize=100&scope=SITE").body<JiraUserPageResponse>()
        assertEquals(2, page.total, "the shared account id must appear ONCE even though two connections know it")
        assertEquals(listOf("$marker Shared Person", "$marker Solo Person"), page.items.map { it.displayName }, "displayName ascending")
    }

    /** A GET with `q` (and any extra params) appended through the URL builder, so diacritics and control characters are encoded for us. */
    private suspend fun HttpClient.search(q: String?, vararg extra: Pair<String, String>): HttpResponse = get("/api/v1/jira-users") {
        url {
            parameters.append("scope", "SITE")
            if (q != null) parameters.append("q", q)
            extra.forEach { (k, v) -> parameters.append(k, v) }
        }
    }

    @Test
    fun `a person beyond the first page is found by q, and paging and total agree with an independent read`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirauserspaging", UserRole.ADMIN)
        val marker = unique("Pg")
        val connectionId = seedConnection()
        val people = (0 until 120).map {
            PersonRef("pg-$marker-$it", "$marker Person ${it.toString().padStart(3, '0')}", null, active = true)
        }
        workItems().replacePeople(connectionId, people)
        // The independent expectation: the names sorted by the test itself, never by the server's SQL.
        val expected = people.map { it.displayName }.sorted()
        val target = expected.last()

        val first = admin.search(marker, "pageSize" to "100").body<JiraUserPageResponse>()
        assertEquals(120, first.total)
        assertEquals(expected.take(100), first.items.map { it.displayName })
        assertFalse(first.items.any { it.displayName == target }, "the person is beyond the first page of 100")

        val second = admin.search(marker, "pageSize" to "100", "page" to "2").body<JiraUserPageResponse>()
        assertEquals(120, second.total)
        assertEquals(expected.drop(100), second.items.map { it.displayName })

        val found = admin.search(target).body<JiraUserPageResponse>()
        assertEquals(1, found.total)
        assertEquals(listOf(target), found.items.map { it.displayName })

        val descending = admin.search(marker, "pageSize" to "10", "page" to "3", "sort" to "-displayName").body<JiraUserPageResponse>()
        assertEquals(120, descending.total)
        assertEquals(expected.reversed().drop(20).take(10), descending.items.map { it.displayName })
    }

    @Test
    fun `q folds case and accents and matches the account id as well as the display name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirausersfold", UserRole.ADMIN)
        val marker = unique("Fd")
        val connectionId = seedConnection()
        val accentedId = "acc-${UUID.randomUUID()}"
        val plainId = "ACC-${UUID.randomUUID()}"
        workItems().replacePeople(
            connectionId,
            listOf(
                PersonRef(accentedId, "Żółć Ünï $marker", null, active = true),
                PersonRef(plainId, "Plain Person $marker", null, active = true),
            ),
        )

        for (q in listOf("zolc uni $marker", "ZOLC UNI $marker", "ŻÓŁĆ ÜNÏ $marker")) {
            val page = admin.search(q).body<JiraUserPageResponse>()
            assertEquals(listOf(accentedId), page.items.map { it.accountId }, "q=$q")
        }
        // A pasted account id (any case) finds exactly its own person, though the name has nothing to do with it.
        val uuidPart = plainId.removePrefix("ACC-")
        assertEquals(listOf(plainId), admin.search(uuidPart).body<JiraUserPageResponse>().items.map { it.accountId })
        assertEquals(listOf(plainId), admin.search(plainId.lowercase()).body<JiraUserPageResponse>().items.map { it.accountId })
        // The LIKE metacharacters in q are literals, not wildcards.
        assertEquals(0, admin.search("%$marker%").body<JiraUserPageResponse>().total)
    }

    @Test
    fun `a bad sort, bad paging, a control character in q or a repeated q is a 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirausersbad", UserRole.ADMIN)
        assertEquals(HttpStatusCode.BadRequest, admin.search(null, "sort" to "accountId").status)
        assertEquals(HttpStatusCode.BadRequest, admin.search(null, "pageSize" to "101").status)
        assertEquals(HttpStatusCode.BadRequest, admin.search(null, "page" to "0").status)
        assertEquals(HttpStatusCode.BadRequest, admin.search("bad\u0007q").status)
        assertEquals(HttpStatusCode.BadRequest, admin.search("one", "q" to "two").status)
        // A blank q is "no filter", not an error.
        assertEquals(HttpStatusCode.OK, admin.search("   ").status)
    }

    @Test
    fun `teamId narrows to that team's CURRENT membership - an expired row does not count`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("jirausersteam", UserRole.ADMIN)
        val marker = unique("Mm")
        val connectionId = seedConnection()
        val inTeamAccountId = "in-team-${UUID.randomUUID()}"
        val expiredAccountId = "expired-${UUID.randomUUID()}"
        val outOfTeamAccountId = "out-of-team-${UUID.randomUUID()}"
        workItems().replacePeople(
            connectionId,
            listOf(
                PersonRef(inTeamAccountId, "$marker In Team", null, active = true),
                PersonRef(expiredAccountId, "$marker Expired", null, active = true),
                PersonRef(outOfTeamAccountId, "$marker Out Of Team", null, active = true),
            ),
        )
        val teamId = TestTeams.seed(unique("jirausersteam"))
        admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(inTeamAccountId, 0L))
        // A membership that ENDED in the past must not count as "current".
        admin.postJson("/api/v1/teams/$teamId/jira-memberships", TeamMembershipCreateRequest(expiredAccountId, 0L, 1L))

        val page = admin.get("/api/v1/jira-users?q=$marker&teamId=$teamId&scope=SITE").body<JiraUserPageResponse>()
        assertEquals(listOf(inTeamAccountId), page.items.map { it.accountId })
    }

    @Test
    fun `an unknown teamId is simply empty, not an error`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("jirausersnoteam")
        val page = client.get("/api/v1/jira-users?teamId=999999").body<JiraUserPageResponse>()
        assertTrue(page.items.isEmpty())
    }
}
