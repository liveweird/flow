package ch.nokillswit

import ch.nokillswit.ingest.DataProfileSections
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
import ch.nokillswit.metrics.MetricsActivityTypeMapping
import ch.nokillswit.metrics.MetricsDomainMapping
import ch.nokillswit.metrics.MetricsDomainStatusStage
import ch.nokillswit.metrics.MetricsFieldConfig
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsStatusStage
import ch.nokillswit.metrics.MetricsWorkCategoryMapping
import ch.nokillswit.metrics.MetricsSprintCapacity
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
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `over-long Jira option ids and labels round-trip - the work-category map and activity types are TEXT`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricslongvalues", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
        // A primitive-valued work-category field: the option's value text IS its id (`fieldValueOptions`), so the id is as
        // long as the Jira label; the seeded work item carries one such value under `duedate` (always a known field id).
        val longValue = "V".repeat(150)
        runBlocking {
            suspendTransaction(sharedDatabaseForTests()) {
                exec(
                    "UPDATE norm.work_items SET custom_fields = jsonb_build_object('duedate', '$longValue') " +
                        "WHERE connection_id = $connId",
                )
            }
        }
        val longActivity = "A".repeat(120)
        val request = DataSourceMetricsConfigRequest(
            fields = MetricsFieldConfig(workCategory = "duedate"),
            activityTypes = listOf(MetricsActivityTypeMapping(seeded.issueType, longActivity)),
            workCategories = listOf(MetricsWorkCategoryMapping(longValue, longValue, "Feature")),
        )

        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/data-sources/$connId/metrics-config", request).status)

        val fetched = admin.getConfig(connId)
        assertEquals(request.workCategories, fetched.workCategories)
        assertEquals(request.activityTypes, fetched.activityTypes)
    }

    @Test
    fun `a user-entered activity type is trimmed and a control character in it is a 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsactivity", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
        fun request(activityType: String) =
            DataSourceMetricsConfigRequest(activityTypes = listOf(MetricsActivityTypeMapping(seeded.issueType, activityType)))

        suspend fun put(activityType: String) = admin.putJson("/api/v1/data-sources/$connId/metrics-config", request(activityType)).status

        assertEquals(HttpStatusCode.BadRequest, put("Deliv\nery"))
        assertEquals(HttpStatusCode.BadRequest, put("Deli\u0000very"))
        assertEquals(HttpStatusCode.NoContent, put("  Delivery  "))
        assertEquals(listOf("Delivery"), admin.getConfig(connId).activityTypes.map { it.activityType })
    }

    @Test
    fun `over-long domain and category strings and an overflowing capacityMd are 400 with a problem body, the limits are accepted`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("metricsbounds", UserRole.ADMIN)
            val connId = runBlocking { createConnection(dataSources()) }
            val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
            val url = "/api/v1/data-sources/$connId/metrics-config"

            fun domains(key: String, name: String) =
                DataSourceMetricsConfigRequest(domains = listOf(MetricsDomainMapping(seeded.projectKey, key, name)))
            fun categories(category: String) = DataSourceMetricsConfigRequest(
                fields = MetricsFieldConfig(workCategory = "duedate"),
                workCategories = listOf(MetricsWorkCategoryMapping("opt-1", null, category)),
            )
            fun capacity(md: Double) = DataSourceMetricsConfigRequest(sprintCapacities = listOf(MetricsSprintCapacity(seeded.sprintId, md)))

            suspend fun assertRejected(request: DataSourceMetricsConfigRequest, field: String) {
                val response = admin.putJson(url, request)
                assertEquals(HttpStatusCode.BadRequest, response.status, field)
                assertTrue(response.body<ProblemDetail>().detail!!.contains(field))
            }
            assertRejected(domains("k".repeat(51), "n"), "domainKey")
            assertRejected(domains("   ", "n"), "domainKey")
            assertRejected(domains("k", "n".repeat(101)), "domainName")
            assertRejected(categories("c".repeat(101)), "category")
            assertRejected(capacity(1e7), "capacityMd")
            assertRejected(capacity(1.005), "capacityMd")

            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, domains("k".repeat(50), "n".repeat(100))).status)
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, capacity(999_999.99)).status)
            assertEquals(999_999.99, admin.getConfig(connId).sprintCapacities.single().capacityMd)
            // `workCategories` needs a value id the field actually carries (the 150-char test above shows the TEXT ids).
            runBlocking {
                suspendTransaction(sharedDatabaseForTests()) {
                    exec("UPDATE norm.work_items SET custom_fields = jsonb_build_object('duedate', 'opt-1') WHERE connection_id = $connId")
                }
            }
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, categories("c".repeat(100))).status)
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
    fun `per-domain stage overrides round-trip through GET, an identical re-PUT is a no-op and changing one bumps and audits`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("metricsoverride", UserRole.ADMIN)
            val connId = runBlocking { createConnection(dataSources()) }
            val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
            val url = "/api/v1/data-sources/$connId/metrics-config"

            val request = DataSourceMetricsConfigRequest(
                statusStages = listOf(
                    MetricsStatusStage("10001", MetricsStage.NOT_STARTED),
                    MetricsStatusStage("10003", MetricsStage.DONE),
                ),
                domains = listOf(MetricsDomainMapping(seeded.projectKey, "platform", "Platform")),
                domainStatusStages = listOf(
                    MetricsDomainStatusStage("platform", "10003", MetricsStage.IN_PROGRESS),
                    MetricsDomainStatusStage("platform", "10001", MetricsStage.NOT_STARTED),
                ),
            )
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, request).status)

            val fetched = admin.getConfig(connId)
            assertEquals(true, fetched.configured)
            // The every-domain rows and the overrides share one table; neither list leaks into the other.
            assertEquals(request.statusStages.toSet(), fetched.statusStages.toSet())
            assertEquals(request.domainStatusStages.toSet(), fetched.domainStatusStages.toSet())
            assertEquals(
                listOf("10001", "10003"),
                fetched.domainStatusStages.map { it.statusId },
                "GET orders overrides by domain then status",
            )

            val beforeNoOp = admin.configRevision()
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson(url, request.copy(domainStatusStages = request.domainStatusStages.reversed())).status,
            )
            assertEquals(beforeNoOp, admin.configRevision(), "a reordered-but-equal re-PUT with overrides must not bump the revision")

            withAuditCapture { capture ->
                val changed = request.copy(domainStatusStages = listOf(MetricsDomainStatusStage("platform", "10003", MetricsStage.DONE)))
                assertEquals(HttpStatusCode.NoContent, admin.putJson(url, changed).status)
                assertEquals(beforeNoOp + 1, admin.configRevision(), "changing only the overrides is a real change")
                assertNotNull(
                    capture.awaitEvent { it.message == "metrics_config.updated" && it.hasKeyValue("dataSourceId", connId.toLong()) },
                    "an override change audits metrics_config.updated",
                )
            }
            assertEquals(
                listOf(MetricsDomainStatusStage("platform", "10003", MetricsStage.DONE)),
                admin.getConfig(connId).domainStatusStages,
                "REPLACE semantics: an override left out of the PUT is removed",
            )

            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, request.copy(domainStatusStages = emptyList())).status)
            assertEquals(emptyList(), admin.getConfig(connId).domainStatusStages)
            assertEquals(request.statusStages.toSet(), admin.getConfig(connId).statusStages.toSet())
        }

    @Test
    fun `an override on a domain that exists only as an unmapped project key is accepted`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsoverrideproj", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
        val url = "/api/v1/data-sources/$connId/metrics-config"

        // `domains` is empty, so DERIVE reads the project key itself as the domain — a valid override target.
        val request = DataSourceMetricsConfigRequest(
            domainStatusStages = listOf(MetricsDomainStatusStage(seeded.projectKey, "10001", MetricsStage.IN_PROGRESS)),
        )
        assertEquals(HttpStatusCode.NoContent, admin.putJson(url, request).status)
        assertEquals(request.domainStatusStages, admin.getConfig(connId).domainStatusStages)

        // ... but once `domains` maps that project elsewhere, the project key is no longer a domain.
        val remapped = request.copy(domains = listOf(MetricsDomainMapping(seeded.projectKey, "elsewhere", "Elsewhere")))
        assertEquals(HttpStatusCode.BadRequest, admin.putJson(url, remapped).status)
    }

    @Test
    fun `an invalid per-domain override is 400 and stores nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsoverride400", UserRole.ADMIN)
        val connId = runBlocking { createConnection(dataSources()) }
        val seeded = runBlocking { seedNormFixture(connId, "PLT", "Task") }
        val url = "/api/v1/data-sources/$connId/metrics-config"
        val domains = listOf(MetricsDomainMapping(seeded.projectKey, "platform", "Platform"))
        val good = MetricsDomainStatusStage("platform", "10001", MetricsStage.DONE)
        val before = admin.configRevision()

        val bad = listOf(
            listOf(MetricsDomainStatusStage("nope", "10001", MetricsStage.DONE)), // unknown domain
            listOf(MetricsDomainStatusStage("", "10001", MetricsStage.DONE)), // '' is the every-domain row, not an override
            listOf(MetricsDomainStatusStage("platform", "no-such-status", MetricsStage.DONE)), // unknown status
            listOf(good, good.copy(stage = MetricsStage.IN_PROGRESS)), // duplicate (domain, status)
        )
        for (overrides in bad) {
            val response = admin.putJson(url, DataSourceMetricsConfigRequest(domains = domains, domainStatusStages = overrides))
            assertEquals(HttpStatusCode.BadRequest, response.status, "overrides $overrides must be rejected")
        }
        assertEquals(before, admin.configRevision(), "a rejected PUT bumps nothing")
        assertEquals(false, admin.getConfig(connId).configured, "a rejected PUT stores nothing")
    }

    @Test
    fun `a non-admin cannot PUT per-domain overrides`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("metricsoverride403")
        val response = user.putJson(
            "/api/v1/data-sources/999999999/metrics-config",
            DataSourceMetricsConfigRequest(domainStatusStages = listOf(MetricsDomainStatusStage("platform", "10001", MetricsStage.DONE))),
        )
        assertEquals(HttpStatusCode.Forbidden, response.status)
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
        // compute for a DERIVE run (`DomainOwnerResolver.resolveOwnerTeamByDomain` — one
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
        // No stored profile: nothing is in a workflow; only the one status the seeded work item's interval carries was seen.
        assertTrue(options.statuses.none { it.inWorkflow })
        assertEquals(setOf("10001"), options.statuses.filter { it.seenInHistory }.map { it.statusId }.toSet())
    }

    @Test
    fun `options flags a status in the stored profile's workflow, independently of history`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsoptionswf", UserRole.ADMIN)
        val ds = dataSources()
        val connId = runBlocking { createConnection(ds) }
        runBlocking {
            seedNormFixture(connId)
            ds.updateProfile(connId, Json.encodeToString(DataProfileSections(workflowStatusIds = listOf("10002", "10099"))))
        }

        val byId = admin.get("/api/v1/data-sources/$connId/metrics-config/options").body<DataSourceMetricsConfigOptions>()
            .statuses.associateBy { it.statusId }
        assertEquals(setOf("10002", "10099"), byId.filterValues { it.inWorkflow }.keys)
        assertEquals(setOf("10001"), byId.filterValues { it.seenInHistory }.keys)
    }

    @Test
    fun `options on the synced stub - the flags equal the raw PROJECT_STATUSES ids and a SQL distinct over the intervals`() {
        val connId = runBlocking { SyncedStubFixture.connectionId() }
        val expectedWorkflow = runBlocking {
            SyncedStubFixture.rawStore().entityPayloadsByKind(connId, "PROJECT_STATUSES")
                .flatMap { payload -> Json.parseToJsonElement(payload).jsonArray }
                .flatMap { issueType -> issueType.jsonObject.getValue("statuses").jsonArray }
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }
                .toSet()
        }
        val distinctSql = "SELECT DISTINCT status_id FROM norm.work_item_status_intervals WHERE connection_id = $connId"
        val expectedSeen = DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(distinctSql).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
                }
            }
        assertTrue(expectedWorkflow.isNotEmpty() && expectedSeen.isNotEmpty(), "the stub must yield both sets")

        testApplication {
            configureApp("app.role" to "web")
            startApplication()
            val admin = seededClient("metricsoptionsstub", UserRole.ADMIN)
            val statuses = admin.get("/api/v1/data-sources/$connId/metrics-config/options").body<DataSourceMetricsConfigOptions>().statuses
            val reported = statuses.map { it.statusId }.toSet()

            assertEquals(expectedWorkflow.intersect(reported), statuses.filter { it.inWorkflow }.map { it.statusId }.toSet())
            assertEquals(expectedSeen.intersect(reported), statuses.filter { it.seenInHistory }.map { it.statusId }.toSet())
            assertTrue(expectedWorkflow.all { it in reported }, "every workflow status is a known norm.statuses row")
        }
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
