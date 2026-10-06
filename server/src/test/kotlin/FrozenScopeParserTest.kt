package ch.nokillswit

import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.reports.frozenContributionsOf
import ch.nokillswit.reports.frozenScopeItemsOf
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The one strict reader of `fact_sprint_snapshot.scope` (`reports/FrozenScope.kt`) behind the sprint reports' USER-level snapshots. */
class FrozenScopeParserTest {

    private fun message(block: () -> Unit) = assertFailsWith<IllegalStateException> { block() }.message.orEmpty()

    @Test
    fun `every key the writer emits is read into the kernel's own item and a null stays null`() {
        val full = FrozenScopeFixtures.scopeItem(
            issueId = 42, assignee = "acc", committed = false, inScopeAtClose = true, addedAtMs = 1_770_724_800_000L,
            removedAtMs = null, commitMd = 1.25, closeMd = 2.5, doneMd = null, done = true, carried = false, dropped = true,
        )
        val items = frozenScopeItemsOf(buildJsonArray { add(full) }.toString(), 7u, 99L)
        assertEquals(
            listOf(
                SprintScopeItem(
                    issueId = 42, addedAtMs = 1_770_724_800_000L, removedAtMs = null, committed = false, inScopeAtClose = true,
                    estimateAtCommitmentMd = 1.25, estimateAtCloseMd = 2.5, estimateAtDoneMd = null, assigneeAtCommitment = "acc",
                    doneInSprint = true, carriedOver = false, dropped = true,
                ),
            ),
            items,
        )
        val unassigned = frozenScopeItemsOf(buildJsonArray { add(FrozenScopeFixtures.scopeItem(1, null)) }.toString(), 7u, 99L)
        assertEquals(null, unassigned.single().assigneeAtCommitment)
        assertEquals(emptyList(), frozenScopeItemsOf("[]", 7u, 99L))
        // Velocity's narrower view of the same document reads the same values.
        val contribution = frozenContributionsOf(buildJsonArray { add(full) }.toString(), 7u, 99L).single()
        assertEquals(1.25, contribution.commitMd)
        assertEquals("acc", contribution.accountId)
    }

    @Test
    fun `the full reader fails loudly naming the connection, sprint and item`() {
        val prefix = "fact_sprint_snapshot.scope malformed for connection 7 sprint 99: "
        fun item(key: String, value: String?): String {
            val base = linkedMapOf(
                "issueId" to "1", "addedAtMs" to "null", "removedAtMs" to "null", "committed" to "true", "inScopeAtClose" to "true",
                "estimateAtCommitmentMd" to "1.0", "estimateAtCloseMd" to "1.0", "estimateAtDoneMd" to "null",
                "assigneeAtCommitment" to "\"acc\"", "doneInSprint" to "false", "carriedOver" to "false", "dropped" to "false",
            )
            if (value == null) base.remove(key) else base[key] = value
            return base.entries.joinToString(prefix = "[{", postfix = "}]") { "\"${it.key}\":${it.value}" }
        }
        fun parse(json: String) = frozenScopeItemsOf(json, 7u, 99L)

        assertEquals(prefix + "item 0 is missing key doneInSprint", message { parse(item("doneInSprint", null)) })
        assertEquals(prefix + "item 0 is missing key dropped", message { parse(item("dropped", null)) })
        assertEquals(prefix + "item 0 key carriedOver is not a boolean", message { parse(item("carriedOver", "\"no\"")) })
        assertEquals(prefix + "item 0 key addedAtMs is not an integer", message { parse(item("addedAtMs", "\"x\"")) })
        assertEquals(prefix + "item 0 key removedAtMs is not an integer", message { parse(item("removedAtMs", "1.5")) })
        assertEquals(prefix + "item 0 key issueId is not an integer", message { parse(item("issueId", "null")) })
        assertEquals(prefix + "item 0 key estimateAtDoneMd is not a number", message { parse(item("estimateAtDoneMd", "\"1\"")) })
        assertEquals(prefix + "item 0 key assigneeAtCommitment is not a string", message { parse(item("assigneeAtCommitment", "5")) })
        assertEquals(prefix + "item 0 key dropped is not a scalar", message { parse(item("dropped", "{}")) })
        assertEquals(prefix + "item 0 is not an object", message { parse("[1]") })
        assertEquals(prefix + "not a JSON array", message { parse("{}") })
    }
}
