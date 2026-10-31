package ch.nokillswit

import ch.nokillswit.norm.CodeActivityStore
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.ProposalMatch
import ch.nokillswit.norm.WorkItemStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `CodeActivityStore.proposalMatches` (the SQL behind the Identities page's proposals) on direct rows: one connection holds the
 * identities, others hold the Jira people, no SYNC. Every address and account id carries a per-test token, because the people of every
 * ACTIVE connection in the shared container are candidates.
 */
class CodeIdentityProposalMatchesTest {
    private val code = CodeActivityStore(sharedDatabaseForTests())
    private val workItems = WorkItemStore(sharedDatabaseForTests())
    private val dataSources = SyncedStubFixture.dataSources()
    private val token = UUID.randomUUID().toString().take(8)

    private fun address(local: String) = "$local-$token@flow-sample.example"

    private fun identity(key: String, vararg emails: String) = NormCodeIdentity(
        key = key,
        kind = IdentityKind.USER,
        userId = key.drop(1).toLong(),
        username = "user-$key-$token",
        displayName = "Identity $key",
        state = "active",
        bot = false,
        emails = emails.toList(),
        deletedAt = null,
    )

    private suspend fun connection() =
        SyncedStubFixture.createConnection(dataSources, uniqueProjectKeys(1), enabled = false, namePrefix = "proposals")

    /** Runs [body] with the identity connection and [peopleConnections] Jira connections; everything is emptied and soft-deleted after. */
    private suspend fun world(peopleConnections: Int, body: suspend (identities: UInt, people: List<UInt>) -> Unit) {
        val identities = connection()
        val people = List(peopleConnections) { connection() }
        try {
            body(identities, people)
        } finally {
            code.replaceIdentities(identities, emptyList())
            people.forEach { workItems.replacePeople(it, emptyList()) }
            (people + identities).forEach { dataSources.delete(it) }
        }
    }

    private fun person(account: String, name: String, email: String?, active: Boolean = true) =
        PersonRef("$account-$token", name, email, active)

    @Test
    fun `a padded or mixed-case Jira email matches the canonical identity address`() = runBlocking {
        world(1) { identities, (jira) ->
            code.replaceIdentities(identities, listOf(identity("u1", address("mixed"))))
            workItems.replacePeople(jira, listOf(person("acc-a", "Mixed Case", "  ${address("MiXeD").uppercase()} ")))
            assertEquals(listOf(ProposalMatch("u1", "acc-a-$token", "Mixed Case")), code.proposalMatches(identities))
        }
    }

    @Test
    fun `an inactive person still matches`() = runBlocking {
        world(1) { identities, (jira) ->
            code.replaceIdentities(identities, listOf(identity("u2", address("gone"))))
            workItems.replacePeople(jira, listOf(person("acc-b", "Departed", address("gone"), active = false)))
            assertEquals(listOf(ProposalMatch("u2", "acc-b-$token", "Departed")), code.proposalMatches(identities))
        }
    }

    @Test
    fun `a person without an email is ignored`() = runBlocking {
        world(1) { identities, (jira) ->
            code.replaceIdentities(identities, listOf(identity("u3", address("nobody"))))
            workItems.replacePeople(jira, listOf(person("acc-c", "No Email", null), person("acc-c2", "Blank Email", "   ")))
            assertEquals(emptyList(), code.proposalMatches(identities))
        }
    }

    @Test
    fun `a soft-deleted connection's people are excluded`() = runBlocking {
        world(2) { identities, (kept, removed) ->
            code.replaceIdentities(identities, listOf(identity("u4", address("kept")), identity("u5", address("removed"))))
            workItems.replacePeople(kept, listOf(person("acc-d", "Kept", address("kept"))))
            workItems.replacePeople(removed, listOf(person("acc-e", "Removed", address("removed"))))
            dataSources.delete(removed)
            assertEquals(listOf(ProposalMatch("u4", "acc-d-$token", "Kept")), code.proposalMatches(identities))
        }
    }

    @Test
    fun `the same account on two connections is one match, named by the first display name`() = runBlocking {
        world(2) { identities, (first, second) ->
            code.replaceIdentities(identities, listOf(identity("u6", address("twice"))))
            workItems.replacePeople(first, listOf(person("acc-f", "Zed Twice", address("twice"))))
            workItems.replacePeople(second, listOf(person("acc-f", "Amy Twice", address("twice"))))
            assertEquals(listOf(ProposalMatch("u6", "acc-f-$token", "Amy Twice")), code.proposalMatches(identities))
        }
    }

    @Test
    fun `two different accounts for one address are two matches, and a secondary address matches too`() = runBlocking {
        world(1) { identities, (jira) ->
            code.replaceIdentities(identities, listOf(identity("u7", address("primary"), address("secondary"))))
            workItems.replacePeople(
                jira,
                listOf(person("acc-g", "Via Primary", address("primary")), person("acc-h", "Via Secondary", address("secondary"))),
            )
            assertEquals(
                listOf(ProposalMatch("u7", "acc-g-$token", "Via Primary"), ProposalMatch("u7", "acc-h-$token", "Via Secondary")),
                code.proposalMatches(identities),
            )
        }
    }
}
