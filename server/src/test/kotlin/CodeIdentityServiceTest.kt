package ch.nokillswit

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityDecisionRequest
import ch.nokillswit.identities.CodeIdentityFilter
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.CodeIdentityService
import ch.nokillswit.identities.CodeIdentityStatus
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.norm.CodeActivityStore
import ch.nokillswit.norm.IdentityKind
import ch.nokillswit.norm.NormCodeIdentity
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.norm.WorkItemStore
import io.ktor.server.plugins.BadRequestException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `identities/CodeIdentityService.kt` on direct rows (identities through `CodeActivityStore`, people through `WorkItemStore`, decisions
 * through the link store), no routes and no SYNC: the PUT pattern's outcomes, the idempotent re-PUT, the unknown-account rule and its
 * exemption, the bulk confirmation counting only what it wrote, the two insert races (pinned deterministically: a raw transaction holds
 * a decision INSERT uncommitted until the service waits on it, then commits), and the orphaned decisions. Every address and account id
 * carries a per-test token (the people of every ACTIVE connection are proposal candidates), every host is unique and emptied afterwards.
 */
class CodeIdentityServiceTest {
    private val database = sharedDatabaseForTests()
    private val store = CodeIdentityLinkStore(database)
    private val code = CodeActivityStore(database)
    private val workItems = WorkItemStore(database)
    private val dataSources = SyncedStubFixture.dataSources()
    private val service = CodeIdentityService(database, store, code, workItems, dataSources)
    private val token = UUID.randomUUID().toString().take(8)
    private val host = "test-$token.example"
    private val everything = PageRequest(1, 100, listOf(SortField("id", false)))

    private fun address(local: String) = "$local-$token@flow-sample.example"

    private fun account(name: String) = "$name-$token"

    private fun identity(key: String, vararg emails: String, bot: Boolean = false) = NormCodeIdentity(
        key = key,
        kind = IdentityKind.USER,
        userId = key.drop(1).toLong(),
        username = "user-$key-$token",
        displayName = "Identity $key",
        state = "active",
        bot = bot,
        emails = emails.toList(),
        deletedAt = null,
    )

    private fun linked(who: String) = CodeIdentityDecisionRequest(CodeIdentityDecision.LINKED, account(who))

    private class World(val identities: UInt, val jira: UInt, val userId: Long)

    /** An identity connection, a Jira connection with [people], and a decider; all removed afterwards (decisions of [host] too). */
    private suspend fun world(people: List<PersonRef>, rows: List<NormCodeIdentity>, body: suspend (World) -> Unit) {
        val identities = SyncedStubFixture.createConnection(dataSources, uniqueProjectKeys(1), enabled = false, namePrefix = "svc-code")
        val jira = SyncedStubFixture.createConnection(dataSources, uniqueProjectKeys(1), enabled = false, namePrefix = "svc-jira")
        val userId = TestUsers.seed(uniqueEmail("svc"), "pw-service-1").toLong()
        try {
            code.replaceIdentities(identities, rows)
            workItems.replacePeople(jira, people)
            body(World(identities, jira, userId))
        } finally {
            store.deleteHost(host)
            code.replaceIdentities(identities, emptyList())
            workItems.replacePeople(jira, emptyList())
            dataSources.delete(identities)
            dataSources.delete(jira)
            TestUsers.softDelete(userId.toUInt())
        }
    }

    private suspend fun keysOf(w: World, filter: CodeIdentityFilter) =
        service.list(w.identities, host, filter, everything).items.map { it.identityKey }

    private fun person(who: String, email: String? = null) = PersonRef(account(who), "Person $who", email, true)

    @Test
    fun `decide inserts, then an identical re-PUT writes nothing, then a change reports what it overwrote`() = runBlocking {
        world(listOf(person("a"), person("b")), listOf(identity("u1"))) { w ->
            val first = service.decide(w.identities, host, "u1", linked("a"), w.userId, 100L)
            assertTrue(first.changed)
            assertNull(first.previous)
            assertEquals(CodeIdentityStatus.LINKED, first.item.status)
            assertEquals("Person a", first.item.decision?.jiraDisplayName)
            assertTrue(first.item.seen)

            val again = service.decide(w.identities, host, "u1", linked("a"), w.userId, 200L)
            assertFalse(again.changed, "an identical decision is idempotent")
            assertEquals(100L, store.find(host, "u1")?.decidedAt, "nothing was rewritten")

            val ignore = CodeIdentityDecisionRequest(CodeIdentityDecision.IGNORED)
            val changed = service.decide(w.identities, host, "u1", ignore, w.userId, 300L)
            assertTrue(changed.changed)
            assertEquals(CodeIdentityDecision.LINKED, changed.previous?.decision)
            assertEquals(account("a"), changed.previous?.jiraAccountId)
            assertEquals(CodeIdentityStatus.IGNORED_OR_BOT, changed.item.status)
            assertEquals(300L, store.find(host, "u1")?.decidedAt)
        }
    }

