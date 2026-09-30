package ch.nokillswit

import ch.nokillswit.norm.PersonRef
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.byLabelThenId
import ch.nokillswit.reports.orgGroups
import io.ktor.server.testing.testApplication
import java.util.UUID
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

/** A group's identity as the wire carries it — the three fields a report's org drill orders by. */
internal data class GroupIdentity(val label: String?, val teamId: UInt?, val accountId: String?)

/** Pins the org drill's order on a report's own output: label (null last), then team id, then account id. */
internal fun assertLabelThenIdOrder(what: String, groups: List<GroupIdentity>) {
    assertEquals(groups.sortedWith(byLabelThenId({ it.label }, { it.teamId }, { it.accountId })), groups, "$what groups: label, then id")
}

/**
 * The org drill's deterministic order — label (null last), then team id, then account id — that velocity, throughput,
 * sprint consistency and every DONE-item report share through `orgGroups` (`.claude/docs/reports.md`). A label-only sort
 * would leave equal labels in the database's grouping order.
 */
class ReportOrgGroupsTest {
    @Test
    fun `equal labels order by team id then account id and null sorts last`() {
        val shuffled = listOf(
            GroupIdentity(null, null, null),
            GroupIdentity("Same", 7u, "b"),
            GroupIdentity("Same", null, "a"),
            GroupIdentity("Same", 7u, "a"),
            GroupIdentity("Same", 3u, "z"),
            GroupIdentity("Alpha", 9u, null),
        )
        val ordered = shuffled.sortedWith(byLabelThenId({ it.label }, { it.teamId }, { it.accountId }))
        assertEquals(
            listOf(
                GroupIdentity("Alpha", 9u, null),
                GroupIdentity("Same", 3u, "z"),
                GroupIdentity("Same", 7u, "a"),
                GroupIdentity("Same", 7u, "b"),
                GroupIdentity("Same", null, "a"),
                GroupIdentity(null, null, null),
            ),
            ordered,
        )
    }

    @Test
    fun `orgGroups breaks an equal display name by account id`() = testApplication {
        usePostgresTestcontainer()
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "org-groups", enabled = false)
        val suffix = UUID.randomUUID().toString().substring(0, 8)
        val sameName = "Same Name $suffix"
        val accountB = "b-$suffix"
        val accountA = "a-$suffix"
        val accountZ = "z-$suffix"
        // Alphabetically first name, but the LAST account id; two people sharing one name in descending id order.
        SyncedStubFixture.workItems().replacePeople(
            connectionId,
            listOf(
                PersonRef(accountB, sameName, null, active = true),
                PersonRef(accountA, sameName, null, active = true),
                PersonRef(accountZ, "Aaa First $suffix", null, active = true),
            ),
        )

        // Items arrive in the order the grouping would emit them: the tie's larger id first, the unassigned bucket in between.
        val items: List<String?> = listOf(accountB, null, accountA, accountZ)
        val groups = suspendTransaction(sharedDatabaseForTests()) {
            orgGroups(ReportLevel.TEAM, items, { null }, { it })
        }

        assertEquals(listOf<String?>(accountZ, accountA, accountB, null), groups.map { it.first.accountId })
        assertEquals(listOf("Aaa First $suffix", sameName, sameName, null), groups.map { it.first.label })
    }
}
