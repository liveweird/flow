package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.metrics.MetricsConfigService.WorkCategoryMap
import ch.nokillswit.metrics.MAX_WORK_CATEGORY_TARGETS
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

    /**
     * A private disabled connection (ACTIVE in the business sense) with each of [categories] mapped on it. The map is global to the
     * team: every active connection contributes, whoever owns a board on it. The shared container holds other classes' mappings too, so
     * every test uses labels with its own [tag] suffix and reads through [TeamWorkCategoryTargetsResponse.mine].
     */
    private suspend fun mappedOn(vararg categories: String): UInt {
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "tgt", enabled = false)
        categories.forEach { mapCategory(connectionId, it) }
        return connectionId
    }

    private fun newTag() = SyncedStubFixture.unique("t").substringAfter('-')

    /** The rows of this test's own labels (the suffix [tag]) plus `UNCATEGORIZED`, in the response's order. */
    private fun TeamWorkCategoryTargetsResponse.mine(tag: String) =
        categories.filter { it.key.endsWith("-$tag") || it.key == "UNCATEGORIZED" }

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
        }
        TestTeams.service.delete(teamId)
    }

    @Test
    fun `a team that owns nothing reads the no-category row and every target is empty`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("tgt-empty", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-empty"))
        try {
            val read = user.targetsOf(teamId)
            assertEquals(teamId, read.teamId)
            assertTrue(TeamWorkCategoryTargetRow("UNCATEGORIZED", null, true) in read.categories)
            assertTrue(read.categories.all { it.targetPercent == null && it.mapped })
            assertNull(read.updatedAt)
            assertNull(read.updatedByUserId)
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `the choosable categories are those mapped on any active data source, whoever owns a board there`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("tgt-avail", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-avail"))
        val tag = newTag()
        try {
            // Two connections, none with a board of this team (it has no board at all): both contribute. Two option ids may share a label.
            val first = mappedOn("Run-$tag", "Build-$tag")
            mapCategory(first, "Build-$tag", valueId = "second-option")
            mappedOn("Explore-$tag")
            // A soft-deleted connection no longer contributes.
            SyncedStubFixture.dataSources().delete(mappedOn("Gone-$tag"))

            val read = user.targetsOf(teamId).mine(tag)
            assertEquals(listOf("Build-$tag", "Explore-$tag", "Run-$tag", "UNCATEGORIZED"), read.map { it.key })
            assertTrue(read.all { it.mapped && it.targetPercent == null })
        } finally {
            cleanUp(teamId)
        }
    }

    @Test
    fun `an ADMIN replaces the set, a reader sees it, and an identical re-PUT writes and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("tgt-put")
        val adminId = TestUsers.seed(email = adminEmail, password = "pw", role = FlowUserRole.ADMIN)
        val admin = authedClient(adminEmail, "pw")
        val user = seededClient("tgt-put-reader", FlowUserRole.USER)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-put"))
        val tag = newTag()
        val (run, build, explore) = listOf("Run-$tag", "Build-$tag", "Explore-$tag")
        try {
            mappedOn(run, build, explore)
            withAuditCapture { capture ->
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets(run to 60, build to 30, "UNCATEGORIZED" to 10)))
                val read = user.targetsOf(teamId)
                val shown = read.mine(tag).associate { it.key to it.targetPercent }
                assertEquals(mapOf(build to 30, explore to null, run to 60, "UNCATEGORIZED" to 10), shown)
                assertEquals(listOf(build, explore, run, "UNCATEGORIZED"), read.mine(tag).map { it.key })
                assertNotNull(read.updatedAt)
                assertEquals(adminId, read.updatedByUserId)
                val event = assertNotNull(
                    capture.awaitEvent { it.message == "team.work_category_targets_updated" && it.hasKeyValue("teamId", teamId.toLong()) },
                )
                assertTrue(event.hasKeyValue("byUserId", adminId.toLong()))
                assertTrue(event.hasKeyValue("targets", "$build=30;$run=60;UNCATEGORIZED=10"))
                assertTrue(event.hasKeyValue("targetsFrom", ""))

                // The same set again, in another order: nothing is written or audited.
                val audited = capture.events.count { it.message == "team.work_category_targets_updated" }
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("UNCATEGORIZED" to 10, build to 30, run to 60)))
                assertEquals(read.updatedAt, user.targetsOf(teamId).updatedAt, "the stored rows were not rewritten")
                assertEquals(audited, capture.events.count { it.message == "team.work_category_targets_updated" })

                // A different set replaces the old one whole (a dropped key is gone), a 0 % row is allowed, and it audits again.
                assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets(explore to 100, run to 0)))
                val replaced = user.targetsOf(teamId).mine(tag).associate { it.key to it.targetPercent }
                assertEquals(mapOf(build to null, explore to 100, run to 0, "UNCATEGORIZED" to null), replaced)
                val second = assertNotNull(
                    capture.awaitEvent {
                        it.message == "team.work_category_targets_updated" && it.hasKeyValue("targets", "$explore=100;$run=0")
                    },
                )
                assertTrue(second.hasKeyValue("targetsFrom", "$build=30;$run=60;UNCATEGORIZED=10"))
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
        val tag = newTag()
        try {
            mappedOn("Run-$tag")
            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets("Run-$tag" to 100)))
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
        val tag = newTag()
        val (run, retired) = listOf("Run-$tag", "Retired-$tag")
        try {
            val connectionId = mappedOn(run, retired)
            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets(run to 70, retired to 30)))
            unmapCategory(connectionId, retired)

            val read = admin.targetsOf(teamId)
            assertEquals(listOf(run, "UNCATEGORIZED", retired), read.mine(tag).map { it.key })
            assertEquals(TeamWorkCategoryTargetRow(retired, 30, mapped = false), read.mine(tag).last())
            assertEquals(TeamWorkCategoryTargetRow(run, 70, mapped = true), read.mine(tag).first())

            // Saving the page as it is shown is a 400 (the client drops the unmapped row first); nothing changed.
            assertEquals(HttpStatusCode.BadRequest, admin.putTargets(teamId, targets(run to 70, retired to 30)))
            assertEquals(read, admin.targetsOf(teamId))

            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets(run to 100)))
            assertEquals(listOf(run, "UNCATEGORIZED"), admin.targetsOf(teamId).mine(tag).map { it.key })
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
        val tag = newTag()
        try {
            mappedOn("Run-$tag")
            assertEquals(HttpStatusCode.Forbidden, user.putTargets(teamId, targets("Run-$tag" to 100)))
            assertEquals(HttpStatusCode.Forbidden, user.putTargets(MISSING_TEAM_ID, targets("Run-$tag" to 100)))
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

    /**
     * The route's 400s, each with a body that breaks ONE rule (every key mapped, the rest valid). The pure shape rules - count, key length,
     * blank key, percent range, duplicate, sum - are pinned one by one in `TeamWorkCategoryTargetsValidationTest`; here they are proved to
     * reach the route, with the count case valid but for its size (101 mapped labels).
     */
    @Test
    fun `an invalid set is a 400 and leaves the stored set untouched`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tgt-400", FlowUserRole.ADMIN)
        val teamId = TestTeams.seed(SyncedStubFixture.unique("tgt-400"))
        val tag = newTag()
        val (run, build) = listOf("Run-$tag", "Build-$tag")
        val many = List(MAX_WORK_CATEGORY_TARGETS + 1) { "Many$it-$tag" }
        val tooMany = TeamWorkCategoryTargetsRequest(many.mapIndexed { i, k -> TeamWorkCategoryTarget(k, if (i == 0) 100 else 0) })
        val manyConnection = mappedOn(*many.toTypedArray())
        try {
            mappedOn(run, build)
            assertEquals(HttpStatusCode.NoContent, admin.putTargets(teamId, targets(run to 100)))
            val before = admin.targetsOf(teamId)

            val rejected = listOf(
                "sum below 100" to targets(run to 60, build to 39),
                "sum above 100" to targets(run to 60, build to 41),
                "a duplicate key" to targets(run to 50, run to 50),
                "a negative percent" to targets(run to 100, build to 1, "UNCATEGORIZED" to -1),
                "a category no active data source maps" to targets(run to 50, "Elsewhere-$tag" to 50),
                "a key with a control character" to targets("$run\u0001" to 100),
                "more than 100 targets" to tooMany,
            )
            for ((label, body) in rejected) {
                assertEquals(HttpStatusCode.BadRequest, admin.putTargets(teamId, body), label)
            }
            // Omitted `targets` and a non-integer percent never clear or round silently.
            for (raw in listOf("{}", """{"targets":[{"key":"$run","targetPercent":99.5}]}""", """{"targets":null}""")) {
                assertEquals(HttpStatusCode.BadRequest, admin.putRaw(teamId, raw), raw)
            }
            assertEquals(before, admin.targetsOf(teamId))
        } finally {
            SyncedStubFixture.dataSources().delete(manyConnection) // 101 labels stay out of every later team's list
            cleanUp(teamId)
        }
    }
}