    @Test
    fun `an identity nobody has is a 404 and an unknown LINKED account is a 400`() = runBlocking {
        world(listOf(person("a")), listOf(identity("u1"))) { w ->
            assertFailsWith<NotFoundException> { service.decide(w.identities, host, "u999", linked("a"), w.userId, 1L) }
            val failure = assertFailsWith<BadRequestException> {
                service.decide(w.identities, host, "u1", linked("nobody"), w.userId, 1L)
            }
            assertFalse(account("nobody") in failure.message.orEmpty(), "the message never echoes the id")
            assertNull(store.find(host, "u1"))
        }
    }

    @Test
    fun `a re-PUT of the stored account is accepted after the person left Jira`() = runBlocking {
        world(listOf(person("a")), listOf(identity("u1"))) { w ->
            service.decide(w.identities, host, "u1", linked("a"), w.userId, 1L)
            workItems.replacePeople(w.jira, emptyList())
            assertFalse(service.decide(w.identities, host, "u1", linked("a"), w.userId, 2L).changed)
            assertFailsWith<BadRequestException> { service.decide(w.identities, host, "u1", linked("b"), w.userId, 3L) }
        }
    }

    @Test
    fun `remove answers the row it removed and then null`() = runBlocking {
        world(listOf(person("a")), listOf(identity("u1"))) { w ->
            service.decide(w.identities, host, "u1", linked("a"), w.userId, 1L)
            val removed = assertNotNull(service.remove(host, "u1"))
            assertEquals(account("a"), removed.jiraAccountId)
            assertNull(service.remove(host, "u1"))
        }
    }

    @Test
    fun `confirmProposals writes the unambiguous matches and the bots once, and counts only what it wrote`() = runBlocking {
        val people = listOf(person("a", address("a")), person("b1", address("b")), person("b2", address("b")), person("d", address("d")))
        val rows = listOf(
            identity("u1", address("a")), // one match: LINKED
            identity("u2", address("b")), // two accounts: ambiguous, no proposal
            identity("u3", address("d")), // one match, but a decision is stored: untouched
            identity("u4", address("none")), // none
            identity("u5", bot = true), // BOT
        )
        world(people, rows) { w ->
            store.insertIfAbsent(IdentityLink(host, "u3", CodeIdentityDecision.IGNORED, null, w.userId, 1L))
            val written = service.confirmProposals(w.identities, host, w.userId, 50L)
            val decisions = written.associate { it.key to it.decision }
            assertEquals(mapOf("u1" to CodeIdentityDecision.LINKED, "u5" to CodeIdentityDecision.BOT), decisions)
            assertEquals(account("a"), written.single { it.key == "u1" }.jiraAccountId)
            assertEquals(CodeIdentityDecision.IGNORED, store.find(host, "u3")?.decision)
            assertEquals(emptyList(), service.confirmProposals(w.identities, host, w.userId, 60L), "a second call writes nothing")
        }
    }

    @Test
    fun `the list joins status, proposals and names, filters and pages in memory`() = runBlocking {
        val people = listOf(person("a", address("a")), person("b"))
        val rows = listOf(identity("u1", address("a")), identity("u2"), identity("u3", address("zoe")))
        world(people, rows) { w ->
            service.decide(w.identities, host, "u2", linked("b"), w.userId, 1L)
            val all = service.list(w.identities, host, CodeIdentityFilter(), everything)
            assertEquals(3L, all.total)
            val byKey = all.items.associateBy { it.identityKey }
            assertEquals(CodeIdentityStatus.PROPOSED, byKey.getValue("u1").status)
            assertEquals("Person a", byKey.getValue("u1").proposal?.jiraDisplayName)
            assertEquals("Person b", byKey.getValue("u2").decision?.jiraDisplayName)
            assertEquals(CodeIdentityStatus.UNMAPPED, byKey.getValue("u3").status)

            assertEquals(listOf("u1"), keysOf(w, CodeIdentityFilter(CodeIdentityStatus.PROPOSED)))
            assertEquals(listOf("u3"), keysOf(w, CodeIdentityFilter(q = "ZOE-")))
            val page = service.list(w.identities, host, CodeIdentityFilter(), PageRequest(2, 2, listOf(SortField("id", false))))
            assertEquals(listOf("u3"), page.items.map { it.identityKey })
            assertEquals(3L, page.total)
        }
    }

