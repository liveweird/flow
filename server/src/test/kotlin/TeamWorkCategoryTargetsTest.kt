package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.metrics.MetricsConfigService.BoardTeamMap
import ch.nokillswit.metrics.MetricsConfigService.DomainMap
import ch.nokillswit.metrics.MetricsConfigService.WorkCategoryMap
import ch.nokillswit.metrics.TeamWorkCategoryTarget
import ch.nokillswit.metrics.TeamWorkCategoryTargetRow
import ch.nokillswit.metrics.TeamWorkCategoryTargetService.TeamWorkCategoryTargets
import ch.nokillswit.metrics.TeamWorkCategoryTargetsRequest
import ch.nokillswit.metrics.TeamWorkCategoryTargetsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A team id no test creates (the id column is a 32-bit INTEGER). */
private const val MISSING_TEAM_ID = 2_000_000_000u

/**
 * Per-team work-category targets (Costs part 3, V32): which categories a team can choose, the whole-set PUT, the sum-to-100 rule, the
 * unmapped flag, clearing, the 403 → 404 → 400 order and the change-only audit. The categories are hand-mapped in the config tables
 * (the stub maps no work category), on private DISABLED connections.
 */
class TeamWorkCategoryTargetsTest {

    private fun url(teamId: UInt) = "/api/v1/teams/$teamId/work-category-targets"

    private fun targets(vararg pairs: Pair<String, Int>) =
        TeamWorkCategoryTargetsRequest(pairs.map { TeamWorkCategoryTarget(it.first, it.second) })

    private suspend fun HttpClient.targetsOf(teamId: UInt) = get(url(teamId)).body<TeamWorkCategoryTargetsResponse>()

    private suspend fun HttpClient.putTargets(teamId: UInt, request: TeamWorkCategoryTargetsRequest) = putJson(url(teamId), request).status

    private suspend fun HttpClient.putRaw(teamId: UInt, body: String) =
        put(url(teamId)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status

    private fun TeamWorkCategoryTargetsResponse.percentByKey() = categories.associate { it.key to it.targetPercent }

    /** A private disabled connection and a team that owns a board on it; each [categories] label is mapped on the connection. */
    private suspend fun ownedByBoard(teamId: UInt, boardId: Long, vararg categories: String): UInt {
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "tgt", enabled = false)
        suspendTransaction(sharedDatabaseForTests()) {
            BoardTeamMap.insert {
                it[BoardTeamMap.connectionId] = connectionId
                it[BoardTeamMap.boardId] = boardId
                it[BoardTeamMap.teamId] = teamId
            }
        }
        categories.forEach { mapCategory(connectionId, it) }
        return connectionId
    }

