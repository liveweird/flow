package ch.nokillswit

import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.metrics.DataSourceMetricsConfig
import ch.nokillswit.metrics.DataSourceMetricsConfigOptions
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsBoardTeamMapping
import ch.nokillswit.metrics.MetricsDomainMapping
import ch.nokillswit.metrics.MetricsFieldConfig
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsStatusStage
import ch.nokillswit.norm.BoardRef
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GET/PUT /api/v1/data-sources/{id}/metrics-config` + `GET .../metrics-config/options` (v0.3.0
 * M1 commit 4, `.claude/docs/domain-model.md` "Configuration"): the computed-defaults GET, the
 * wholesale-replace PUT (id validation against `norm` reference rows, the no-op precedent, the
 * board→team `409`), and the options endpoint's reference data.
 */
class MetricsConfigRoutesTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())

    private suspend fun createConnection(dataSources: DataSourceService, projectKeys: List<String> = listOf("ENG")): UInt =
        dataSources.create(
            DataSourceRequest(
                name = unique("metrics-config-conn"),
                enabled = true,
                syncIntervalMinutes = 60,
                backfillFrom = "2025-01-01",
                reconcileHourUtc = 3,
                jira = JiraConnectionRequest(
                    siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                    email = "svc-${unique("acct")}@example.com",
                    apiToken = "token-${UUID.randomUUID()}",
                    projectKeys = projectKeys,
                    authScheme = JiraAuthScheme.BASIC,
                ),
            ),
        )

    /** Every `norm` reference row this test's PUT bodies validate against, and their ids for convenience. */
    private data class SeededFixture(
        val boardId: Long,
        val secondBoardId: Long,
        val sprintId: Long,
        val projectKey: String,
        val issueType: String,
    )

    /**
     * A cheap, direct-write substitute for a real Jira sync (`WorkItemStore`'s own service methods
     * — never a raw table poke): two statuses (a mapped TODO + an UNKNOWN-category one), two
     * boards, one sprint and one live work item — enough `norm` reference data for every id
     * [ch.nokillswit.metrics.validateDataSourceMetricsConfig] checks against.
     */
    private suspend fun seedNormFixture(connectionId: UInt, projectKey: String = "ENG", issueType: String = "Story"): SeededFixture {
        val items = workItems()
        val now = System.currentTimeMillis()
        items.replaceStatuses(
            connectionId,
            listOf(
                StatusRef("10001", "To Do", StatusCategory.TODO),
                StatusRef("10002", "In Progress", StatusCategory.IN_PROGRESS),
                StatusRef("10003", "Done", StatusCategory.DONE),
                StatusRef("10099", "Weird State", StatusCategory.UNKNOWN),
            ),
        )
        val boardId = 500L + (0..100_000L).random()
        val secondBoardId = boardId + 1
        items.replaceBoards(
            connectionId,
            listOf(
                BoardRef(boardId, "Board Alpha", "scrum", projectKey, emptyList()),
                BoardRef(secondBoardId, "Board Beta", "scrum", projectKey, emptyList()),
            ),
        )
        val sprintId = boardId + 1000
        items.replaceSprints(connectionId, listOf(SprintRef(sprintId, boardId, "Sprint 1", "active", now, now + 1, null, null)))

        writeWorkItem(connectionId, issueId = boardId, projectKey = projectKey, issueType = issueType)
        return SeededFixture(boardId, secondBoardId, sprintId, projectKey, issueType)
    }

    /**
     * A SECOND live work item under a different project key, ADDITIVE to whatever
     * [seedNormFixture] already wrote — `replaceWorkItem` is keyed by issue id (never wholesale),
     * unlike `replaceStatuses`/`replaceBoards`/`replaceSprints`, so this is safe to call afterwards
     * without clobbering the reference rows a prior [seedNormFixture] call already seeded. Gives a
     * SECOND distinct `domains[].projectKey` for the order-insensitivity test.
     */
    private suspend fun writeWorkItem(connectionId: UInt, issueId: Long, projectKey: String, issueType: String) {
        val now = System.currentTimeMillis()
        val facts = WorkItemFacts(
            issueKey = "$projectKey-1",
            projectKey = projectKey,
            issueType = issueType,
            isSubtask = false,
            parentIssueId = null,
            summary = "Seed issue",
            currentStatusId = "10001",
            resolution = null,
            priority = null,
            assigneeAccountId = null,
            reporterAccountId = null,
            createdAtMs = now,
            updatedAtMs = now,
            resolvedAtMs = null,
            storyPoints = null,
            originalEstimateSeconds = null,
            timeSpentSeconds = 0,
            labels = emptyList(),
            components = emptyList(),
            fixVersions = emptyList(),
            teamValueJson = null,
            rank = null,
            tombstone = TombstoneKind.NONE,
        )
        val normalized = NormalizedIssue(
            issueId = issueId,
            facts = facts,
            currentStatusName = "To Do",
            currentStatusCategory = StatusCategory.TODO,
            statusIntervals = listOf(NormalizedStatusInterval(1, "10001", "To Do", StatusCategory.TODO, now, null, IntervalSource.CREATED)),
            fieldIntervals = emptyList(),
            fieldChanges = emptyList(),
            worklogs = emptyList(),
            currentSprintIds = emptyList(),
            flagged = false,
            anomalies = emptyList(),
        )
        workItems().replaceWorkItem(connectionId, normalized, now)
    }

    private suspend fun HttpClient.getConfig(id: UInt) = get("/api/v1/data-sources/$id/metrics-config").body<DataSourceMetricsConfig>()
    private suspend fun HttpClient.configRevision() = get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision

    @Test
    fun `defaults on a synced-but-unconfigured connection - the seeded stage map equals the category map`() {
        // SyncedStubFixture is READ-ONLY across the whole suite (`.claude/docs/testing.md`) — this
        // test only ever GETs the connection's computed defaults, never PUTs a config onto it.
        val connId = runBlocking { SyncedStubFixture.connectionId() }
        val expectedStages = runBlocking {
            workItems().allStatusRefs(connId)
                .mapNotNull { status ->
                    when (status.category) {
                        StatusCategory.TODO -> status.statusId to MetricsStage.NOT_STARTED
                        StatusCategory.IN_PROGRESS -> status.statusId to MetricsStage.IN_PROGRESS
                        StatusCategory.DONE -> status.statusId to MetricsStage.DONE
                        StatusCategory.UNKNOWN -> null
                    }
                }
                .toMap()
        }

        testApplication {
            configureApp("app.role" to "web")
            startApplication()
            val admin = seededClient("metricsdefaults", UserRole.ADMIN)
            val config = admin.getConfig(connId)

            assertEquals(false, config.configured)
            assertEquals(expectedStages, config.statusStages.associate { it.statusId to it.stage })
            assertEquals(emptyList(), config.boards)
            assertEquals(emptyList(), config.workCategories)
            assertEquals(emptyList(), config.blockedStatuses)
            assertEquals(null, config.fields.workCategory)
        }
    }

    @Test
    fun `wholesale replace round-trips through GET, and an identical re-PUT is a no-op`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsreplace", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }

        val request = DataSourceMetricsConfigRequest(
            statusStages = listOf(MetricsStatusStage("10001", MetricsStage.NOT_STARTED), MetricsStatusStage("10003", MetricsStage.DONE)),
            fields = MetricsFieldConfig(epicDue = "duedate"),
            domains = listOf(MetricsDomainMapping(seeded.projectKey, "domain-key", "Domain Name")),
            boards = emptyList(),
        )

        val beforeRevision = admin.configRevision()
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", request).status)
        assertEquals(beforeRevision + 1, admin.configRevision(), "a real change must bump the shared revision")

        val fetched = admin.getConfig(connId)
        assertEquals(true, fetched.configured)
        // Row order is not guaranteed by a plain `SELECT` with no `ORDER BY` — compare as sets.
        assertEquals(request.statusStages.toSet(), fetched.statusStages.toSet())
        assertEquals(request.fields, fetched.fields)
        assertEquals(request.domains.toSet(), fetched.domains.toSet())

        val beforeNoOp = admin.configRevision()
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", request).status)
        assertEquals(beforeNoOp, admin.configRevision(), "an identical re-PUT must not bump the revision")
    }

    @Test
    fun `a no-op re-PUT is order-insensitive - every list reversed still bumps nothing and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsreorder", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
        val secondProjectKey = "GTM"
        runBlocking { writeWorkItem(connId, issueId = seeded.boardId + 777, projectKey = secondProjectKey, issueType = "Bug") }

        val forward = DataSourceMetricsConfigRequest(
            statusStages = listOf(MetricsStatusStage("10001", MetricsStage.NOT_STARTED), MetricsStatusStage("10003", MetricsStage.DONE)),
            domains = listOf(
                MetricsDomainMapping(seeded.projectKey, "domain-a", "Domain A"),
                MetricsDomainMapping(secondProjectKey, "domain-b", "Domain B"),
            ),
            blockedStatuses = listOf("10001", "10002"),
        )
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", forward).status)

        val reversed = forward.copy(
            statusStages = forward.statusStages.reversed(),
            domains = forward.domains.reversed(),
            blockedStatuses = forward.blockedStatuses.reversed(),
        )

        withAuditCapture { capture ->
            val beforeRevision = admin.configRevision()
            val beforeCount = capture.events.count { it.message == "metrics_config.updated" }
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", reversed).status)
            assertEquals(beforeRevision, admin.configRevision(), "a reordered-but-equal re-PUT must not bump the revision")
            assertEquals(
                beforeCount,
                capture.events.count { it.message == "metrics_config.updated" },
                "a reordered-but-equal re-PUT must not audit",
            )
        }
    }

    @Test
    fun `a changing PUT audits metrics_config-updated with byUserId, dataSourceId and configRevision`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsaudit", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        runBlocking { seedNormFixture(connId) }

        withAuditCapture { capture ->
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson(
                    "/api/v1/data-sources/$connId/metrics-config",
                    DataSourceMetricsConfigRequest(blockedStatuses = listOf("10001")),
                ).status,
            )
            val event = capture.awaitEvent { it.message == "metrics_config.updated" && it.hasKeyValue("dataSourceId", connId.toLong()) }
            assertNotNull(event, "PUT must audit metrics_config.updated")
            assertTrue(event.keyValuePairs.any { it.key == "configRevision" })
        }
    }

    @Test
    fun `an unknown status, board, project or sprint id is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsunknown", UserRole.ADMIN)
        val connId = runBlocking {
            val ds = dataSources()
            val id = createConnection(ds)
            seedNormFixture(id)
            id
        }

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(statusStages = listOf(MetricsStatusStage("nonexistent-status", MetricsStage.DONE))),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(blockedStatuses = listOf("nonexistent-status")),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(domains = listOf(MetricsDomainMapping("NOPE", "d", "D"))),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(boards = listOf(MetricsBoardTeamMapping(999_999_999L, 1u))),
            ).status,
        )
    }

    @Test
    fun `a board mapped to a team already mapped elsewhere is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsboardteam", UserRole.ADMIN)
        val teamId = TestTeams.seed(unique("metrics-team"))
        val connId = runBlocking {
            val ds = dataSources()
            val id = createConnection(ds)
            id
        }
        val seeded = runBlocking { seedNormFixture(connId) }

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(boards = listOf(MetricsBoardTeamMapping(seeded.boardId, teamId))),
            ).status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(
                    boards = listOf(
                        MetricsBoardTeamMapping(seeded.boardId, teamId),
                        MetricsBoardTeamMapping(seeded.secondBoardId, teamId),
                    ),
                ),
            ).status,
            "the SAME connection's request maps two boards to one team — the uq_metrics_board_team_map_team_id clash",
        )
    }

    @Test
    fun `a domain's owner team round-trips through GET`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsownerroundtrip", UserRole.ADMIN)
        val ownerTeamId = TestTeams.seed(unique("owner-team"))
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId) }

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(
                    domains = listOf(MetricsDomainMapping(seeded.projectKey, "domain-key", "Domain Name", ownerTeamId)),
                ),
            ).status,
        )

        val fetched = admin.getConfig(connId)
        assertEquals(true, fetched.configured)
        assertEquals(ownerTeamId, fetched.domains.single().ownerTeamId)
    }

    @Test
    fun `an unknown or soft-deleted owner team is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsownerbadteam", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId) }

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(
                    domains = listOf(MetricsDomainMapping(seeded.projectKey, "domain-key", "Domain Name", 999_999_999u)),
                ),
            ).status,
            "an unknown team id",
        )

        val softDeletedTeamId = TestTeams.seed(unique("soft-deleted-owner"))
        runBlocking { TestTeams.service.delete(softDeletedTeamId) }
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(
                    domains = listOf(MetricsDomainMapping(seeded.projectKey, "domain-key", "Domain Name", softDeletedTeamId)),
                ),
            ).status,
            "a soft-deleted team id",
        )
    }

    @Test
    fun `a same-domain owner disagreement is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsownerdisagree", UserRole.ADMIN)
        val teamA = TestTeams.seed(unique("owner-team-a"))
        val teamB = TestTeams.seed(unique("owner-team-b"))
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
        val secondProjectKey = "GTM"
        runBlocking { writeWorkItem(connId, issueId = seeded.boardId + 777, projectKey = secondProjectKey, issueType = "Bug") }

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(
                    domains = listOf(
                        MetricsDomainMapping(seeded.projectKey, "shared-domain", "Shared Domain", teamA),
                        MetricsDomainMapping(secondProjectKey, "shared-domain", "Shared Domain", teamB),
                    ),
                ),
            ).status,
            "two projects in the same domain disagreeing on an owner",
        )
    }

    @Test
    fun `an unconfigured owner falls back to the board default the deriver would resolve`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsownerdefault", UserRole.ADMIN)
        val boardTeamId = TestTeams.seed(unique("board-owner-team"))
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId) }

        // Truly unconfigured: no config stored anywhere yet, so no board is mapped either — the
        // computed default's own board fallback has nothing to resolve, hence null.
        val defaults = admin.getConfig(connId)
        assertEquals(false, defaults.configured)
        assertEquals(listOf(null), defaults.domains.map { it.ownerTeamId })

        // Map ONLY the board to a team (never the owner itself) — the connection is now
        // `configured`, but this domain's OWN ownerTeamId stays unset in storage; GET must still
        // fill it with the SAME board-fallback default `MetricsDeriver.ownerTeamByDomain` would
        // compute for a DERIVE run (`MetricsConfigService.resolveOwnerTeamByDomain` — one
        // implementation).
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(
                "/api/v1/data-sources/$connId/metrics-config",
                DataSourceMetricsConfigRequest(
                    domains = listOf(MetricsDomainMapping(seeded.projectKey, "domain-key", "Domain Name")),
                    boards = listOf(MetricsBoardTeamMapping(seeded.boardId, boardTeamId)),
                ),
            ).status,
        )

        val fetched = admin.getConfig(connId)
        assertEquals(true, fetched.configured)
        assertEquals(boardTeamId, fetched.domains.single().ownerTeamId)
    }

    @Test
    fun `an identical re-PUT carrying an explicit owner team is still a no-op`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsownernoop", UserRole.ADMIN)
        val ownerTeamId = TestTeams.seed(unique("owner-team-noop"))
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId) }

        val request = DataSourceMetricsConfigRequest(
            domains = listOf(MetricsDomainMapping(seeded.projectKey, "domain-key", "Domain Name", ownerTeamId)),
        )
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", request).status)

        val beforeRevision = admin.configRevision()
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", request).status)
        assertEquals(beforeRevision, admin.configRevision(), "an identical re-PUT (owner team included) must not bump the revision")
    }

    @Test
    fun `non-admin gets 403 before an unknown connection's 404 or a malformed body`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("metricsuser403")

        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/data-sources/999999999/metrics-config").status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/data-sources/999999999/metrics-config/options").status)
        val malformed = user.put("/api/v1/data-sources/999999999/metrics-config") { setBody("{ not json") }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
    }

    @Test
    fun `an unknown connection is 404 for an admin`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("metricsmissing404", UserRole.ADMIN)

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999999/metrics-config").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999999/metrics-config/options").status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson("/api/v1/data-sources/999999999/metrics-config", DataSourceMetricsConfigRequest()).status,
        )
    }

    @Test
    fun `options lists this connection's own norm reference data`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsoptions", UserRole.ADMIN)
        val connId = runBlocking {
            val ds = dataSources()
            val id = createConnection(ds)
            id
        }
        val seeded = runBlocking { seedNormFixture(connId) }

        val options = admin.get("/api/v1/data-sources/$connId/metrics-config/options").body<DataSourceMetricsConfigOptions>()
        assertTrue(options.statuses.any { it.statusId == "10001" })
        assertTrue(options.boards.any { it.boardId == seeded.boardId })
        assertTrue(options.projects.contains(seeded.projectKey))
        assertTrue(options.issueTypes.contains(seeded.issueType))
        assertTrue(options.sprints.any { it.sprintId == seeded.sprintId })
        assertEquals(emptyList(), options.workCategoryValues, "no ?workCategoryField= given")
    }

    @Test
    fun `a repeated workCategoryField is a 400, never a silent first-value-wins`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsoptionsrepeat", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }

        val repeated = admin.get("/api/v1/data-sources/$connId/metrics-config/options?workCategoryField=a&workCategoryField=b")
        assertEquals(HttpStatusCode.BadRequest, repeated.status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/data-sources/$connId/metrics-config/options?workCategoryField=a").status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/data-sources/$connId/metrics-config/options?workCategoryField=").status)
    }
}