    @Test
    fun `an orphaned decision is listed unseen, can be changed and removed, but never found by q`() = runBlocking {
        world(listOf(person("a")), listOf(identity("u1"))) { w ->
            store.insertIfAbsent(IdentityLink(host, "u77", CodeIdentityDecision.LINKED, account("a"), w.userId, 1L))
            val listed = service.list(w.identities, host, CodeIdentityFilter(), everything).items.associateBy { it.identityKey }
            val orphan = listed.getValue("u77")
            assertFalse(orphan.seen)
            assertEquals(CodeIdentityStatus.LINKED, orphan.status)
            assertEquals(IdentityKind.USER, orphan.kind)
            assertEquals(emptyList(), orphan.emails)
            assertNull(orphan.username)
            assertTrue(listed.getValue("u1").seen)

            assertEquals(listOf("u77"), keysOf(w, CodeIdentityFilter(CodeIdentityStatus.LINKED)))
            assertEquals(emptyList(), keysOf(w, CodeIdentityFilter(q = "u77")))

            val ignore = CodeIdentityDecisionRequest(CodeIdentityDecision.IGNORED)
            val outcome = service.decide(w.identities, host, "u77", ignore, w.userId, 2L)
            assertTrue(outcome.changed)
            assertFalse(outcome.item.seen)
            assertNotNull(service.remove(host, "u77"))
            assertFailsWith<NotFoundException> { service.decide(w.identities, host, "u77", linked("a"), w.userId, 3L) }
        }
    }

    /**
     * Holds an INSERT of a decision for ([host], [key]) uncommitted on a raw connection, runs [action] (which must block on that row),
     * waits until it does, commits, and answers what [action] returned. The rollback in the `finally` releases the row when an
     * assertion fails first. Timeouts fail a hang fast instead of leaving it to the stall watchdog.
     */
    private suspend fun <T> withRawInsertCommittedMidway(
        key: String,
        decision: CodeIdentityDecision,
        userId: Long,
        action: suspend () -> T,
    ): T =
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement(
                "INSERT INTO code_identity_links (source_host, identity_key, decision, jira_account_id, decided_by_user_id, decided_at) " +
                    "VALUES (?, ?, ?, NULL, ?, 7)",
            ).use { insert ->
                insert.setString(1, host)
                insert.setString(2, key)
                insert.setString(3, decision.name)
                insert.setLong(4, userId)
                insert.executeUpdate()
            }
            try {
                withTimeout(RACE_TIMEOUT_MS) {
                    coroutineScope {
                        val running = async { action() }
                        awaitLockWait("code_identity_links")
                        raw.commit()
                        running.await()
                    }
                }
            } finally {
                raw.rollback()
            }
        }

    @Test
    fun `confirmProposals loses a race to a concurrent decision silently and does not report that key`() = runBlocking {
        val people = listOf(person("a", address("a")), person("b", address("b")))
        world(people, listOf(identity("u1", address("a")), identity("u2", address("b")))) { w ->
            // u1's decision commits while confirm is blocked on its speculative insert: it must not be counted or audited as confirm's.
            val written = withRawInsertCommittedMidway("u1", CodeIdentityDecision.IGNORED, w.userId) {
                service.confirmProposals(w.identities, host, w.userId, 50L)
            }
            assertEquals(listOf("u2"), written.map { it.key }, "only the key confirm really inserted")
            val raw = assertNotNull(store.find(host, "u1"))
            assertEquals(CodeIdentityDecision.IGNORED, raw.decision, "the concurrent decision won")
            assertEquals(7L, raw.decidedAt)
            assertEquals(CodeIdentityDecision.LINKED, store.find(host, "u2")?.decision)
        }
    }

    @Test
    fun `decide loses the insert race, then overwrites the winner and reports it as the previous decision`() = runBlocking {
        world(listOf(person("a")), listOf(identity("u1"))) { w ->
            val outcome = withRawInsertCommittedMidway("u1", CodeIdentityDecision.IGNORED, w.userId) {
                service.decide(w.identities, host, "u1", linked("a"), w.userId, 50L)
            }
            assertTrue(outcome.changed)
            val previous = assertNotNull(outcome.previous, "the audit's decisionFrom is the raw row, not an absent decision")
            assertEquals(CodeIdentityDecision.IGNORED, previous.decision)
            assertEquals(7L, previous.decidedAt)
            val stored = assertNotNull(store.find(host, "u1"))
            assertEquals(CodeIdentityDecision.LINKED, stored.decision)
            assertEquals(account("a"), stored.jiraAccountId)
            assertEquals(50L, stored.decidedAt)
        }
    }

    private companion object {
        const val RACE_TIMEOUT_MS = 60_000L
    }
}