    /** A private disabled connection where [teamId] is the configured owner of one domain; each [categories] label is mapped on it. */
    private suspend fun ownedByDomain(teamId: UInt, vararg categories: String): UInt {
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "tgt", enabled = false)
        suspendTransaction(sharedDatabaseForTests()) {
            DomainMap.insert {
                it[DomainMap.connectionId] = connectionId
                it[projectKey] = "TGT"
                it[domainKey] = "tgt"
                it[domainName] = "Tgt"
                it[ownerTeamId] = teamId
            }
        }
        categories.forEach { mapCategory(connectionId, it) }
        return connectionId
    }

    private suspend fun mapCategory(connectionId: UInt, category: String, valueId: String = "v-$category") {
        suspendTransaction(sharedDatabaseForTests()) {
            WorkCategoryMap.insert {
                it[WorkCategoryMap.connectionId] = connectionId
                it[WorkCategoryMap.valueId] = valueId
                it[WorkCategoryMap.category] = category
            }
        }
    }

    private suspend fun unmapCategory(connectionId: UInt, category: String) {
        suspendTransaction(sharedDatabaseForTests()) {
            WorkCategoryMap.deleteWhere { (WorkCategoryMap.connectionId eq connectionId) and (WorkCategoryMap.category eq category) }
        }
    }

    private suspend fun cleanUp(teamId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            TeamWorkCategoryTargets.deleteWhere { TeamWorkCategoryTargets.teamId eq teamId }
            BoardTeamMap.deleteWhere { BoardTeamMap.teamId eq teamId }
            DomainMap.deleteWhere { DomainMap.ownerTeamId eq teamId }
        }
        TestTeams.service.delete(teamId)
    }

    @Test
    fun `a team that owns nothing can choose only the no-category row, and anyone signed in reads it`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("tgt-empty", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-empty"))
        try {
            val read = user.targetsOf(teamId)
            assertEquals(teamId, read.teamId)
            assertEquals(listOf(TeamWorkCategoryTargetRow("UNCATEGORIZED", null, true)), read.categories)
            assertNull(read.updatedAt)
            assertNull(read.updatedByUserId)
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `the choosable categories are those mapped where the team owns a board or a domain, on active data sources`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("tgt-avail", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-avail"))
        val otherTeamId = TestTeams.seed(SyncedStubFixture.unique("tgt-avail-other"))
        try {
            // A board owner: two labels (two option ids may share one label - it lists once).
            val byBoard = ownedByBoard(teamId, 1L, "Run", "Build")
            mapCategory(byBoard, "Build", valueId = "second-option")
            // An explicit domain owner on another connection.
            ownedByDomain(teamId, "Explore")
            // Another team's connection, and a soft-deleted connection this team owns a domain on: neither contributes.
            ownedByBoard(otherTeamId, 2L, "Foreign")
            SyncedStubFixture.dataSources().delete(ownedByDomain(teamId, "Gone"))

            val read = user.targetsOf(teamId)
            assertEquals(listOf("Build", "Explore", "Run", "UNCATEGORIZED"), read.categories.map { it.key })
            assertTrue(read.categories.all { it.mapped && it.targetPercent == null })
        } finally {
            cleanUp(teamId)
            cleanUp(otherTeamId)
        }
    }

    @Test
    fun `an ADMIN replaces the set, a reader sees it, and an identical re-PUT writes and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-put", FlowUserRole.ADMIN)
        val user = seededClient("tgt-put-reader", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-put"))
        try {
            ownedByBoard(teamId, 10L, "Run", "Build", "Explore")
            withAuditCapture { capture ->
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Run" to 60, "Build" to 30, "UNCATEGORIZED" to 10)))
                val read = user.targetsOf(teamId)
                assertEquals(mapOf("Build" to 30, "Explore" to null, "Run" to 60, "UNCATEGORIZED" to 10), read.percentByKey())
                assertEquals(listOf("Build", "Explore", "Run", "UNCATEGORIZED"), read.categories.map { it.key })
                assertNotNull(read.updatedAt)
                assertNotNull(read.updatedByUserId)
                val event = assertNotNull(
                    capture.awaitEvent { it.message == "team.work_category_targets_updated" && it.hasKeyValue("teamId", teamId.toLong()) },
                )
                assertTrue(event.hasKeyValue("targets", "Build=30;Run=60;UNCATEGORIZED=10"))
                assertTrue(event.hasKeyValue("targetsFrom", ""))

                // The same set again, in another order: nothing is written or audited.
                val audited = capture.events.count { it.message == "team.work_category_targets_updated" }
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("UNCATEGORIZED" to 10, "Build" to 30, "Run" to 60)))
                assertEquals(read.updatedAt, user.targetsOf(teamId).updatedAt, "the stored rows were not rewritten")
                assertEquals(audited, capture.events.count { it.message == "team.work_category_targets_updated" })

                // A different set replaces the old one whole (a dropped key is gone), a 0 % row is allowed, and it audits again.
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Explore" to 100, "Run" to 0)))
                val replaced = user.targetsOf(teamId).percentByKey()
                assertEquals(mapOf("Build" to null, "Explore" to 100, "Run" to 0, "UNCATEGORIZED" to null), replaced)
                val second = assertNotNull(
                    capture.awaitEvent {
                        it.message == "team.work_category_targets_updated" && it.hasKeyValue("targets", "Explore=100;Run=0")
                    },
                )
                assertTrue(second.hasKeyValue("targetsFrom", "Build=30;Run=60;UNCATEGORIZED=10"))
            }
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `an empty set clears the targets, and clearing twice audits once`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-clear", FlowUserRole.ADMIN)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-clear"))
        try {
            ownedByBoard(teamId, 20L, "Run")
            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Run" to 100)))
            withAuditCapture { capture ->
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets()))
                val read = admin.targetsOf(teamId)
                assertTrue(read.categories.all { it.targetPercent == null && it.mapped })
                assertNull(read.updatedAt)
                assertNotNull(capture.awaitEvent { it.message == "team.work_category_targets_updated" && it.hasKeyValue("targets", "") })
                val audited = capture.events.count { it.message == "team.work_category_targets_updated" }
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets()))
                assertEquals(audited, capture.events.count { it.message == "team.work_category_targets_updated" })
            }
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `a category whose mapping is gone is flagged unmapped, cannot be saved again and drops out on replace`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-unmapped", FlowUserRole.ADMIN)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-unmapped"))
        try {
            val connectionId = ownedByBoard(teamId, 30L, "Run", "Retired")
            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Run" to 70, "Retired" to 30)))
            unmapCategory(connectionId, "Retired")

            val read = admin.targetsOf(teamId)
            assertEquals(listOf("Run", "UNCATEGORIZED", "Retired"), read.categories.map { it.key })
            assertEquals(TeamWorkCategoryTargetRow("Retired", 30, mapped = false), read.categories.last())
            assertEquals(TeamWorkCategoryTargetRow("Run", 70, mapped = true), read.categories.first())

            // Saving the page as it is shown is a 400 (the client drops the unmapped row first); nothing changed.
            assertEquals(HttpStatusCode.BadRequest, admin.putTargets(teamId, targets("Run" to 70, "Retired" to 30)))
            assertEquals(read, admin.targetsOf(teamId))

            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Run" to 100)))
            assertEquals(listOf("Run", "UNCATEGORIZED"), admin.targetsOf(teamId).categories.map { it.key })
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `a non-admin gets 403 first - for a real team, an unknown team and an invalid body alike`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-authz-admin", FlowUserRole.ADMIN)
        val user = seededClient("tgt-authz", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-authz"))
        try {
            ownedByBoard(teamId, 40L, "Run")
            assertEquals(HttpStatusCode.Forbidden, user.putTargets(teamId, targets("Run" to 100)))
            assertEquals(HttpStatusCode.Forbidden, user.putTargets(MISSING_TEAM_ID, targets("Run" to 100)))
            assertEquals(HttpStatusCode.Forbidden, user.putTargets(teamId, targets("Nope" to 5)), "an invalid body is still 403")
            assertEquals(HttpStatusCode.Forbidden, user.putRaw(teamId, "{}"))
            assertTrue(admin.targetsOf(teamId).categories.all { it.targetPercent == null }, "nothing was written")
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `a missing or soft-deleted team is 404 for the read and for an ADMIN write, before the body is checked`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-404", FlowUserRole.ADMIN)
        assertEquals(HttpStatusCode.NotFound, admin.get(url(MISSING_TEAM_ID)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putTargets(MISSING_TEAM_ID, targets("Run" to 100)))
        assertEquals(HttpStatusCode.NotFound, admin.putTargets(MISSING_TEAM_ID, targets("Run" to 1)), "404 wins over the sum rule")

        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-404-gone"))
        TestTeams.service.delete(teamId)
        assertEquals(HttpStatusCode.NotFound, admin.get(url(teamId)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putTargets(teamId, targets()))
    }

    @Test
    fun `an invalid set is a 400 and leaves the stored set untouched`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-400", FlowUserRole.ADMIN)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-400"))
        try {
            ownedByBoard(teamId, 50L, "Run", "Build")
            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Run" to 100)))
            val before = admin.targetsOf(teamId)

            val rejected = listOf(
                "sum below 100" to targets("Run" to 60, "Build" to 39),
                "sum above 100" to targets("Run" to 60, "Build" to 41),
                "a single 99" to targets("Run" to 99),
                "a duplicate key" to targets("Run" to 50, "Run" to 50),
                "a percent over 100" to targets("Run" to 101),
                "a negative percent" to targets("Run" to 101, "Build" to -1),
                "a category the team cannot choose" to targets("Run" to 50, "Elsewhere" to 50),
                "a blank key" to targets("  " to 100),
                "a key with a control character" to targets("Run\u0001" to 100),
                "a key over 100 characters" to targets("R".repeat(101) to 100),
                "more than 100 targets" to targets(*Array(101) { "K$it" to 0 }),
            )
            for ((label, body) in rejected) {
                assertEquals(HttpStatusCode.BadRequest, admin.putTargets(teamId, body), label)
            }
            // Omitted `targets` and a non-integer percent never clear or round silently.
            for (raw in listOf("{}", """{"targets":[{"key":"Run","targetPercent":99.5}]}""", """{"targets":null}""")) {
                assertEquals(HttpStatusCode.BadRequest, admin.putRaw(teamId, raw), raw)
            }
            assertEquals(before, admin.targetsOf(teamId))
        } finally {
            cleanUp(teamId)
        }
    }
}
